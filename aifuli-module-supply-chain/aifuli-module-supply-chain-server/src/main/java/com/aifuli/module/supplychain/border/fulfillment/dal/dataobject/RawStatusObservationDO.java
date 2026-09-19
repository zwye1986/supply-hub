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
 * 供应商原始状态观察，对应 {@code sc_raw_status_observation}。
 *
 * <p>只保存摘要与原始状态码，不落明文卡密。详见
 * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#10-adapter-元数据调用审计与敏感资产}。</p>
 */
@TableName("sc_raw_status_observation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawStatusObservationDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String observationNo;
    private String observationKey;
    private String sourceType;
    private Long operationId;
    private Long bOrderId;
    private Long connectorVersionId;
    private String externalOrderNo;
    private String externalStatus;
    private String externalSubStatus;
    private Long mappingDefinitionId;
    private String mappedResultClass;
    private byte[] payloadDigest;
    private LocalDateTime observedAt;
    private LocalDateTime receivedAt;
    private String traceId;
}
