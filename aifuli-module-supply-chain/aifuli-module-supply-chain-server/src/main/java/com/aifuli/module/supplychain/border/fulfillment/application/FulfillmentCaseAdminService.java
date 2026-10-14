package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpointCodec;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengErrorClassification;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengExecutionPermit;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengFlowTrigger;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengStableFailureReason;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentCaseActionDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentExceptionCaseDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentCaseActionMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentExceptionCaseMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentExceptionCaseStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

import static com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentCaseActionCodes.*;

/**
 * 履约异常 Case 人工核实与失败关单受控应用服务（两步命令的受理与查询）。
 *
 * <p>职责（设计 §6）：
 * <ul>
 *   <li>查询：Case、原单状态、资金处置摘要、脱敏证据及可操作条件；只读不外呼；</li>
 *   <li>核实受理：同事务命令审计＋持久 Timer 提交；HTTP 成功只表示命令已受理，
 *       外呼执行由 {@link FulfillmentCaseVerifyExecutionService} 承担；</li>
 *   <li>失败确认：仅接受同 Case 最近成功、生成后不超过 5 分钟、同当前代次且未失效的核实结果；
 *       由真实操作人在同事务结案，不提供独立释放资金按钮。</li>
 * </ul>
 *
 * <p>不变量：命令指纹绑定真实 actor、命令类型、Case／原单身份及全部业务参数；
 * 相同 actionNo 且相同指纹幂等返回，同号异参拒绝；核实与动账权限分开；服务端始终复核。
 *
 * <p>设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#6-存量人工单的受控入口。
 */
@Slf4j
@Service
public class FulfillmentCaseAdminService {

    public static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    /** 核实结论：供应商已取消且零交付退款成功，具备 §5.2 失败确认资格。 */
    public static final String CONCLUSION_SUPPLIER_CANCELLED = "HUASHENG_SUPPLIER_CANCELLED";
    /** 核实结论：供应商终态失败且退款成功，具备 §5.2 失败确认资格。 */
    public static final String CONCLUSION_SUPPLIER_FAILED = "HUASHENG_SUPPLIER_FAILED";
    /** 核实结论：可信未建单（四次有效 7014），具备 §5.4 失败确认资格。 */
    public static final String CONCLUSION_NOT_CREATED = "HUASHENG_NOT_CREATED";
    /** 核实结论：原单仍在途，不提供失败确认资格。 */
    public static final String CONCLUSION_ORDER_WAITING = "SUPPLIER_ORDER_WAITING";
    /** 核实结论：原单已成功，禁止失败释放。 */
    public static final String CONCLUSION_ORDER_SUCCEEDED = "SUPPLIER_ORDER_SUCCEEDED";
    /** 核实结论：退款未完成或已提卡，不可失败确认。 */
    public static final String CONCLUSION_REFUND_PENDING = "SUPPLIER_REFUND_PENDING";
    /** 核实结论：查无此单但未满足可信未建单判据，不可确认。 */
    public static final String CONCLUSION_ORDER_NOT_FOUND = "SUPPLIER_ORDER_NOT_FOUND";
    /** 核实结论：查询被拒或结果未知，不可确认。 */
    public static final String CONCLUSION_QUERY_REJECTED = "SUPPLIER_QUERY_REJECTED";

    private final FulfillmentExceptionCaseMapper exceptionCaseMapper;
    private final FulfillmentCaseActionMapper caseActionMapper;
    private final FulfillmentOperationMapper operationMapper;
    private final BOrderMapper bOrderMapper;
    private final com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyExecutionMapper executionMapper;
    private final com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort envelopeCrypto;
    private final FulfillmentRequestMapper requestMapper;
    private final SupplyChainOrderMapper orderMapper;
    private final FundReservationService fundReservationService;
    private final SupplierOrderLifecycle supplierOrderLifecycle;
    private final PublishedConnectorConfigurationResolver connectorResolver;
    private final HuashengCheckpointCodec checkpointCodec;
    private final PersistentTimer timer;
    private final SupplyChainDatabaseClock databaseClock;
    private final ObjectMapper objectMapper;

    public FulfillmentCaseAdminService(
            FulfillmentExceptionCaseMapper exceptionCaseMapper,
            FulfillmentCaseActionMapper caseActionMapper,
            FulfillmentOperationMapper operationMapper,
            BOrderMapper bOrderMapper,
            FulfillmentRequestMapper requestMapper,
            SupplyChainOrderMapper orderMapper,
            FundReservationService fundReservationService,
            SupplierOrderLifecycle supplierOrderLifecycle,
            PublishedConnectorConfigurationResolver connectorResolver,
            HuashengCheckpointCodec checkpointCodec,
            PersistentTimer timer,
            SupplyChainDatabaseClock databaseClock,
            ObjectMapper objectMapper,
            com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyExecutionMapper executionMapper,
            com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort envelopeCrypto) {
        this.executionMapper = Objects.requireNonNull(executionMapper);
        this.envelopeCrypto = Objects.requireNonNull(envelopeCrypto);
        this.exceptionCaseMapper = Objects.requireNonNull(exceptionCaseMapper);
        this.caseActionMapper = Objects.requireNonNull(caseActionMapper);
        this.operationMapper = Objects.requireNonNull(operationMapper);
        this.bOrderMapper = Objects.requireNonNull(bOrderMapper);
        this.requestMapper = Objects.requireNonNull(requestMapper);
        this.orderMapper = Objects.requireNonNull(orderMapper);
        this.fundReservationService = Objects.requireNonNull(fundReservationService);
        this.supplierOrderLifecycle = Objects.requireNonNull(supplierOrderLifecycle);
        this.connectorResolver = Objects.requireNonNull(connectorResolver);
        this.checkpointCodec = Objects.requireNonNull(checkpointCodec);
        this.timer = Objects.requireNonNull(timer);
        this.databaseClock = Objects.requireNonNull(databaseClock);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    // ==================== 查询（只读，不外呼） ====================

    /** 资金处置摘要：仍冻结／已扣款／已释放／未知，无预留时不默认已释放（设计 §8.1）。 */
    public record FundDispositionSummary(String reservationStatus, Long totalAmount, Long committedAmount,
                                         Long releasedAmount, Long remainingAmount) {
    }

    /** Case 详情视图：Case、原单状态、原因、价差与资金处置摘要及可操作条件。 */
    public record CaseDetailView(String caseNo, String reasonCode, Short caseStatus, Long caseVersion, Long generation,
                                 String bOrderNo, Short bOrderFulfillmentStatus, String terminalReasonCode,
                                 String terminalReasonNote, Long expectedSupplierSettlementAmount,
                                 Long reportedSupplierSettlementAmount, Short settlementStatus,
                                 String orderNo, Short orderStatus, String businessStage,
                                 FundDispositionSummary fundDisposition,
                                 String latestActionNo, String latestActionStatus, String latestVerifyConclusion,
                                 boolean canVerify, boolean canConfirmFailure) {
    }

    public CaseDetailView getCaseDetail(String caseNo) {
        var ctx = loadCaseContext(caseNo);
        var snapshot = fundReservationService.findSnapshotByReference(ctx.request().fundsBuyerId(),
                FundReferenceType.CARD_ORDER.code(), ctx.request().getRequestNo());
        FundDispositionSummary fund = snapshot == null ? null : new FundDispositionSummary(
                fundStatusName(snapshot.status()), snapshot.totalAmount(), snapshot.committedAmount(),
                snapshot.releasedAmount(), snapshot.remainingAmount());
        var latestAction = caseActionMapper.selectList(new LambdaQueryWrapper<FulfillmentCaseActionDO>()
                        .eq(FulfillmentCaseActionDO::getCaseId, ctx.caseDo().getId()))
                .stream().max(java.util.Comparator.comparing(FulfillmentCaseActionDO::getId)).orElse(null);
        boolean activeCase = ctx.caseDo().getActiveCaseFlag() != null && ctx.caseDo().getActiveCaseFlag() == 1;
        boolean canVerify = isManualCase(ctx.caseDo(), ctx.bOrder())
                && (latestAction == null || latestAction.getActiveIntentFlag() == null);
        boolean canConfirmFailure = activeCase && latestAction != null
                && SUCCEEDED.equals(latestAction.getExecutionStatus())
                && isConfirmableConclusion(latestAction.getVerifyConclusion())
                && isManualCase(ctx.caseDo(), ctx.bOrder())
                && latestAction.getFreshUntil() != null && databaseClock.now().isBefore(latestAction.getFreshUntil())
                && Objects.equals(latestAction.getActualGeneration(), ctx.latestOperation().getExecutionGeneration())
                && Objects.equals(latestAction.getActualCaseVersion(), ctx.caseDo().getVersion())
                && snapshot != null && snapshot.committedAmount() == 0 && snapshot.remainingAmount() > 0
                && hasSafeEvidence(ctx, latestAction);
        return new CaseDetailView(
                ctx.caseDo().getCaseNo(), ctx.caseDo().getReasonCode(), ctx.caseDo().getStatus(),
                ctx.caseDo().getVersion(), ctx.latestOperation().getExecutionGeneration(),
                ctx.bOrder().getBOrderNo(), ctx.bOrder().getFulfillmentStatus(),
                ctx.bOrder().getTerminalReasonCode(), ctx.bOrder().getTerminalReasonNote(),
                ctx.bOrder().getExpectedSupplierSettlementAmount(),
                ctx.bOrder().getReportedSupplierSettlementAmount(), ctx.bOrder().getSettlementStatus(),
                ctx.order().getOrderNo(), ctx.order().getOrderStatus(), ctx.order().getBusinessStage(),
                fund,
                latestAction != null ? latestAction.getActionNo() : null,
                latestAction != null ? latestAction.getExecutionStatus() : null,
                latestAction != null ? latestAction.getVerifyConclusion() : null,
                canVerify, canConfirmFailure);
    }

    private static String fundStatusName(short status) {
        return switch (status) {
            case FundReservationService.RESERVED -> "RESERVED";
            case FundReservationService.COMMITTED -> "COMMITTED";
            case FundReservationService.RELEASED -> "RELEASED";
            default -> "UNKNOWN";
        };
    }

    /** 命令视图：返回实际 caseVersion、generation、核实时间及 freshUntil，供后续确认使用（设计 §6.2）。 */
    public record CaseActionView(String actionNo, String commandType, String executionStatus,
                                 String verifyConclusion, String externalSupplierStatus,
                                 Integer validNotCreatedCount, LocalDateTime freshUntil,
                                 Long actualCaseVersion, Long actualGeneration,
                                 String completionSummary, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public CaseActionView getAction(String caseNo, String actionNo) {
        var ctx = loadCaseContext(caseNo);
        var action = caseActionMapper.selectByActionNo(actionNo);
        if (action == null || !Objects.equals(action.getCaseId(), ctx.caseDo().getId())) {
            throw new IllegalArgumentException("Case action does not exist: " + actionNo);
        }
        return toActionView(action);
    }

    private static CaseActionView toActionView(FulfillmentCaseActionDO action) {
        return new CaseActionView(action.getActionNo(), action.getCommandType(), action.getExecutionStatus(),
                action.getVerifyConclusion(), action.getExternalSupplierStatus(), action.getValidNotCreatedCount(),
                action.getFreshUntil(), action.getActualCaseVersion(), action.getActualGeneration(),
                action.getCompletionSummary(), action.getCreatedAt(), action.getUpdatedAt());
    }

    // ==================== 核实受理（HTTP 成功只表示命令已受理） ====================

    public record VerifySubmitCommand(String caseNo, String actionNo, Long expectedCaseVersion,
                                      Long expectedGeneration, String reason, Long actorId) {
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public CaseActionView submitVerify(VerifySubmitCommand command) {
        Objects.requireNonNull(command, "command is required");
        var ctx = loadCaseContext(command.caseNo());
        requireHuashengCard(ctx, "verify");
        byte[] fingerprint = fingerprint(command.actorId(), VERIFY_ORIGIN, command.caseNo(),
                ctx.bOrder().getBOrderNo(), ctx.latestOperation().getOperationNo(),
                command.expectedCaseVersion(), command.expectedGeneration(), null, command.reason());

        // 幂等：相同 actionNo 且相同指纹返回原受理；同号异参拒绝（设计 §6.3）。
        var existing = caseActionMapper.selectByActionNoForUpdate(command.actionNo());
        if (existing != null) {
            if (!Arrays.equals(existing.getRequestFingerprint(), fingerprint)) {
                throw new IllegalArgumentException("ActionNo replay with different fingerprint: " + command.actionNo());
            }
            return toActionView(existing);
        }

        requireManualCase(ctx);
        Objects.requireNonNull(command.actorId(), "管理端操作人必需");

        // 同一 Case 同时只允许一个核实意图：数据库唯一约束及锁后检查（设计 §6.2）。
        var activeIntent = caseActionMapper.selectActiveIntentByCaseId(ctx.caseDo().getId());
        if (activeIntent != null) {
            throw new IllegalStateException("Case already has an active verify intent: " + activeIntent.getActionNo());
        }
        if (!Objects.equals(ctx.caseDo().getVersion(), command.expectedCaseVersion())) {
            throw new IllegalStateException("Case version mismatch, refresh and retry");
        }
        long currentGeneration = ctx.latestOperation().getExecutionGeneration() != null
                ? ctx.latestOperation().getExecutionGeneration() : 0L;
        if (currentGeneration != command.expectedGeneration()) {
            throw new IllegalStateException("Generation mismatch, refresh and retry");
        }

        LocalDateTime now = databaseClock.now();
        var action = FulfillmentCaseActionDO.builder()
                .actionNo(command.actionNo())
                .caseNo(ctx.caseDo().getCaseNo())
                .caseId(ctx.caseDo().getId())
                .bOrderId(ctx.bOrder().getId())
                .operationNo(ctx.latestOperation().getOperationNo())
                .commandType(VERIFY_ORIGIN)
                .activeIntentFlag(true)
                .requestFingerprint(fingerprint)
                .actorId(command.actorId())
                .expectedCaseVersion(command.expectedCaseVersion())
                .expectedGeneration(command.expectedGeneration())
                .executionStatus(RECEIVED)
                .createdAt(now)
                .updatedAt(now)
                .version(0L)
                .build();
        caseActionMapper.insert(action);

        // 提交过早时由持久 Timer 等到 nextQueryAt；候选分支共享退避间隔（设计 §6.2）。
        LocalDateTime fireAt = now.plusSeconds(1);
        var candidate = peekCandidate(ctx);
        if (candidate != null && !candidate.exited() && candidate.nextQueryAt() != null
                && candidate.nextQueryAt().isAfter(fireAt)) {
            fireAt = candidate.nextQueryAt();
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("actionNo", command.actionNo());
        timer.scheduleOnce(FulfillmentTimerTaskTypes.HUASHENG_CASE_VERIFY,
                "HS:CASE_VERIFY:" + command.actionNo(), payload, databaseClock.toInstant(fireAt));

        afterCommitLog("[Case核实][命令受理] caseNo={}, actionNo={}, actorId={}, fireAt={}",
                command.caseNo(), command.actionNo(), command.actorId(), fireAt);
        return toActionView(action);
    }

    // ==================== 失败确认（第二步关单命令） ====================

    public record ConfirmFailureCommand(String caseNo, String actionNo, String verifyActionNo,
                                        Long expectedCaseVersion, Long expectedGeneration,
                                        String reason, Long actorId) {
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public CaseActionView confirmFailure(ConfirmFailureCommand command) {
        Objects.requireNonNull(command, "command is required");
        var ctx = loadCaseContext(command.caseNo());
        requireHuashengCard(ctx, "confirm-failure");
        byte[] fingerprint = fingerprint(command.actorId(), CONFIRM_FAILURE, command.caseNo(),
                ctx.bOrder().getBOrderNo(), ctx.latestOperation().getOperationNo(),
                command.expectedCaseVersion(), command.expectedGeneration(), command.verifyActionNo(), command.reason());

        // 幂等：相同 actionNo 且相同指纹返回原结果；同号异参拒绝（设计 §6.3）。
        var existing = caseActionMapper.selectByActionNoForUpdate(command.actionNo());
        if (existing != null) {
            if (!Arrays.equals(existing.getRequestFingerprint(), fingerprint)) {
                throw new IllegalArgumentException("ActionNo replay with different fingerprint: " + command.actionNo());
            }
            return toActionView(existing);
        }

        requireManualCase(ctx);
        Objects.requireNonNull(command.actorId(), "管理端操作人必需");

        if (!Objects.equals(ctx.caseDo().getVersion(), command.expectedCaseVersion())) {
            throw new IllegalStateException("Case version mismatch, re-verify required");
        }
        long currentGeneration = ctx.latestOperation().getExecutionGeneration() != null
                ? ctx.latestOperation().getExecutionGeneration() : 0L;
        if (currentGeneration != command.expectedGeneration()) {
            throw new IllegalStateException("Generation mismatch, re-verify required");
        }

        // 只能使用同 Case 最近一次成功、新鲜、同当前代次且未失效的核实结果（设计 §6.3）。
        var verifyAction = caseActionMapper.selectByActionNoForUpdate(command.verifyActionNo());
        if (verifyAction == null || !Objects.equals(verifyAction.getCaseId(), ctx.caseDo().getId())
                || !VERIFY_ORIGIN.equals(verifyAction.getCommandType())) {
            throw new IllegalArgumentException("Referenced verify action is invalid: " + command.verifyActionNo());
        }
        LocalDateTime now = databaseClock.now();
        if (!SUCCEEDED.equals(verifyAction.getExecutionStatus())
                || verifyAction.getFreshUntil() == null || !now.isBefore(verifyAction.getFreshUntil())) {
            throw new IllegalStateException("Verify result is not fresh, re-verify required");
        }
        if (!Objects.equals(verifyAction.getActualGeneration(), currentGeneration)) {
            throw new IllegalStateException("Verify result generation is stale, re-verify required");
        }
        if (!Objects.equals(verifyAction.getOperationNo(), ctx.latestOperation().getOperationNo())) {
            throw new IllegalStateException("核实操作身份已变化");
        }
        var latestVerify = caseActionMapper.selectOne(new LambdaQueryWrapper<FulfillmentCaseActionDO>()
                .eq(FulfillmentCaseActionDO::getCaseId, ctx.caseDo().getId())
                .eq(FulfillmentCaseActionDO::getCommandType, VERIFY_ORIGIN)
                .orderByDesc(FulfillmentCaseActionDO::getId).last("LIMIT 1 FOR UPDATE"));
        if (latestVerify == null || !Objects.equals(latestVerify.getActionNo(), command.verifyActionNo())
                || !Objects.equals(verifyAction.getActualCaseVersion(), ctx.caseDo().getVersion())) {
            throw new IllegalStateException("必须使用当前 Case 最近核实结果");
        }
        String conclusion = verifyAction.getVerifyConclusion();
        if (!isConfirmableConclusion(conclusion)) {
            throw new IllegalStateException("Verify conclusion does not qualify failure confirmation: " + conclusion);
        }

        // 服务端复核判据：可信取消/失败走 §5.2，可信未建单走 §5.4；操作人文字不能覆盖判定。
        var checkpoint = decryptCheckpoint(ctx);
        var evidence = readEvidence(verifyAction, ctx);
        for (var historical : caseActionMapper.selectList(new LambdaQueryWrapper<FulfillmentCaseActionDO>()
                .eq(FulfillmentCaseActionDO::getBOrderId, ctx.bOrder().getId())
                .isNotNull(FulfillmentCaseActionDO::getEvidenceCiphertext).last("FOR UPDATE"))) {
            var previous = readEvidence(historical, ctx);
            if ("SUCCESS".equals(previous.path("status").asText()) || "Y".equals(previous.path("isTake").asText())
                    || previous.path("hasAsset").asBoolean(true)
                    || !previous.path("validIdentity").asBoolean(false)) throw new IllegalStateException("历史核实成功、提卡、身份冲突或资产事实禁止失败释放");
        }
        if (!"QUERY".equals(evidence.path("endpoint").asText())
                || !Objects.equals(ctx.bOrder().getBOrderNo(), evidence.path("bOrderNo").asText())
                || evidence.path("frozenNum").asInt(-1) != checkpoint.placeParameters().num()
                || !Objects.equals(java.util.HexFormat.of().formatHex(ctx.latestOperation().getConfigDigest()), evidence.path("configDigest").asText())
                || !Objects.equals(checkpoint.placeParameters().outOrderSn(), evidence.path("outOrderSn").asText())
                || !Objects.equals(checkpoint.placeParametersDigest(), evidence.path("placeDigest").asText())
                || !evidence.has("hasAsset") || evidence.path("hasAsset").asBoolean(true)) {
            throw new IllegalStateException("核实证据身份或资产不满足失败确认");
        }
        if (!CONCLUSION_NOT_CREATED.equals(conclusion)
                && (!"ACCEPTED".equals(evidence.path("resultClass").asText())
                    || !"SUCCESS".equals(evidence.path("refundStatus").asText())
                    || !"N".equals(evidence.path("isTake").asText())
                    || evidence.path("num").asInt(-1) != checkpoint.placeParameters().num()
                    || evidence.path("orderSn").asText().isBlank()
                    || (checkpoint.orderSn() != null && !Objects.equals(checkpoint.orderSn(), evidence.path("orderSn").asText()))
                    || !Objects.equals(CONCLUSION_SUPPLIER_CANCELLED.equals(conclusion) ? "CANCEL" : "FAILED", evidence.path("status").asText()))) {
            throw new IllegalStateException("取消或失败全量核实证据不合格");
        }
        if (checkpoint.pendingAssetPayload() != null) {
            throw new IllegalStateException("Pending protected asset blocks failure confirmation");
        }
        boolean supplierCancelled;
        HuashengStableFailureReason closureReason;
        if (CONCLUSION_SUPPLIER_CANCELLED.equals(conclusion)) {
            supplierCancelled = true;
            closureReason = HuashengStableFailureReason.HUASHENG_SUPPLIER_CANCELLED;
        } else if (CONCLUSION_SUPPLIER_FAILED.equals(conclusion)) {
            supplierCancelled = false;
            closureReason = HuashengStableFailureReason.HUASHENG_SUPPLIER_FAILED;
        } else {
            // 可信未建单：复核候选身份与四次有效查询（设计 §5.4、§6.3）。
            var candidate = checkpoint.notCreatedCandidate();
            var latestValidCount = candidate != null ? candidate.validNotCreatedQueryCount() : 0;
            if (candidate == null || candidate.exited()
                    || evidence.path("rawErrorCode").asInt() != 7014
                    || checkpoint.orderSn() != null || checkpoint.observation() != null
                    || latestValidCount < HuashengErrorClassification.REQUIRED_VALID_NOT_CREATED_QUERIES
                    || !HuashengErrorClassification.isPlaceCreationRejection(candidate.earliestRejectionCode())) {
                throw new IllegalStateException("Trusted not-created criteria not satisfied server-side");
            }
            supplierCancelled = false;
            closureReason = HuashengStableFailureReason.HUASHENG_NOT_CREATED;
        }

        var action = FulfillmentCaseActionDO.builder()
                .actionNo(command.actionNo())
                .caseNo(ctx.caseDo().getCaseNo())
                .caseId(ctx.caseDo().getId())
                .bOrderId(ctx.bOrder().getId())
                .operationNo(ctx.latestOperation().getOperationNo())
                .commandType(CONFIRM_FAILURE)
                .activeIntentFlag(true)
                .requestFingerprint(fingerprint)
                .actorId(command.actorId())
                .verifyActionNo(command.verifyActionNo())
                .expectedCaseVersion(command.expectedCaseVersion())
                .expectedGeneration(command.expectedGeneration())
                .executionStatus(RECEIVED)
                .createdAt(now)
                .updatedAt(now)
                .version(0L)
                .build();
        caseActionMapper.insert(action);

        // 锁内先以核实代次检查原始证据；终态后再使旧资格失效，不能复制事实冒充新 QUERY（设计 §7 接管集成修订）。
        var permit = new HuashengExecutionPermit(ctx.latestOperation().getOperationNo(),
                ctx.bOrder().getId(), currentGeneration,
                AdapterStepCode.valueOf(ctx.latestOperation().getAdapterStepCode()),
                HuashengFlowTrigger.RECOVER, now.plusMinutes(5));
        Long reportedAmount = CONCLUSION_NOT_CREATED.equals(conclusion) ? null
                : evidence.has("totalPriceMinorUnits") ? evidence.path("totalPriceMinorUnits").longValue() : null;
        if (!CONCLUSION_NOT_CREATED.equals(conclusion) && reportedAmount == null) throw new IllegalStateException("缺少供应商金额证据");
        supplierOrderLifecycle.closeHuashengTrustedFailure(permit,
                OperationOutcome.builder()
                        .outcomeClass(OperationOutcomeClass.REJECTED)
                        .externalOrderNo(CONCLUSION_NOT_CREATED.equals(conclusion) ? null : evidence.path("orderSn").asText())
                        .reportedSettlementAmount(reportedAmount)
                        .build(),
                command.actionNo(),
                new SupplierOrderLifecycle.HuashengTrustedFailureClosure(supplierCancelled, closureReason,
                        "人工失败确认（" + (command.reason() != null && !command.reason().isBlank()
                                ? sanitize(command.reason()) : "操作人确认") + "）",
                        reportedAmount));

        // 共同关单入口已更新 Operation，重新读取其CAS版本；仍在同一聚合锁与事务内。
        var terminatedOperation = operationMapper.selectById(ctx.latestOperation().getId());
        long nextGen = currentGeneration + 1;
        terminatedOperation.setExecutionGeneration(nextGen);
        terminatedOperation.setUpdatedAt(now);
        if (operationMapper.updateById(terminatedOperation) != 1) {
            throw new IllegalStateException("Confirm generation CAS failed: " + terminatedOperation.getOperationNo());
        }

        // 真实操作人在同事务结案：CONFIRM_FAILURE、operatorId、resolvedAt（设计 §7.4）。
        var caseDo = ctx.caseDo();
        caseDo.setStatus(FulfillmentExceptionCaseStatus.RESOLVED.getCode());
        caseDo.setResolutionAction(CONFIRM_FAILURE);
        caseDo.setOperatorId(command.actorId());
        caseDo.setResolvedAt(now);
        caseDo.setActiveCaseFlag(null);
        caseDo.setUpdatedAt(now);
        if (exceptionCaseMapper.update(caseDo, new LambdaUpdateWrapper<FulfillmentExceptionCaseDO>()
                .eq(FulfillmentExceptionCaseDO::getId, caseDo.getId())
                .set(FulfillmentExceptionCaseDO::getActiveCaseFlag, null)
                .set(FulfillmentExceptionCaseDO::getResolvedAt, now)) != 1) {
            throw new IllegalStateException("Case resolution CAS failed: " + caseDo.getCaseNo());
        }

        action.setExecutionStatus(SUCCEEDED);
        action.setActiveIntentFlag(null);
        action.setActualCaseVersion(caseDo.getVersion());
        action.setActualGeneration(nextGen);
        action.setCompletionSummary("失败确认完成，稳定原因=" + closureReason.name());
        action.setUpdatedAt(now);
        if (caseActionMapper.update(action, new LambdaUpdateWrapper<FulfillmentCaseActionDO>()
                .eq(FulfillmentCaseActionDO::getId, action.getId())
                .set(FulfillmentCaseActionDO::getActiveIntentFlag, null)) != 1) {
            throw new IllegalStateException("Confirm action finalize CAS failed: " + command.actionNo());
        }
        afterCommitLog("[Case失败确认][完成] caseNo={}, actionNo={}, actorId={}, reason={}",
                command.caseNo(), command.actionNo(), command.actorId(), closureReason);
        return toActionView(action);
    }

    // ==================== 内部工具 ====================

    static boolean isManualCase(FulfillmentExceptionCaseDO caseDo, BOrderDO bOrder) {
        return Objects.equals(caseDo.getActiveCaseFlag(), 1)
                && (Objects.equals(caseDo.getStatus(), FulfillmentExceptionCaseStatus.OPEN.getCode())
                    || Objects.equals(caseDo.getStatus(), FulfillmentExceptionCaseStatus.PROCESSING.getCode()))
                && Objects.equals(bOrder.getFulfillmentStatus(), BOrderFulfillmentStatus.MANUAL.getCode());
    }

    private void requireManualCase(CaseContext ctx) {
        if (!isManualCase(ctx.caseDo(), ctx.bOrder())) throw new IllegalStateException("仅允许活动原人工 Case 两步处置");
    }

    /** UI 资格只作保守提示；密文无法认证、历史反向事实或待核资产均关闭按钮，命令仍锁内复核。设计 §6.3。 */
    private boolean hasSafeEvidence(CaseContext ctx, FulfillmentCaseActionDO latest) {
        try {
            var checkpoint = decryptCheckpoint(ctx);
            if (checkpoint.pendingAssetPayload() != null) return false;
            var reason = CONCLUSION_NOT_CREATED.equals(latest.getVerifyConclusion()) ? HuashengStableFailureReason.HUASHENG_NOT_CREATED
                    : CONCLUSION_SUPPLIER_CANCELLED.equals(latest.getVerifyConclusion()) ? HuashengStableFailureReason.HUASHENG_SUPPLIER_CANCELLED
                    : HuashengStableFailureReason.HUASHENG_SUPPLIER_FAILED;
            if (supplierOrderLifecycle.hasHuashengFailureEvidenceConflict(ctx.latestOperation().getOperationNo(), reason)) return false;
            var evidence = readEvidence(latest, ctx);
            if (!evidence.path("validIdentity").asBoolean(false) || evidence.path("hasAsset").asBoolean(true)) return false;
            for (var historical : caseActionMapper.selectList(new LambdaQueryWrapper<FulfillmentCaseActionDO>()
                    .eq(FulfillmentCaseActionDO::getBOrderId, ctx.bOrder().getId())
                    .isNotNull(FulfillmentCaseActionDO::getEvidenceCiphertext))) {
                var previous = readEvidence(historical, ctx);
                if ("SUCCESS".equals(previous.path("status").asText()) || "Y".equals(previous.path("isTake").asText())
                        || previous.path("hasAsset").asBoolean(true) || !previous.path("validIdentity").asBoolean(false)) return false;
            }
            if (CONCLUSION_NOT_CREATED.equals(latest.getVerifyConclusion())) {
                var candidate = checkpoint.notCreatedCandidate();
                return candidate != null && !candidate.exited() && checkpoint.orderSn() == null
                        && candidate.validNotCreatedQueryCount() == HuashengErrorClassification.REQUIRED_VALID_NOT_CREATED_QUERIES
                        && evidence.path("rawErrorCode").asInt() == 7014;
            }
            return "N".equals(evidence.path("isTake").asText()) && "SUCCESS".equals(evidence.path("refundStatus").asText())
                    && evidence.has("totalPriceMinorUnits");
        } catch (RuntimeException exception) {
            // 无法验证的历史数据不构成动账授权；确认入口保留具体异常因果。
            return false;
        }
    }

    static void afterCommitLog(String message, Object... arguments) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { log.info(message, arguments); }
                });
    }

    private com.fasterxml.jackson.databind.JsonNode readEvidence(FulfillmentCaseActionDO action, CaseContext ctx) {
        if (!Objects.equals(action.getEvidenceSchemaVersion(), 1) || action.getEvidenceCiphertext() == null) {
            throw new IllegalStateException("核实证据缺失或版本不支持");
        }
        byte[] aad = String.join("|", "HS_CASE_VERIFY_EVIDENCE", action.getActionNo(), action.getCaseNo(),
                action.getOperationNo(), ctx.bOrder().getConnectorVersionId().toString(),
                ctx.bOrder().getOfferVersionId().toString(), action.getActualGeneration().toString()).getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = envelopeCrypto.decrypt(action.getEvidenceCiphertext(), action.getEvidenceKeyVersion(), aad);
        try { return objectMapper.readTree(plaintext); }
        catch (java.io.IOException e) { throw new IllegalStateException("核实证据不可解析", e); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }

    private static boolean isConfirmableConclusion(String conclusion) {
        return CONCLUSION_SUPPLIER_CANCELLED.equals(conclusion)
                || CONCLUSION_SUPPLIER_FAILED.equals(conclusion)
                || CONCLUSION_NOT_CREATED.equals(conclusion);
    }

    private record CaseContext(FulfillmentExceptionCaseDO caseDo, BOrderDO bOrder,
                               FulfillmentRequestDO request, SupplyChainOrderDO order,
                               FulfillmentOperationDO latestOperation) {
    }

    private CaseContext loadCaseContext(String caseNo) {
        var caseDo = exceptionCaseMapper.selectOne(new LambdaQueryWrapper<FulfillmentExceptionCaseDO>()
                .eq(FulfillmentExceptionCaseDO::getCaseNo, caseNo));
        if (caseDo == null) {
            throw new IllegalArgumentException("Fulfillment case does not exist: " + caseNo);
        }
        var bOrder = bOrderMapper.selectById(caseDo.getBOrderId());
        if (bOrder == null) {
            throw new IllegalStateException("BOrder does not exist for case: " + caseNo);
        }
        var request = requestMapper.selectById(bOrder.getFulfillmentRequestId());
        if (request == null) {
            throw new IllegalStateException("FulfillmentRequest does not exist for case: " + caseNo);
        }
        var order = orderMapper.selectById(bOrder.getOrderId());
        if (order == null) {
            throw new IllegalStateException("SupplyChainOrder does not exist for case: " + caseNo);
        }
        var operations = operationMapper.selectList(new LambdaQueryWrapper<FulfillmentOperationDO>()
                .eq(FulfillmentOperationDO::getBOrderId, bOrder.getId())
                .orderByAsc(FulfillmentOperationDO::getSequenceNo));
        if (operations.isEmpty()) {
            throw new IllegalStateException("No operations exist for case: " + caseNo);
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            request = executionMapper.lockRequest(bOrder.getFulfillmentRequestId());
            order = executionMapper.lockOrder(bOrder.getOrderId());
            var locator = bOrder;
            bOrder = executionMapper.lockBOrders(order.getId()).stream()
                    .filter(row -> Objects.equals(row.getId(), locator.getId())).findFirst().orElseThrow();
            operations = executionMapper.lockOperations(order.getId()).stream()
                    .filter(op -> Objects.equals(op.getBOrderId(), locator.getId()))
                    .sorted(java.util.Comparator.comparing(FulfillmentOperationDO::getSequenceNo)).toList();
            caseDo = exceptionCaseMapper.selectOne(new LambdaQueryWrapper<FulfillmentExceptionCaseDO>()
                    .eq(FulfillmentExceptionCaseDO::getCaseNo, caseNo).last("FOR UPDATE"));
        }
        return new CaseContext(caseDo, bOrder, request, order, operations.getLast());
    }

    /** 只支持 HUA_SHENG_ORDER_V1、CARD、quantity=1 的原人工单（设计 §6.1）。 */
    private void requireHuashengCard(CaseContext ctx, String action) {
        var connectorConfig = connectorResolver.resolve(ctx.bOrder().getConnectorVersionId());
        if (!HuashengOrderV1Metadata.TYPE.equals(connectorConfig.adapterType())) {
            throw new IllegalArgumentException("Case " + action + " only supports HUA_SHENG_ORDER_V1");
        }
        if (ctx.order().getQuantity() == null || ctx.order().getQuantity() != 1L) {
            throw new IllegalArgumentException("Case " + action + " only supports quantity=1 orders");
        }
        if (!com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderType.CARD.name().equals(ctx.order().getOrderType())) throw new IllegalArgumentException("仅支持 CARD 原单");
    }

    private HuashengCheckpoint decryptCheckpoint(CaseContext ctx) {
        var operation = ctx.latestOperation();
        var binding = new HuashengCheckpoint.Binding(ctx.bOrder().getId(), ctx.bOrder().getBOrderNo(),
                operation.getOperationNo(), operation.getSequenceNo(),
                AdapterStepCode.valueOf(operation.getAdapterStepCode()),
                ctx.bOrder().getConnectorVersionId(), ctx.bOrder().getOfferVersionId(), operation.getConfigDigest());
        return checkpointCodec.decrypt(new HuashengCheckpointCodec.Stored(
                operation.getCheckpointSchemaVersion(), operation.getCheckpointKeyVersion(),
                operation.getCheckpointCiphertext()), binding);
    }

    private HuashengCheckpoint.NotCreatedCandidate peekCandidate(CaseContext ctx) {
        try {
            return decryptCheckpoint(ctx).notCreatedCandidate();
        } catch (Exception e) {
            return null;
        }
    }

    /** 命令指纹：绑定真实 actor、命令类型、Case／原单身份及全部业务参数（设计 §6.3）。 */
    private static byte[] fingerprint(Long actorId, String commandType, String caseNo, String bOrderNo,
                                      String operationNo, Long expectedCaseVersion, Long expectedGeneration,
                                      String verifyActionNo, String reason) {
        String canonical;
        try {
            canonical = new ObjectMapper().writeValueAsString(Arrays.asList(actorId, commandType, caseNo,
                    bOrderNo, operationNo, expectedCaseVersion, expectedGeneration, verifyActionNo, reason));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("命令指纹规范化失败", e);
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sanitize(String text) {
        StringBuilder sb = new StringBuilder(Math.min(text.length(), 256));
        for (int i = 0; i < text.length() && sb.length() < 256; i++) {
            char c = text.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
