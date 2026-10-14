package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpointCodec;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengErrorClassification;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengExecutionPermit;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengFlowPolicy;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengFlowTrigger;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengOperationExecutor;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengStepFact;
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
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.FrozenCredentialSetResolver;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import static com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentCaseActionCodes.*;

/**
 * 履约异常 Case 核实执行服务（Timer 驱动）。
 *
 * <p>职责（设计 §6.2）：取得华昇进程互斥锁后，短事务锁定聚合／Case 并生成新代次，
 * 事务外执行一次原单 QUERY（复用现有 token 管理器与执行互斥锁，不创建第二个授权客户端），
 * 最后短事务保存结论、受保护证据与候选计数。查询已开始标记存在时不得再次外呼；
 * 租约到期仍无完整结果则由收尾 Timer 终结为 UNKNOWN 并清除活动意图。
 *
 * <p>不变量：每个 action 恰一次 QUERY 认领，仍允许门面已有的一次 61 重发；
 * 不执行 PLACE／CONFIRM／TAKE／取消；迟到返回只能留在旧 action 诊断中，不能重新取得核实资格。
 *
 * <p>设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#62-核实不是恢复购买。
 */
@Slf4j
@Service
public class FulfillmentCaseVerifyExecutionService {

    public static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    /** 核实结果可用于失败确认的窗口：5 分钟（设计 §6.3）。 */
    static final int VERIFY_FRESHNESS_MINUTES = 5;

    private final FulfillmentExceptionCaseMapper exceptionCaseMapper;
    private final FulfillmentCaseActionMapper caseActionMapper;
    private final FulfillmentOperationMapper operationMapper;
    private final BOrderMapper bOrderMapper;
    private final FulfillmentRequestMapper requestMapper;
    private final SupplyChainOrderMapper orderMapper;
    private final SupplierMapper supplierMapper;
    private final SupplierOfferVersionMapper offerVersionMapper;
    private final PublishedConnectorConfigurationResolver connectorResolver;
    private final FrozenCredentialSetResolver frozenCredentialSetResolver;
    private final FulfillmentOperationTicketFactory ticketFactory;
    private final HuashengCheckpointCodec checkpointCodec;
    private final EnvelopeCryptoPort envelopeCrypto;
    private final PersistentTimer timer;
    private final SupplyChainDatabaseClock databaseClock;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<HuashengOperationExecutor> executorProvider;
    private final ObjectProvider<ReentrantLock> lockProvider;

    /** 经 Spring 代理进入短事务，禁止同类调用绕过事务拦截。设计 §6.2。 */
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<FulfillmentCaseVerifyExecutionService> transactionalSelf;
    @org.springframework.beans.factory.annotation.Autowired
    private com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyExecutionMapper executionMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private SupplierOrderLifecycle supplierOrderLifecycle;

    public FulfillmentCaseVerifyExecutionService(
            FulfillmentExceptionCaseMapper exceptionCaseMapper,
            FulfillmentCaseActionMapper caseActionMapper,
            FulfillmentOperationMapper operationMapper,
            BOrderMapper bOrderMapper,
            FulfillmentRequestMapper requestMapper,
            SupplyChainOrderMapper orderMapper,
            SupplierMapper supplierMapper,
            SupplierOfferVersionMapper offerVersionMapper,
            PublishedConnectorConfigurationResolver connectorResolver,
            FrozenCredentialSetResolver frozenCredentialSetResolver,
            FulfillmentOperationTicketFactory ticketFactory,
            HuashengCheckpointCodec checkpointCodec,
            EnvelopeCryptoPort envelopeCrypto,
            PersistentTimer timer,
            SupplyChainDatabaseClock databaseClock,
            ObjectMapper objectMapper,
            ObjectProvider<HuashengOperationExecutor> executorProvider,
            @Qualifier("huashengExecutionLock") ObjectProvider<ReentrantLock> lockProvider) {
        this.exceptionCaseMapper = Objects.requireNonNull(exceptionCaseMapper);
        this.caseActionMapper = Objects.requireNonNull(caseActionMapper);
        this.operationMapper = Objects.requireNonNull(operationMapper);
        this.bOrderMapper = Objects.requireNonNull(bOrderMapper);
        this.requestMapper = Objects.requireNonNull(requestMapper);
        this.orderMapper = Objects.requireNonNull(orderMapper);
        this.supplierMapper = Objects.requireNonNull(supplierMapper);
        this.offerVersionMapper = Objects.requireNonNull(offerVersionMapper);
        this.connectorResolver = Objects.requireNonNull(connectorResolver);
        this.frozenCredentialSetResolver = Objects.requireNonNull(frozenCredentialSetResolver);
        this.ticketFactory = Objects.requireNonNull(ticketFactory);
        this.checkpointCodec = Objects.requireNonNull(checkpointCodec);
        this.envelopeCrypto = Objects.requireNonNull(envelopeCrypto);
        this.timer = Objects.requireNonNull(timer);
        this.databaseClock = Objects.requireNonNull(databaseClock);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.executorProvider = executorProvider;
        this.lockProvider = lockProvider;
    }

    /**
     * Timer 驱动的核实执行编排：互斥锁 → 短事务 STARTED+新代次 → 事务外一次 QUERY → 短事务保存。
     */
    public String executeVerify(String actionNo) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Case QUERY 编排不得在调用者事务中运行");
        }
        var lock = lockProvider.getIfAvailable(ReentrantLock::new);
        var executor = executorProvider.getIfAvailable();
        if (executor == null) {
            throw new IllegalStateException("Huasheng fulfillment executor is not available");
        }
        boolean locked = false;
        try {
            locked = lock.tryLock(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("Huasheng execution lock is busy for case verify: " + actionNo);
            }
            var started = transactionalSelf.getObject().markVerifyStarted(actionNo);
            if (started == null) {
                return "ALREADY_STARTED_OR_FINISHED";
            }
            try {
                var outcome = executor.execute(started.ticket());
                if (!(outcome.protocolFact() instanceof HuashengStepFact fact)) {
                    throw new IllegalStateException("Case verify requires HuashengStepFact");
                }
                transactionalSelf.getObject().completeVerify(actionNo, fact);
                return "VERIFY_COMPLETED";
            } catch (Exception e) {
                transactionalSelf.getObject().completeVerifyUnknown(actionNo);
                log.error("[Case核实][执行降级终结] actionNo={}", actionNo, e);
                return "VERIFY_UNKNOWN";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Case verify interrupted: " + actionNo, e);
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    /** 短事务：RECEIVED→STARTED、查询已开始标记、新代次与租约；返回外呼凭证与聚合上下文。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public VerifyStartContext markVerifyStarted(String actionNo) {
        var action = caseActionMapper.selectByActionNo(actionNo);
        if (action == null || !VERIFY_ORIGIN.equals(action.getCommandType())) {
            return null;
        }
        if (!RECEIVED.equals(action.getExecutionStatus())) {
            // Timer 重投或进程恢复发现已开始标记时不得再次外呼（设计 §6.2）。
            return null;
        }
        var ctx = loadCaseContext(action.getCaseNo());
        action = caseActionMapper.selectByActionNoForUpdate(actionNo);
        if (!RECEIVED.equals(action.getExecutionStatus()) || Boolean.TRUE.equals(action.getQueryStartedFlag())) return null;
        LocalDateTime now = databaseClock.now();
        long currentGen = ctx.latestOperation().getExecutionGeneration() != null
                ? ctx.latestOperation().getExecutionGeneration() : 0L;
        if (!Objects.equals(action.getExpectedGeneration(), currentGen)
                || !Objects.equals(action.getOperationNo(), ctx.latestOperation().getOperationNo())
                || !Objects.equals(action.getExpectedCaseVersion(), ctx.caseDo().getVersion())
                || !FulfillmentCaseAdminService.isManualCase(ctx.caseDo(), ctx.bOrder())) {
            finalizeAction(action, UNKNOWN, "聚合状态或版本已变化，核实未开始");
            return null;
        }
        var candidate = decryptCheckpoint(ctx).notCreatedCandidate();
        if (candidate != null && !candidate.exited() && now.isBefore(candidate.nextQueryAt())) {
            ObjectNode payload = objectMapper.createObjectNode().put("actionNo", actionNo);
            timer.scheduleOnce(FulfillmentTimerTaskTypes.HUASHENG_CASE_VERIFY,
                    "HS:CASE_VERIFY:WAIT:" + actionNo + ":" + candidate.nextQueryAt(), payload,
                    databaseClock.toInstant(candidate.nextQueryAt()));
            return null;
        }
        long nextGen = currentGen + 1;
        ctx.latestOperation().setExecutionGeneration(nextGen);
        ctx.latestOperation().setUpdatedAt(now);
        if (operationMapper.updateById(ctx.latestOperation()) != 1) {
            throw new IllegalStateException("Verify generation CAS failed: " + ctx.latestOperation().getOperationNo());
        }
        action.setExecutionStatus(STARTED);
        action.setQueryStartedFlag(true);
        action.setLeaseUntil(now.plusSeconds(HuashengFlowPolicy.LEASE_SECONDS));
        action.setActualCaseVersion(ctx.caseDo().getVersion());
        action.setActualGeneration(nextGen);
        action.setUpdatedAt(now);
        if (caseActionMapper.updateById(action) != 1) {
            throw new IllegalStateException("Verify action CAS failed: " + actionNo);
        }
        // 租约到期收尾 Timer：到期仍无完整结果则终结为 UNKNOWN 并清除活动意图（设计 §6.2）。
        ObjectNode finalizePayload = objectMapper.createObjectNode();
        finalizePayload.put("actionNo", actionNo);
        timer.scheduleOnce(FulfillmentTimerTaskTypes.HUASHENG_CASE_VERIFY_FINALIZE,
                "HS:CASE_VERIFY_FINALIZE:" + actionNo, finalizePayload,
                databaseClock.toInstant(action.getLeaseUntil()));

        var ticket = buildQueryTicket(ctx, nextGen, actionNo);
        return new VerifyStartContext(ctx, ticket);
    }

    /** 短事务保存核实结论；同事务更新候选计数；代次已变化的结果失效为 UNKNOWN。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void completeVerify(String actionNo, HuashengStepFact fact) {
        var action = caseActionMapper.selectByActionNo(actionNo);
        if (action == null || (!STARTED.equals(action.getExecutionStatus())
                && !UNKNOWN.equals(action.getExecutionStatus()))) {
            return;
        }
        var ctx = loadCaseContext(action.getCaseNo());
        action = caseActionMapper.selectByActionNoForUpdate(actionNo);
        if (UNKNOWN.equals(action.getExecutionStatus()) && Boolean.TRUE.equals(action.getQueryStartedFlag())) {
            // 迟到事实只归属旧命令诊断；不更新最近核实资格、候选计数或新执行代次。设计 §6.2。
            if (action.getEvidenceCiphertext() == null) {
                storeEvidence(action, ctx, fact, databaseClock.now());
                finalizeAction(action, UNKNOWN, "迟到核实事实已保存，仍不可用于失败确认");
            }
            return;
        }
        if (!STARTED.equals(action.getExecutionStatus())) return;
        long currentGen = ctx.latestOperation().getExecutionGeneration() != null
                ? ctx.latestOperation().getExecutionGeneration() : 0L;
        if (!Objects.equals(currentGen, action.getActualGeneration())
                || !Objects.equals(action.getOperationNo(), ctx.latestOperation().getOperationNo())
                || action.getLeaseUntil() == null || !databaseClock.now().isBefore(action.getLeaseUntil())
                || !Objects.equals(action.getActualCaseVersion(), ctx.caseDo().getVersion())
                || !FulfillmentCaseAdminService.isManualCase(ctx.caseDo(), ctx.bOrder())) {
            storeEvidence(action, ctx, fact, databaseClock.now());
            invalidateGeneration(ctx, action);
            finalizeAction(action, UNKNOWN, "代次已变化，核实结果失效");
            return;
        }

        LocalDateTime now = databaseClock.now();
        supplierOrderLifecycle.recordHuashengResult(ctx.latestOperation().getOperationNo(), fact,
                actionNo, action.getUpdatedAt());
        var candidate = decryptCandidate(ctx);
        var checkpoint = decryptCheckpoint(ctx);
        if (candidate != null && !candidate.exited() && fact.resultClass() == HuashengStepFact.StepResult.ACCEPTED) {
            updateCheckpointCandidate(ctx, candidate.validNotCreatedQueryCount(), true);
        }
        if (fact.endpoint() != HuashengStepFact.Endpoint.QUERY
                || !Objects.equals(fact.outOrderSn(), checkpoint.placeParameters().outOrderSn())
                || fact.protectedAssetPayload() != null
                || (fact.resultClass() == HuashengStepFact.StepResult.ACCEPTED
                    && (fact.num() != checkpoint.placeParameters().num() || fact.orderSn() == null
                        || fact.orderSn().isBlank() || (checkpoint.orderSn() != null
                            && !Objects.equals(fact.orderSn(), checkpoint.orderSn()))))) {
            storeEvidence(action, ctx, fact, now);
            invalidateGeneration(ctx, action);
            finalizeAction(action, UNKNOWN, "原单身份、数量或资产证据不满足安全核实条件");
            return;
        }
        String conclusion;
        String supplierStatus;
        int validCount = candidate != null ? candidate.validNotCreatedQueryCount() : 0;

        if (fact.resultClass() == HuashengStepFact.StepResult.ACCEPTED) {
            supplierStatus = fact.status() != null ? fact.status().name() : null;
            conclusion = switch (fact.status()) {
                case CANCEL -> fact.refundStatus() == HuashengStepFact.RefundStatus.SUCCESS
                        && fact.isTake() == HuashengStepFact.IsTake.N
                        ? FulfillmentCaseAdminService.CONCLUSION_SUPPLIER_CANCELLED
                        : FulfillmentCaseAdminService.CONCLUSION_REFUND_PENDING;
                case FAILED -> fact.refundStatus() == HuashengStepFact.RefundStatus.SUCCESS
                        && fact.isTake() == HuashengStepFact.IsTake.N
                        ? FulfillmentCaseAdminService.CONCLUSION_SUPPLIER_FAILED
                        : FulfillmentCaseAdminService.CONCLUSION_REFUND_PENDING;
                case SUCCESS -> FulfillmentCaseAdminService.CONCLUSION_ORDER_SUCCEEDED;
                case WAIT -> FulfillmentCaseAdminService.CONCLUSION_ORDER_WAITING;
                case null, default -> FulfillmentCaseAdminService.CONCLUSION_QUERY_REJECTED;
            };
        } else if (fact.rawErrorCode() != null && fact.rawErrorCode() == 7014
                && candidate != null && !candidate.exited()) {
            // 有效未建单查询：人工核实与自动流程共享同一持久判据并累计（设计 §6.2）。
            validCount = Math.min(HuashengErrorClassification.REQUIRED_VALID_NOT_CREATED_QUERIES,
                    candidate.validNotCreatedQueryCount() + 1);
            updateCheckpointCandidate(ctx, validCount, false);
            conclusion = validCount >= HuashengErrorClassification.REQUIRED_VALID_NOT_CREATED_QUERIES
                    ? FulfillmentCaseAdminService.CONCLUSION_NOT_CREATED
                    : FulfillmentCaseAdminService.CONCLUSION_ORDER_NOT_FOUND;
            supplierStatus = "NOT_FOUND";
        } else {
            conclusion = FulfillmentCaseAdminService.CONCLUSION_QUERY_REJECTED;
            supplierStatus = fact.rawErrorCode() != null ? String.valueOf(fact.rawErrorCode()) : null;
            if (candidate != null && !candidate.exited()) updateCheckpointCandidate(ctx, validCount, false);
        }

        storeEvidence(action, ctx, fact, now);
        action.setVerifyConclusion(conclusion);
        action.setExternalSupplierStatus(supplierStatus != null && supplierStatus.length() <= 64 ? supplierStatus : null);
        action.setValidNotCreatedCount(validCount);
        action.setFreshUntil(now.plusMinutes(VERIFY_FRESHNESS_MINUTES));
        finalizeAction(action, SUCCEEDED, "核实完成，结论=" + conclusion + "，有效未建单次数=" + validCount);
        FulfillmentCaseAdminService.afterCommitLog("[Case核实][完成] actionNo={}, conclusion={}, validCount={}", actionNo, conclusion, validCount);
    }

    /** 网络不确定、宕机或未持久化结果：该 action 不能用于释放，终结为 UNKNOWN 并清除活动意图。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void completeVerifyUnknown(String actionNo) {
        var action = caseActionMapper.selectByActionNo(actionNo);
        if (action == null || !STARTED.equals(action.getExecutionStatus())) {
            return;
        }
        var ctx = loadCaseContext(action.getCaseNo());
        action = caseActionMapper.selectByActionNoForUpdate(actionNo);
        if (!STARTED.equals(action.getExecutionStatus())) return;
        invalidateGeneration(ctx, action);
        finalizeAction(action, UNKNOWN, "核实结果未知，不能用于失败确认；可提交新的 actionNo");
    }

    /** Timer 驱动的租约到期收尾：到期仍无完整结果则终结为 UNKNOWN（设计 §6.2）。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String finalizeExpiredVerify(String actionNo) {
        var action = caseActionMapper.selectByActionNo(actionNo);
        if (action == null || !STARTED.equals(action.getExecutionStatus())) {
            return "SKIPPED";
        }
        var ctx = loadCaseContext(action.getCaseNo());
        action = caseActionMapper.selectByActionNoForUpdate(actionNo);
        if (!STARTED.equals(action.getExecutionStatus())) return "SKIPPED";
        if (action.getLeaseUntil() != null && databaseClock.now().isBefore(action.getLeaseUntil())) {
            return "LEASE_NOT_EXPIRED";
        }
        invalidateGeneration(ctx, action);
        finalizeAction(action, UNKNOWN, "核实租约到期，结果未知");
        return "FINALIZED_UNKNOWN";
    }

    private void invalidateGeneration(CaseContext ctx, FulfillmentCaseActionDO action) {
        if (Objects.equals(ctx.latestOperation().getExecutionGeneration(), action.getActualGeneration())) {
            // 未知终结只推进本代次的退避，不能算有效 7014；幂等或迟到结果不再推进。设计 §7 接管集成修订。
            var candidate = decryptCandidate(ctx);
            if (candidate != null && !candidate.exited()) {
                updateCheckpointCandidate(ctx, candidate.validNotCreatedQueryCount(), false);
            }
            ctx.latestOperation().setExecutionGeneration(action.getActualGeneration() + 1);
            ctx.latestOperation().setUpdatedAt(databaseClock.now());
            if (operationMapper.updateById(ctx.latestOperation()) != 1) throw new IllegalStateException("核实失效代次 CAS 失败");
        }
    }

    private void finalizeAction(FulfillmentCaseActionDO action, String status, String summary) {
        action.setExecutionStatus(status);
        action.setActiveIntentFlag(null);
        action.setCompletionSummary(summary != null && summary.length() > 1024 ? summary.substring(0, 1024) : summary);
        action.setUpdatedAt(databaseClock.now());
        if (caseActionMapper.update(action, new LambdaUpdateWrapper<FulfillmentCaseActionDO>()
                .eq(FulfillmentCaseActionDO::getId, action.getId())
                .set(FulfillmentCaseActionDO::getActiveIntentFlag, null)) != 1) {
            throw new IllegalStateException("Verify action finalize CAS failed: " + action.getActionNo());
        }
    }

    /** 只读 QUERY 出站凭证：复用现有工厂与冻结凭据，不创建第二个授权客户端。 */
    private OperationTicket buildQueryTicket(CaseContext ctx, long generation, String actionNo) {
        var connectorConfig = connectorResolver.resolve(ctx.bOrder().getConnectorVersionId());
        if (!HuashengOrderV1Metadata.TYPE.equals(connectorConfig.adapterType())) {
            throw new IllegalStateException("Case verify only supports HUA_SHENG_ORDER_V1");
        }
        var resolvedCredentials = frozenCredentialSetResolver.resolve(
                connectorConfig.credentialSetId(), connectorConfig.frozenCredentialItems(),
                HuashengOrderV1Metadata.OUTBOUND_KEYS);
        try {
            char[] appSecret = resolvedCredentials.get(HuashengOrderV1Metadata.APP_SECRET);
            char[] privateKey = resolvedCredentials.get(HuashengOrderV1Metadata.PRIVATE_KEY);
            if (appSecret == null || privateKey == null) {
                throw new IllegalStateException("Resolved credentials missing required outbound keys");
            }
            var material = new OperationTicket.CredentialMaterial(new String(appSecret), new String(privateKey));
            var permit = new HuashengExecutionPermit(ctx.latestOperation().getOperationNo(),
                    ctx.bOrder().getId(), generation, AdapterStepCode.QUERY_ORDER,
                    HuashengFlowTrigger.RECOVER, databaseClock.now().plusSeconds(HuashengFlowPolicy.LEASE_SECONDS));
            var supplier = supplierMapper.selectById(ctx.bOrder().getSupplierId());
            var offerVersion = offerVersionMapper.selectById(ctx.bOrder().getOfferVersionId());
            return ticketFactory.createForHuasheng(ctx.latestOperation(), ctx.bOrder(), ctx.request(),
                    supplier, offerVersion, connectorConfig, permit, material, actionNo);
        } finally {
            resolvedCredentials.values().forEach(chars -> {
                if (chars != null) Arrays.fill(chars, '\0');
            });
        }
    }

    /** 受保护核实证据：显式 schema/key 版本，AAD 绑定命令与聚合身份及代次（设计 §7）。 */
    private void storeEvidence(FulfillmentCaseActionDO action, CaseContext ctx, HuashengStepFact fact, LocalDateTime now) {
        try {
            ObjectNode evidence = objectMapper.createObjectNode();
            evidence.put("recordedAt", now.toString());
            evidence.put("endpoint", fact.endpoint().name());
            evidence.put("outOrderSn", fact.outOrderSn());
            evidence.put("orderSn", fact.orderSn());
            evidence.put("num", fact.num());
            evidence.put("hasAsset", fact.protectedAssetPayload() != null);
            if (fact.protectedAssetPayload() != null) evidence.put("assetPayload",
                    java.util.Base64.getEncoder().encodeToString(fact.protectedAssetPayload()));
            var checkpoint = decryptCheckpoint(ctx);
            evidence.put("validIdentity", fact.endpoint() == HuashengStepFact.Endpoint.QUERY
                    && Objects.equals(fact.outOrderSn(), checkpoint.placeParameters().outOrderSn())
                    && (fact.resultClass() != HuashengStepFact.StepResult.ACCEPTED
                        || (fact.num() == checkpoint.placeParameters().num() && fact.orderSn() != null && !fact.orderSn().isBlank()
                            && (checkpoint.orderSn() == null || Objects.equals(checkpoint.orderSn(), fact.orderSn())))));
            evidence.put("placeDigest", decryptCheckpoint(ctx).placeParametersDigest());
            evidence.put("configDigest", java.util.HexFormat.of().formatHex(ctx.latestOperation().getConfigDigest()));
            evidence.put("frozenNum", checkpoint.placeParameters().num());
            evidence.put("bOrderNo", ctx.bOrder().getBOrderNo());
            var attempts = evidence.putArray("attempts");
            for (var attempt : fact.attempts()) {
                var observation = attempts.addObject();
                observation.put("endpoint", attempt.endpoint());
                if (attempt.httpStatus() != null) observation.put("httpStatus", attempt.httpStatus());
                if (attempt.rawBusinessCode() != null) observation.put("rawBusinessCode", attempt.rawBusinessCode());
                observation.put("elapsedMs", attempt.durationMs());
                observation.put("physicalSendCount", attempt.physicalSendCount());
                observation.put("wasBusinessCallStarted", attempt.wasBusinessCallStarted());
            }
            evidence.put("resultClass", fact.resultClass().name());
            if (fact.status() != null) evidence.put("status", fact.status().name());
            if (fact.refundStatus() != null) evidence.put("refundStatus", fact.refundStatus().name());
            if (fact.isTake() != null) evidence.put("isTake", fact.isTake().name());
            if (fact.rawErrorCode() != null) evidence.put("rawErrorCode", fact.rawErrorCode());
            if (fact.totalPriceMinorUnits() != null) evidence.put("totalPriceMinorUnits", fact.totalPriceMinorUnits());
            if (fact.supplierTraceId() != null) evidence.put("supplierTraceId", fact.supplierTraceId());
            byte[] plaintext = objectMapper.writeValueAsBytes(evidence);
            byte[] aad = (String.join("|", "HS_CASE_VERIFY_EVIDENCE", action.getActionNo(), action.getCaseNo(),
                    action.getOperationNo(), Long.toString(ctx.bOrder().getConnectorVersionId()),
                    Long.toString(ctx.bOrder().getOfferVersionId()),
                    Long.toString(action.getActualGeneration() != null ? action.getActualGeneration() : 0L)))
                    .getBytes(StandardCharsets.UTF_8);
            var encrypted = envelopeCrypto.encrypt(plaintext, aad);
            Arrays.fill(plaintext, (byte) 0);
            action.setEvidenceSchemaVersion(1);
            action.setEvidenceKeyVersion(encrypted.keyVersion());
            action.setEvidenceCiphertext(encrypted.ciphertext());
        } catch (Exception e) {
            throw new IllegalStateException("Case verify evidence encryption failed: " + action.getActionNo(), e);
        }
    }

    /** 人工核实的候选计数与自动流程共享同一持久判据（设计 §6.2）。 */
    private void updateCheckpointCandidate(CaseContext ctx, int newValidCount, boolean exited) {
        var operation = ctx.latestOperation();
        var checkpoint = decryptCheckpoint(ctx);
        var candidate = checkpoint.notCreatedCandidate();
        if (candidate == null) {
            return;
        }
        int queryResultCount = candidate.queryResultCount() + (exited ? 0 : 1);
        var updated = new HuashengCheckpoint.NotCreatedCandidate(
                candidate.earliestRejectionCode(), candidate.firstRejectionAt(), newValidCount,
                exited ? candidate.lastQueryAt() : databaseClock.now().truncatedTo(ChronoUnit.MILLIS),
                exited ? candidate.nextQueryAt() : databaseClock.now().plusSeconds(
                        HuashengErrorClassification.notCreatedBackoffSeconds(queryResultCount)).truncatedTo(ChronoUnit.MILLIS),
                exited || candidate.exited(), queryResultCount);
        var nextCheckpoint = new HuashengCheckpoint(checkpoint.binding(), checkpoint.placeParameters(),
                checkpoint.orderSn(), checkpoint.observation(), checkpoint.progress(),
                checkpoint.pendingAssetPayload(), updated);
        var stored = checkpointCodec.encrypt(nextCheckpoint);
        operation.setCheckpointSchemaVersion(stored.schemaVersion());
        operation.setCheckpointKeyVersion(stored.keyVersion());
        operation.setCheckpointCiphertext(stored.ciphertext());
        operation.setUpdatedAt(databaseClock.now());
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Verify candidate checkpoint CAS failed: " + operation.getOperationNo());
        }
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

    private HuashengCheckpoint.NotCreatedCandidate decryptCandidate(CaseContext ctx) {
        try {
            return decryptCheckpoint(ctx).notCreatedCandidate();
        } catch (Exception e) {
            return null;
        }
    }

    /** 核实执行聚合上下文。 */
    public record CaseContext(FulfillmentExceptionCaseDO caseDo, BOrderDO bOrder,
                              FulfillmentRequestDO request, SupplyChainOrderDO order,
                              FulfillmentOperationDO latestOperation) {
    }

    /** 外呼启动上下文：聚合与出站凭证。 */
    public record VerifyStartContext(CaseContext ctx, OperationTicket ticket) {
    }

    CaseContext loadCaseContext(String caseNo) {
        var caseDo = exceptionCaseMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FulfillmentExceptionCaseDO>()
                .eq(FulfillmentExceptionCaseDO::getCaseNo, caseNo));
        if (caseDo == null) {
            throw new IllegalArgumentException("Fulfillment case does not exist: " + caseNo);
        }
        var locator = bOrderMapper.selectById(caseDo.getBOrderId());
        if (locator == null) throw new IllegalStateException("Case 原单不存在");
        var request = executionMapper.lockRequest(locator.getFulfillmentRequestId());
        var order = executionMapper.lockOrder(locator.getOrderId());
        var bOrders = executionMapper.lockBOrders(locator.getOrderId());
        var operations = executionMapper.lockOperations(locator.getOrderId()).stream()
                .filter(op -> Objects.equals(op.getBOrderId(), locator.getId()))
                .sorted(java.util.Comparator.comparing(FulfillmentOperationDO::getSequenceNo)).toList();
        caseDo = exceptionCaseMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FulfillmentExceptionCaseDO>()
                .eq(FulfillmentExceptionCaseDO::getCaseNo, caseNo).last("FOR UPDATE"));
        var bOrder = bOrders.stream().filter(row -> Objects.equals(row.getId(), locator.getId())).findFirst().orElse(null);
        if (bOrder == null) {
            throw new IllegalStateException("BOrder does not exist for case: " + caseNo);
        }
        if (request == null) {
            throw new IllegalStateException("FulfillmentRequest does not exist for case: " + caseNo);
        }
        if (order == null) {
            throw new IllegalStateException("SupplyChainOrder does not exist for case: " + caseNo);
        }
        if (operations.isEmpty()) {
            throw new IllegalStateException("No operations exist for case: " + caseNo);
        }
        return new CaseContext(caseDo, bOrder, request, order, operations.getLast());
    }
}
