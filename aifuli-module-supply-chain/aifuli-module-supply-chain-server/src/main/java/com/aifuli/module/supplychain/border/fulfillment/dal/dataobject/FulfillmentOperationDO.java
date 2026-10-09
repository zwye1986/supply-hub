package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * BOrder 下可独立恢复的外部履约操作；恢复围栏与 checkpoint 密钥身份各自持久化。
 * 华昇字段设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#41-追加迁移。
 */
@TableName("sc_fulfillment_operation")
@Data
@Builder
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
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}
