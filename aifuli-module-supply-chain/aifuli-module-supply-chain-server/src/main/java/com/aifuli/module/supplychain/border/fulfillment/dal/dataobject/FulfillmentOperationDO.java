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
 * BOrder 下可独立恢复的外部履约操作；恢复围栏与 checkpoint 密钥身份各自持久化。
 * 华昇字段设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#41-追加迁移。
 */
@TableName("sc_fulfillment_operation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FulfillmentOperationDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String operationNo;
    private Long bOrderId;
    private Long targetAssetId;
    private String operationKind;
    private String adapterStepCode;
    private Integer sequenceNo;
    private String idempotencyKey;
    private byte[] configDigest;
    private String externalOperationNo;
    private Short status;
    private String resultClass;
    private Integer attemptCount;
    private LocalDateTime nextRecoverAt;
    private Integer checkpointSchemaVersion;
    private byte[] checkpointCiphertext;
    /** 恢复检查点信封加密密钥版本。 */
    private String checkpointKeyVersion;
    /** 履约操作认领与恢复围栏代次。 */
    private Long executionGeneration;
    /** 当前履约操作认领到期时间，Asia/Shanghai。 */
    private LocalDateTime executionLeaseUntil;
    /** 本操作已认领的原单查单恢复次数。 */
    private Integer recoveryCount;
    /** 自动恢复截止时间，Asia/Shanghai。 */
    private LocalDateTime recoveryDeadlineAt;
    /** 本操作首次可能发送业务请求的时间，Asia/Shanghai。 */
    private LocalDateTime businessSendPossibleAt;
    /**
     * 本操作无业务副作用证明：1=全部历史发送均为明确建单前拒绝或从未发送业务请求，
     * 0=可能存在业务副作用，NULL=未知（历史行或尚无结果）。
     * 证明必须覆盖本单全部相关历史尝试；缺失即无法证明安全拒绝，不默认无副作用。
     * 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#41-决策输入与所有权。
     */
    private Boolean sideEffectFree;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}
