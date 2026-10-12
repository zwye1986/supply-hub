package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAcceptanceReceiptDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAcceptanceReceiptMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.StandardAcceptanceFingerprintPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 冻结回执身份读取：按可信 projectId+buyerOrderNo 读取历史绑定快照，供 Facade 在当前 Schema 解析之前
 * 判断是否使用冻结 SKU／Schema 计算格式2或格式3指纹。v2区分不存在与存在但不可信，旧入口遇格式3
 * 立即返回永久键冲突；格式0／缺冻结字段不得从当前目录补造为可信快照。
 * 设计见 docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#41-冻结优先。
 */
@Service
@RequiredArgsConstructor
public class FrozenAcceptanceIdentityReader {

    /** 格式 2（无用户指纹）：回执冻结了服务端解析的 SKU 与目标 Schema 版本。 */
    private static final short FINGERPRINT_FORMAT_NO_USER = 2;

    private final FulfillmentAcceptanceReceiptMapper receiptMapper;

    /** 冻结身份：受理时服务端解析的真实平台 SKU 与目标 Schema 编号。 */
    public record FrozenAcceptanceIdentity(Long platformSkuId, String targetSchemaCode,
                                           int targetSchemaVersion) {
    }

    /**
     * null表示无回执；存在但binding为空表示不可信，不得把两者混为首次请求。
     * 设计见 docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#41-冻结优先。
     */
    public record ReceiptIdentity(Short fingerprintVersion, String buyerSkuCode, String requestedPlatformSkuCode,
                                  FrozenAcceptanceIdentity binding) { }

    public ReceiptIdentity readIdentity(Long projectId, String buyerOrderNo) {
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectByProjectKey(projectId, buyerOrderNo);
        if (receipt == null) { return null; }
        Short format = receipt.getFingerprintVersion();
        boolean trusted = format != null && (format == FINGERPRINT_FORMAT_NO_USER
                || format == StandardAcceptanceFingerprintPolicy.PLATFORM_SKU_FORMAT_VERSION)
                && receipt.getPlatformSkuId() != null && receipt.getPlatformSkuId() > 0
                && receipt.getTargetSchemaCode() != null && !receipt.getTargetSchemaCode().isBlank()
                && receipt.getTargetSchemaVersion() != null && receipt.getTargetSchemaVersion() > 0;
        if (format != null && format == StandardAcceptanceFingerprintPolicy.PLATFORM_SKU_FORMAT_VERSION) {
            trusted &= receipt.getRequestedPlatformSkuCode() != null && !receipt.getRequestedPlatformSkuCode().isBlank()
                    && receipt.getBuyerSkuCode() != null && !receipt.getBuyerSkuCode().isBlank();
        }
        return new ReceiptIdentity(format, receipt.getBuyerSkuCode(), receipt.getRequestedPlatformSkuCode(),
                trusted ? new FrozenAcceptanceIdentity(receipt.getPlatformSkuId(), receipt.getTargetSchemaCode(),
                        receipt.getTargetSchemaVersion()) : null);
    }

    /**
     * v1/RPC兼容读取：格式3立即冲突；无回执或格式0／格式2冻结字段不完整返回null，
     * 沿原历史兼容错误处理，不把当前目录数据冒充冻结来源。
     */
    public FrozenAcceptanceIdentity read(Long projectId, String buyerOrderNo) {
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectByProjectKey(projectId, buyerOrderNo);
        if (receipt != null && receipt.getFingerprintVersion() != null
                && receipt.getFingerprintVersion() == StandardAcceptanceFingerprintPolicy.PLATFORM_SKU_FORMAT_VERSION) {
            // v1与RPC不能借格式3快照改算格式2，更不能重新解析现行映射。跨合同永久键冲突。
            throw com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception(
                    com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.IDEMPOTENCY_CONFLICT);
        }
        if (receipt == null || receipt.getFingerprintVersion() == null
                || receipt.getFingerprintVersion() != FINGERPRINT_FORMAT_NO_USER) {
            return null;
        }
        if (receipt.getPlatformSkuId() == null || receipt.getTargetSchemaCode() == null
                || receipt.getTargetSchemaVersion() == null) {
            return null;
        }
        return new FrozenAcceptanceIdentity(receipt.getPlatformSkuId(),
                receipt.getTargetSchemaCode(), receipt.getTargetSchemaVersion());
    }
}
