package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 供应商网络调用脱敏传输审计，对应 {@code sc_fulfillment_call_log}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#10-adapter-元数据调用审计与敏感资产}。</p>
 */
@TableName("sc_fulfillment_call_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FulfillmentCallLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 供应商调用稳定审计编号，前缀 CL_ */
    private String callNo;
    private Long operationId;
    private Long bOrderId;
    private Integer attemptNo;
    private String direction;
    private Long connectorVersionId;
    private String endpointCode;
    private LocalDateTime startedAt;
    private Long durationMs;
    private String resultClass;
    private Integer httpStatus;
    private byte[] requestDigest;
    private byte[] responseDigest;
    private Integer redactionVersion;
    private String traceId;
    private LocalDateTime createdAt;
}
