package com.aifuli.module.supplychain.border.fulfillment.domain;

import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepAction;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Adapter产出的认证事实，不是资金指令；原绑定/目标摘要用于锁后核验，不携明文手机号或原报文。
 * 安全失败必须显式分类，不能伪装成普通解析未知；资金动作由受控生命周期决定，回调HTTP/Inbox另行接入。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#76-t04公共终态与资金切片2026-10-18。
 */
public record SingleStepSupplierFact(SingleStepCheckpoint.Binding binding, SingleStepAction source,
                                    String billno, String targetDigest, long quantity,
                                    Verification verification, Observation observation, String externalOrderNo,
                                    LocalDateTime receivedAt, byte[] payloadDigest,
                                    Long reportedSettlementAmount, boolean zeroDeliveryConfirmed) implements FulfillmentProtocolFact {
    /** T03非资金事实兼容入口；没有明确零交付合同就不授予失败释放资格。设计同详细设计§7.6。 */
    public SingleStepSupplierFact(SingleStepCheckpoint.Binding binding, SingleStepAction source, String billno,
                                  String targetDigest, long quantity, Verification verification, Observation observation,
                                  String externalOrderNo, LocalDateTime receivedAt, byte[] payloadDigest) {
        this(binding, source, billno, targetDigest, quantity, verification, observation, externalOrderNo,
                receivedAt, payloadDigest, null, false);
    }

    public SingleStepSupplierFact {
        Objects.requireNonNull(binding); Objects.requireNonNull(source); Objects.requireNonNull(verification);
        Objects.requireNonNull(observation); Objects.requireNonNull(receivedAt);
        if (billno == null || !billno.matches("[0-9]{24}") || targetDigest == null
                || !targetDigest.matches("[0-9a-f]{64}") || quantity != 1
                || payloadDigest == null || payloadDigest.length != 32
                || externalOrderNo != null && (externalOrderNo.isBlank() || externalOrderNo.length() > 128)) {
            throw new IllegalArgumentException("单步骤协议事实无效");
        }
        payloadDigest = payloadDigest.clone();
    }
    public enum Verification { VERIFIED, AUTHENTICATION_FAILED, IDENTITY_CONFLICT, UNVERIFIED }
    public enum Observation { ACCEPTED, PROCESSING, EXISTS_UNKNOWN, NOT_FOUND, SUCCESS, FAILURE, RESPONSE_UNKNOWN }
    @Override public byte[] payloadDigest() { return payloadDigest.clone(); }
    @Override public String toString() { return "SingleStepSupplierFact[redacted]"; }
}
