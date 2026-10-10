package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAcceptanceReceiptDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAcceptanceReceiptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 冻结回执身份读取：按可信 projectId+buyerOrderNo 读取历史绑定快照，供 Facade 在当前 Schema 解析之前
 * 判断是否使用冻结 SKU／Schema 计算格式 2 指纹。仅格式 2 且平台SKU／Schema 编号完整的回执为可信绑定；
 * 格式 0／缺冻结字段沿原历史兼容路径，不从当前目录补造。
 * 设计见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#8-冻结事实回放与兼容边界。
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
     * 无回执返回 null（首次尝试走当前目录解析）；有回执但非格式 2 或冻结字段不完整返回 null
     * （沿原历史兼容错误处理，不把当前目录数据冒充冻结来源）。
     */
    public FrozenAcceptanceIdentity read(Long projectId, String buyerOrderNo) {
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectByProjectKey(projectId, buyerOrderNo);
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
