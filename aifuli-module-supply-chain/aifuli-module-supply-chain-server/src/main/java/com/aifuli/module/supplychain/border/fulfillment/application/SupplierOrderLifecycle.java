package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAssetDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentCallLogDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentExceptionCaseDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RawStatusObservationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAssetMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentCallLogMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentExceptionCaseMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RawStatusObservationMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyEntryStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.CallDigestFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentAssetStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentCallDirection;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentExceptionCaseStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentExceptionReasonCodes;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentRequestStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.fulfillment.domain.RawObservationFact;
import com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.OrderStatusLogMapper;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
import com.aifuli.module.supplychain.border.order.domain.BOrderSettlementStatus;
import com.aifuli.module.supplychain.border.order.domain.CardOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryDecision;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryFact;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.qnm.QnmMaskingUtils;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengType201AssetCodec;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengFlowPolicy;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengExecutionPermit;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengStableFailureReason;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengStepFact;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 供应商订单（BOrder）与履约外部操作生命周期服务。
 *
 * <p>核心职责：在短事务内利用 CAS 乐观锁条件推进 Operation 与 BOrder 状态，
 * 同步更新 FulfillmentRequest 交付进度，对齐 {@link CardOrderSummaryPolicy} 推进总订单公共状态与业务阶段，
 * 记录不可变状态变更审计，并在终态时完成资金 Commit 或 Release 闭环。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}
 * 以及 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierOrderLifecycle {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final byte[] EMPTY_DIGEST = new byte[32];
    private static final int CALL_LOG_REDACTION_VERSION = 1;

    private final BOrderMapper bOrderMapper;
    private final FulfillmentOperationMapper operationMapper;
    private final FulfillmentRequestMapper requestMapper;
    private final SupplyChainOrderMapper orderMapper;
    private final FulfillmentAssetMapper assetMapper;
    private final FulfillmentCallLogMapper callLogMapper;
    private final RawStatusObservationMapper observationMapper;
    private final FulfillmentExceptionCaseMapper exceptionCaseMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final FundReservationService fundReservationService;
    private final CardOrderSummaryPolicy cardOrderSummaryPolicy;
    private final EnvelopeCryptoPort envelopeCrypto;
    private final HmacFingerprintPort hmacFingerprint;
    private final BusinessNoGenerator businessNoGenerator;
    private final SupplyChainDatabaseClock databaseClock;
    private final ObjectMapper objectMapper;
    private final RechargeFrequencyLifecycle frequencyLifecycle;

    /**
     * 根据执行结果状态事实推进 BOrder、Operation、Request、Order 及资金/资产闭环。
     *
     * @param operationNo 履约操作编号
     * @param outcome     标准化履约结果事实
     * @param traceId     调用链跟踪标识
     * @return 状态转换结果
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public TransitionResult transition(String operationNo, OperationOutcome outcome, String traceId) {
        return transitionInternal(operationNo, outcome, traceId, false, null, null);
    }

    /**
     * 查单、可信回调或内部核实的终态事实入口。MANUAL只能由权威SUCCEEDED/REJECTED恢复，不能据此重新发送。
     * 资格非空时验证原占用代次，迟到旧执行者只留观察，不能影响新持有者或资金。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#5-结果窗口和资金。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public TransitionResult confirmTrustedOutcome(String operationNo, OperationOutcome outcome, String traceId,
                                                   RechargeFrequencyExecutionPermit permit) {
        return transitionInternal(operationNo, outcome, traceId, true, permit, null);
    }

    /**
     * 内部受控编排显式提交可信拒绝和已存在的下一充值操作，不自动发现PENDING或创建路由。
     * 所有合法性校验、旧事实、活动尝试切换及冻结队列绑定处于同一事务；失败完整回滚。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#5-结果窗口和资金。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public TransitionResult confirmTrustedRejectedAndContinue(String operationNo, OperationOutcome outcome, String traceId,
                                                               RechargeFrequencyExecutionPermit permit, String nextOperationNo) {
        log.info("[供应商订单][lifecycle] 受控续履约 operationNo={}, nextOperationNo={}, traceId={}", operationNo, nextOperationNo, traceId);
        Objects.requireNonNull(permit, "续履约必须携带原资格");
        Objects.requireNonNull(nextOperationNo, "续履约必须显式指定下一操作");
        return transitionInternal(operationNo, outcome, traceId, true, permit, nextOperationNo);
    }

    /**
     * 华昇受控成功提交入口：仅允许携带有效 permit、完整 type201 资产及与冻结成本一致的金额。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public TransitionResult commitHuashengSuccess(HuashengExecutionPermit permit, OperationOutcome outcome, String traceId) {
        Objects.requireNonNull(permit, "permit must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        log.info("[供应商订单][lifecycle] 华昇受控成功提交入口 operationNo={}, generation={}, traceId={}",
                permit.operationNo(), permit.executionGeneration(), traceId);

        var locked = frequencyLifecycle.lockForResult(permit.operationNo());
        FulfillmentOperationDO operation = locked != null ? locked.operation() : operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .eq(FulfillmentOperationDO::getOperationNo, permit.operationNo()));
        if (operation == null) {
            throw new IllegalArgumentException("FulfillmentOperation does not exist: " + permit.operationNo());
        }
        BOrderDO bOrder = locked != null ? locked.bOrder() : bOrderMapper.selectById(operation.getBOrderId());
        if (bOrder == null) {
            throw new IllegalStateException("BOrder does not exist for operation: " + permit.operationNo());
        }
        FulfillmentRequestDO request = locked != null ? locked.request() : requestMapper.selectById(bOrder.getFulfillmentRequestId());
        if (request == null) {
            throw new IllegalStateException("FulfillmentRequest does not exist: " + bOrder.getFulfillmentRequestId());
        }
        SupplyChainOrderDO order = locked != null ? locked.order() : orderMapper.selectById(bOrder.getOrderId());
        if (order == null) {
            throw new IllegalStateException("SupplyChainOrder does not exist: " + bOrder.getOrderId());
        }

        // Guard 1: permit 身份与代次
        if (!Objects.equals(permit.operationNo(), operation.getOperationNo())
                || !Objects.equals(permit.bOrderId(), operation.getBOrderId())) {
            throw new IllegalStateException("Permit identity mismatch");
        }
        if (operation.getExecutionGeneration() == null
                || !operation.getExecutionGeneration().equals(permit.executionGeneration())) {
            throw new IllegalStateException("Permit generation mismatch: permit=" + permit.executionGeneration()
                    + ", current=" + operation.getExecutionGeneration());
        }

        // Guard 2: 金额一致性
        if (outcome.reportedSettlementAmount() == null
                || !outcome.reportedSettlementAmount().equals(bOrder.getExpectedSupplierSettlementAmount())) {
            throw new IllegalStateException("Reported settlement amount mismatch: expected="
                    + bOrder.getExpectedSupplierSettlementAmount() + ", reported=" + outcome.reportedSettlementAmount());
        }

        // Guard 3: 资产载荷与 type201 严格合法性
        if (outcome.assets() == null || outcome.assets().size() != 1) {
            throw new IllegalStateException("Huasheng commit requires exactly one asset");
        }
        OperationAssetFact assetFact = outcome.assets().get(0);
        if (assetFact.protectedPayload() == null || !HuashengFlowPolicy.isValidType201Payload(assetFact.protectedPayload())) {
            throw new IllegalStateException("Huasheng asset payload is not valid type 201");
        }

        LocalDateTime now = databaseClock.now();
        persistCallAndObservation(operation, bOrder, outcome, traceId, now);

        // 1. Operation 终态
        operation.setStatus(SupplierOrderOperationStatus.SUCCEEDED.getCode());
        operation.setResultClass(outcome.outcomeClass().name());
        if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
            operation.setExternalOperationNo(outcome.externalOrderNo());
        }
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setCompletedAt(now);
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
        }

        // 2. BOrder 终态
        bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.SUCCEEDED.getCode());
        bOrder.setFulfilledQuantity(bOrder.getRequestedQuantity());
        bOrder.setActiveFlag(null);
        bOrder.setReportedSupplierSettlementAmount(outcome.reportedSettlementAmount());
        bOrder.setConfirmedSupplierSettlementAmount(outcome.reportedSettlementAmount());
        bOrder.setSettlementStatus(BOrderSettlementStatus.MATCHED.getCode());
        if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
            bOrder.setExternalOrderNo(outcome.externalOrderNo());
        }
        bOrder.setCompletedAt(now);
        bOrder.setUpdatedAt(now);
        if (updateBOrderState(bOrder, false) != 1) {
            throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
        }

        // 3. 持久化资产（调用已有 persistAssets / persistHuashengAsset）
        persistAssets(bOrder, operation, request, order, outcome, now);

        // 4. FulfillmentRequest 终态
        request.setFulfilledQuantity(bOrder.getRequestedQuantity());
        request.setStatus(FulfillmentRequestStatus.SUCCEEDED.getCode());
        request.setUpdatedAt(now);
        if (requestMapper.updateById(request) != 1) {
            throw new IllegalStateException("FulfillmentRequest CAS update lost optimistic lock: " + request.getRequestNo());
        }

        // 5. 对齐 CardOrderSummaryPolicy 推进总订单
        OrderSummaryFact fact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT,
                FulfillmentRequestStatus.SUCCEEDED.getCode(),
                order.getQuantity(), order.getQuantity(), null);
        OrderSummaryDecision decision = cardOrderSummaryPolicy.decide(fact);
        short fromOrderStatus = order.getOrderStatus();
        order.setOrderStatus(decision.orderStatus());
        order.setAftersaleStatus(decision.aftersaleStatus());
        order.setBusinessStage(decision.businessStage());
        long nextDetailVersion = (order.getDetailVersion() != null ? order.getDetailVersion() : 0L) + 1L;
        order.setDetailVersion(nextDetailVersion);
        order.setCompletedAt(now);
        order.setUpdatedAt(now);
        if (orderMapper.updateById(order) != 1) {
            throw new IllegalStateException("SupplyChainOrder CAS update lost optimistic lock: " + order.getOrderNo());
        }

        // 6. 审计日志
        statusLogMapper.insert(OrderStatusLogDO.builder()
                .orderId(order.getId())
                .detailVersion(nextDetailVersion)
                .fromOrderStatus(fromOrderStatus)
                .toOrderStatus(decision.orderStatus())
                .fromAftersaleStatus((short) 0)
                .toAftersaleStatus((short) 0)
                .businessStage(decision.businessStage())
                .sourceEventType("FULFILLMENT_SUCCEEDED")
                .sourceEventNo(bOrder.getBOrderNo())
                .reasonCode("SUPPLIER_SUCCEEDED")
                .traceId(traceId)
                .createdAt(now).build());

        // 7. 资金确认闭环：实扣资金
        fundReservationService.commitByReference(request.getBuyerId(),
                FundReferenceType.CARD_ORDER.code(), request.getRequestNo(),
                order.getOrderNo(), traceId, bOrder.getBOrderNo());

        logAfterCommit("[供应商订单][lifecycle] 华昇 commitHuashengSuccess 完成 operationNo={}, bOrderNo={}, orderNo={}",
                operation.getOperationNo(), bOrder.getBOrderNo(), order.getOrderNo());
        return new TransitionResult(operation.getOperationNo(), bOrder.getBOrderNo(), order.getOrderNo(),
                operation.getStatus(), bOrder.getFulfillmentStatus(), order.getOrderStatus(), true);
    }

    /**
     * 可信失败收敛命令：区分供应商取消与失败、携带稳定原因与实报金额（价差独立留存）。
     * 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#33-原因记录、#53-同事务写入及冲突留证。
     */
    public record HuashengTrustedFailureClosure(boolean supplierCancelled,
                                                HuashengStableFailureReason reason,
                                                String reasonNote,
                                                Long reportedSettlementAmount) {
    }

    /**
     * 锁后追加独立的华昇结果事实，不覆盖历史，不依赖普通日志判断四次查单或历史成功。
     * 调用方必须已通过当前执行围栏；与结果/Timer或人工动作证据同一事务。
     * 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#7-持久化与协议兼容。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
    public void recordHuashengResult(String operationNo, HuashengStepFact fact, String traceId, LocalDateTime startedAt) {
        var locked = frequencyLifecycle.lockForResult(operationNo);
        var op = locked.operation();
        var b = locked.bOrder();
        int resultOrdinal = Math.toIntExact(callLogMapper.selectCount(new LambdaQueryWrapper<FulfillmentCallLogDO>()
                .eq(FulfillmentCallLogDO::getOperationId, op.getId())
                .isNotNull(FulfillmentCallLogDO::getEvidenceCiphertext)) + 1);
        // 两分支保持 Integer：无码未知留 NULL，不拆箱或伪造业务成功（设计 §7）。
        var row = FulfillmentCallLogDO.builder().callNo(businessNoGenerator.next(BusinessNoPrefixes.CL))
                .operationId(op.getId()).bOrderId(b.getId()).executionGeneration(op.getExecutionGeneration())
                .attemptNo(resultOrdinal)
                .direction(fact.endpoint() == HuashengStepFact.Endpoint.CALLBACK ? "INBOUND" : "OUTBOUND")
                .connectorVersionId(b.getConnectorVersionId()).endpointCode(fact.endpoint().name())
                .startedAt(Objects.requireNonNull(startedAt)).durationMs(fact.attempts().stream().mapToLong(HuashengStepFact.AttemptObservation::durationMs).sum())
                .resultClass(fact.resultClass().name()).remoteCode(fact.resultClass() == HuashengStepFact.StepResult.ACCEPTED ? Integer.valueOf(0) : fact.rawErrorCode())
                .supplierTraceId(fact.supplierTraceId()).businessSendStarted(fact.attempts().isEmpty() ? null
                        : fact.attempts().stream().anyMatch(HuashengStepFact.AttemptObservation::wasBusinessCallStarted))
                .requestDigest(EMPTY_DIGEST).redactionVersion(CALL_LOG_REDACTION_VERSION)
                .traceId(traceId == null ? "" : traceId).createdAt(databaseClock.now()).evidenceSchemaVersion(1).build();
        var node = objectMapper.createObjectNode();
        node.put("endpoint", fact.endpoint().name()); node.put("resultClass", fact.resultClass().name());
        node.put("outOrderSn", fact.outOrderSn()); node.put("orderSn", fact.orderSn()); node.put("num", fact.num());
        node.put("status", fact.status() == null ? null : fact.status().name()); node.put("isTake", fact.isTake().name());
        node.put("hasAsset", fact.protectedAssetPayload() != null);
        node.put("rawErrorCode", fact.rawErrorCode()); node.put("recordedAt", row.getCreatedAt().toString());
        node.put("startedAt", row.getStartedAt().toString()); node.put("sideEffectFree", Boolean.TRUE.equals(op.getSideEffectFree()));
        node.put("refundStatus", fact.refundStatus().name());
        node.put("securityReason", fact.securityReason() == null ? null : fact.securityReason().name());
        node.put("tokenOnly", !fact.attempts().isEmpty() && fact.attempts().stream().allMatch(attempt ->
                "TOKEN".equals(attempt.endpoint()) && !attempt.wasBusinessCallStarted()));
        node.put("totalPriceRaw", fact.totalPriceRaw()); node.put("totalPriceMinorUnits", fact.totalPriceMinorUnits());
        byte[] plain = null;
        try {
            plain = objectMapper.writeValueAsBytes(node);
            var encrypted = envelopeCrypto.encrypt(plain, resultEvidenceAad(row, op, b));
            row.setEvidenceKeyVersion(encrypted.keyVersion()); row.setEvidenceCiphertext(encrypted.ciphertext());
            callLogMapper.insert(row);
        } catch (java.io.IOException e) { throw new IllegalStateException("华昇结果事实编码失败", e); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    private byte[] resultEvidenceAad(FulfillmentCallLogDO row, FulfillmentOperationDO op, BOrderDO b) {
        return String.join("|", "HS_RESULT_V1", row.getCallNo(), op.getOperationNo(), b.getBOrderNo(),
                b.getConnectorVersionId().toString(), b.getOfferVersionId().toString(),
                row.getExecutionGeneration().toString()).getBytes(StandardCharsets.UTF_8);
    }

    /** 自动流程在动账前检查历史冲突，失败留证转人工；人工确认仍由关单入口抛错并回滚。设计 §5.3。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER)
    public boolean hasHuashengFailureEvidenceConflict(String operationNo, HuashengStableFailureReason reason) {
        try {
            assertHuashengFailureEvidence(frequencyLifecycle.lockForResult(operationNo),
                    new HuashengTrustedFailureClosure(reason == HuashengStableFailureReason.HUASHENG_SUPPLIER_CANCELLED, reason, "", null));
            return false;
        } catch (IllegalStateException | IllegalArgumentException evidenceConflict) {
            return true;
        }
    }

    /** 历史 checkpoint 和逐次结果的共同释放围栏，旧密文无安全证明时不伪造查询次数。设计 §5.2、§5.4。 */
    private void assertHuashengFailureEvidence(RechargeFrequencyLifecycle.LockedExecution locked,
                                               HuashengTrustedFailureClosure closure) {
        var b = locked.bOrder();
        var codec = new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpointCodec(envelopeCrypto);
        var ops = locked.relatedOperations().stream().filter(op -> Objects.equals(op.getBOrderId(), b.getId())).toList();
        var byId = ops.stream().collect(java.util.stream.Collectors.toMap(FulfillmentOperationDO::getId, op -> op));
        for (var op : ops) {
            if (OperationOutcomeClass.SUCCEEDED.name().equals(op.getResultClass())) throw new IllegalStateException("历史成功禁止失败释放");
            if (op.getCheckpointCiphertext() == null) throw new IllegalStateException("历史检查点缺失，不能证明释放安全");
            var binding = new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint.Binding(
                    b.getId(), b.getBOrderNo(), op.getOperationNo(), op.getSequenceNo(), AdapterStepCode.valueOf(op.getAdapterStepCode()),
                    b.getConnectorVersionId(), b.getOfferVersionId(), op.getConfigDigest());
            var cp = codec.decrypt(new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpointCodec.Stored(
                    op.getCheckpointSchemaVersion(), op.getCheckpointKeyVersion(), op.getCheckpointCiphertext()), binding);
            var observation = cp.observation();
            if (cp.pendingAssetPayload() != null || (observation != null
                    && (observation.status() == com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderStatus.SUCCESS
                        || observation.isTake().asOptional().orElse(null) == com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTakeStatus.Y))) {
                throw new IllegalStateException("历史成功、提卡或待核资产禁止失败释放");
            }
        }
        int validCount = 0;
        int queryResultCount = 0;
        LocalDateTime previousResultAt = null;
        var generations = new java.util.HashSet<String>();
        boolean notCreated = closure.reason() == HuashengStableFailureReason.HUASHENG_NOT_CREATED;
        com.fasterxml.jackson.databind.JsonNode latestFact = null;
        Long latestGeneration = null;
        var facts = callLogMapper.selectList(new LambdaQueryWrapper<FulfillmentCallLogDO>()
                .eq(FulfillmentCallLogDO::getBOrderId, b.getId()).isNotNull(FulfillmentCallLogDO::getEvidenceCiphertext)
                .orderByAsc(FulfillmentCallLogDO::getId));
        for (var row : facts) {
            var op = byId.get(row.getOperationId());
            if (op == null || !Objects.equals(row.getEvidenceSchemaVersion(), 1)) throw new IllegalStateException("结果证据身份或版本不符");
            byte[] plain = envelopeCrypto.decrypt(row.getEvidenceCiphertext(), row.getEvidenceKeyVersion(), resultEvidenceAad(row, op, b));
            com.fasterxml.jackson.databind.JsonNode fact;
            try { fact = objectMapper.readTree(plain); }
            catch (java.io.IOException e) { throw new IllegalStateException("结果证据不可解析", e); }
            finally { Arrays.fill(plain, (byte) 0); }
            latestFact = fact;
            latestGeneration = row.getExecutionGeneration();
            if (!Objects.equals(b.getBillno(), fact.path("outOrderSn").asText())
                    || "SUCCESS".equals(fact.path("status").asText()) || "Y".equals(fact.path("isTake").asText())
                    || fact.path("hasAsset").asBoolean(true)
                    || (b.getExternalOrderNo() != null && !fact.path("orderSn").isNull()
                        && !Objects.equals(b.getExternalOrderNo(), fact.path("orderSn").asText()))) {
                throw new IllegalStateException("历史成功、提卡、资产或身份冲突禁止失败释放");
            }
            if (!notCreated) continue;
            if ("ACCEPTED".equals(fact.path("resultClass").asText()) || !fact.path("orderSn").isNull()
                    || "CONFIRM".equals(fact.path("endpoint").asText()) || "TAKE".equals(fact.path("endpoint").asText())) {
                throw new IllegalStateException("已建单不得作为可信未建单释放");
            }
            if ("PLACE".equals(fact.path("endpoint").asText()) && op.getSequenceNo() == 1
                    && com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengErrorClassification
                        .isPlaceCreationRejection(fact.path("rawErrorCode").asInt())
                    && fact.path("sideEffectFree").asBoolean(false) && previousResultAt == null) {
                previousResultAt = LocalDateTime.parse(fact.path("recordedAt").asText());
            } else if ("QUERY".equals(fact.path("endpoint").asText()) && previousResultAt != null) {
                var startedAt = LocalDateTime.parse(fact.path("startedAt").asText());
                int delay = com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengErrorClassification.notCreatedBackoffSeconds(queryResultCount);
                if (startedAt.isBefore(previousResultAt.plusSeconds(delay))) throw new IllegalStateException("查单未满足持久退避");
                previousResultAt = LocalDateTime.parse(fact.path("recordedAt").asText());
                queryResultCount++;
                if (fact.path("rawErrorCode").asInt() == 7014
                        && generations.add(op.getOperationNo() + ":" + row.getExecutionGeneration())) validCount++;
            } else {
                throw new IllegalStateException("首次拒绝前存在未核实历史或重复创建");
            }
        }
        if (notCreated && validCount < com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengErrorClassification.REQUIRED_VALID_NOT_CREATED_QUERIES) {
            throw new IllegalStateException("缺少首次拒绝及四份有效未建单查询证据");
        }
        if (latestFact == null || !Objects.equals(latestGeneration, locked.operation().getExecutionGeneration())) {
            throw new IllegalStateException("缺少当前代次可信失败结果证据");
        }
        if (notCreated) return;
        boolean supplierFailure = closure.reason() == HuashengStableFailureReason.HUASHENG_SUPPLIER_CANCELLED
                || closure.reason() == HuashengStableFailureReason.HUASHENG_SUPPLIER_FAILED;
        if (supplierFailure) {
            String status = closure.supplierCancelled() ? "CANCEL" : "FAILED";
            boolean callbackFailure = "CALLBACK".equals(latestFact.path("endpoint").asText())
                    && "FAILED".equals(latestFact.path("status").asText()) && latestFact.path("num").asInt(-1) == 0;
            boolean queryFailure = !"CALLBACK".equals(latestFact.path("endpoint").asText())
                    && status.equals(latestFact.path("status").asText()) && latestFact.path("num").asInt(-1) == 1
                    && "N".equals(latestFact.path("isTake").asText()) && "SUCCESS".equals(latestFact.path("refundStatus").asText());
            if (!"ACCEPTED".equals(latestFact.path("resultClass").asText()) || (!callbackFailure && !queryFailure)) {
                throw new IllegalStateException("供应商终态缺少可信零交付与退款证据");
            }
        } else {
            int code = latestFact.path("rawErrorCode").asInt();
            if (!"PLACE".equals(latestFact.path("endpoint").asText()) || locked.operation().getSequenceNo() != 1
                    || !latestFact.path("sideEffectFree").asBoolean(false)
                    || (com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengErrorClassification.isAuthorizationRejection(code)
                        && !latestFact.path("tokenOnly").asBoolean(false))) {
                throw new IllegalStateException("初始拒绝缺少完整无副作用证据");
            }
        }
    }

    /**
     * 华昇受控失败释放入口：仅允许携带有效 permit、可信失败且无已交付资产。
     * 委托 {@link #closeHuashengTrustedFailure}，默认按供应商履约失败收敛。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public TransitionResult releaseHuashengRejection(HuashengExecutionPermit permit, OperationOutcome outcome, String traceId) {
        return closeHuashengTrustedFailure(permit, outcome, traceId, new HuashengTrustedFailureClosure(
                false, HuashengStableFailureReason.HUASHENG_SUPPLIER_FAILED,
                "供应商履约失败", outcome.reportedSettlementAmount()));
    }

    /**
     * 华昇可信失败收敛事务（设计 §5.2、§5.3）。
     *
     * <p>短事务内统一：锁聚合 → 再核终态／代次／证据 → 写供应商取消或失败 → 写 Request／平台失败 →
     * 写稳定原因与原始观察 → 释放预留 → 使旧执行资格失效。金额差异保留在 expected／reported 与
     * settlementStatus=MISMATCH；confirmedSupplierSettlementAmount 不伪造。同向终态且预留已释放的重放幂等返回；
     * 资金终态与订单组合不一致转存储异常，不自行修复。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public TransitionResult closeHuashengTrustedFailure(HuashengExecutionPermit permit, OperationOutcome outcome,
                                                        String traceId, HuashengTrustedFailureClosure closure) {
        Objects.requireNonNull(permit, "permit must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(closure, "closure must not be null");
        log.info("[供应商订单][lifecycle] 华昇可信失败收敛入口 operationNo={}, generation={}, cancelled={}, reason={}, traceId={}",
                permit.operationNo(), permit.executionGeneration(), closure.supplierCancelled(),
                closure.reason(), traceId);

        var locked = frequencyLifecycle.lockForResult(permit.operationNo());
        if (locked == null) throw new IllegalStateException("可信失败关单必须取得完整聚合锁");
        FulfillmentOperationDO operation = locked != null ? locked.operation() : operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .eq(FulfillmentOperationDO::getOperationNo, permit.operationNo()));
        if (operation == null) {
            throw new IllegalArgumentException("FulfillmentOperation does not exist: " + permit.operationNo());
        }
        BOrderDO bOrder = locked != null ? locked.bOrder() : bOrderMapper.selectById(operation.getBOrderId());
        if (bOrder == null) {
            throw new IllegalStateException("BOrder does not exist for operation: " + permit.operationNo());
        }
        FulfillmentRequestDO request = locked != null ? locked.request() : requestMapper.selectById(bOrder.getFulfillmentRequestId());
        if (request == null) {
            throw new IllegalStateException("FulfillmentRequest does not exist: " + bOrder.getFulfillmentRequestId());
        }
        SupplyChainOrderDO order = locked != null ? locked.order() : orderMapper.selectById(bOrder.getOrderId());
        if (order == null) {
            throw new IllegalStateException("SupplyChainOrder does not exist: " + bOrder.getOrderId());
        }

        // Guard 1: permit 身份与代次
        if (!Objects.equals(permit.operationNo(), operation.getOperationNo())
                || !Objects.equals(permit.bOrderId(), operation.getBOrderId())) {
            throw new IllegalStateException("Permit identity mismatch");
        }
        if (operation.getExecutionGeneration() == null
                || !operation.getExecutionGeneration().equals(permit.executionGeneration())) {
            throw new IllegalStateException("Permit generation mismatch: permit=" + permit.executionGeneration()
                    + ", current=" + operation.getExecutionGeneration());
        }

        // Guard 2: 不得存在已交付资产（设计 §5.2 第 3 项）
        List<FulfillmentAssetDO> existingAssets = assetMapper.selectList(new LambdaQueryWrapper<FulfillmentAssetDO>()
                .eq(FulfillmentAssetDO::getBOrderId, bOrder.getId()));
        if (!existingAssets.isEmpty()) {
            throw new IllegalStateException("Delivered assets already exist for BOrder: " + bOrder.getBOrderNo());
        }

        // Guard 3: 终态幂等与反向终态冲突（设计 §5.2 第 5 项）
        short targetStatus = closure.supplierCancelled()
                ? BOrderFulfillmentStatus.CANCELLED.getCode() : BOrderFulfillmentStatus.FAILED.getCode();
        if (BOrderFulfillmentStatus.isConvergedTerminal(bOrder.getFulfillmentStatus())) {
            if (bOrder.getFulfillmentStatus() == targetStatus) {
                var snapshot = fundReservationService.lockSnapshotByReference(request.getBuyerId(),
                        FundReferenceType.CARD_ORDER.code(), request.getRequestNo());
                if (snapshot == null) {
                    throw new IllegalStateException("Fund reservation missing for terminal BOrder: " + bOrder.getBOrderNo());
                }
                if (snapshot.status() == FundReservationService.RELEASED && snapshot.committedAmount() == 0
                        && snapshot.remainingAmount() == 0 && snapshot.releasedAmount() == snapshot.totalAmount()
                        && operation.getStatus() == SupplierOrderOperationStatus.FAILED.getCode()
                        && request.getStatus() == FulfillmentRequestStatus.FAILED.getCode()
                        && order.getOrderStatus() == SupplyChainOrderStatus.FAILED.getCode()
                        && SupplyChainOrderTypes.STAGE_CARD_FAILED.equals(order.getBusinessStage())
                        && closure.reason().name().equals(bOrder.getTerminalReasonCode())) {
                    log.info("[供应商订单][lifecycle] 可信失败收敛幂等返回 operationNo={}, bOrderNo={}",
                            operation.getOperationNo(), bOrder.getBOrderNo());
                    return new TransitionResult(operation.getOperationNo(), bOrder.getBOrderNo(), order.getOrderNo(),
                            operation.getStatus(), bOrder.getFulfillmentStatus(), order.getOrderStatus(), true);
                }
                throw new IllegalStateException("Terminal BOrder with non-released reservation: " + bOrder.getBOrderNo());
            }
            throw new IllegalStateException("Terminal contradiction on trusted failure closure: " + bOrder.getBOrderNo());
        }

        LocalDateTime now = databaseClock.now();
        if (!now.isBefore(permit.leaseUntil())) throw new IllegalStateException("可信失败资格已过期");
        var reservation = fundReservationService.lockSnapshotByReference(request.getBuyerId(), FundReferenceType.CARD_ORDER.code(), request.getRequestNo());
        if (reservation == null || reservation.status() != FundReservationService.RESERVED
                || reservation.committedAmount() != 0 || reservation.releasedAmount() != 0
                || reservation.remainingAmount() != reservation.totalAmount()) {
            throw new IllegalStateException("预留状态或金额组合不满足安全释放");
        }
        assertHuashengFailureEvidence(locked, closure);
        persistCallAndObservation(operation, bOrder, outcome, traceId, now);

        // 1. Operation 终态：FAILED，结果仍为 REJECTED（设计 §3.2）
        operation.setStatus(SupplierOrderOperationStatus.FAILED.getCode());
        operation.setResultClass(outcome.outcomeClass().name());
        operation.setCompletedAt(now);
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
        }

        // 2. BOrder 终态：可信取消为 CANCELLED，其余失败为 FAILED；显式清 NULL 活动标记（设计 §3.2、§5.3）
        short fromBOrderStatus = bOrder.getFulfillmentStatus();
        bOrder.setFulfillmentStatus(targetStatus);
        if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
            bOrder.setExternalOrderNo(outcome.externalOrderNo());
        }
        bOrder.setFulfilledQuantity(0L);
        bOrder.setCompletedAt(now);
        bOrder.setUpdatedAt(now);
        bOrder.setTerminalReasonCode(closure.reason().name());
        bOrder.setTerminalReasonNote(sanitizeNote(closure.reasonNote()));
        if (closure.reportedSettlementAmount() != null) {
            bOrder.setReportedSupplierSettlementAmount(closure.reportedSettlementAmount());
            bOrder.setSettlementStatus(Objects.equals(closure.reportedSettlementAmount(),
                    bOrder.getExpectedSupplierSettlementAmount())
                    ? BOrderSettlementStatus.MATCHED.getCode() : BOrderSettlementStatus.MISMATCH.getCode());
        }
        if (bOrderMapper.update(bOrder, new LambdaUpdateWrapper<BOrderDO>()
                .eq(BOrderDO::getId, bOrder.getId())
                .set(BOrderDO::getActiveFlag, null)
                .set(BOrderDO::getCompletedAt, bOrder.getCompletedAt())
                .set(BOrderDO::getTerminalReasonCode, bOrder.getTerminalReasonCode())
                .set(BOrderDO::getTerminalReasonNote, bOrder.getTerminalReasonNote())) != 1) {
            throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
        }

        // 3. FulfillmentRequest 终态
        request.setStatus(FulfillmentRequestStatus.FAILED.getCode());
        request.setUpdatedAt(now);
        if (requestMapper.updateById(request) != 1) {
            throw new IllegalStateException("FulfillmentRequest CAS update lost optimistic lock: " + request.getRequestNo());
        }

        // 4. 对齐 CardOrderSummaryPolicy 推进总订单：平台 FAILED、CARD_FAILED（设计 §3.2）
        OrderSummaryFact fact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT,
                FulfillmentRequestStatus.FAILED.getCode(),
                order.getQuantity(), 0L, closure.reason().name());
        OrderSummaryDecision decision = cardOrderSummaryPolicy.decide(fact);
        short fromOrderStatus = order.getOrderStatus();
        order.setOrderStatus(decision.orderStatus());
        order.setAftersaleStatus(decision.aftersaleStatus());
        order.setBusinessStage(decision.businessStage());
        long nextDetailVersion = (order.getDetailVersion() != null ? order.getDetailVersion() : 0L) + 1L;
        order.setDetailVersion(nextDetailVersion);
        order.setCompletedAt(now);
        order.setUpdatedAt(now);
        if (orderMapper.updateById(order) != 1) {
            throw new IllegalStateException("SupplyChainOrder CAS update lost optimistic lock: " + order.getOrderNo());
        }

        // 5. 审计日志：稳定原因（设计 §3.3）
        statusLogMapper.insert(OrderStatusLogDO.builder()
                .orderId(order.getId())
                .detailVersion(nextDetailVersion)
                .fromOrderStatus(fromOrderStatus)
                .toOrderStatus(decision.orderStatus())
                .fromAftersaleStatus((short) 0)
                .toAftersaleStatus((short) 0)
                .businessStage(decision.businessStage())
                .sourceEventType(closure.supplierCancelled() ? "FULFILLMENT_CANCELLED" : "FULFILLMENT_FAILED")
                .sourceEventNo(bOrder.getBOrderNo())
                .reasonCode(closure.reason().name())
                .traceId(traceId)
                .createdAt(now).build());

        // 6. 资金释放闭环：恰一次释放本单 remainingAmount（设计 §3.1）
        fundReservationService.releaseByReference(request.getBuyerId(),
                FundReferenceType.CARD_ORDER.code(), request.getRequestNo(),
                order.getOrderNo(), traceId, bOrder.getBOrderNo());

        logAfterCommit("[供应商订单][lifecycle] 华昇可信失败收敛完成 operationNo={}, bOrderNo={}, fromBOrderStatus={}, toBOrderStatus={}, reason={}, orderNo={}",
                operation.getOperationNo(), bOrder.getBOrderNo(), fromBOrderStatus, targetStatus, closure.reason(), order.getOrderNo());
        return new TransitionResult(operation.getOperationNo(), bOrder.getBOrderNo(), order.getOrderNo(),
                operation.getStatus(), bOrder.getFulfillmentStatus(), order.getOrderStatus(), true);
    }

    /** 终结说明脱敏：截断至 DDL 上限，去除控制字符。 */
    private static String sanitizeNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(note.length(), 256));
        for (int i = 0; i < note.length() && sb.length() < 256; i++) {
            char c = note.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 锁后比较已交付华昇完整资产，仅判断幂等，不写资产或资金。
     * 不只比较凭证指纹：供应商 id、有效期及所有三态字段均参与规范化载荷比较。
     * 设计见 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#82-持久防重与收敛。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean matchesHuashengDeliveredAsset(String operationNo, List<OperationAssetFact> facts) {
        var locked = frequencyLifecycle.lockForResult(operationNo);
        if (locked == null || facts == null || facts.size() != 1) return false;
        var assets = assetMapper.selectList(new LambdaQueryWrapper<FulfillmentAssetDO>()
                .eq(FulfillmentAssetDO::getBOrderId, locked.bOrder().getId()));
        if (assets.size() != 1) return false;
        var previous = assets.getFirst();
        var fact = facts.getFirst();
        if (!Objects.equals(previous.getSequenceNo(), fact.sequenceNo())
                || !Objects.equals(previous.getAssetType(), fact.assetType())
                || !Objects.equals(previous.getSupplierId(), locked.bOrder().getSupplierId())
                || !Objects.equals(previous.getBuyerId(), locked.request().getBuyerId())
                || !Objects.equals(previous.getFulfillmentRequestId(), locked.request().getId())
                || !Objects.equals(previous.getOperationId(), locked.operation().getId())
                || !Objects.equals(previous.getUnitQuantity(), 1L)
                || previous.getExternalAssetId() != null || previous.getExternalProductId() != null
                || previous.getValidFrom() != null || previous.getValidUntil() != null) return false;
        byte[] expected = null;
        byte[] plaintext = null;
        byte[] canonical = null;
        try {
            var codec = new HuashengType201AssetCodec();
            expected = codec.validate(fact);
            byte[] aad = (locked.bOrder().getId() + "|" + fact.sequenceNo() + "|" + fact.assetType()).getBytes(StandardCharsets.UTF_8);
            plaintext = envelopeCrypto.decrypt(previous.getEncryptedPayload(), previous.getKeyVersion(), aad);
            canonical = codec.validateStoredPayload(fact, plaintext);
            return MessageDigest.isEqual(expected, canonical);
        } catch (IllegalArgumentException invalidFact) {
            return false;
        } finally {
            if (expected != null) Arrays.fill(expected, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            if (canonical != null) Arrays.fill(canonical, (byte) 0);
        }
    }

    /** 提交后才记录华昇资金业务成功；回滚不输出假完成（生产闭环设计 §8.2、日志规范 §3.2）。 */
    private static void logAfterCommit(String message, Object... arguments) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Huasheng completion log requires transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { log.info(message, arguments); }
        });
    }

    private boolean isHuasheng(FulfillmentOperationDO operation, OperationOutcome outcome) {
        if (operation != null) {
            String step = operation.getAdapterStepCode();
            if (AdapterStepCode.PLACE_ORDER.name().equals(step)
                    || AdapterStepCode.CONFIRM_ORDER.name().equals(step)
                    || AdapterStepCode.TAKE_CARD.name().equals(step)
                    || AdapterStepCode.QUERY_ORDER.name().equals(step)) {
                return true;
            }
            if (operation.getCheckpointSchemaVersion() != null && operation.getCheckpointSchemaVersion() == 1) {
                return true;
            }
        }
        if (outcome != null && outcome.protocolFact() instanceof HuashengStepFact) {
            return true;
        }
        return false;
    }

    private TransitionResult transitionInternal(String operationNo, OperationOutcome outcome, String traceId,
                                               boolean trustedRecovery, RechargeFrequencyExecutionPermit permit, String nextOperationNo) {
        log.info("[供应商订单][lifecycle] transition 入口 operationNo={}, outcomeClass={}, traceId={}",
                operationNo, outcome != null ? outcome.outcomeClass() : null, traceId);
        Objects.requireNonNull(operationNo, "operationNo must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");

        var locked = frequencyLifecycle.lockForResult(operationNo);
        FulfillmentOperationDO operation = locked != null ? locked.operation() : operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .eq(FulfillmentOperationDO::getOperationNo, operationNo));
        if (operation == null) {
            throw new IllegalArgumentException("FulfillmentOperation does not exist: " + operationNo);
        }
        if (isHuasheng(operation, outcome)) {
            throw new IllegalStateException("华昇操作必须使用专用受控 permit 入口推进: operationNo=" + operationNo);
        }
        BOrderDO bOrder = locked != null ? locked.bOrder() : bOrderMapper.selectById(operation.getBOrderId());
        if (bOrder == null) {
            throw new IllegalStateException("BOrder does not exist for operation: " + operationNo);
        }
        FulfillmentRequestDO request = locked != null ? locked.request() : requestMapper.selectById(bOrder.getFulfillmentRequestId());
        if (request == null) {
            throw new IllegalStateException("FulfillmentRequest does not exist: " + bOrder.getFulfillmentRequestId());
        }
        SupplyChainOrderDO order = locked != null ? locked.order() : orderMapper.selectById(bOrder.getOrderId());
        if (order == null) {
            throw new IllegalStateException("SupplyChainOrder does not exist: " + bOrder.getOrderId());
        }

        FulfillmentOperationDO next = nextOperationNo == null ? null : validateContinuation(locked, outcome, permit, nextOperationNo);
        LocalDateTime now = databaseClock.now();
        // 观察以专用原子SQL去重；RR下的定位预读不能用于判断最新观察存在与否。
        persistCallAndObservation(operation, bOrder, outcome, traceId, now);
        boolean terminal = operation.getStatus() != null && (operation.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode()
                || operation.getStatus() == SupplierOrderOperationStatus.FAILED.getCode());
        boolean authoritativeTerminal = outcome.outcomeClass() == OperationOutcomeClass.SUCCEEDED
                || outcome.outcomeClass() == OperationOutcomeClass.REJECTED;
        // 原迁移风险仅接受无发送permit的可信终态；任何旧资格或新出现的同单风险均只能留观察。
        // 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
        boolean migrationRecovery = trustedRecovery && authoritativeTerminal && permit == null
                && RechargeFrequencyLifecycle.isMigrationRisk(locked);
        boolean legacyRiskWithoutTrustedTerminal = locked != null && locked.entry() != null
                && locked.entry().getMigrationAcquiredAt() != null
                && locked.entry().getStatus() == RechargeFrequencyEntryStatus.HOLDING.getCode()
                && !migrationRecovery;
        boolean late = legacyRiskWithoutTrustedTerminal || (locked != null && locked.entry() != null
                && ((!RechargeFrequencyLifecycle.isCurrentHolder(locked) && !migrationRecovery)
                    || (permit != null && (permit.queueId() != locked.queue().getId()
                        || permit.entryId() != locked.entry().getId()
                        || permit.generation() != locked.entry().getOccupancyGeneration()
                        || !permit.operationNo().equals(operationNo)))));
        if (terminal) {
            if (authoritativeTerminal && ((operation.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode())
                    != (outcome.outcomeClass() == OperationOutcomeClass.SUCCEEDED))) {
                openContradictoryCase(operation, bOrder, request, order, now);
            }
            if (!late) { frequencyLifecycle.reconcile(locked); }
            return new TransitionResult(operationNo, bOrder.getBOrderNo(), order.getOrderNo(), operation.getStatus(),
                    bOrder.getFulfillmentStatus(), order.getOrderStatus(), true);
        }
        if (late || (operation.getStatus() != null && operation.getStatus() == SupplierOrderOperationStatus.MANUAL.getCode()
                && (!trustedRecovery || !authoritativeTerminal))) {
            return new TransitionResult(operationNo, bOrder.getBOrderNo(), order.getOrderNo(), operation.getStatus(),
                    bOrder.getFulfillmentStatus(), order.getOrderStatus(), false);
        }

        OperationOutcomeClass outcomeClass = outcome.outcomeClass();

        boolean frequencyHolding = RechargeFrequencyLifecycle.isCurrentHolder(locked) || migrationRecovery;
        switch (outcomeClass) {
            case SUCCEEDED -> handleSucceeded(operation, bOrder, request, order, outcome, traceId, now, frequencyHolding);
            case REJECTED -> {
                if (next == null) { handleRejected(operation, bOrder, request, order, outcome, traceId, now, frequencyHolding); }
                else { handleRejectedAndContinue(locked, next, outcome, traceId, now); }
            }
            case RESULT_UNKNOWN -> handleResultUnknown(operation, bOrder, outcome, now);
            case ACCEPTED -> handleAccepted(operation, bOrder, outcome, now);
            default -> handleManualOrOther(operation, bOrder, request, order, outcome, traceId, now, frequencyHolding);
        }

        frequencyLifecycle.reconcile(locked);
        log.info("[供应商订单][lifecycle] transition 完成 operationNo={}, opStatus={}, bOrderStatus={}, orderStatus={}",
                operation.getOperationNo(), operation.getStatus(), bOrder.getFulfillmentStatus(), order.getOrderStatus());
        boolean isTerminal = operation.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode()
                || operation.getStatus() == SupplierOrderOperationStatus.FAILED.getCode()
                || operation.getStatus() == SupplierOrderOperationStatus.MANUAL.getCode();
        return new TransitionResult(operation.getOperationNo(), bOrder.getBOrderNo(), order.getOrderNo(),
                operation.getStatus(), bOrder.getFulfillmentStatus(), order.getOrderStatus(), isTerminal);
    }

    /**
     * 短事务认领待执行 Operation：仅 {@code PENDING/CLAIMED} CAS 推进为 {@code EXECUTING}。
     *
     * @return true 表示本节点获得外呼资格
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean claimForExecution(String operationNo) {
        return claimForExecution(operationNo, null);
    }

    /** 普通执行入口必须提供可信实际target类型；频控项只能通过不可变permit及发送marker获得资格。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean claimForExecution(String operationNo, String actualTargetType) {
        if (frequencyLifecycle.requiresFrequency(operationNo, actualTargetType)) { return false; }
        Objects.requireNonNull(operationNo, "operationNo must not be null");
        FulfillmentOperationDO operation = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .eq(FulfillmentOperationDO::getOperationNo, operationNo));
        if (operation == null || operation.getStatus() == null) {
            return false;
        }
        if (operation.getStatus() != SupplierOrderOperationStatus.PENDING.getCode()
                && operation.getStatus() != SupplierOrderOperationStatus.CLAIMED.getCode()) {
            log.info("[供应商订单][lifecycle] claim 跳过 operationNo={}, status={}",
                    operationNo, operation.getStatus());
            return false;
        }
        LocalDateTime now = databaseClock.now();
        operation.setStatus(SupplierOrderOperationStatus.EXECUTING.getCode());
        operation.setUpdatedAt(now);
        boolean claimed = operationMapper.updateById(operation) == 1;
        log.info("[供应商订单][lifecycle] claimForExecution operationNo={}, claimed={}", operationNo, claimed);
        return claimed;
    }

    private void handleSucceeded(FulfillmentOperationDO operation, BOrderDO bOrder,
                                 FulfillmentRequestDO request, SupplyChainOrderDO order,
                                 OperationOutcome outcome, String traceId, LocalDateTime now, boolean frequencyHolding) {
        boolean isCardIssuing = AdapterStepCode.SEND_KM.name().equals(operation.getAdapterStepCode());
        boolean emptyCards = outcome.assets() == null || outcome.assets().isEmpty();

        if (isCardIssuing && emptyCards) {
            log.warn("[供应商订单][lifecycle] 权威成功但缺少卡密资产，进入 MANUAL 人工处理 operationNo={}, bOrderNo={}",
                    operation.getOperationNo(), bOrder.getBOrderNo());
            operation.setStatus(SupplierOrderOperationStatus.SUCCEEDED.getCode());
            operation.setResultClass(outcome.outcomeClass().name());
            if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
                operation.setExternalOperationNo(outcome.externalOrderNo());
            }
            operation.setAttemptCount(operation.getAttemptCount() + 1);
            operation.setCompletedAt(now);
            operation.setUpdatedAt(now);
            if (operationMapper.updateById(operation) != 1) {
                throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
            }

            bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.SUCCEEDED.getCode());
            bOrder.setFulfilledQuantity(bOrder.getRequestedQuantity());
            bOrder.setActiveFlag(null);
            if (outcome.reportedSettlementAmount() != null) {
                bOrder.setReportedSupplierSettlementAmount(outcome.reportedSettlementAmount());
                bOrder.setConfirmedSupplierSettlementAmount(outcome.reportedSettlementAmount());
            } else {
                bOrder.setReportedSupplierSettlementAmount(bOrder.getExpectedSupplierSettlementAmount());
                bOrder.setConfirmedSupplierSettlementAmount(bOrder.getExpectedSupplierSettlementAmount());
            }
            bOrder.setSettlementStatus(BOrderSettlementStatus.MATCHED.getCode());
            if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
                bOrder.setExternalOrderNo(outcome.externalOrderNo());
            }
            bOrder.setCompletedAt(now);
            bOrder.setUpdatedAt(now);
            if (updateBOrderState(bOrder, frequencyHolding) != 1) {
                throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
            }

            request.setStatus(FulfillmentRequestStatus.MANUAL.getCode());
            request.setUpdatedAt(now);
            if (requestMapper.updateById(request) != 1) {
                throw new IllegalStateException("FulfillmentRequest CAS update lost optimistic lock: " + request.getRequestNo());
            }

            OrderSummaryFact fact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_CARD,
                    SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT,
                    FulfillmentRequestStatus.MANUAL.getCode(),
                    order.getQuantity(), 0L, "QNM-ASSET-RECOVERY-001");
            OrderSummaryDecision decision = cardOrderSummaryPolicy.decide(fact);
            short fromOrderStatus = order.getOrderStatus();
            order.setOrderStatus(decision.orderStatus());
            order.setAftersaleStatus(decision.aftersaleStatus());
            order.setBusinessStage(decision.businessStage());
            long nextDetailVersion = (order.getDetailVersion() != null ? order.getDetailVersion() : 0L) + 1L;
            order.setDetailVersion(nextDetailVersion);
            order.setUpdatedAt(now);
            if (orderMapper.updateById(order) != 1) {
                throw new IllegalStateException("SupplyChainOrder CAS update lost optimistic lock: " + order.getOrderNo());
            }

            statusLogMapper.insert(OrderStatusLogDO.builder()
                    .orderId(order.getId())
                    .detailVersion(nextDetailVersion)
                    .fromOrderStatus(fromOrderStatus)
                    .toOrderStatus(decision.orderStatus())
                    .fromAftersaleStatus((short) 0)
                    .toAftersaleStatus((short) 0)
                    .businessStage(decision.businessStage())
                    .sourceEventType("FULFILLMENT_MANUAL")
                    .sourceEventNo(bOrder.getBOrderNo())
                    .reasonCode(FulfillmentExceptionReasonCodes.QNM_ASSET_RECOVERY_001)
                    .traceId(traceId)
                    .createdAt(now).build());

            fundReservationService.commitByReference(request.getBuyerId(),
                    FundReferenceType.CARD_ORDER.code(), request.getRequestNo(),
                    order.getOrderNo(), traceId, bOrder.getBOrderNo());
            openRecoverAssetCase(operation, bOrder, request, order, now);
            return;
        }

        // 1. 推进 Operation 状态为 40 成功
        operation.setStatus(SupplierOrderOperationStatus.SUCCEEDED.getCode());
        operation.setResultClass(outcome.outcomeClass().name());
        if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
            operation.setExternalOperationNo(outcome.externalOrderNo());
        }
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setCompletedAt(now);
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
        }

        // 2. 推进 BOrder 状态为 40 成功
        bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.SUCCEEDED.getCode());
        bOrder.setFulfilledQuantity(bOrder.getRequestedQuantity());
        bOrder.setActiveFlag(null); // 终态 activeFlag 置空
        if (outcome.reportedSettlementAmount() != null) {
            bOrder.setReportedSupplierSettlementAmount(outcome.reportedSettlementAmount());
            bOrder.setConfirmedSupplierSettlementAmount(outcome.reportedSettlementAmount());
        } else {
            bOrder.setReportedSupplierSettlementAmount(bOrder.getExpectedSupplierSettlementAmount());
            bOrder.setConfirmedSupplierSettlementAmount(bOrder.getExpectedSupplierSettlementAmount());
        }
        bOrder.setSettlementStatus(BOrderSettlementStatus.MATCHED.getCode());
        if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
            bOrder.setExternalOrderNo(outcome.externalOrderNo());
        }
        bOrder.setCompletedAt(now);
        bOrder.setUpdatedAt(now);
        if (updateBOrderState(bOrder, frequencyHolding) != 1) {
            throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
        }

        // 3. 持久化交付资产至 sc_fulfillment_asset
        persistAssets(bOrder, operation, request, order, outcome, now);

        // 4. 推进 FulfillmentRequest 状态为 40 成功
        request.setFulfilledQuantity(bOrder.getRequestedQuantity());
        request.setStatus(FulfillmentRequestStatus.SUCCEEDED.getCode());
        request.setUpdatedAt(now);
        if (requestMapper.updateById(request) != 1) {
            throw new IllegalStateException("FulfillmentRequest CAS update lost optimistic lock: " + request.getRequestNo());
        }

        // 5. 对齐 CardOrderSummaryPolicy 推进总订单为 30 成功
        OrderSummaryFact fact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT,
                FulfillmentRequestStatus.SUCCEEDED.getCode(),
                order.getQuantity(), order.getQuantity(), null);
        OrderSummaryDecision decision = cardOrderSummaryPolicy.decide(fact);
        short fromOrderStatus = order.getOrderStatus();
        order.setOrderStatus(decision.orderStatus());
        order.setAftersaleStatus(decision.aftersaleStatus());
        order.setBusinessStage(decision.businessStage());
        long nextDetailVersion = (order.getDetailVersion() != null ? order.getDetailVersion() : 0L) + 1L;
        order.setDetailVersion(nextDetailVersion);
        order.setCompletedAt(now);
        order.setUpdatedAt(now);
        if (orderMapper.updateById(order) != 1) {
            throw new IllegalStateException("SupplyChainOrder CAS update lost optimistic lock: " + order.getOrderNo());
        }

        // 6. 记录订单状态变更审计
        statusLogMapper.insert(OrderStatusLogDO.builder()
                .orderId(order.getId())
                .detailVersion(nextDetailVersion)
                .fromOrderStatus(fromOrderStatus)
                .toOrderStatus(decision.orderStatus())
                .fromAftersaleStatus((short) 0)
                .toAftersaleStatus((short) 0)
                .businessStage(decision.businessStage())
                .sourceEventType("FULFILLMENT_SUCCEEDED")
                .sourceEventNo(bOrder.getBOrderNo())
                .reasonCode("SUPPLIER_SUCCEEDED")
                .traceId(traceId)
                .createdAt(now).build());

        // 7. 资金确认闭环：调用 fundReservationService.commit 实扣资金
        fundReservationService.commitByReference(request.getBuyerId(),
                FundReferenceType.CARD_ORDER.code(), request.getRequestNo(),
                order.getOrderNo(), traceId, bOrder.getBOrderNo());
    }

    /**
     * 锁后当前读校验原占用与显式下一操作；同BO必须下一sequence，跨BO必须下一attempt的首操作。
     * 旧MANUAL的历史completedAt仅在可信拒绝恢复时兼容，其他终态/查单/未知发送风险均拒绝。
     * Request活动指针历史NULL可兼容，非空必须指向原当前尝试；从不靠普通快照猜测合法next。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#5-结果窗口和资金。
     */
    private FulfillmentOperationDO validateContinuation(RechargeFrequencyLifecycle.LockedExecution locked,
                                                        OperationOutcome outcome, RechargeFrequencyExecutionPermit permit,
                                                        String nextOperationNo) {
        if (locked == null || !RechargeFrequencyLifecycle.isCurrentHolder(locked)
                || outcome.outcomeClass() != OperationOutcomeClass.REJECTED
                || permit.queueId() != locked.queue().getId() || permit.entryId() != locked.entry().getId()
                || permit.generation() != locked.entry().getOccupancyGeneration()
                || !permit.operationNo().equals(locked.operation().getOperationNo())) { throw invalidContinuation(); }
        var old = locked.operation(); var oldBOrder = locked.bOrder();
        if (!isRechargeOperation(old) || (old.getStatus() != SupplierOrderOperationStatus.EXECUTING.getCode()
                && old.getStatus() != SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode()
                && old.getStatus() != SupplierOrderOperationStatus.MANUAL.getCode())
                || (locked.request().getStatus() != FulfillmentRequestStatus.FULFILLING.getCode()
                && locked.request().getStatus() != FulfillmentRequestStatus.MANUAL.getCode())
                || (locked.order().getOrderStatus() != SupplyChainOrderStatus.PROCESSING.getCode()
                && locked.order().getOrderStatus() != SupplyChainOrderStatus.MANUAL.getCode())
                || (locked.request().getActiveBOrderId() != null && !locked.request().getActiveBOrderId().equals(oldBOrder.getId()))
                || !Boolean.TRUE.equals(oldBOrder.getActiveFlag())
                || (oldBOrder.getCompletedAt() != null && !(old.getStatus() == SupplierOrderOperationStatus.MANUAL.getCode()
                && oldBOrder.getFulfillmentStatus() == BOrderFulfillmentStatus.MANUAL.getCode()))
                || !isContinuableBOrder(oldBOrder)) { throw invalidContinuation(); }
        var next = locked.relatedOperations().stream().filter(op -> nextOperationNo.equals(op.getOperationNo())).findFirst().orElseThrow(SupplierOrderLifecycle::invalidContinuation);
        var nextBOrder = locked.relatedBOrders().stream().filter(bo -> bo.getId().equals(next.getBOrderId())).findFirst().orElseThrow(SupplierOrderLifecycle::invalidContinuation);
        if (!isUnsentRecharge(next) || !nextBOrder.getFulfillmentRequestId().equals(locked.request().getId())
                || !nextBOrder.getOrderId().equals(locked.order().getId()) || nextBOrder.getFulfilledQuantity() != 0L) { throw invalidContinuation(); }
        boolean sameBOrder = nextBOrder.getId().equals(oldBOrder.getId());
        if (sameBOrder ? next.getSequenceNo() != Math.incrementExact(old.getSequenceNo())
                : nextBOrder.getAttemptNo() != Math.incrementExact(oldBOrder.getAttemptNo()) || next.getSequenceNo() != 1
                || Boolean.TRUE.equals(nextBOrder.getActiveFlag()) || nextBOrder.getCompletedAt() != null
                || (nextBOrder.getFulfillmentStatus() != BOrderFulfillmentStatus.WAITING.getCode()
                && nextBOrder.getFulfillmentStatus() != BOrderFulfillmentStatus.PENDING.getCode())
                || nextBOrder.getExternalOrderNo() != null) { throw invalidContinuation(); }
        for (var other : locked.relatedOperations()) {
            if (other.getId().equals(old.getId()) || other.getId().equals(next.getId()) || !isRechargeOperation(other)) { continue; }
            var bo = locked.relatedBOrders().stream().filter(row -> row.getId().equals(other.getBOrderId())).findFirst().orElseThrow(SupplierOrderLifecycle::invalidContinuation);
            boolean earlier = bo.getAttemptNo() < oldBOrder.getAttemptNo()
                    || (bo.getId().equals(oldBOrder.getId()) && other.getSequenceNo() < old.getSequenceNo());
            boolean rejected = other.getStatus() == SupplierOrderOperationStatus.FAILED.getCode()
                    && OperationOutcomeClass.REJECTED.name().equals(other.getResultClass());
            if (!rejected && (earlier || !isUnsentRecharge(other))) { throw invalidContinuation(); }
        }
        return next;
    }

    private static boolean isContinuableBOrder(BOrderDO bOrder) {
        return bOrder.getFulfilledQuantity() == 0L && (bOrder.getFulfillmentStatus() == BOrderFulfillmentStatus.WAITING.getCode()
                || bOrder.getFulfillmentStatus() == BOrderFulfillmentStatus.PENDING.getCode()
                || bOrder.getFulfillmentStatus() == BOrderFulfillmentStatus.PROCESSING.getCode()
                || bOrder.getFulfillmentStatus() == BOrderFulfillmentStatus.MANUAL.getCode());
    }
    private static boolean isRechargeOperation(FulfillmentOperationDO operation) {
        return FulfillmentOperationKind.FULFILL.name().equals(operation.getOperationKind())
                && (AdapterStepCode.SEND.name().equals(operation.getAdapterStepCode()) || AdapterStepCode.SEND_KM.name().equals(operation.getAdapterStepCode()));
    }
    private static boolean isUnsentRecharge(FulfillmentOperationDO operation) {
        return isRechargeOperation(operation) && (operation.getStatus() == SupplierOrderOperationStatus.PENDING.getCode()
                || operation.getStatus() == SupplierOrderOperationStatus.CLAIMED.getCode())
                && operation.getAttemptCount() == 0 && operation.getResultClass() == null
                && operation.getExternalOperationNo() == null && operation.getCompletedAt() == null;
    }
    private static IllegalStateException invalidContinuation() { return new IllegalStateException("可信续履约资格或下一操作不合法"); }

    private static final String CONTINUATION_EVENT = "FULFILLMENT_CONTINUED";
    private static final String CONTINUATION_REASON = "TRUSTED_REJECTION_CONTINUE";

    /**
     * 旧操作可靠拒绝而整单仍有显式合法候选：仅结束旧操作/尝试，恢复请求汇总并原子切换活动引用。
     * 资金继续预占；冻结队列的代次、SPU、序号和时间窗口不变，下一发送仍必须独立claim/marker。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#5-结果窗口和资金。
     */
    private void handleRejectedAndContinue(RechargeFrequencyLifecycle.LockedExecution locked, FulfillmentOperationDO next,
                                           OperationOutcome outcome, String traceId, LocalDateTime now) {
        var operation = locked.operation(); var oldBOrder = locked.bOrder();
        operation.setStatus(SupplierOrderOperationStatus.FAILED.getCode()); operation.setResultClass(outcome.outcomeClass().name());
        operation.setAttemptCount(Math.incrementExact(operation.getAttemptCount())); operation.setCompletedAt(now); operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) { throw new IllegalStateException("续履约旧操作版本冲突"); }
        var nextBOrder = locked.relatedBOrders().stream().filter(bo -> bo.getId().equals(next.getBOrderId())).findFirst().orElseThrow(SupplierOrderLifecycle::invalidContinuation);
        if (!oldBOrder.getId().equals(nextBOrder.getId())) {
            // MP默认NOT_NULL不会清空active_flag，显式CAS清空，先结束原活动尝试再激活新尝试。
            if (bOrderMapper.update(null, new LambdaUpdateWrapper<BOrderDO>().eq(BOrderDO::getId, oldBOrder.getId())
                    .eq(BOrderDO::getVersion, oldBOrder.getVersion()).set(BOrderDO::getActiveFlag, null)
                    .set(BOrderDO::getFulfillmentStatus, BOrderFulfillmentStatus.FAILED.getCode())
                    .set(BOrderDO::getCompletedAt, now).set(BOrderDO::getUpdatedAt, now)
                    .set(BOrderDO::getVersion, Math.incrementExact(oldBOrder.getVersion()))) != 1) { throw new IllegalStateException("续履约旧尝试版本冲突"); }
            oldBOrder.setActiveFlag(null); oldBOrder.setFulfillmentStatus(BOrderFulfillmentStatus.FAILED.getCode());
            oldBOrder.setCompletedAt(now); oldBOrder.setVersion(Math.incrementExact(oldBOrder.getVersion()));
        }
        nextBOrder.setActiveFlag(true); nextBOrder.setFulfillmentStatus(BOrderFulfillmentStatus.PROCESSING.getCode());
        nextBOrder.setCompletedAt(null); nextBOrder.setUpdatedAt(now);
        if (updateBOrderState(nextBOrder, true) != 1) { throw new IllegalStateException("续履约活动尝试版本冲突"); }
        var request = locked.request(); request.setStatus(FulfillmentRequestStatus.FULFILLING.getCode());
        request.setActiveBOrderId(nextBOrder.getId()); request.setUpdatedAt(now);
        if (requestMapper.updateById(request) != 1) { throw new IllegalStateException("续履约请求版本冲突"); }
        var order = locked.order(); short from = order.getOrderStatus();
        var decision = cardOrderSummaryPolicy.decide(new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT, request.getStatus(), order.getQuantity(), 0L, null));
        order.setOrderStatus(decision.orderStatus()); order.setAftersaleStatus(decision.aftersaleStatus()); order.setBusinessStage(decision.businessStage());
        order.setDetailVersion(Math.incrementExact(order.getDetailVersion())); order.setUpdatedAt(now);
        if (orderMapper.updateById(order) != 1) { throw new IllegalStateException("续履约总单版本冲突"); }
        statusLogMapper.insert(OrderStatusLogDO.builder().orderId(order.getId()).detailVersion(order.getDetailVersion())
                .fromOrderStatus(from).toOrderStatus(decision.orderStatus()).fromAftersaleStatus((short)0).toAftersaleStatus(decision.aftersaleStatus())
                .businessStage(decision.businessStage()).sourceEventType(CONTINUATION_EVENT).sourceEventNo(operation.getOperationNo())
                .reasonCode(CONTINUATION_REASON).traceId(traceId).createdAt(now).build());
        frequencyLifecycle.bindContinuingOperation(locked, next);
    }

    /**
     * 频控已锁定完整BOrder；显式写可空状态字段，保留MP的版本CAS及回写新版本。
     * MANUAL/可信恢复可清历史completedAt，真实终态可清activeFlag，不能依赖默认NOT_NULL。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#5-结果窗口和资金。
     */
    private int updateBOrderState(BOrderDO bOrder, boolean explicitNullableState) {
        if (!explicitNullableState) { return bOrderMapper.updateById(bOrder); }
        return bOrderMapper.update(bOrder, new LambdaUpdateWrapper<BOrderDO>().eq(BOrderDO::getId, bOrder.getId())
                .set(BOrderDO::getActiveFlag, bOrder.getActiveFlag()).set(BOrderDO::getCompletedAt, bOrder.getCompletedAt()));
    }

    private void handleRejected(FulfillmentOperationDO operation, BOrderDO bOrder,
                                FulfillmentRequestDO request, SupplyChainOrderDO order,
                                OperationOutcome outcome, String traceId, LocalDateTime now, boolean frequencyHolding) {
        // 1. 推进 Operation 状态为 50 失败
        operation.setStatus(SupplierOrderOperationStatus.FAILED.getCode());
        operation.setResultClass(outcome.outcomeClass().name());
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setCompletedAt(now);
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
        }

        // 2. 推进 BOrder 状态为 50 失败
        bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.FAILED.getCode());
        bOrder.setFulfilledQuantity(0L);
        bOrder.setActiveFlag(null); // 终态 activeFlag 置空
        bOrder.setCompletedAt(now);
        bOrder.setUpdatedAt(now);
        if (updateBOrderState(bOrder, frequencyHolding) != 1) {
            throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
        }

        // 3. M1 切片单候选耗尽：推进 FulfillmentRequest 为 50 失败
        request.setStatus(FulfillmentRequestStatus.FAILED.getCode());
        request.setUpdatedAt(now);
        if (requestMapper.updateById(request) != 1) {
            throw new IllegalStateException("FulfillmentRequest CAS update lost optimistic lock: " + request.getRequestNo());
        }

        // 4. 对齐 CardOrderSummaryPolicy 推进总订单为 40 失败
        OrderSummaryFact fact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT,
                FulfillmentRequestStatus.FAILED.getCode(),
                order.getQuantity(), 0L, outcome.externalErrorCode());
        OrderSummaryDecision decision = cardOrderSummaryPolicy.decide(fact);
        short fromOrderStatus = order.getOrderStatus();
        order.setOrderStatus(decision.orderStatus());
        order.setAftersaleStatus(decision.aftersaleStatus());
        order.setBusinessStage(decision.businessStage());
        long nextDetailVersion = (order.getDetailVersion() != null ? order.getDetailVersion() : 0L) + 1L;
        order.setDetailVersion(nextDetailVersion);
        order.setCompletedAt(now);
        order.setUpdatedAt(now);
        if (orderMapper.updateById(order) != 1) {
            throw new IllegalStateException("SupplyChainOrder CAS update lost optimistic lock: " + order.getOrderNo());
        }

        // 5. 记录订单状态变更审计
        statusLogMapper.insert(OrderStatusLogDO.builder()
                .orderId(order.getId())
                .detailVersion(nextDetailVersion)
                .fromOrderStatus(fromOrderStatus)
                .toOrderStatus(decision.orderStatus())
                .fromAftersaleStatus((short) 0)
                .toAftersaleStatus((short) 0)
                .businessStage(decision.businessStage())
                .sourceEventType("FULFILLMENT_FAILED")
                .sourceEventNo(bOrder.getBOrderNo())
                .reasonCode(outcome.externalErrorCode() != null ? outcome.externalErrorCode() : "SUPPLIER_REJECTED")
                .traceId(traceId)
                .createdAt(now).build());

        // 6. 资金释放闭环：全败释放预冻结资金
        fundReservationService.releaseByReference(request.getBuyerId(),
                FundReferenceType.CARD_ORDER.code(), request.getRequestNo(),
                order.getOrderNo(), traceId, bOrder.getBOrderNo());
    }

    private void handleResultUnknown(FulfillmentOperationDO operation, BOrderDO bOrder,
                                     OperationOutcome outcome, LocalDateTime now) {
        operation.setStatus(SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode());
        operation.setResultClass(outcome.outcomeClass().name());
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setNextRecoverAt(now.plusSeconds(30));
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
        }

        bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.PROCESSING.getCode());
        bOrder.setUpdatedAt(now);
        if (bOrderMapper.updateById(bOrder) != 1) {
            throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
        }
    }

    private void handleAccepted(FulfillmentOperationDO operation, BOrderDO bOrder,
                                OperationOutcome outcome, LocalDateTime now) {
        operation.setStatus(SupplierOrderOperationStatus.EXECUTING.getCode());
        operation.setResultClass(outcome.outcomeClass().name());
        if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
            operation.setExternalOperationNo(outcome.externalOrderNo());
        }
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
        }

        bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.PENDING.getCode());
        if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
            bOrder.setExternalOrderNo(outcome.externalOrderNo());
        }
        bOrder.setUpdatedAt(now);
        if (bOrderMapper.updateById(bOrder) != 1) {
            throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
        }
    }

    private void handleManualOrOther(FulfillmentOperationDO operation, BOrderDO bOrder,
                                     FulfillmentRequestDO request, SupplyChainOrderDO order,
                                     OperationOutcome outcome, String traceId, LocalDateTime now, boolean frequencyHolding) {
        operation.setStatus(SupplierOrderOperationStatus.MANUAL.getCode());
        operation.setResultClass(outcome.outcomeClass().name());
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setCompletedAt(now);
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
        }

        bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.MANUAL.getCode());
        // 频控MANUAL仍是未核实的活动尝试，不能靠MP忽略NULL偶然保留标记。
        bOrder.setActiveFlag(frequencyHolding ? true : null);
        bOrder.setCompletedAt(frequencyHolding ? null : now);
        bOrder.setUpdatedAt(now);
        if (updateBOrderState(bOrder, frequencyHolding) != 1) {
            throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
        }

        request.setStatus(FulfillmentRequestStatus.MANUAL.getCode());
        request.setUpdatedAt(now);
        if (requestMapper.updateById(request) != 1) {
            throw new IllegalStateException("FulfillmentRequest CAS update lost optimistic lock: " + request.getRequestNo());
        }

        OrderSummaryFact fact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT,
                FulfillmentRequestStatus.MANUAL.getCode(),
                order.getQuantity(), 0L, outcome.externalErrorCode());
        OrderSummaryDecision decision = cardOrderSummaryPolicy.decide(fact);
        short fromOrderStatus = order.getOrderStatus();
        order.setOrderStatus(decision.orderStatus());
        order.setAftersaleStatus(decision.aftersaleStatus());
        order.setBusinessStage(decision.businessStage());
        long nextDetailVersion = (order.getDetailVersion() != null ? order.getDetailVersion() : 0L) + 1L;
        order.setDetailVersion(nextDetailVersion);
        order.setUpdatedAt(now);
        if (orderMapper.updateById(order) != 1) {
            throw new IllegalStateException("SupplyChainOrder CAS update lost optimistic lock: " + order.getOrderNo());
        }

        statusLogMapper.insert(OrderStatusLogDO.builder()
                .orderId(order.getId())
                .detailVersion(nextDetailVersion)
                .fromOrderStatus(fromOrderStatus)
                .toOrderStatus(decision.orderStatus())
                .fromAftersaleStatus((short) 0)
                .toAftersaleStatus((short) 0)
                .businessStage(decision.businessStage())
                .sourceEventType("FULFILLMENT_MANUAL")
                .sourceEventNo(bOrder.getBOrderNo())
                .reasonCode("MANUAL_REQUIRED")
                .traceId(traceId)
                .createdAt(now).build());
    }

    private void persistAssets(BOrderDO bOrder, FulfillmentOperationDO operation,
                               FulfillmentRequestDO request, SupplyChainOrderDO order,
                               OperationOutcome outcome, LocalDateTime now) {
        if (outcome.assets() == null || outcome.assets().isEmpty()) {
            return;
        }
        for (OperationAssetFact assetFact : outcome.assets()) {
            if (assetFact.hasProtectedPayload()) {
                persistHuashengAsset(bOrder, operation, request, assetFact, now);
                continue;
            }
            String assetNo = businessNoGenerator.next(BusinessNoPrefixes.FA);
            Map<String, String> payloadMap = new LinkedHashMap<>();
            payloadMap.put("assetType", assetFact.assetType());
            if (assetFact.cardNo() != null) {
                payloadMap.put("cardCode", assetFact.cardNo());
            }
            if (assetFact.cardSecret() != null) {
                payloadMap.put("cardSecret", assetFact.cardSecret());
            }
            byte[] plaintext;
            try {
                plaintext = objectMapper.writeValueAsBytes(payloadMap);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize asset payload", e);
            }

            byte[] associatedData = (bOrder.getId() + "|" + assetFact.sequenceNo() + "|" + assetFact.assetType())
                    .getBytes(StandardCharsets.UTF_8);
            EnvelopeCryptoPort.EncryptedPayload encrypted = envelopeCrypto.encrypt(plaintext, associatedData);

            String fingerprintSource = bOrder.getSupplierId() + ":"
                    + (assetFact.cardNo() != null ? assetFact.cardNo() : "") + ":"
                    + (assetFact.cardSecret() != null ? assetFact.cardSecret() : "");
            byte[] fingerprint = hmacFingerprint.fingerprint("FULFILLMENT_ASSET",
                    fingerprintSource.getBytes(StandardCharsets.UTF_8));

            String maskedValue;
            if (assetFact.cardNo() != null && !assetFact.cardNo().isBlank()) {
                maskedValue = QnmMaskingUtils.maskCardNo(assetFact.cardNo());
            } else if (assetFact.cardSecret() != null && !assetFact.cardSecret().isBlank()) {
                maskedValue = QnmMaskingUtils.maskSecret(assetFact.cardSecret());
            } else {
                maskedValue = "";
            }

            FulfillmentAssetDO assetDO = FulfillmentAssetDO.builder()
                    .assetNo(assetNo)
                    .fulfillmentRequestId(request.getId())
                    .bOrderId(bOrder.getId())
                    .operationId(operation.getId())
                    .supplierId(bOrder.getSupplierId())
                    .buyerId(request.getBuyerId())
                    // 标准资产不再从共享总单复制购买人身份（052 已删除标准专用 buyer_user_id 列）；
                    // 审计主体为真实 ADMIN_USER 操作人与实际 project/order。
                    // 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#5-052增量历史清理与安全覆盖。
                    .sequenceNo(assetFact.sequenceNo())
                    .unitQuantity(1L)
                    .assetType(assetFact.assetType())
                    .externalAssetId(assetFact.externalAssetId())
                    .encryptedPayload(encrypted.ciphertext())
                    .maskedValue(maskedValue)
                    .fingerprint(fingerprint)
                    .keyVersion(encrypted.keyVersion())
                    .validFrom(assetFact.validFrom())
                    .validUntil(assetFact.validUntil())
                    .lifecycleStatus(FulfillmentAssetStatus.AVAILABLE.getCode())
                    .version(0L)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            assetMapper.insert(assetDO);
            log.info("[供应商订单][lifecycle] 资产持久化成功 assetNo={}, bOrderNo={}, sequenceNo={}",
                    assetNo, bOrder.getBOrderNo(), assetFact.sequenceNo());
        }
    }

    /**
     * 华昇封闭payload持久基础；调用者已持有BOrder聚合锁，本方法不自行授予资金或状态推进资格。
     * 同顺序号须完整事实相等才能幂等；跨单凭证冲突由唯一键抛出，外层整笔事务回滚，不吞异常。
     * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#71-task2桥接与持久基础边界。
     */
    private void persistHuashengAsset(BOrderDO bOrder, FulfillmentOperationDO operation,
                                     FulfillmentRequestDO request, OperationAssetFact fact, LocalDateTime now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("华昇资产持久化必须处于聚合事务内");
        }
        HuashengType201AssetCodec codec = new HuashengType201AssetCodec();
        byte[] plaintext = codec.validate(fact);
        byte[] fingerprintInput = null;
        byte[] previousPlaintext = null;
        byte[] previousCanonical = null;
        try {
            fingerprintInput = codec.fingerprintInput(bOrder.getSupplierId(), fact);
            byte[] fingerprint = hmacFingerprint.fingerprint(HuashengType201AssetCodec.FINGERPRINT_PURPOSE, fingerprintInput);
            byte[] aad = (bOrder.getId() + "|" + fact.sequenceNo() + "|" + fact.assetType()).getBytes(StandardCharsets.UTF_8);
            FulfillmentAssetDO previous = assetMapper.selectOne(new LambdaQueryWrapper<FulfillmentAssetDO>()
                    .eq(FulfillmentAssetDO::getBOrderId, bOrder.getId())
                    .eq(FulfillmentAssetDO::getSequenceNo, fact.sequenceNo()));
            if (previous != null) {
                boolean sameIdentity = Objects.equals(previous.getOperationId(), operation.getId())
                        && Objects.equals(previous.getFulfillmentRequestId(), request.getId())
                        && Objects.equals(previous.getSupplierId(), bOrder.getSupplierId())
                        && Objects.equals(previous.getBuyerId(), request.getBuyerId())
                        && Objects.equals(previous.getAssetType(), fact.assetType())
                        && Objects.equals(previous.getUnitQuantity(), 1L)
                        && previous.getExternalAssetId() == null && previous.getExternalProductId() == null
                        && previous.getValidFrom() == null && previous.getValidUntil() == null
                        && Arrays.equals(previous.getFingerprint(), fingerprint);
                if (!sameIdentity) throw new IllegalStateException("华昇type201资产事实冲突");
                previousPlaintext = envelopeCrypto.decrypt(previous.getEncryptedPayload(), previous.getKeyVersion(), aad);
                try {
                    previousCanonical = codec.validateStoredPayload(fact, previousPlaintext);
                } catch (IllegalArgumentException invalidStoredFact) {
                    // codec异常为固定安全拒绝；对外统一为事实冲突，不附带敏感JSON解析原因。
                    throw new IllegalStateException("华昇type201资产事实冲突");
                }
                if (!MessageDigest.isEqual(plaintext, previousCanonical)) {
                    throw new IllegalStateException("华昇type201资产事实冲突");
                }
                return;
            }
            EnvelopeCryptoPort.EncryptedPayload encrypted = envelopeCrypto.encrypt(plaintext, aad);
            String assetNo = businessNoGenerator.next(BusinessNoPrefixes.FA);
            assetMapper.insert(FulfillmentAssetDO.builder()
                    .assetNo(assetNo).fulfillmentRequestId(request.getId()).bOrderId(bOrder.getId())
                    .operationId(operation.getId()).supplierId(bOrder.getSupplierId()).buyerId(request.getBuyerId())
                    .sequenceNo(fact.sequenceNo()).unitQuantity(1L).assetType(fact.assetType())
                    .encryptedPayload(encrypted.ciphertext()).maskedValue("****").fingerprint(fingerprint)
                    .keyVersion(encrypted.keyVersion()).lifecycleStatus(FulfillmentAssetStatus.AVAILABLE.getCode())
                    .version(0L).createdAt(now).updatedAt(now).build());
            String bOrderNo = bOrder.getBOrderNo();
            int sequenceNo = fact.sequenceNo();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("[华昇资产持久化][事务提交确认] assetNo={}, bOrderNo={}, sequenceNo={}", assetNo, bOrderNo, sequenceNo);
                }
            });
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            if (fingerprintInput != null) Arrays.fill(fingerprintInput, (byte) 0);
            if (previousPlaintext != null) Arrays.fill(previousPlaintext, (byte) 0);
            if (previousCanonical != null) Arrays.fill(previousCanonical, (byte) 0);
        }
    }

    private void persistCallAndObservation(FulfillmentOperationDO operation, BOrderDO bOrder,
                                           OperationOutcome outcome, String traceId, LocalDateTime now) {
        persistCallLog(operation, bOrder, outcome.callDigest(), now);
        persistObservation(operation, bOrder, outcome, traceId, now);
    }

    private void persistCallLog(FulfillmentOperationDO operation, BOrderDO bOrder,
                                CallDigestFact digest, LocalDateTime now) {
        if (digest == null) {
            return;
        }
        int attemptNo = (operation.getAttemptCount() == null ? 0 : operation.getAttemptCount()) + 1;
        long duration = digest.durationMs();
        LocalDateTime startedAt = now.minusNanos(Math.max(0, duration) * 1_000_000L);
        byte[] requestDigest = digest.requestDigest() != null ? digest.requestDigest() : EMPTY_DIGEST;
        callLogMapper.insert(FulfillmentCallLogDO.builder()
                .callNo(businessNoGenerator.next(BusinessNoPrefixes.CL))
                .operationId(operation.getId())
                .bOrderId(bOrder.getId())
                .attemptNo(attemptNo)
                .direction(FulfillmentCallDirection.OUTBOUND.name())
                .connectorVersionId(bOrder.getConnectorVersionId() != null ? bOrder.getConnectorVersionId() : 0L)
                .endpointCode(digest.endpointCode() != null ? digest.endpointCode() : "")
                .startedAt(startedAt)
                .durationMs(duration)
                .resultClass(digest.resultClass() != null ? digest.resultClass() : "")
                .httpStatus(digest.httpStatus())
                .requestDigest(requestDigest)
                .responseDigest(digest.responseDigest())
                .redactionVersion(CALL_LOG_REDACTION_VERSION)
                .traceId(digest.traceId() != null ? digest.traceId() : "")
                .createdAt(now)
                .build());
    }

    private void persistObservation(FulfillmentOperationDO operation, BOrderDO bOrder,
                                    OperationOutcome outcome, String traceId, LocalDateTime now) {
        RawObservationFact observation = outcome.rawObservation();
        if (observation == null) {
            return;
        }
        byte[] payloadDigest = observation.payloadDigest() != null ? observation.payloadDigest() : EMPTY_DIGEST;
        String sourceType = observation.sourceType() != null ? observation.sourceType() : "CREATE_RESPONSE";
        String observationKey = operation.getOperationNo() + ":" + sourceType + ":" + HexFormat.of().formatHex(payloadDigest);
        if (observationKey.length() > 128) {
            observationKey = observationKey.substring(0, 128);
        }
        String externalSubStatus = observation.externalSubStatus();
        if (externalSubStatus != null) {
            externalSubStatus = QnmMaskingUtils.maskResponseBody(externalSubStatus);
        }
        observationMapper.insertIdempotent(RawStatusObservationDO.builder()
                .observationNo(businessNoGenerator.next(BusinessNoPrefixes.OBS))
                .observationKey(observationKey)
                .sourceType(sourceType)
                .operationId(operation.getId())
                .bOrderId(bOrder.getId())
                .connectorVersionId(bOrder.getConnectorVersionId() != null ? bOrder.getConnectorVersionId() : 0L)
                .externalOrderNo(observation.externalOrderNo())
                .externalStatus(observation.externalStatus() != null ? observation.externalStatus() : "UNKNOWN")
                .externalSubStatus(externalSubStatus)
                .mappingDefinitionId(0L)
                .mappedResultClass(outcome.outcomeClass().name())
                .payloadDigest(payloadDigest)
                .observedAt(observation.observedAt() != null ? observation.observedAt() : now)
                .receivedAt(now)
                .traceId(traceId != null ? traceId : "")
                .build());
    }

    /** 矛盾供应商终态仅开异常审计，不覆写既有订单事实或重复资金动作。设计见限频设计 #5-结果窗口和资金。 */
    private void openContradictoryCase(FulfillmentOperationDO operation, BOrderDO bOrder,
                                       FulfillmentRequestDO request, SupplyChainOrderDO order, LocalDateTime now) {
        if (exceptionCaseMapper.selectActiveByOperationId(operation.getId()) != null) { return; }
        String evidenceRef = "TERMINAL_CONTRADICTION:" + operation.getOperationNo();
        exceptionCaseMapper.insert(FulfillmentExceptionCaseDO.builder()
                .caseNo(businessNoGenerator.next(BusinessNoPrefixes.CASE)).orderId(order.getId())
                .fulfillmentRequestId(request.getId()).bOrderId(bOrder.getId()).operationId(operation.getId())
                .reasonCode(FulfillmentExceptionReasonCodes.SUPPLIER_TERMINAL_CONTRADICTION)
                .status(FulfillmentExceptionCaseStatus.OPEN.getCode()).evidenceRef(evidenceRef)
                .evidenceDigest(hmacFingerprint.fingerprint("EXCEPTION_CASE", evidenceRef.getBytes(StandardCharsets.UTF_8)))
                .activeCaseFlag(1).createdAt(now).updatedAt(now).version(0L).build());
    }

    private void openRecoverAssetCase(FulfillmentOperationDO operation, BOrderDO bOrder,
                                      FulfillmentRequestDO request, SupplyChainOrderDO order,
                                      LocalDateTime now) {
        if (exceptionCaseMapper.selectActiveByOperationId(operation.getId()) != null) {
            return;
        }
        String evidenceRef = "RECOVER_ASSET:" + bOrder.getId();
        byte[] evidenceDigest = hmacFingerprint.fingerprint("EXCEPTION_CASE",
                evidenceRef.getBytes(StandardCharsets.UTF_8));
        exceptionCaseMapper.insert(FulfillmentExceptionCaseDO.builder()
                .caseNo(businessNoGenerator.next(BusinessNoPrefixes.CASE))
                .orderId(order.getId())
                .fulfillmentRequestId(request.getId())
                .bOrderId(bOrder.getId())
                .operationId(operation.getId())
                .reasonCode(FulfillmentExceptionReasonCodes.QNM_ASSET_MISSING_AFTER_SUCCESS)
                .status(FulfillmentExceptionCaseStatus.OPEN.getCode())
                .evidenceRef(evidenceRef)
                .evidenceDigest(evidenceDigest)
                .activeCaseFlag(1)
                .createdAt(now)
                .updatedAt(now)
                .version(0L)
                .build());
        log.info("[供应商订单][lifecycle] 已打开空卡密异常 Case operationNo={}, bOrderNo={}",
                operation.getOperationNo(), bOrder.getBOrderNo());
    }

    public record TransitionResult(String operationNo, String bOrderNo, String orderNo,
                                    short operationStatus, short bOrderStatus, short orderStatus,
                                    boolean isTerminal) {
    }
}
