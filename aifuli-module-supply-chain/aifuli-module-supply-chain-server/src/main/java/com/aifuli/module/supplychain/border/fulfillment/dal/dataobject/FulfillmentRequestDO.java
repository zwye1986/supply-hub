package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_fulfillment_request")
@Data
@Builder
public class FulfillmentRequestDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestNo;
    private Long orderId;
    private Long buyerId;
    /** 结算方分销商内部主键，逻辑引用sc_buyer.id；随首次资金预留冻结。
     * 设计：docs/superpowers/specs/2026-10-14-project-settlement-buyer-design.md#3-数据与资金。 */
    private Long settlementBuyerId;

    /** 历史 NULL 预留归属于原 Buyer；新请求必须使用首次冻结的资金身份。 */
    public Long fundsBuyerId() {
        return settlementBuyerId == null ? buyerId : settlementBuyerId;
    }
    private Long listingId;
    private Long listingVersionId;
    private Long platformSkuId;
    private String targetSchemaCode;
    private Integer targetSchemaVersion;
    private byte[] targetCiphertext;
    private byte[] targetDigest;
    private String targetKeyVersion;
    private Long requestedQuantity;
    private Long fulfilledQuantity;
    private Long activeBOrderId;
    private Short status;
    private Long aggregateVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
