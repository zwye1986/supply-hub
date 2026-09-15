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
    private String buyerCodeSnapshot;
    private String buyerNameSnapshot;
    private String buyerUserId;
    /** 履约目标手机号；仅直充与账户绑定权益写入，卡密类为空 */
    private String fulfillmentAccountValue;
    private String buyerOrderNo;
    private Long acceptanceReceiptId;
    private Long displaySupplierId;
    private String displaySupplierCode;
    private String displaySupplierName;
    private Long buyerSettlementAmount;
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
