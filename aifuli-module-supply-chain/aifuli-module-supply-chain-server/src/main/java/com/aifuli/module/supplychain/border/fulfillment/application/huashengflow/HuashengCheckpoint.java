package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 华昇原单的不可变恢复事实，不含认证材料、资金指令或持久对象。
 *
 * <p>绑定与 PLACE 参数一经受理固定；观察仅接受已核身份的订单端点金额。
 * 数组双向防御复制；所有字符串表示和通用 JSON 序列化均禁止泄露明文。
 * 本类不判断退款可信性或提交资金，后续 lifecycle/guard 在锁后负责推进。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#42-检查点-v1。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengCheckpoint {
    private final Binding binding;
    private final PlaceParameters placeParameters;
    private final String orderSn;
    private final Observation observation;
    private final Progress progress;
    private final byte[] pendingAssetPayload;

    /** 核内部原单身份；缺金额保持缺省，不用配置成本补值。设计同 §4.2.1。 */
    public HuashengCheckpoint(Binding binding, PlaceParameters placeParameters, String orderSn,
            Observation observation, Progress progress, byte[] pendingAssetPayload) {
        require(binding != null && placeParameters != null && progress != null);
        // outOrderSn 来自冻结 billno，不等同 bOrderNo；锁后 billno 核验由 lifecycle 承担（设计 §3.3）。
        if (orderSn != null) text(orderSn, 128);
        if (observation != null) {
            require(Objects.equals(orderSn, observation.orderSn())
                    && placeParameters.outOrderSn().equals(observation.outOrderSn()));
        }
        require(pendingAssetPayload == null || pendingAssetPayload.length > 0
                && pendingAssetPayload.length <= HuashengFlowCodes.CHECKPOINT_MAX_BYTES);
        this.binding = binding;
        this.placeParameters = placeParameters;
        this.orderSn = orderSn;
        this.observation = observation;
        this.progress = progress;
        this.pendingAssetPayload = pendingAssetPayload == null ? null : pendingAssetPayload.clone();
    }

    public Binding binding() { return binding; }
    public PlaceParameters placeParameters() { return placeParameters; }
    public String placeParametersDigest() { return HuashengCheckpointCodec.placeDigest(placeParameters); }
    public String orderSn() { return orderSn; }
    public Observation observation() { return observation; }
    public Progress progress() { return progress; }
    public byte[] pendingAssetPayload() { return pendingAssetPayload == null ? null : pendingAssetPayload.clone(); }
    @Override public String toString() { return "HuashengCheckpoint[redacted]"; }

    /** 锁后实体提供的绑定；AAD 不能取密文内自报值。数组双向复制。设计同 §4.2。 */
    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    public record Binding(long bOrderId, String bOrderNo, String operationNo, int sequenceNo, AdapterStepCode step,
                          long connectorVersionId, long offerVersionId, byte[] configDigest) {
        public Binding {
            require(bOrderId > 0 && connectorVersionId > 0 && offerVersionId > 0 && sequenceNo > 0);
            text(bOrderNo, 32); text(operationNo, 32);
            require(bOrderNo.indexOf('|') < 0 && operationNo.indexOf('|') < 0);
            require(step == AdapterStepCode.PLACE_ORDER || step == AdapterStepCode.CONFIRM_ORDER
                    || step == AdapterStepCode.TAKE_CARD || step == AdapterStepCode.QUERY_ORDER);
            require(configDigest != null && configDigest.length == HuashengFlowCodes.DIGEST_BYTES);
            configDigest = configDigest.clone();
        }
        @Override public byte[] configDigest() { return configDigest.clone(); }
        @Override public String toString() { return "HuashengCheckpoint.Binding[redacted]"; }
    }

    /** 规范化、可重建但不可漂移的 PLACE 参数；不含 token/appId/签名。设计同 §3.3、§4.2.1。 */
    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    public record PlaceParameters(String outOrderSn, long couponId, int num, int expireTime,
                                  String outMobile, String notifyUrl) {
        public PlaceParameters {
            text(outOrderSn, 32); text(notifyUrl, HuashengFlowCodes.CHECKPOINT_MAX_STRING_LENGTH);
            require(couponId > 0 && num == HuashengFlowCodes.TYPE201_ORDER_QUANTITY
                    && expireTime >= HuashengFlowCodes.EXPIRE_TIME_MIN_SECONDS
                    && expireTime <= HuashengFlowCodes.EXPIRE_TIME_MAX_SECONDS);
            if (outMobile != null) require(outMobile.length() <= HuashengFlowCodes.CHECKPOINT_MAX_STRING_LENGTH);
        }
        @Override public String toString() { return "HuashengCheckpoint.PlaceParameters[redacted]"; }
    }

    /** 金额来源只可为订单端点；缺省退款/提卡事实不造默认值。设计同 §4.2.1、§6。 */
    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    public record Observation(HuashengEndpoint endpoint, String orderSn, String outOrderSn, int num,
                              String totalPriceRaw, long totalPriceMinorUnits, HuashengOrderStatus status,
                              HuashengField<HuashengRefundStatus> refundStatus, HuashengField<HuashengTakeStatus> isTake) {
        public Observation {
            require(endpoint == HuashengEndpoint.PLACE || endpoint == HuashengEndpoint.CONFIRM
                    || endpoint == HuashengEndpoint.QUERY || endpoint == HuashengEndpoint.CALLBACK);
            text(orderSn, 128); text(outOrderSn, 32);
            // 新失败回调保留零交付；历史 V1 CALLBACK/FAILED/num=1 仅兼容读取。
            // 其它端点仍是原单数量，禁止零数量观察混入订单恢复。
            // 设计：docs/superpowers/specs/2026-10-12-huasheng-fulfillment-hardening-design.md#22-检查点与兼容。
            boolean failedCallbackZero = endpoint == HuashengEndpoint.CALLBACK
                    && status == HuashengOrderStatus.FAILED && num == HuashengFlowCodes.FAILED_CALLBACK_DELIVERY_QUANTITY;
            require((num == HuashengFlowCodes.TYPE201_ORDER_QUANTITY || failedCallbackZero) && status != null
                    && refundStatus != null && isTake != null);
            // 只核持久值不变量；协议入口仍用 A parser，不引用其内部结果载体（设计 §4.2.1）。
            require(totalPriceRaw != null && totalPriceRaw.length() <= 32
                    && totalPriceRaw.matches("[0-9]+(\\.[0-9]{1,4})?"));
            try {
                require(new BigDecimal(totalPriceRaw).movePointRight(2).setScale(0, RoundingMode.HALF_UP)
                        .longValueExact() == totalPriceMinorUnits);
            } catch (ArithmeticException invalidMoney) {
                throw new IllegalArgumentException(HuashengFlowCodes.INVALID_CHECKPOINT);
            }
        }
        @Override public String toString() { return "HuashengCheckpoint.Observation[redacted]"; }
    }

    /** 整笔累计计数和首发送截止；不是当前 operation 的 generation/lease。设计同 §4.1、§5.3。 */
    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    public record Progress(int queryCount, int confirmCount, int takeCount, LocalDateTime firstSendAt,
                           LocalDateTime recoveryDeadlineAt, String originTraceId) {
        public Progress {
            require(queryCount >= 0 && confirmCount >= 0 && takeCount >= 0);
            require((firstSendAt == null) == (recoveryDeadlineAt == null));
            if (firstSendAt != null) {
                require(recoveryDeadlineAt.isAfter(firstSendAt));
                require(firstSendAt.getNano() % 1_000_000 == 0 && recoveryDeadlineAt.getNano() % 1_000_000 == 0);
            }
            if (originTraceId != null) text(originTraceId, 128);
        }
        @Override public String toString() { return "HuashengCheckpoint.Progress[redacted]"; }
    }

    static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException(HuashengFlowCodes.INVALID_CHECKPOINT);
    }

    static String text(String value, int maxLength) {
        require(value != null && !value.isBlank() && value.length() <= maxLength);
        return value;
    }
}
