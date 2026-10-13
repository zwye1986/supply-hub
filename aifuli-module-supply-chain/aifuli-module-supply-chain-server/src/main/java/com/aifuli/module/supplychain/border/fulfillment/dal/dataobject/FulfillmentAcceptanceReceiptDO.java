package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_fulfillment_acceptance_receipt")
@Data
@Builder
public class FulfillmentAcceptanceReceiptDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String receiptNo;
    private Long buyerId;
    /** 采购项目内部主键；历史受理回执可为空（项目永久键=project_id+buyer_order_no，见052生成列） */
    private Long projectId;
    private String buyerOrderNo;
    /** 采购商商品编码快照：格式0/2为首次请求编码，格式3为服务端解析的BPD编码 */
    private String buyerSkuCode;
    /** 格式3首次请求的平台SKU编码；格式0/2为空 */
    private String requestedPlatformSkuCode;
    /** 受理时冻结的平台SKU内部主键；历史回执未冻结该绑定时为空 */
    private Long platformSkuId;
    private Long quantity;
    private String targetSchemaCode;
    /** 受理时冻结的履约目标Schema版本；历史回执未冻结该绑定时为空 */
    private Integer targetSchemaVersion;
    private byte[] targetDigest;
    private byte[] invariantFingerprint;
    private byte[] requestFingerprint;
    /**
     * 受理请求指纹格式版本：0历史未验证不可重放，2采购商编码无用户格式，3平台SKU编码无用户格式。
     * 与064变更集DDL COMMENT语义一致。
     * 设计见 docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#6-数据增量与存量安全。
     */
    private Short fingerprintVersion;
    /** 受理时消费的标准商品报价凭证主键；历史回执未绑定报价时为空 */
    private Long pricingQuoteId;
    private Integer listingVersionNo;
    private Long listingVersionId;
    private Integer attemptNo;
    private Short status;
    private Long resultOrderId;
    private String resultOrderNo;
    private String resultFulfillmentRequestNo;
    private String rejectionCode;
    private LocalDateTime acceptedAt;
    private LocalDateTime rejectedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
}
