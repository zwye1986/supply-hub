package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAcceptanceReceiptDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAcceptanceReceiptMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.StandardAcceptanceFingerprintPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * 按可信项目永久键读取受理时冻结的商品协议身份，不读取现行目录，也不授予续跑权限。
 *
 * <p>格式0或冻结绑定损坏保持失败关闭；真正同参/异参及CAS竞争仍由claim当前锁裁决。
 * 详见 docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#61-请求准备。</p>
 */
@Service
@RequiredArgsConstructor
public class FrozenAcceptanceIdentityReader {
    private final FulfillmentAcceptanceReceiptMapper receiptMapper;

    /** 普通只读查询；无回执才允许应用层走现行身份解析，禁止以当前SKU补齐历史绑定。 */
    @Transactional(transactionManager = "supplyChainTransactionManager", readOnly = true)
    public Optional<FrozenAcceptanceIdentity> find(Long buyerId, Long projectId, String buyerOrderNo) {
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectByProjectKey(projectId, buyerOrderNo);
        if (receipt == null) { return Optional.empty(); }
        if (!Objects.equals(receipt.getBuyerId(), buyerId)
                || !Objects.equals(receipt.getProjectId(), projectId)
                || !Objects.equals(receipt.getBuyerOrderNo(), buyerOrderNo)
                || receipt.getFingerprintVersion() == null
                || receipt.getFingerprintVersion() != StandardAcceptanceFingerprintPolicy.FORMAT_VERSION
                || receipt.getPlatformSkuId() == null || receipt.getPlatformSkuId() <= 0
                || receipt.getTargetSchemaCode() == null || receipt.getTargetSchemaCode().isBlank()
                || receipt.getTargetSchemaVersion() == null || receipt.getTargetSchemaVersion() < 1) {
            throw new FulfillmentAcceptanceLifecycle.IdempotencyConflictException();
        }
        return Optional.of(new FrozenAcceptanceIdentity(receipt.getPlatformSkuId(), receipt.getTargetSchemaCode(),
                receipt.getTargetSchemaVersion(), Objects.equals(receipt.getStatus(), AcceptanceReceiptStatus.ACCEPTED.getCode())));
    }

    /** 仅冻结协议身份和已接受事实；不含目标、秘密或可写DAL对象，accepted不代替claim裁决。 */
    public record FrozenAcceptanceIdentity(Long platformSkuId, String schemaCode, int schemaVersion, boolean accepted) {
        public FulfillmentAcceptanceLifecycle.TargetSchemaSnapshot targetSchema() {
            return new FulfillmentAcceptanceLifecycle.TargetSchemaSnapshot(platformSkuId, schemaCode, schemaVersion, true);
        }
    }
}
