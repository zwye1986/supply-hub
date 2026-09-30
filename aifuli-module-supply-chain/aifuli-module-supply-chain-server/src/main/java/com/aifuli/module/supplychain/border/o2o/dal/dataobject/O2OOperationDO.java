package com.aifuli.module.supplychain.border.o2o.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * O2O 外部操作 DO，对应 {@code sc_o2o_operation}。
 *
 * <p>维护预下单、查单、退款与渠道通知等可恢复业务副作用；PREORDER 操作的 operation_no 同时作为唯一 invocationId。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等}。</p>
 */
@TableName("sc_o2o_operation")
@Data
@Builder
public class O2OOperationDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String operationNo;
    private Long o2oDetailId;
    private Long refundCaseId;
    private String operationType;
    private String idempotencyKey;
    private byte[] requestFingerprint;
    private String externalOperationNo;
    private Short status;
    private String resultClass;
    private Integer attemptCount;
    private LocalDateTime nextRecoverAt;
    private byte[] responseDigest;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
