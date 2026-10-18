package com.aifuli.module.supplychain.infrastructure.security;

import com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialItemIdentity;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialItemMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSetMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/**
 * 冻结身份到指定秘密材料的窄入口。短独立事务内先锁集，再按项主键升序当前读并核全部身份，最后解析。
 * 不保存历史secretRef、不改变旧resolveSet，不能在聚合锁内调用；返回数组调用者用毕擦除。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#33-冻结投影与首单-checkpoint。
 */
@Component
@RequiredArgsConstructor
public class FrozenCredentialSetResolver {
    private final CredentialSetMapper sets;
    private final CredentialItemMapper items;
    private final List<CredentialItemResolver> providers;

    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Map<String, char[]> resolve(Long setId, List<FrozenCredentialItemIdentity> expectedItems, Set<String> requestedKeys) {
        if (setId == null || expectedItems == null || expectedItems.isEmpty() || requestedKeys == null || requestedKeys.isEmpty()) throw invalid();
        var expected = Set.copyOf(expectedItems);
        var expectedKeys = new HashSet<String>();
        var expectedIds = new HashSet<Long>();
        expectedItems.forEach(item -> { expectedKeys.add(item.itemKey()); expectedIds.add(item.itemId()); });
        if (expected.size() != expectedItems.size() || expectedKeys.size() != expectedItems.size()
                || expectedIds.size() != expectedItems.size() || !expectedKeys.containsAll(requestedKeys)) throw invalid();
        var set = sets.selectByIdForUpdate(setId);
        if (set == null || !active(set.getStatus())) throw invalid();
        var rows = items.selectList(new LambdaQueryWrapper<CredentialItemDO>()
                .eq(CredentialItemDO::getCredentialSetId, setId).orderByAsc(CredentialItemDO::getId).last("FOR UPDATE"));
        var actual = new HashSet<FrozenCredentialItemIdentity>();
        for (var row : rows) {
            if (!active(row.getStatus()) || row.getId() == null || row.getActiveSecretVersion() == null) throw invalid();
            actual.add(new FrozenCredentialItemIdentity(row.getId(), row.getItemKey(), row.getProviderType(), row.getActiveSecretVersion()));
        }
        if (actual.size() != rows.size() || !actual.equals(expected)) throw invalid();
        Map<String, char[]> resolved = new LinkedHashMap<>();
        try {
            // 方法返回后Spring才提交；提交失败时调用者尚未接管，不能仅依赖下方catch擦除。
            // 设计见 docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#32-ticket扩展。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) resolved.values().forEach(chars -> { if (chars != null) Arrays.fill(chars, '\0'); });
                }
            });
            for (var row : rows) {
                if (!requestedKeys.contains(row.getItemKey())) continue;
                var provider = providers.stream().filter(value -> value.supports(row.getProviderType())).findFirst().orElseThrow(FrozenCredentialSetResolver::invalid);
                char[] material = provider.resolve(row);
                resolved.put(row.getItemKey(), material);
                if (material == null || material.length == 0) throw invalid();
            }
            return Collections.unmodifiableMap(resolved);
        } catch (RuntimeException | Error failure) {
            resolved.values().forEach(chars -> { if (chars != null) Arrays.fill(chars, '\0'); });
            throw failure;
        }
    }
    private static boolean active(Short status) { return status != null && status == CredentialStatus.ACTIVE.getCode(); }
    private static IllegalStateException invalid() { return new IllegalStateException("冻结凭据身份或材料不可用"); }
}
