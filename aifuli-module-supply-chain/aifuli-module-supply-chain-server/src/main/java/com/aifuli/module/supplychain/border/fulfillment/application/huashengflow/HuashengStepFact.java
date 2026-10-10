package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentInternalOnlySerializer;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentProtocolFact;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.List;
import java.util.Objects;

/**
 * 华昇单步骤执行纯协议事实。
 *
 * <p>职责：
 * 承载向华昇发起单次步骤（PLACE/CONFIRM/QUERY/TAKE）后的领域纯事实数据，
 * 绝不携带 Mapper、A5 对象、Throwable、原始报文或资金动作。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#31-边界与责任矩阵}
 * 以及 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#33-冻结投影与首单-checkpoint}。</p>
 */
@JsonSerialize(using = FulfillmentInternalOnlySerializer.class)
public final class HuashengStepFact implements FulfillmentProtocolFact {

    public enum Endpoint {
        PLACE, CONFIRM, QUERY, TAKE, CALLBACK
    }

    public enum StepResult {
        ACCEPTED, RESULT_UNKNOWN
    }

    public enum Status {
        WAIT, SUCCESS, FAILED, CANCEL, UNKNOWN
    }

    public enum RefundStatus {
        ABSENT, NONE, WAIT, SUCCESS
    }

    public enum IsTake {
        ABSENT, N, Y
    }

    /** 物理外呼安全尝试观察事实（不包含明文报文、密钥或 Throwable）。 */
    @JsonSerialize(using = FulfillmentInternalOnlySerializer.class)
    public record AttemptObservation(
            String endpoint,
            Integer httpStatus,
            Integer rawBusinessCode,
            long durationMs,
            long requestBytes,
            long responseBytes,
            int physicalSendCount,
            boolean wasBusinessCallStarted
    ) {
        @Override
        public String toString() {
            return "AttemptObservation[endpoint=" + endpoint + ", httpStatus=" + httpStatus + ", code=" + rawBusinessCode + "]";
        }
    }

    private final Endpoint endpoint;
    private final StepResult resultClass;
    private final String outOrderSn;
    private final String orderSn;
    private final int num;
    private final String totalPriceRaw;
    private final Long totalPriceMinorUnits;
    private final Status status;
    private final RefundStatus refundStatus;
    private final IsTake isTake;
    private final byte[] protectedAssetPayload;
    private final List<AttemptObservation> attempts;

    public HuashengStepFact(
            Endpoint endpoint,
            StepResult resultClass,
            String outOrderSn,
            String orderSn,
            int num,
            String totalPriceRaw,
            Long totalPriceMinorUnits,
            Status status,
            RefundStatus refundStatus,
            IsTake isTake,
            byte[] protectedAssetPayload,
            List<AttemptObservation> attempts
    ) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint is required");
        this.resultClass = Objects.requireNonNull(resultClass, "resultClass is required");
        this.outOrderSn = Objects.requireNonNull(outOrderSn, "outOrderSn is required");
        this.orderSn = orderSn;
        this.num = num;
        this.totalPriceRaw = totalPriceRaw;
        this.totalPriceMinorUnits = totalPriceMinorUnits;
        this.status = status;
        this.refundStatus = refundStatus == null ? RefundStatus.ABSENT : refundStatus;
        this.isTake = isTake == null ? IsTake.ABSENT : isTake;
        this.protectedAssetPayload = protectedAssetPayload == null ? null : protectedAssetPayload.clone();
        this.attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    public Endpoint endpoint() { return endpoint; }
    public StepResult resultClass() { return resultClass; }
    public String outOrderSn() { return outOrderSn; }
    public String orderSn() { return orderSn; }
    public int num() { return num; }
    public String totalPriceRaw() { return totalPriceRaw; }
    public Long totalPriceMinorUnits() { return totalPriceMinorUnits; }
    public Status status() { return status; }
    public RefundStatus refundStatus() { return refundStatus; }
    public IsTake isTake() { return isTake; }
    public byte[] protectedAssetPayload() { return protectedAssetPayload == null ? null : protectedAssetPayload.clone(); }
    public List<AttemptObservation> attempts() { return attempts; }

    @Override
    public String toString() {
        return "HuashengStepFact[endpoint=" + endpoint + ", result=" + resultClass + ", outOrderSn=" + outOrderSn + "]";
    }
}
