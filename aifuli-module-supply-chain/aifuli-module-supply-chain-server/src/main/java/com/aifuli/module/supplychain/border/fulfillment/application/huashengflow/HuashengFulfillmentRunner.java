package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationTicketFactory;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.infrastructure.security.FrozenCredentialSetResolver;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 华昇生产履约 Runner。
 *
 * <p>职责：
 * 串联进程级互斥锁、MDC 隔离、凭据提取（短事务前）与擦除、生命周期 claim/apply 事务及事务外出站外呼。
 * 1. 唯一互斥锁在 claim 前尝试，覆盖 claim/HTTP/apply 全过程；
 * 2. 锁忙抛出 {@link HuashengLockBusyException}，使 TimerEngine 调度重试，绝不吞掉触发；
 * 3. 每次后台执行建立新 traceId 与 executionId，并在 finally 中严格恢复 MDC 原快照；
 * 4. 凭据在持锁但进入聚合事务前解析，使用完毕后在 finally 中零化擦除；
 * 5. 驱动唯一下一步执行、租约看门狗驱动恢复与准备超时看门狗收尾。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#31-执行边界}、
 * {@code #5-资格唯一下一步与宕机恢复}。
 */
@Slf4j
public class HuashengFulfillmentRunner {

    private final ReentrantLock huashengExecutionLock;
    private final HuashengFulfillmentLifecycle lifecycle;
    private final HuashengOperationExecutor executor;
    private final FulfillmentOperationTicketFactory ticketFactory;
    private final FrozenCredentialSetResolver frozenCredentialSetResolver;
    private final PublishedConnectorConfigurationResolver connectorResolver;
    private final FulfillmentOperationMapper operationMapper;
    private final BOrderMapper bOrderMapper;
    private final FulfillmentRequestMapper requestMapper;
    private final SupplierMapper supplierMapper;
    private final SupplierOfferVersionMapper offerVersionMapper;
    private final SupplyChainDatabaseClock databaseClock;

    public HuashengFulfillmentRunner(
            ReentrantLock huashengExecutionLock,
            HuashengFulfillmentLifecycle lifecycle,
            HuashengOperationExecutor executor,
            FulfillmentOperationTicketFactory ticketFactory,
            FrozenCredentialSetResolver frozenCredentialSetResolver,
            PublishedConnectorConfigurationResolver connectorResolver,
            FulfillmentOperationMapper operationMapper,
            BOrderMapper bOrderMapper,
            FulfillmentRequestMapper requestMapper,
            SupplierMapper supplierMapper,
            SupplierOfferVersionMapper offerVersionMapper,
            SupplyChainDatabaseClock databaseClock
    ) {
        this.huashengExecutionLock = Objects.requireNonNull(huashengExecutionLock, "huashengExecutionLock must not be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.ticketFactory = Objects.requireNonNull(ticketFactory, "ticketFactory must not be null");
        this.frozenCredentialSetResolver = Objects.requireNonNull(frozenCredentialSetResolver, "frozenCredentialSetResolver must not be null");
        this.connectorResolver = Objects.requireNonNull(connectorResolver, "connectorResolver must not be null");
        this.operationMapper = Objects.requireNonNull(operationMapper, "operationMapper must not be null");
        this.bOrderMapper = Objects.requireNonNull(bOrderMapper, "bOrderMapper must not be null");
        this.requestMapper = Objects.requireNonNull(requestMapper, "requestMapper must not be null");
        this.supplierMapper = Objects.requireNonNull(supplierMapper, "supplierMapper must not be null");
        this.offerVersionMapper = Objects.requireNonNull(offerVersionMapper, "offerVersionMapper must not be null");
        this.databaseClock = Objects.requireNonNull(databaseClock, "databaseClock must not be null");
    }

    /**
     * 获取华昇进程执行互斥锁。
     */
    public ReentrantLock executionLock() {
        return huashengExecutionLock;
    }

    /**
     * 执行正常履约步骤（PLACE / CONFIRM / TAKE）。
     */
    public String runExecute(String operationNo, String originTraceId) {
        return withMdc(originTraceId, () -> withLock(operationNo, () -> {
            FulfillmentOperationDO operation = operationMapper.selectOne(
                    new LambdaQueryWrapper<FulfillmentOperationDO>()
                            .eq(FulfillmentOperationDO::getOperationNo, operationNo));
            if (operation == null) {
                log.warn("[华昇Runner][未找到操作] operationNo={}", operationNo);
                return "NOT_FOUND";
            }
            if (isTerminal(operation.getStatus())) {
                log.info("[华昇Runner][操作已达终态] operationNo={}, status={}", operationNo, operation.getStatus());
                return "ALREADY_COMPLETED";
            }
            BOrderDO bOrder = bOrderMapper.selectById(operation.getBOrderId());
            if (bOrder == null) {
                throw new IllegalStateException("BOrder not found for operation: " + operationNo);
            }
            PublishedConnectorConfiguration connectorConfig = connectorResolver.resolve(bOrder.getConnectorVersionId());
            if (!HuashengOrderV1Metadata.TYPE.equals(connectorConfig.adapterType())) {
                throw new IllegalArgumentException("Unsupported adapter type: " + connectorConfig.adapterType());
            }

            // 凭据在持进程锁但进入 claim 事务前解析
            Map<String, char[]> resolvedCredentials = frozenCredentialSetResolver.resolve(
                    connectorConfig.credentialSetId(),
                    connectorConfig.frozenCredentialItems(),
                    HuashengOrderV1Metadata.OUTBOUND_KEYS
            );
            try {
                char[] appSecret = resolvedCredentials.get(HuashengOrderV1Metadata.APP_SECRET);
                char[] privateKey = resolvedCredentials.get(HuashengOrderV1Metadata.PRIVATE_KEY);
                if (appSecret == null || privateKey == null) {
                    throw new IllegalStateException("Resolved credentials missing required outbound keys");
                }
                OperationTicket.CredentialMaterial credentialMaterial =
                        new OperationTicket.CredentialMaterial(new String(appSecret), new String(privateKey));

                // 短事务认领执行资格
                HuashengExecutionPermit permit = lifecycle.claimForExecution(operationNo);
                if (permit == null) {
                    log.info("[华昇Runner][认领跳过] operationNo={} 不具备认领资格", operationNo);
                    return "SKIPPED_NOT_ELIGIBLE";
                }

                // 读取认领后具有最新 generation 的 operation
                FulfillmentOperationDO currentOperation = operationMapper.selectById(operation.getId());
                FulfillmentRequestDO request = requestMapper.selectById(bOrder.getFulfillmentRequestId());
                SupplierDO supplier = supplierMapper.selectById(bOrder.getSupplierId());
                SupplierOfferVersionDO offerVersion = offerVersionMapper.selectById(bOrder.getOfferVersionId());

                String traceId = MDC.get("traceId");
                OperationTicket ticket = ticketFactory.createForHuasheng(
                        currentOperation, bOrder, request, supplier, offerVersion, connectorConfig, permit, credentialMaterial, traceId
                );

                // 事务外出站外呼
                log.info("[华昇Runner][出站调用] operationNo={}, step={}, generation={}",
                        operationNo, permit.step(), permit.executionGeneration());
                OperationOutcome outcome = executor.execute(ticket);

                // 短事务应用事实
                HuashengApplyResult applyResult = lifecycle.apply(permit, outcome, traceId);
                log.info("[华昇Runner][应用结果] operationNo={}, decision={}, applied={}",
                        operationNo, applyResult.decision(), applyResult.applied());
                return applyResult.decision() != null ? applyResult.decision().name() : "APPLIED";
            } finally {
                resolvedCredentials.values().forEach(chars -> {
                    if (chars != null) Arrays.fill(chars, '\0');
                });
            }
        }));
    }

    /**
     * 执行查单恢复（QUERY_ORDER）。
     */
    public String runRecovery(String operationNo, Long expectedGeneration, String originTraceId) {
        return withMdc(originTraceId, () -> withLock(operationNo, () -> {
            FulfillmentOperationDO operation = operationMapper.selectOne(
                    new LambdaQueryWrapper<FulfillmentOperationDO>()
                            .eq(FulfillmentOperationDO::getOperationNo, operationNo));
            if (operation == null) {
                return "NOT_FOUND";
            }
            if (isTerminal(operation.getStatus())) {
                return "ALREADY_COMPLETED";
            }
            if (expectedGeneration != null && operation.getExecutionGeneration() != null
                    && !expectedGeneration.equals(operation.getExecutionGeneration())) {
                log.info("[华昇Runner][恢复代次过时] operationNo={}, currentGen={}, expectedGen={}",
                        operationNo, operation.getExecutionGeneration(), expectedGeneration);
                return "OBSOLETE_GENERATION";
            }
            return executeRecoveryInternal(operation, originTraceId);
        }));
    }

    /**
     * 执行租约到期看门狗检查并驱动恢复。
     */
    public String runLeaseWatchdog(String operationNo, Long expectedGeneration, String originTraceId) {
        return withMdc(originTraceId, () -> withLock(operationNo, () -> {
            FulfillmentOperationDO operation = operationMapper.selectOne(
                    new LambdaQueryWrapper<FulfillmentOperationDO>()
                            .eq(FulfillmentOperationDO::getOperationNo, operationNo));
            if (operation == null) {
                return "NOT_FOUND";
            }
            if (isTerminal(operation.getStatus())) {
                return "ALREADY_COMPLETED";
            }
            if (expectedGeneration != null && operation.getExecutionGeneration() != null
                    && !expectedGeneration.equals(operation.getExecutionGeneration())) {
                log.info("[华昇Runner][看门狗代次过时] operationNo={}, currentGen={}, expectedGen={}",
                        operationNo, operation.getExecutionGeneration(), expectedGeneration);
                return "OBSOLETE_GENERATION";
            }
            LocalDateTime now = databaseClock.now();
            if (operation.getExecutionLeaseUntil() != null && now.isBefore(operation.getExecutionLeaseUntil())) {
                log.debug("[华昇Runner][租约未到期] operationNo={}, leaseUntil={}",
                        operationNo, operation.getExecutionLeaseUntil());
                return "LEASE_NOT_EXPIRED";
            }
            log.info("[华昇Runner][租约已到期驱动恢复] operationNo={}, generation={}",
                    operationNo, operation.getExecutionGeneration());
            return executeRecoveryInternal(operation, originTraceId);
        }));
    }

    /**
     * 执行准备看门狗收尾检查（5分钟未认领且无发送 marker 转 MANUAL）。
     */
    public String runPreparationWatchdog(String operationNo, String originTraceId) {
        return withMdc(originTraceId, () -> withLock(operationNo, () -> {
            boolean transitioned = lifecycle.handlePreparationWatchdog(operationNo);
            return transitioned ? "TRANSITIONED_TO_MANUAL" : "SKIPPED";
        }));
    }

    private String executeRecoveryInternal(FulfillmentOperationDO operation, String originTraceId) {
        BOrderDO bOrder = bOrderMapper.selectById(operation.getBOrderId());
        if (bOrder == null) {
            throw new IllegalStateException("BOrder not found for operation: " + operation.getOperationNo());
        }
        PublishedConnectorConfiguration connectorConfig = connectorResolver.resolve(bOrder.getConnectorVersionId());

        Map<String, char[]> resolvedCredentials = frozenCredentialSetResolver.resolve(
                connectorConfig.credentialSetId(),
                connectorConfig.frozenCredentialItems(),
                HuashengOrderV1Metadata.OUTBOUND_KEYS
        );
        try {
            char[] appSecret = resolvedCredentials.get(HuashengOrderV1Metadata.APP_SECRET);
            char[] privateKey = resolvedCredentials.get(HuashengOrderV1Metadata.PRIVATE_KEY);
            if (appSecret == null || privateKey == null) {
                throw new IllegalStateException("Resolved credentials missing required outbound keys");
            }
            OperationTicket.CredentialMaterial credentialMaterial =
                    new OperationTicket.CredentialMaterial(new String(appSecret), new String(privateKey));

            HuashengExecutionPermit permit = lifecycle.claimForRecovery(operation.getOperationNo());
            if (permit == null) {
                log.info("[华昇Runner][恢复认领不满足资格] operationNo={}", operation.getOperationNo());
                return "NOT_ELIGIBLE";
            }

            FulfillmentOperationDO currentOperation = operationMapper.selectById(operation.getId());
            FulfillmentRequestDO request = requestMapper.selectById(bOrder.getFulfillmentRequestId());
            SupplierDO supplier = supplierMapper.selectById(bOrder.getSupplierId());
            SupplierOfferVersionDO offerVersion = offerVersionMapper.selectById(bOrder.getOfferVersionId());

            String traceId = MDC.get("traceId");
            OperationTicket ticket = ticketFactory.createForHuasheng(
                    currentOperation, bOrder, request, supplier, offerVersion, connectorConfig, permit, credentialMaterial, traceId
            );

            log.info("[华昇Runner][恢复出站QUERY] operationNo={}, generation={}",
                    operation.getOperationNo(), permit.executionGeneration());
            OperationOutcome outcome = executor.execute(ticket);

            HuashengApplyResult applyResult = lifecycle.apply(permit, outcome, traceId);
            log.info("[华昇Runner][恢复应用结果] operationNo={}, decision={}",
                    operation.getOperationNo(), applyResult.decision());
            return applyResult.decision() != null ? applyResult.decision().name() : "APPLIED";
        } finally {
            resolvedCredentials.values().forEach(chars -> {
                if (chars != null) Arrays.fill(chars, '\0');
            });
        }
    }

    private <T> T withLock(String operationNo, Supplier<T> action) {
        boolean locked = false;
        try {
            locked = huashengExecutionLock.tryLock();
            if (!locked) {
                log.warn("[华昇Runner][锁忙] 无法获取华昇互斥锁 operationNo={}", operationNo);
                throw new HuashengLockBusyException("Huasheng execution lock is busy for operation: " + operationNo);
            }
            return action.get();
        } finally {
            if (locked) {
                huashengExecutionLock.unlock();
            }
        }
    }

    private <T> T withMdc(String originTraceId, Supplier<T> action) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        try {
            String newTraceId = UUID.randomUUID().toString().replace("-", "");
            String newExecutionId = UUID.randomUUID().toString().replace("-", "");
            MDC.put("traceId", newTraceId);
            MDC.put("executionId", newExecutionId);
            if (originTraceId != null && !originTraceId.isBlank()) {
                MDC.put("originTraceId", originTraceId);
            } else {
                MDC.remove("originTraceId");
            }
            return action.get();
        } finally {
            if (previousMdc != null) {
                MDC.setContextMap(previousMdc);
            } else {
                MDC.clear();
            }
        }
    }

    private static boolean isTerminal(Short status) {
        if (status == null) return false;
        return status == SupplierOrderOperationStatus.SUCCEEDED.getCode()
                || status == SupplierOrderOperationStatus.FAILED.getCode()
                || status == SupplierOrderOperationStatus.MANUAL.getCode();
    }
}
