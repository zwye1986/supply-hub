package com.aifuli.module.supplychain.border.instore.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 到店购外部操作 DO，对应 {@code sc_instore_operation}。
 *
 * <p>维护建单、查支付、取消支付、退款与查退款等可恢复业务副作用；PREORDER 操作的 operation_no 同时作为唯一 invocationId。
 * 与 O2O 操作区别：无 CHANNEL_NOTIFY 操作类型（旅通为 pull 模型，中台不推送渠道）。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#4-中台侧场景详情模块}。</p>
 */
@TableName("sc_instore_operation")
@Data
@Builder
public class InstoreOperationDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String operationNo;
    private Long instoreOrderId;
    private Long paymentFlowId;
    private Long refundCaseId;
    private Long supplierOrderId;
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
