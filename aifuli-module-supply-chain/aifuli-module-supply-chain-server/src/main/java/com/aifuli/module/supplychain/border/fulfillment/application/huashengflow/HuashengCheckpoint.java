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
    private final NotCreatedCandidate notCreatedCandidate;

    /** 核内部原单身份；缺金额保持缺省，不用配置成本补值。设计同 §4.2.1。 */
    public HuashengCheckpoint(Binding binding, PlaceParameters placeParameters, String orderSn,
            Observation observation, Progress progress, byte[] pendingAssetPayload) {
        this(binding, placeParameters, orderSn, observation, progress, pendingAssetPayload, null);
    }

    /** V2 完整构造：在 V1 事实之上携带可信未建单候选（可为 null 表示无候选，兼容 V1 读取）。 */
    public HuashengCheckpoint(Binding binding, PlaceParameters placeParameters, String orderSn,
            Observation observation, Progress progress, byte[] pendingAssetPayload, NotCreatedCandidate notCreatedCandidate) {
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
        this.notCreatedCandidate = notCreatedCandidate;
    }

    public Binding binding() { return binding; }
    public PlaceParameters placeParameters() { return placeParameters; }
    public String placeParametersDigest() { return HuashengCheckpointCodec.placeDigest(placeParameters); }
    public String orderSn() { return orderSn; }
    public Observation observation() { return observation; }
    public Progress progress() { return progress; }
    public byte[] pendingAssetPayload() { return pendingAssetPayload == null ? null : pendingAssetPayload.clone(); }
    /** 可信未建单候选事实；null 表示未进入或 V1 兼容读取。 */
    public NotCreatedCandidate notCreatedCandidate() { return notCreatedCandidate; }
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

    /**
     * 可信未建单候选的不可变恢复事实（V2 新增；V1 缺失字段兼容读取为无候选）。
     *
     * <p>职责：持久化首次创建拒绝后的退避查单状态——最早拒绝码、首次拒绝时间、
     * 有效 7014 累计次数、最近查询时间与永久退出标记；与结果状态及 Timer 意图同事务更新，
     * 不能仅保存在内存、重启清零或由客户端传次数。
     *
     * <p>不变量：次数为 0～4（人工第4次核实持久化后等待独立确认，不自动动账）；
     * 时间毫秒精度与 Progress 对齐；exited=true 表示已查到订单或产生成功/交付事实，
     * 永久退出该候选分支，不得被后续 7014 覆盖。
     *
     * <p>设计路径与章节锚点：
     * docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#54-可信未建单的最终失败。
     */
    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    public record NotCreatedCandidate(int earliestRejectionCode, LocalDateTime firstRejectionAt,
                                      int validNotCreatedQueryCount, LocalDateTime lastQueryAt,
                                      LocalDateTime nextQueryAt, boolean exited, int queryResultCount) {
        /** 兼容缺少查询序次的V2：已有有效次数是已终止查询次数的下界，不补造有效证据。设计 §7。 */
        public NotCreatedCandidate(int earliestRejectionCode, LocalDateTime firstRejectionAt,
                                   int validNotCreatedQueryCount, LocalDateTime lastQueryAt,
                                   LocalDateTime nextQueryAt, boolean exited) {
            this(earliestRejectionCode, firstRejectionAt, validNotCreatedQueryCount, lastQueryAt,
                    nextQueryAt, exited, validNotCreatedQueryCount);
        }

        public NotCreatedCandidate {
            require(HuashengErrorClassification.isPlaceCreationRejection(earliestRejectionCode));
            require(firstRejectionAt != null && nextQueryAt != null);
            require(validNotCreatedQueryCount >= 0
                    && validNotCreatedQueryCount <= HuashengErrorClassification.REQUIRED_VALID_NOT_CREATED_QUERIES);
            require(queryResultCount >= validNotCreatedQueryCount);
            require(firstRejectionAt.getNano() % 1_000_000 == 0 && nextQueryAt.getNano() % 1_000_000 == 0);
            if (lastQueryAt != null) {
                require(lastQueryAt.getNano() % 1_000_000 == 0);
            }
        }
        @Override public String toString() { return "HuashengCheckpoint.NotCreatedCandidate[redacted]"; }
    }

    static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException(HuashengFlowCodes.INVALID_CHECKPOINT);
    }

    static String text(String value, int maxLength) {
        require(value != null && !value.isBlank() && value.length() <= maxLength);
        return value;
    }
}
