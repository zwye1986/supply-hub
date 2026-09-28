package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 履约异常处置单，对应 {@code sc_fulfillment_exception_case}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#74-fulfillmentexceptioncase-与奇那梦卡密恢复}。</p>
 */
@TableName("sc_fulfillment_exception_case")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FulfillmentExceptionCaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseNo;
    private Long orderId;
    private Long fulfillmentRequestId;
    private Long bOrderId;
    private Long operationId;
    private String reasonCode;
    private Short status;
    private String resolutionAction;
    private String evidenceRef;
    private byte[] evidenceDigest;
    private Long recoveredAssetId;
    private Long operatorId;
    private String resolutionNote;
    private Integer activeCaseFlag;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}
