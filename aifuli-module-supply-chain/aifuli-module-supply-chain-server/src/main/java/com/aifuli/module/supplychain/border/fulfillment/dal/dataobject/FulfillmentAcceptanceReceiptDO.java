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
    private String buyerSkuCode;
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
     * 受理请求指纹格式版本：0历史未验证（含用户身份成分，不可用于重放比较），2无用户规范化格式。
     * 与DDL COMMENT语义一致，见052变更集。
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
