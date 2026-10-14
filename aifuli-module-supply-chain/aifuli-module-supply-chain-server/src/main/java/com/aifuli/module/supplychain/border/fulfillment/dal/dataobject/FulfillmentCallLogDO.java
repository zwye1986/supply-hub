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
    /** 本次调用最终业务错误码，0表示业务成功；未获得响应时为NULL表示未知。 */
    private Integer remoteCode;
    /** 供应商返回的诊断编号，仅保存无控制字符且不超过128字符的合法编号。 */
    private String supplierTraceId;
    /** 平台稳定错误原因码；未分类时为NULL。 */
    private String stableErrorReason;
    /** 本次调用是否已开始业务请求；NULL未知。 */
    private Boolean businessSendStarted;
    /** 结果事实所属执行代次；历史行NULL。 */
    private Long executionGeneration;
    /** 独立结果证据schema版本；历史行NULL。 */
    private Integer evidenceSchemaVersion;
    /** 独立结果证据信封密钥版本；历史行NULL。 */
    private String evidenceKeyVersion;
    /** 独立结果证据密文，AAD绑定调用、原单、版本与代次。 */
    private byte[] evidenceCiphertext;
    private byte[] requestDigest;
    private byte[] responseDigest;
    private Integer redactionVersion;
    private String traceId;
    private LocalDateTime createdAt;
}
