package com.aifuli.module.supplychain.infrastructure.security;

import com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialSlotIdentity;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialSlotStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 原单冻结槽身份的材料入口：短独立事务内锁槽、核身份，再解析，避免核验后轮换的竞态。
 * 不在订单/资金聚合锁内调用；成功数组所有权交调用者，用毕擦除，不保存历史secretRef或材料。
 * 设计见 docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#23-凭据。
 */
@Component
@RequiredArgsConstructor
public class FrozenCredentialSlotResolver {
    private final CredentialSlotMapper slots;
    private final List<CredentialProvider> providers;

    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public char[] resolve(FrozenCredentialSlotIdentity expected) {
        if (expected == null) throw invalid();
        var slot = slots.selectOne(new LambdaQueryWrapper<CredentialSlotDO>()
                .eq(CredentialSlotDO::getId, expected.slotId()).last("FOR UPDATE"));
        if (slot == null || slot.getStatus() == null || slot.getStatus() != CredentialSlotStatus.ACTIVE.getCode()
                || !Objects.equals(slot.getId(), expected.slotId())
                || !Objects.equals(slot.getProviderType(), expected.providerType())
                || !Objects.equals(slot.getActiveSecretVersion(), expected.activeSecretVersion())) {
            throw invalid();
        }
        var provider = providers.stream().filter(value -> value.supports(expected.providerType()))
                .findFirst().orElseThrow(FrozenCredentialSlotResolver::invalid);
        char[] material = provider.resolve(slot);
        if (material == null || material.length == 0) throw invalid();
        try {
            // 解析成功不等于短事务已提交；提交失败/结果不明时，工厂还未接管材料。
            // 设计见 docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#32-ticket扩展。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) Arrays.fill(material, '\0');
                }
            });
            return material;
        } catch (RuntimeException | Error failure) {
            Arrays.fill(material, '\0');
            throw failure;
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("冻结凭据槽身份或材料不可用");
    }
}
