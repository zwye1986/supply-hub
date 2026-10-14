package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HuashengFlowPolicyTest {

    private static final String OUT_ORDER_SN = "BILL_20261009_001";
    private static final String ORDER_SN = "HS_ORDER_888";
    private static final long EXPECTED_COST = 999L; // 9.99 CNY
    private static final String PRICE_RAW = "9.99";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 12, 0, 0);

    @Test
    void missingUntakenProofAndHistoricalSuccessBlockFailureEvenWithRefundSuccess() {
        var cp = checkpointWithOrderSn();
        var missingTake = new HuashengStepFact(HuashengStepFact.Endpoint.QUERY, HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST, HuashengStepFact.Status.CANCEL,
                HuashengStepFact.RefundStatus.SUCCESS, HuashengStepFact.IsTake.ABSENT, null, List.of());
        assertThat(HuashengFlowPolicy.decide(cp, missingTake, EXPECTED_COST, NOW)).isEqualTo(HuashengFlowDecision.MANUAL);
        var old = cp.observation();
        var success = new HuashengCheckpoint.Observation(old.endpoint(), old.orderSn(), old.outOrderSn(), old.num(),
                old.totalPriceRaw(), old.totalPriceMinorUnits(),
                com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderStatus.SUCCESS,
                old.refundStatus(), old.isTake());
        var historical = new HuashengCheckpoint(cp.binding(), cp.placeParameters(), cp.orderSn(), success, cp.progress(), null);
        var cancelled = new HuashengStepFact(HuashengStepFact.Endpoint.QUERY, HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST, HuashengStepFact.Status.CANCEL,
                HuashengStepFact.RefundStatus.SUCCESS, HuashengStepFact.IsTake.N, null, List.of());
        assertThat(HuashengFlowPolicy.decide(historical, cancelled, EXPECTED_COST, NOW)).isEqualTo(HuashengFlowDecision.MANUAL);
    }

    @Test
    void fourthNotCreatedQueryCannotBypassOriginalIdentityOrKnownOrder() {
        var cp = initialCheckpoint();
        var candidate = new HuashengCheckpoint.NotCreatedCandidate(7010, NOW.minusMinutes(2), 3,
                NOW.minusSeconds(60), NOW, false);
        var active = new HuashengCheckpoint(cp.binding(), cp.placeParameters(), null, null, cp.progress(), null, candidate);
        var fourth = new HuashengStepFact(HuashengStepFact.Endpoint.QUERY, HuashengStepFact.StepResult.RESULT_UNKNOWN,
                OUT_ORDER_SN, null, 1, null, null, HuashengStepFact.Status.UNKNOWN, null, null, null, List.of(), 7014, null, null);
        assertThat(HuashengFlowPolicy.decide(active, fourth, EXPECTED_COST, NOW)).isEqualTo(HuashengFlowDecision.FINAL_NOT_CREATED);
        var wrong = new HuashengStepFact(HuashengStepFact.Endpoint.QUERY, HuashengStepFact.StepResult.RESULT_UNKNOWN,
                "wrong-original-order", null, 1, null, null, HuashengStepFact.Status.UNKNOWN, null, null, null, List.of(), 7014, null, null);
        assertThat(HuashengFlowPolicy.decide(active, wrong, EXPECTED_COST, NOW)).isEqualTo(HuashengFlowDecision.MANUAL);

        // 配额在认领时已消耗：第20次合法QUERY仍能提供第4份有效证据，只禁止第21次认领。
        var lastClaim = new HuashengCheckpoint(active.binding(), active.placeParameters(), null, null,
                new HuashengCheckpoint.Progress(20, 0, 0, NOW.minusMinutes(10), NOW.plusMinutes(20), "trace"), null, candidate);
        assertThat(HuashengFlowPolicy.decide(lastClaim, fourth, EXPECTED_COST, NOW)).isEqualTo(HuashengFlowDecision.FINAL_NOT_CREATED);
    }

    private HuashengCheckpoint initialCheckpoint() {
        return initialCheckpoint(0, 0, 0, null, null);
    }

    private HuashengCheckpoint initialCheckpoint(int queryCount, int confirmCount, int takeCount,
                                                 LocalDateTime firstSendAt, LocalDateTime deadlineAt) {
        var binding = new HuashengCheckpoint.Binding(100L, "BO_001", "OP_001", 1,
                AdapterStepCode.PLACE_ORDER, 200L, 300L, new byte[32]);
        var place = new HuashengCheckpoint.PlaceParameters(OUT_ORDER_SN, 12345L, 1, 300, "13800138000", "http://callback");
        var progress = new HuashengCheckpoint.Progress(queryCount, confirmCount, takeCount, firstSendAt, deadlineAt, "TRACE_001");
        return new HuashengCheckpoint(binding, place, null, null, progress, null);
    }

    private HuashengCheckpoint checkpointWithOrderSn() {
        var binding = new HuashengCheckpoint.Binding(100L, "BO_001", "OP_001", 1,
                AdapterStepCode.PLACE_ORDER, 200L, 300L, new byte[32]);
        var place = new HuashengCheckpoint.PlaceParameters(OUT_ORDER_SN, 12345L, 1, 300, "13800138000", "http://callback");
        var observation = new HuashengCheckpoint.Observation(
                com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint.PLACE,
                ORDER_SN, OUT_ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderStatus.WAIT,
                HuashengField.ofValue(com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengRefundStatus.NONE),
                HuashengField.ofValue(com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTakeStatus.N)
        );
        var progress = new HuashengCheckpoint.Progress(0, 0, 0, NOW, NOW.plusMinutes(30), "TRACE_001");
        return new HuashengCheckpoint(binding, place, ORDER_SN, observation, progress, null);
    }

    private byte[] sampleType201Payload() {
        // schema version 1 type 201 valid payload
        String json = "{\"payloadSchemaVersion\":1,\"assetType\":\"CARD_CODE_SECRET\",\"cardCode\":\"TEST_CARD_CODE\",\"cardSecret\":\"TEST_CARD_PASS\",\"supplierPayload\":{\"adapterType\":\"HUA_SHENG_ORDER_V1\",\"type\":201,\"id\":null,\"code\":\"TEST_CARD_CODE\",\"pass\":\"TEST_CARD_PASS\",\"validTime\":\"长期有效\"}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void successfulFulfillmentDoesNotRequireRawYuanContinuity() {
        var cp = checkpointWithOrderSn();
        var changed = new HuashengStepFact(HuashengStepFact.Endpoint.QUERY, HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, "9.994", EXPECTED_COST, HuashengStepFact.Status.SUCCESS,
                HuashengStepFact.RefundStatus.NONE, HuashengStepFact.IsTake.N, null, List.of());
        assertThat(HuashengFlowPolicy.decide(cp, changed, EXPECTED_COST, NOW)).isEqualTo(HuashengFlowDecision.NEXT_TAKE);
        var sameValue = new HuashengStepFact(HuashengStepFact.Endpoint.QUERY, HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, "9.9900", EXPECTED_COST, HuashengStepFact.Status.SUCCESS,
                HuashengStepFact.RefundStatus.NONE, HuashengStepFact.IsTake.N, null, List.of());
        assertThat(HuashengFlowPolicy.decide(cp, sameValue, EXPECTED_COST, NOW)).isEqualTo(HuashengFlowDecision.NEXT_TAKE);
    }

    @Test
    @DisplayName("WAIT -> NEXT_CONFIRM")
    void placeWaitProceedsToConfirm() {
        var checkpoint = initialCheckpoint();
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.PLACE,
                HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.WAIT,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.NEXT_CONFIRM);
    }

    @Test
    @DisplayName("SUCCESS -> NEXT_TAKE (而非订单成功)")
    void placeSuccessProceedsToTake() {
        var checkpoint = initialCheckpoint();
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.PLACE,
                HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.SUCCESS,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.NEXT_TAKE);
    }

    @Test
    @DisplayName("FAILED合法退款 -> FINAL_REJECTION (释放候选)")
    void placeFailedWithLegitimateRefundProceedsToRejection() {
        var checkpoint = initialCheckpoint();
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.PLACE,
                HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.FAILED,
                HuashengStepFact.RefundStatus.SUCCESS,
                HuashengStepFact.IsTake.N,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.FINAL_REJECTION);
    }

    @Test
    @DisplayName("FAILED矛盾退款状态 -> MANUAL (不可信)")
    void placeFailedWithContradictoryRefundProceedsToManual() {
        var checkpoint = initialCheckpoint();
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.PLACE,
                HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.FAILED,
                HuashengStepFact.RefundStatus.NONE,
                HuashengStepFact.IsTake.N,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.MANUAL);
    }

    @Test
    @DisplayName("CANCEL缺退款 -> MANUAL (保留)")
    void queryCancelWithoutRefundProceedsToManual() {
        var checkpoint = checkpointWithOrderSn();
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.QUERY,
                HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.CANCEL,
                HuashengStepFact.RefundStatus.NONE,
                HuashengStepFact.IsTake.N,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.MANUAL);
    }

    @Test
    @DisplayName("CANCEL显式退款SUCCESS且未提卡 -> FINAL_CANCELLATION")
    void queryCancelWithRefundSuccessProceedsToRejection() {
        var checkpoint = checkpointWithOrderSn();
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.QUERY,
                HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.CANCEL,
                HuashengStepFact.RefundStatus.SUCCESS,
                HuashengStepFact.IsTake.N,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.FINAL_CANCELLATION);
    }

    @Test
    @DisplayName("QUERY7014/UNKNOWN -> WAIT_QUERY")
    void query7014ProceedsToWaitQuery() {
        var checkpoint = checkpointWithOrderSn();
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.QUERY,
                HuashengStepFact.StepResult.RESULT_UNKNOWN,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.UNKNOWN,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.WAIT_QUERY);
    }

    @Test
    @DisplayName("QUERY7014/UNKNOWN累积达到MAX_QUERY_COUNT(20) -> MANUAL")
    void query7014ReachingMaxQueryCountProceedsToManual() {
        // 第20次已在claim事务计入，apply不再次加一。
        var progress = new HuashengCheckpoint.Progress(20, 0, 0, NOW, NOW.plusMinutes(30), "TRACE_001");
        var binding = new HuashengCheckpoint.Binding(100L, "BO_001", "OP_001", 1,
                AdapterStepCode.PLACE_ORDER, 200L, 300L, new byte[32]);
        var place = new HuashengCheckpoint.PlaceParameters(OUT_ORDER_SN, 12345L, 1, 300, "13800138000", "http://callback");
        var checkpoint = new HuashengCheckpoint(binding, place, ORDER_SN, null, progress, null);

        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.QUERY,
                HuashengStepFact.StepResult.RESULT_UNKNOWN,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.UNKNOWN,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.MANUAL);
    }

    @Test
    @DisplayName("QUERY SUCCESS/isTake=Y -> NEXT_TAKE")
    void querySuccessIsTakeYProceedsToTake() {
        var checkpoint = checkpointWithOrderSn();
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.QUERY,
                HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.SUCCESS,
                HuashengStepFact.RefundStatus.NONE,
                HuashengStepFact.IsTake.Y,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.NEXT_TAKE);
    }

    @Test
    @DisplayName("TAKE且资产有效 -> FINAL_SUCCESS")
    void takeWithValidAssetProceedsToSuccess() {
        var checkpoint = checkpointWithOrderSn();
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.TAKE,
                HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, null, null,
                HuashengStepFact.Status.SUCCESS,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                sampleType201Payload(), List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.FINAL_SUCCESS);
    }

    @Test
    @DisplayName("合法价差WAIT -> 仅QUERY保护")
    void moneyMismatchWaitOnlyQueriesOriginalOrder() {
        var checkpoint = initialCheckpoint();
        // 期望999分，返回1000分
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.PLACE,
                HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, "10.00", 1000L,
                HuashengStepFact.Status.WAIT,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.WAIT_QUERY);
    }

    @Test
    @DisplayName("身份冲突 (outOrderSn不符) -> MANUAL")
    void outOrderSnMismatchProceedsToManual() {
        var checkpoint = initialCheckpoint();
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.PLACE,
                HuashengStepFact.StepResult.ACCEPTED,
                "OTHER_OUT_ORDER_SN", ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.WAIT,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.MANUAL);
    }

    @Test
    @DisplayName("六型/非201载荷 -> MANUAL")
    void non201AssetPayloadProceedsToManual() {
        var checkpoint = checkpointWithOrderSn();
        // type 202 instead of 201
        String non201 = "{\"payloadSchemaVersion\":1,\"assetType\":\"CARD_CODE_SECRET\",\"cardCode\":\"C\",\"cardSecret\":\"P\",\"supplierPayload\":{\"adapterType\":\"HUA_SHENG_ORDER_V1\",\"type\":202,\"id\":null,\"code\":\"C\",\"pass\":\"P\"}}";
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.TAKE,
                HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, null, null,
                HuashengStepFact.Status.SUCCESS,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                non201.getBytes(StandardCharsets.UTF_8), List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.MANUAL);
    }

    @Test
    @DisplayName("超过最大重试限制 -> MANUAL")
    void exceedMaxQueryCountProceedsToManual() {
        // queryCount >= 20
        var checkpoint = initialCheckpoint(20, 0, 0, NOW.minusMinutes(10), NOW.plusMinutes(20));
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.QUERY,
                HuashengStepFact.StepResult.RESULT_UNKNOWN,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.UNKNOWN,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.MANUAL);
    }

    @Test
    @DisplayName("超过恢复截止时间 -> MANUAL")
    void exceedRecoveryDeadlineProceedsToManual() {
        // recoveryDeadlineAt is in the past
        var checkpoint = initialCheckpoint(5, 0, 0, NOW.minusMinutes(40), NOW.minusMinutes(10));
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.QUERY,
                HuashengStepFact.StepResult.RESULT_UNKNOWN,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.UNKNOWN,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.MANUAL);
    }

    @Test
    @DisplayName("CALLBACK SUCCESS且资产有效 -> FINAL_SUCCESS")
    void callbackSuccessWithValidAssetProceedsToFinalSuccess() {
        var checkpoint = checkpointWithOrderSn();
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.CALLBACK,
                HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.SUCCESS,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                sampleType201Payload(), List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.FINAL_SUCCESS);
    }

    @Test
    @DisplayName("CALLBACK FAILED且合法退款 -> FINAL_REJECTION")
    void callbackFailedWithRefundProceedsToFinalRejection() {
        var checkpoint = checkpointWithOrderSn();
        var fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.CALLBACK,
                HuashengStepFact.StepResult.ACCEPTED,
                OUT_ORDER_SN, ORDER_SN, 0, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.FAILED,
                HuashengStepFact.RefundStatus.SUCCESS,
                HuashengStepFact.IsTake.N,
                null, List.of()
        );

        var decision = HuashengFlowPolicy.decide(checkpoint, fact, EXPECTED_COST, NOW);
        assertThat(decision).isEqualTo(HuashengFlowDecision.FINAL_REJECTION);
    }

    @Test
    @DisplayName("FAILED 回调交付数量为零，未提供退款字段仍沿正式失败语义释放")
    void callbackFailedZeroDeliveryWithAbsentRefundFieldsIsTrusted() {
        var fact = new HuashengStepFact(HuashengStepFact.Endpoint.CALLBACK,
                HuashengStepFact.StepResult.ACCEPTED, OUT_ORDER_SN, ORDER_SN, 0, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.FAILED, HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT, null, List.of());
        assertThat(HuashengFlowPolicy.decide(checkpointWithOrderSn(), fact, EXPECTED_COST, NOW))
                .isEqualTo(HuashengFlowDecision.FINAL_REJECTION);
    }

    @Test
    @DisplayName("失败回调不能用订单订购数量一冒充零交付")
    void callbackFailedWithNonzeroDeliveryRemainsManual() {
        var fact = new HuashengStepFact(HuashengStepFact.Endpoint.CALLBACK,
                HuashengStepFact.StepResult.ACCEPTED, OUT_ORDER_SN, ORDER_SN, 1, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.FAILED, HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT, null, List.of());
        assertThat(HuashengFlowPolicy.decide(checkpointWithOrderSn(), fact, EXPECTED_COST, NOW))
                .isEqualTo(HuashengFlowDecision.MANUAL);
    }

    @Test
    @DisplayName("失败回调零交付仍不能覆盖已提卡待核证据")
    void callbackFailedZeroDeliveryCannotReleasePendingAsset() {
        var original = checkpointWithOrderSn();
        var held = new HuashengCheckpoint(original.binding(), original.placeParameters(), original.orderSn(),
                original.observation(), original.progress(), sampleType201Payload());
        var fact = new HuashengStepFact(HuashengStepFact.Endpoint.CALLBACK,
                HuashengStepFact.StepResult.ACCEPTED, OUT_ORDER_SN, ORDER_SN, 0, PRICE_RAW, EXPECTED_COST,
                HuashengStepFact.Status.FAILED, HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT, null, List.of());
        assertThat(HuashengFlowPolicy.decide(held, fact, EXPECTED_COST, NOW))
                .isEqualTo(HuashengFlowDecision.MANUAL);
    }

    @Test
    void requestExpiryAllowsOneReconstructionOnlyWithCompletePreCreationProof() {
        var fact = new HuashengStepFact(HuashengStepFact.Endpoint.PLACE, HuashengStepFact.StepResult.RESULT_UNKNOWN,
                OUT_ORDER_SN, null, 1, null, null, HuashengStepFact.Status.UNKNOWN, null, null, null, List.of(),
                66, com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengSecurityReason.REQUEST_EXPIRED, null);
        var cp = initialCheckpoint();
        assertThat(HuashengFlowPolicy.decide(cp, fact, EXPECTED_COST, NOW, true, 1)).isEqualTo(HuashengFlowDecision.RETRY_PRE_SEND);
        assertThat(HuashengFlowPolicy.decide(cp, fact, EXPECTED_COST, NOW, true, 2)).isEqualTo(HuashengFlowDecision.MANUAL);
        assertThat(HuashengFlowPolicy.decide(cp, fact, EXPECTED_COST, NOW, false, 1)).isEqualTo(HuashengFlowDecision.WAIT_QUERY);
    }
}
