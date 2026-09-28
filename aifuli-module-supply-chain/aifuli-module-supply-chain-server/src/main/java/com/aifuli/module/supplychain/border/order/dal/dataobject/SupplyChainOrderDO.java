package com.aifuli.module.supplychain.border.order.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_supply_chain_order")
@Data
@Builder
public class SupplyChainOrderDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String orderType;
    private Long buyerId;
    /** 采购项目内部主键；历史订单及非项目场景可为空 */
    private Long projectId;
    /** 标准商品报价凭证主键；O2O/INSTORE为空 */
    private Long pricingQuoteId;
    /** 冻结的项目SKU综合定价版本主键 */
    private Long pricingVersionId;
    private String buyerCodeSnapshot;
    private String buyerNameSnapshot;
    private String buyerUserId;
    /** 履约目标手机号；仅直充与账户绑定权益写入，卡密类为空 */
    private String fulfillmentAccountValue;
    private String buyerOrderNo;
    /** 受理时项目业务编码快照；CARD场景可空（044 变更集新增列，订单视图回显所属项目） */
    private String projectCodeSnapshot;
    /** 受理时项目名称快照；CARD场景可空（044 变更集新增列，订单视图回显所属项目） */
    private String projectNameSnapshot;
    private Long acceptanceReceiptId;
    private Long displaySupplierId;
    private String displaySupplierCode;
    private String displaySupplierName;
    private Long buyerSettlementAmount;
    /** 冻结的商品销售单价，单位CNY分 */
    private Long salesUnitPriceCents;
    /** 冻结的商品小计，单位CNY分 */
    private Long productSubtotalCents;
    /** 冻结的平台服务费，单位CNY分 */
    private Long serviceFeeCents;
    /** 冻结的用户应付总额，单位CNY分 */
    private Long userPayableAmountCents;
    /** 后续标准售后可全额退还的服务费快照，单位CNY分 */
    private Long refundableServiceFeeCents;
    /** 冻结的税务展示属性JSON */
    private String taxMetadataJson;
    private String currency;
    private Long platformSkuId;
    private String skuCodeSnapshot;
    private String skuNameSnapshot;
    private Long quantity;
    private byte[] buyerOrderDigest;
    private Short orderStatus;
    private Short aftersaleStatus;
    private String businessStage;
    private String detailType;
    private Long detailId;
    private Long detailVersion;
    private byte[] requestFingerprint;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
