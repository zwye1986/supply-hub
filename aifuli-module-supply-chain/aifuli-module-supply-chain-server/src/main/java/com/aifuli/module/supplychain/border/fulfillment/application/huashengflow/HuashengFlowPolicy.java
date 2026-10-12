package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

/**
 * 华昇生产履约本地策略与决策核心。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#53-watchdog与计数、#6-协议事实推进规则。
 */
public final class HuashengFlowPolicy {

    /** 单次调用超时预算：120 秒。 */
    public static final int FACADE_TIMEOUT_SECONDS = 120;
    /** 资格租约时长：150 秒。 */
    public static final int LEASE_SECONDS = 150;
    /** QUERY 轮询间隔：30 秒。 */
    public static final int QUERY_INTERVAL_SECONDS = 30;
    /** 从首业务可能发送起自动恢复截止：30 分钟。 */
    public static final int RECOVERY_DEADLINE_MINUTES = 30;
    /** 最大 QUERY 认领次数：20。 */
    public static final int MAX_QUERY_COUNT = 20;
    /** 最大 CONFIRM 认领次数：3。 */
    public static final int MAX_CONFIRM_COUNT = 3;
    /** 最大 TAKE 认领次数：5。 */
    public static final int MAX_TAKE_COUNT = 5;
    /** 准备 watchdog 超时：5 分钟。 */
    public static final int PREPARATION_WATCHDOG_MINUTES = 5;
    /** 本地准备重试上限：3 次。 */
    public static final int MAX_LOCAL_PREPARATION_RETRIES = 3;

    private static final ObjectMapper JSON = new ObjectMapper();

    private HuashengFlowPolicy() {
    }

    /**
     * 核心步骤决策纯函数。
     * 输入均为锁后不可变快照，输出唯一推进决策，绝不返回“重下PLACE”。
     */
    public static HuashengFlowDecision decide(HuashengCheckpoint checkpoint, HuashengStepFact fact,
                                              long expectedSettlementAmountMinorUnits, LocalDateTime now) {
        Objects.requireNonNull(checkpoint, "checkpoint is required");
        Objects.requireNonNull(fact, "fact is required");
        Objects.requireNonNull(now, "now is required");

        // 2. 远端未知/异常结果处理
        if (fact.resultClass() == HuashengStepFact.StepResult.RESULT_UNKNOWN
                || fact.status() == HuashengStepFact.Status.UNKNOWN) {
            if (fact.endpoint() != HuashengStepFact.Endpoint.CALLBACK && isExceededDeadlineOrQueryCount(checkpoint, now)) {
                return HuashengFlowDecision.MANUAL;
            }
            return HuashengFlowDecision.WAIT_QUERY;
        }

        // 3. 基础订单身份校验：失败回调报告零交付，原单订购数量仍从冻结参数核实。
        // 设计：docs/superpowers/specs/2026-10-12-huasheng-fulfillment-hardening-design.md#21-订购数量与交付数量。
        boolean failedCallback = fact.endpoint() == HuashengStepFact.Endpoint.CALLBACK
                && fact.status() == HuashengStepFact.Status.FAILED;
        int expectedFactQuantity = failedCallback ? HuashengFlowCodes.FAILED_CALLBACK_DELIVERY_QUANTITY
                : HuashengFlowCodes.TYPE201_ORDER_QUANTITY;
        if (checkpoint.placeParameters().num() != HuashengFlowCodes.TYPE201_ORDER_QUANTITY
                || fact.num() != expectedFactQuantity) {
            return HuashengFlowDecision.MANUAL;
        }
        if (!Objects.equals(fact.outOrderSn(), checkpoint.placeParameters().outOrderSn())) {
            return HuashengFlowDecision.MANUAL;
        }
        if (checkpoint.orderSn() != null && fact.orderSn() != null
                && !Objects.equals(fact.orderSn(), checkpoint.orderSn())) {
            return HuashengFlowDecision.MANUAL;
        }

        // 原元数值连续性独立于分值舍入：9.99 与9.9900等价，9.994虽同分仍是冲突（设计 §4.2）。
        if (fact.totalPriceRaw() != null && checkpoint.observation() != null) {
            try {
                if (new BigDecimal(fact.totalPriceRaw()).compareTo(new BigDecimal(checkpoint.observation().totalPriceRaw())) != 0) {
                    return HuashengFlowDecision.MANUAL;
                }
            } catch (NumberFormatException invalidMoney) { return HuashengFlowDecision.MANUAL; }
        }

        // 待核卡密也是已知资产；无 list 的迟到失败不能掩盖此前提卡事实并释放资金（设计 §6.3）。
        if (checkpoint.pendingAssetPayload() != null
                && (fact.status() == HuashengStepFact.Status.FAILED || fact.status() == HuashengStepFact.Status.CANCEL)) {
            return HuashengFlowDecision.MANUAL;
        }

        // 4. 按步骤端点分派
        return switch (fact.endpoint()) {
            case PLACE -> decidePlace(checkpoint, fact, expectedSettlementAmountMinorUnits);
            case CONFIRM -> decideConfirm(checkpoint, fact, expectedSettlementAmountMinorUnits);
            case QUERY -> decideQuery(checkpoint, fact, expectedSettlementAmountMinorUnits);
            case TAKE -> decideTake(checkpoint, fact, expectedSettlementAmountMinorUnits);
            case CALLBACK -> decideCallback(checkpoint, fact, expectedSettlementAmountMinorUnits);
        };
    }

    private static boolean isExceededDeadlineOrQueryCount(HuashengCheckpoint checkpoint, LocalDateTime now) {
        if (checkpoint.progress().recoveryDeadlineAt() != null
                && !now.isBefore(checkpoint.progress().recoveryDeadlineAt())) {
            return true;
        }
        return checkpoint.progress().queryCount() >= MAX_QUERY_COUNT;
    }

    private static boolean isMoneyValid(HuashengStepFact fact, long expectedSettlementAmountMinorUnits) {
        if (fact.totalPriceMinorUnits() == null || fact.totalPriceRaw() == null) {
            return false;
        }
        if (fact.totalPriceMinorUnits() != expectedSettlementAmountMinorUnits) {
            return false;
        }
        if (!fact.totalPriceRaw().matches("[0-9]+(\\.[0-9]{1,4})?")) {
            return false;
        }
        try {
            long parsedMinorUnits = new BigDecimal(fact.totalPriceRaw())
                    .movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            return parsedMinorUnits == expectedSettlementAmountMinorUnits;
        } catch (ArithmeticException | NumberFormatException e) {
            return false;
        }
    }

    private static HuashengFlowDecision decidePlace(HuashengCheckpoint checkpoint, HuashengStepFact fact,
                                                    long expectedSettlementAmountMinorUnits) {
        if (!isMoneyValid(fact, expectedSettlementAmountMinorUnits)) {
            return HuashengFlowDecision.MANUAL;
        }

        return switch (fact.status()) {
            case WAIT -> {
                if (checkpoint.progress().confirmCount() >= MAX_CONFIRM_COUNT) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.NEXT_CONFIRM;
            }
            case SUCCESS -> {
                if (checkpoint.progress().takeCount() >= MAX_TAKE_COUNT) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.NEXT_TAKE;
            }
            case FAILED -> {
                // 合法退款：refundStatus 不能为 NONE 或 WAIT，isTake 不能为 Y
                if (fact.refundStatus() == HuashengStepFact.RefundStatus.NONE
                        || fact.refundStatus() == HuashengStepFact.RefundStatus.WAIT
                        || fact.isTake() == HuashengStepFact.IsTake.Y) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.FINAL_REJECTION;
            }
            case CANCEL -> {
                // CANCEL 仅在显式退款 SUCCESS 且未提卡时可释放，其余保留
                if (fact.refundStatus() == HuashengStepFact.RefundStatus.SUCCESS
                        && fact.isTake() != HuashengStepFact.IsTake.Y) {
                    yield HuashengFlowDecision.FINAL_REJECTION;
                }
                yield HuashengFlowDecision.MANUAL;
            }
            case UNKNOWN -> HuashengFlowDecision.WAIT_QUERY;
        };
    }

    private static HuashengFlowDecision decideConfirm(HuashengCheckpoint checkpoint, HuashengStepFact fact,
                                                      long expectedSettlementAmountMinorUnits) {
        if (!isMoneyValid(fact, expectedSettlementAmountMinorUnits)) {
            return HuashengFlowDecision.MANUAL;
        }

        return switch (fact.status()) {
            case WAIT -> {
                if (checkpoint.progress().confirmCount() >= MAX_CONFIRM_COUNT) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.NEXT_CONFIRM;
            }
            case SUCCESS -> {
                if (checkpoint.progress().takeCount() >= MAX_TAKE_COUNT) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.NEXT_TAKE;
            }
            case FAILED -> {
                if (fact.refundStatus() == HuashengStepFact.RefundStatus.NONE
                        || fact.refundStatus() == HuashengStepFact.RefundStatus.WAIT
                        || fact.isTake() == HuashengStepFact.IsTake.Y) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.FINAL_REJECTION;
            }
            case CANCEL -> {
                if (fact.refundStatus() == HuashengStepFact.RefundStatus.SUCCESS
                        && fact.isTake() != HuashengStepFact.IsTake.Y) {
                    yield HuashengFlowDecision.FINAL_REJECTION;
                }
                yield HuashengFlowDecision.MANUAL;
            }
            case UNKNOWN -> HuashengFlowDecision.WAIT_QUERY;
        };
    }

    private static HuashengFlowDecision decideQuery(HuashengCheckpoint checkpoint, HuashengStepFact fact,
                                                    long expectedSettlementAmountMinorUnits) {
        if (!isMoneyValid(fact, expectedSettlementAmountMinorUnits)) {
            return HuashengFlowDecision.MANUAL;
        }

        return switch (fact.status()) {
            case WAIT -> {
                // WAIT / NONE / N: 受控 CONFIRM
                if (fact.refundStatus() != HuashengStepFact.RefundStatus.NONE
                        && fact.refundStatus() != HuashengStepFact.RefundStatus.ABSENT) {
                    yield HuashengFlowDecision.MANUAL;
                }
                if (fact.isTake() == HuashengStepFact.IsTake.Y) {
                    yield HuashengFlowDecision.MANUAL;
                }
                if (checkpoint.progress().confirmCount() >= MAX_CONFIRM_COUNT) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.NEXT_CONFIRM;
            }
            case SUCCESS -> {
                // SUCCESS / NONE / N 或 Y: TAKE，Y 仍可重取同套卡密
                if (checkpoint.progress().takeCount() >= MAX_TAKE_COUNT) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.NEXT_TAKE;
            }
            case FAILED -> {
                // FAILED / SUCCESS / N: 可信失败候选
                if (fact.refundStatus() == HuashengStepFact.RefundStatus.SUCCESS
                        && fact.isTake() != HuashengStepFact.IsTake.Y) {
                    yield HuashengFlowDecision.FINAL_REJECTION;
                }
                yield HuashengFlowDecision.MANUAL;
            }
            case CANCEL -> {
                // CANCEL / SUCCESS / N: 可信失败候选，其余保留
                if (fact.refundStatus() == HuashengStepFact.RefundStatus.SUCCESS
                        && fact.isTake() != HuashengStepFact.IsTake.Y) {
                    yield HuashengFlowDecision.FINAL_REJECTION;
                }
                yield HuashengFlowDecision.MANUAL;
            }
            case UNKNOWN -> HuashengFlowDecision.WAIT_QUERY;
        };
    }

    private static HuashengFlowDecision decideTake(HuashengCheckpoint checkpoint, HuashengStepFact fact,
                                                   long expectedSettlementAmountMinorUnits) {
        // TAKE 没有总价，核对 checkpoint 中记录的已观察金额
        if (checkpoint.observation() == null) {
            return HuashengFlowDecision.WAIT_QUERY;
        }
        if (checkpoint.observation().totalPriceMinorUnits() != expectedSettlementAmountMinorUnits) {
            return HuashengFlowDecision.MANUAL;
        }

        if (fact.status() != HuashengStepFact.Status.SUCCESS) {
            if (checkpoint.progress().queryCount() >= MAX_QUERY_COUNT) {
                return HuashengFlowDecision.MANUAL;
            }
            return HuashengFlowDecision.WAIT_QUERY;
        }

        // 校验资产载荷是否为完整合法 type 201 卡密
        byte[] payload = fact.protectedAssetPayload();
        if (payload == null || !isValidType201Payload(payload)) {
            return HuashengFlowDecision.MANUAL;
        }

        return HuashengFlowDecision.FINAL_SUCCESS;
    }

    private static HuashengFlowDecision decideCallback(HuashengCheckpoint checkpoint, HuashengStepFact fact,
                                                       long expectedSettlementAmountMinorUnits) {
        if (!isMoneyValid(fact, expectedSettlementAmountMinorUnits)) {
            return HuashengFlowDecision.MANUAL;
        }

        return switch (fact.status()) {
            case SUCCESS -> {
                byte[] payload = fact.protectedAssetPayload();
                if (payload == null || !isValidType201Payload(payload)) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.FINAL_SUCCESS;
            }
            case FAILED -> {
                if (fact.refundStatus() == HuashengStepFact.RefundStatus.NONE
                        || fact.refundStatus() == HuashengStepFact.RefundStatus.WAIT
                        || fact.isTake() == HuashengStepFact.IsTake.Y
                        || fact.protectedAssetPayload() != null) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.FINAL_REJECTION;
            }
            case CANCEL -> {
                if (fact.refundStatus() == HuashengStepFact.RefundStatus.SUCCESS
                        && fact.isTake() != HuashengStepFact.IsTake.Y
                        && fact.protectedAssetPayload() == null) {
                    yield HuashengFlowDecision.FINAL_REJECTION;
                }
                yield HuashengFlowDecision.MANUAL;
            }
            case WAIT -> {
                if (checkpoint.progress().confirmCount() >= MAX_CONFIRM_COUNT) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.NEXT_CONFIRM;
            }
            case UNKNOWN -> HuashengFlowDecision.WAIT_QUERY;
        };
    }

    /**
     * 严格校验载荷是否为合法 type 201 资产。
     */
    public static boolean isValidType201Payload(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > HuashengFlowCodes.CHECKPOINT_MAX_BYTES) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(payload);
            if (root == null || !root.isObject()) {
                return false;
            }
            if (root.path("payloadSchemaVersion").asInt() != HuashengType201AssetCodec.PAYLOAD_SCHEMA_VERSION) {
                return false;
            }
            String code = root.path("cardCode").asText(null);
            String pass = root.path("cardSecret").asText(null);
            if (code == null || code.isBlank() || pass == null || pass.isBlank()) {
                return false;
            }
            JsonNode supplier = root.path("supplierPayload");
            if (supplier == null || !supplier.isObject()) {
                return false;
            }
            if (!HuashengType201AssetCodec.ADAPTER_TYPE.equals(supplier.path("adapterType").asText())) {
                return false;
            }
            if (supplier.path("type").asInt() != HuashengType201AssetCodec.CARD_TYPE) {
                return false;
            }
            var fact = OperationAssetFact.huashengType201(code, pass, payload);
            byte[] validated = new HuashengType201AssetCodec().validate(fact);
            Arrays.fill(validated, (byte) 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
