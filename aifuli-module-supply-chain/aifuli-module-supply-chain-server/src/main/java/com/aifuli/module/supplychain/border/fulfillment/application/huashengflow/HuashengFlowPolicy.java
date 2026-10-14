package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengSecurityReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

/**
 * 华昇生产履约本地策略与决策核心。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#53-watchdog与计数、#6-协议事实推进规则、
 * docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#4-错误分类规则、#5-价格差异与可信失败解耦。
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
     * 核心步骤决策纯函数（保守入口：无错误分类上下文时关闭无副作用快捷路径与发送前重试）。
     * 输入均为锁后不可变快照，输出唯一推进决策，绝不返回“重下PLACE”。
     */
    public static HuashengFlowDecision decide(HuashengCheckpoint checkpoint, HuashengStepFact fact,
                                              long expectedSettlementAmountMinorUnits, LocalDateTime now) {
        return decide(checkpoint, fact, expectedSettlementAmountMinorUnits, now, false, Integer.MAX_VALUE);
    }

    /**
     * 完整决策纯函数：在协议事实决策之上叠加阶段化错误分类与价格差异保护。
     *
     * @param sideEffectFreeProven     本单持久证明“没有业务副作用”（覆盖全部历史尝试；false 关闭快捷路径）
     * @param operationAttemptCount    当前操作已发生的总认领次数（发送前重试预算输入）
     */
    public static HuashengFlowDecision decide(HuashengCheckpoint checkpoint, HuashengStepFact fact,
                                              long expectedSettlementAmountMinorUnits, LocalDateTime now,
                                              boolean sideEffectFreeProven, int operationAttemptCount) {
        Objects.requireNonNull(checkpoint, "checkpoint is required");
        Objects.requireNonNull(fact, "fact is required");
        Objects.requireNonNull(now, "now is required");

        // 非零响应也必须绑定原单；错误分类不得先于身份/资产安全围栏。设计 §5.4。
        if (!Objects.equals(fact.outOrderSn(), checkpoint.placeParameters().outOrderSn())
                || (fact.orderSn() != null && checkpoint.orderSn() != null
                    && !Objects.equals(fact.orderSn(), checkpoint.orderSn()))) return HuashengFlowDecision.MANUAL;
        if (fact.status() == HuashengStepFact.Status.CANCEL || fact.status() == HuashengStepFact.Status.FAILED
                || (fact.rawErrorCode() != null && fact.rawErrorCode() == 7014)) {
            var previous = checkpoint.observation();
            if (checkpoint.pendingAssetPayload() != null || (previous != null
                    && (previous.status() == com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderStatus.SUCCESS
                        || previous.isTake().asOptional().map(value -> value == com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTakeStatus.Y).orElse(false)))) {
                return HuashengFlowDecision.MANUAL;
            }
        }

        // 1. 业务错误分类：业务拒绝码优先于未知恢复投影，按端点与阶段决策
        if (fact.resultClass() == HuashengStepFact.StepResult.RESULT_UNKNOWN
                && fact.rawErrorCode() != null) {
            return decideBusinessError(checkpoint, fact, now, sideEffectFreeProven, operationAttemptCount);
        }

        // 2. 远端未知/异常结果处理（无码未知：HTTP/网络/解析/准备失败，保守原单核实）
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

        // 成功履约不以金额核对为门槛；其它事实仍保留原购买／失败安全规则（生产闭环设计 §7.3）。
        if (fact.status() != HuashengStepFact.Status.SUCCESS && fact.totalPriceRaw() != null && checkpoint.observation() != null) {
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

    /** 金额核对三态：MATCHED一致、MISMATCHED格式有效但金额不符（价格差异）、INVALID格式或内部不一致。 */
    private enum MoneyCheck { MATCHED, MISMATCHED, INVALID }

    private static boolean isExceededDeadlineOrQueryCount(HuashengCheckpoint checkpoint, LocalDateTime now) {
        if (checkpoint.progress().recoveryDeadlineAt() != null
                && !now.isBefore(checkpoint.progress().recoveryDeadlineAt())) {
            return true;
        }
        return checkpoint.progress().queryCount() >= MAX_QUERY_COUNT;
    }

    /**
     * 金额核对：分值与元字符串双重一致为 MATCHED；格式有效、内部一致但与冻结成本不符为 MISMATCHED
     * （价格差异，进入保护分支而非直接人工）；其余为 INVALID（格式非法不是单纯价格差异）。
     */
    private static MoneyCheck checkMoney(HuashengStepFact fact, long expectedSettlementAmountMinorUnits) {
        if (fact.totalPriceMinorUnits() == null || fact.totalPriceRaw() == null) {
            return MoneyCheck.INVALID;
        }
        if (!fact.totalPriceRaw().matches("[0-9]+(\\.[0-9]{1,4})?")) {
            return MoneyCheck.INVALID;
        }
        try {
            long parsedMinorUnits = new BigDecimal(fact.totalPriceRaw())
                    .movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (parsedMinorUnits != fact.totalPriceMinorUnits()) {
                return MoneyCheck.INVALID;
            }
        } catch (ArithmeticException | NumberFormatException e) {
            return MoneyCheck.INVALID;
        }
        return fact.totalPriceMinorUnits() == expectedSettlementAmountMinorUnits
                ? MoneyCheck.MATCHED : MoneyCheck.MISMATCHED;
    }

    /**
     * 阶段化业务错误分类决策（设计 §4.2～§4.4、§5.4）。
     *
     * <p>规则输出封闭动作：失败候选（FINAL_REJECTION）、进入未建单候选、发送前重试（RETRY_PRE_SEND）、
     * 原单核实（WAIT_QUERY）、人工保留（MANUAL）；最终动账只在锁后生命周期执行。
     */
    private static HuashengFlowDecision decideBusinessError(HuashengCheckpoint checkpoint, HuashengStepFact fact,
                                                            LocalDateTime now, boolean sideEffectFreeProven,
                                                            int operationAttemptCount) {
        int code = fact.rawErrorCode();

        // 可信未建单候选分支：QUERY 正式解析得到 7014 才累计有效次数，第 4 次收敛为最终失败。
        var candidate = checkpoint.notCreatedCandidate();
        if (candidate != null && !candidate.exited() && checkpoint.orderSn() == null
                && checkpoint.observation() == null && checkpoint.pendingAssetPayload() == null) {
            if (fact.endpoint() == HuashengStepFact.Endpoint.QUERY && code == 7014) {
                int next = candidate.validNotCreatedQueryCount() + 1;
                if (next >= HuashengErrorClassification.REQUIRED_VALID_NOT_CREATED_QUERIES) {
                    return HuashengFlowDecision.FINAL_NOT_CREATED;
                }
            }
            // 候选仍在但本次结果不是有效 7014（其它错误码或端点）：不计数，按通用规则处理。
        }

        // 配额在认领时消耗；本次可信终态仍可应用，到限仅阻止下一次自动外呼（设计 §7 接管集成修订）。
        if (fact.endpoint() != HuashengStepFact.Endpoint.CALLBACK && isExceededDeadlineOrQueryCount(checkpoint, now)) {
            return HuashengFlowDecision.MANUAL;
        }

        boolean firstPlace = fact.endpoint() == HuashengStepFact.Endpoint.PLACE
                && checkpoint.binding().step() == AdapterStepCode.PLACE_ORDER
                && checkpoint.binding().sequenceNo() == 1
                && checkpoint.orderSn() == null
                && checkpoint.observation() == null;

        if (fact.endpoint() == HuashengStepFact.Endpoint.PLACE) {
            // 首次 PLACE 明确创建拒绝：进入未建单退避查单候选，不立即释放（设计 §4.2、§5.4）。
            if (firstPlace && candidate == null
                    && HuashengErrorClassification.isPlaceCreationRejection(code)) {
                return HuashengFlowDecision.WAIT_QUERY;
            }
            // 订单创建前的明确永久拒绝 + 无副作用证明：失败释放（设计 §4.2）。
            if (firstPlace && sideEffectFreeProven
                    && HuashengErrorClassification.isDefinitivePreCreationRejection(code, fact.securityReason())) {
                return HuashengFlowDecision.FINAL_REJECTION;
            }
            // 明确过期仅允许首认领后重新构造一次；仍过期保留人工配置排障，不能继续购买或释放。
            if (firstPlace && sideEffectFreeProven && code == 66
                    && fact.securityReason() == com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengSecurityReason.REQUEST_EXPIRED) {
                return operationAttemptCount <= 1 ? HuashengFlowDecision.RETRY_PRE_SEND : HuashengFlowDecision.MANUAL;
            }
            // 第二次 61 或发送前暂时故障：首次创建前且证明无副作用，按发送前授权预算处理（设计 §4.3、§4.5）。
            boolean preSendRetryable = code == 61
                    || (fact.rawErrorCode() != null && HuashengErrorClassification.isTransientBeforeSend(code));
            if (firstPlace && sideEffectFreeProven && preSendRetryable
                    && operationAttemptCount < HuashengErrorClassification.MAX_PRE_SEND_CLAIMS) {
                return HuashengFlowDecision.RETRY_PRE_SEND;
            }
            // 发送前暂时故障耗尽预算且证明没有创建业务请求：以授权不可用失败释放（设计 §4.2.3）。
            if (firstPlace && sideEffectFreeProven && preSendRetryable
                    && operationAttemptCount >= HuashengErrorClassification.MAX_PRE_SEND_CLAIMS) {
                return HuashengFlowDecision.FINAL_REJECTION;
            }
            // 已有原单、历史风险或其余错误码：优先核原单，不盲目重复写操作（设计 §4.4）。
            return HuashengFlowDecision.WAIT_QUERY;
        }

        if (fact.endpoint() == HuashengStepFact.Endpoint.QUERY) {
            // 7025 修正查询标识为二选一后再查原单；7014 单次不存在不是未创建证明；
            // 7301 查原单后受控重取同套卡密；7201/7202/7205/7206 查取消、退款与提卡事实。
            // 一律原单核实，保持 billno/orderSn，不创建新单（设计 §4.4）。
            return HuashengFlowDecision.WAIT_QUERY;
        }

        // CONFIRM/TAKE 已有订单阶段：任何前置拒绝只能终止本次操作，不能直接终止整个原单（设计 §4.2）。
        return HuashengFlowDecision.WAIT_QUERY;
    }

    /**
     * 计算 PLACE 业务错误对“未建单候选进入”的资格；供生命周期在锁后判断并持久化候选。
     * 仅首次 PLACE、无已知原单、无候选且错误码属创建拒绝候选清单时返回 true。
     */
    public static boolean qualifiesNotCreatedCandidacy(HuashengCheckpoint checkpoint, HuashengStepFact fact) {
        return fact.endpoint() == HuashengStepFact.Endpoint.PLACE
                && checkpoint.binding().step() == AdapterStepCode.PLACE_ORDER
                && checkpoint.binding().sequenceNo() == 1
                && checkpoint.orderSn() == null
                && checkpoint.observation() == null
                && checkpoint.notCreatedCandidate() == null
                && fact.rawErrorCode() != null
                && fact.resultClass() == HuashengStepFact.StepResult.RESULT_UNKNOWN
                && HuashengErrorClassification.isPlaceCreationRejection(fact.rawErrorCode());
    }

    /**
     * 判断事实是否表明供应商订单已存在（候选分支永久退出判据，设计 §5.4.4）。
     */
    public static boolean indicatesSupplierOrderExists(HuashengStepFact fact) {
        return fact.resultClass() == HuashengStepFact.StepResult.ACCEPTED
                && fact.orderSn() != null;
    }

    private static HuashengFlowDecision decidePlace(HuashengCheckpoint checkpoint, HuashengStepFact fact,
                                                    long expectedSettlementAmountMinorUnits) {
        MoneyCheck money = checkMoney(fact, expectedSettlementAmountMinorUnits);
        if (money == MoneyCheck.INVALID && fact.status() != HuashengStepFact.Status.SUCCESS) {
            return HuashengFlowDecision.MANUAL;
        }
        boolean priceMismatch = money == MoneyCheck.MISMATCHED;

        return switch (fact.status()) {
            case WAIT -> {
                // 价格差异保护：停止 CONFIRM/TAKE，仅查询原单（设计 §5.1）。
                if (priceMismatch) {
                    yield HuashengFlowDecision.WAIT_QUERY;
                }
                if (checkpoint.progress().confirmCount() >= MAX_CONFIRM_COUNT) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.NEXT_CONFIRM;
            }
            case SUCCESS -> {
                // 已履约成功后只取原单卡密，不再以金额差异阻断交付（生产闭环设计 §7.3）。
                if (checkpoint.progress().takeCount() >= MAX_TAKE_COUNT) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.NEXT_TAKE;
            }
            case FAILED -> {
                // 缺失退款/未提卡事实不是安全失败证明。价差独立留存。
                if (fact.refundStatus() != HuashengStepFact.RefundStatus.SUCCESS
                        || fact.isTake() != HuashengStepFact.IsTake.N) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.FINAL_REJECTION;
            }
            case CANCEL -> {
                // CANCEL 仅在显式退款 SUCCESS 且未提卡时按可信取消收敛（BOrder CANCELLED），价差独立留存。
                if (fact.refundStatus() == HuashengStepFact.RefundStatus.SUCCESS
                        && fact.isTake() == HuashengStepFact.IsTake.N) {
                    yield HuashengFlowDecision.FINAL_CANCELLATION;
                }
                yield HuashengFlowDecision.MANUAL;
            }
            case UNKNOWN -> HuashengFlowDecision.WAIT_QUERY;
        };
    }

    private static HuashengFlowDecision decideConfirm(HuashengCheckpoint checkpoint, HuashengStepFact fact,
                                                      long expectedSettlementAmountMinorUnits) {
        MoneyCheck money = checkMoney(fact, expectedSettlementAmountMinorUnits);
        if (money == MoneyCheck.INVALID && fact.status() != HuashengStepFact.Status.SUCCESS) {
            return HuashengFlowDecision.MANUAL;
        }
        boolean priceMismatch = money == MoneyCheck.MISMATCHED;

        return switch (fact.status()) {
            case WAIT -> {
                if (priceMismatch) {
                    yield HuashengFlowDecision.WAIT_QUERY;
                }
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
                if (fact.refundStatus() != HuashengStepFact.RefundStatus.SUCCESS
                        || fact.isTake() != HuashengStepFact.IsTake.N) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.FINAL_REJECTION;
            }
            case CANCEL -> {
                if (fact.refundStatus() == HuashengStepFact.RefundStatus.SUCCESS
                        && fact.isTake() == HuashengStepFact.IsTake.N) {
                    yield HuashengFlowDecision.FINAL_CANCELLATION;
                }
                yield HuashengFlowDecision.MANUAL;
            }
            case UNKNOWN -> HuashengFlowDecision.WAIT_QUERY;
        };
    }

    private static HuashengFlowDecision decideQuery(HuashengCheckpoint checkpoint, HuashengStepFact fact,
                                                    long expectedSettlementAmountMinorUnits) {
        MoneyCheck money = checkMoney(fact, expectedSettlementAmountMinorUnits);
        if (money == MoneyCheck.INVALID && fact.status() != HuashengStepFact.Status.SUCCESS) {
            return HuashengFlowDecision.MANUAL;
        }
        boolean priceMismatch = money == MoneyCheck.MISMATCHED;

        return switch (fact.status()) {
            case WAIT -> {
                // WAIT / NONE / N: 受控 CONFIRM；价格差异只查询不确认（设计 §5.1）。
                if (fact.refundStatus() != HuashengStepFact.RefundStatus.NONE
                        && fact.refundStatus() != HuashengStepFact.RefundStatus.ABSENT) {
                    yield HuashengFlowDecision.MANUAL;
                }
                if (fact.isTake() == HuashengStepFact.IsTake.Y) {
                    yield HuashengFlowDecision.MANUAL;
                }
                if (priceMismatch) {
                    yield HuashengFlowDecision.WAIT_QUERY;
                }
                if (checkpoint.progress().confirmCount() >= MAX_CONFIRM_COUNT) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.NEXT_CONFIRM;
            }
            case SUCCESS -> {
                // SUCCESS/isTake=Y独立记供应商成功；本地仍须获取并保存原单卡密（生产闭环设计 §7.3）。
                if (checkpoint.progress().takeCount() >= MAX_TAKE_COUNT) {
                    yield HuashengFlowDecision.MANUAL;
                }
                yield HuashengFlowDecision.NEXT_TAKE;
            }
            case FAILED -> {
                // FAILED / SUCCESS / N: 可信失败候选；金额差异允许留存（设计 §5.1、§5.2）。
                if (fact.refundStatus() == HuashengStepFact.RefundStatus.SUCCESS
                        && fact.isTake() == HuashengStepFact.IsTake.N) {
                    yield HuashengFlowDecision.FINAL_REJECTION;
                }
                yield HuashengFlowDecision.MANUAL;
            }
            case CANCEL -> {
                // CANCEL / SUCCESS / N: 可信取消候选（BOrder CANCELLED）；金额差异允许留存。
                if (fact.refundStatus() == HuashengStepFact.RefundStatus.SUCCESS
                        && fact.isTake() == HuashengStepFact.IsTake.N) {
                    yield HuashengFlowDecision.FINAL_CANCELLATION;
                }
                yield HuashengFlowDecision.MANUAL;
            }
            case UNKNOWN -> HuashengFlowDecision.WAIT_QUERY;
        };
    }

    private static HuashengFlowDecision decideTake(HuashengCheckpoint checkpoint, HuashengStepFact fact,
                                                   long expectedSettlementAmountMinorUnits) {
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
        MoneyCheck money = checkMoney(fact, expectedSettlementAmountMinorUnits);
        if (money == MoneyCheck.INVALID && fact.status() != HuashengStepFact.Status.SUCCESS) {
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
                // 零交付、已退款的可信失败回调；金额差异不否定可信失败（设计 §5.2）。
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
                    yield HuashengFlowDecision.FINAL_CANCELLATION;
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
     * 严格校验201／202资产，保留历史方法名；唯一schema校验由资产codec负责（生产闭环设计 §7.3）。
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
            var fact = OperationAssetFact.huashengType201(code, pass, payload);
            byte[] validated = new HuashengType201AssetCodec().validate(fact);
            Arrays.fill(validated, (byte) 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
