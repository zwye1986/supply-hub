package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import static com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentExecutionFailureCodes.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationExecutorPort;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandler;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengFulfillmentRunner;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;

/**
 * 履约操作执行处理器：消费 {@link FulfillmentTimerTaskTypes#OPERATION_EXECUTE} 持久化任务，
 * 短事务 claim 后装配不可变 {@link OperationTicket}，调用 {@link OperationExecutorPort}，
 * 再由 {@link SupplierOrderLifecycle} 在短事务内收敛状态机与资金/资产闭环。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}。</p>
 */
@Slf4j
@Component
public class FulfillmentOperationExecuteHandler implements PersistentTimerHandler {

    public static final String TASK_TYPE = FulfillmentTimerTaskTypes.OPERATION_EXECUTE;

    private final FulfillmentOperationMapper operationMapper;
    private final BOrderMapper bOrderMapper;
    private final FulfillmentRequestMapper requestMapper;
    private final SupplyChainOrderMapper orderMapper;
    private final SupplierMapper supplierMapper;
    private final SupplierOfferVersionMapper offerVersionMapper;
    private final PublishedConnectorConfigurationResolver connectorResolver;
    private final EnvelopeCryptoPort envelopeCrypto;
    private final OperationExecutorPort operationExecutorPort;
    private final SupplierOrderLifecycle supplierOrderLifecycle;
    private final ObjectMapper objectMapper;
    private final FulfillmentOperationTicketFactory ticketFactory;
    private final RechargeFrequencyLifecycle frequencyLifecycle;
    private final ObjectProvider<HuashengFulfillmentRunner> huashengRunnerProvider;
    private ObjectProvider<com.aifuli.module.supplychain.border.fulfillment.application.singlestep.SingleStepFulfillmentRunner> singleStepRunners;

    /** 延迟读取避免Timer注册循环；手工构造的旧协议测试不要求单步组件。详见三家详细设计§4.7。 */
    @Autowired
    void setSingleStepRunners(ObjectProvider<com.aifuli.module.supplychain.border.fulfillment.application.singlestep.SingleStepFulfillmentRunner> runners) {
        this.singleStepRunners = runners;
    }

    @Autowired
    public FulfillmentOperationExecuteHandler(
            FulfillmentOperationMapper operationMapper,
            BOrderMapper bOrderMapper,
            FulfillmentRequestMapper requestMapper,
            SupplyChainOrderMapper orderMapper,
            SupplierMapper supplierMapper,
            SupplierOfferVersionMapper offerVersionMapper,
            PublishedConnectorConfigurationResolver connectorResolver,
            EnvelopeCryptoPort envelopeCrypto,
            OperationExecutorPort operationExecutorPort,
            SupplierOrderLifecycle supplierOrderLifecycle,
            ObjectMapper objectMapper,
            FulfillmentOperationTicketFactory ticketFactory,
            RechargeFrequencyLifecycle frequencyLifecycle,
            ObjectProvider<HuashengFulfillmentRunner> huashengRunnerProvider) {
        this.operationMapper = operationMapper;
        this.bOrderMapper = bOrderMapper;
        this.requestMapper = requestMapper;
        this.orderMapper = orderMapper;
        this.supplierMapper = supplierMapper;
        this.offerVersionMapper = offerVersionMapper;
        this.connectorResolver = connectorResolver;
        this.envelopeCrypto = envelopeCrypto;
        this.operationExecutorPort = operationExecutorPort;
        this.supplierOrderLifecycle = supplierOrderLifecycle;
        this.objectMapper = objectMapper;
        this.ticketFactory = ticketFactory;
        this.frequencyLifecycle = frequencyLifecycle;
        this.huashengRunnerProvider = huashengRunnerProvider;
    }

    public FulfillmentOperationExecuteHandler(
            FulfillmentOperationMapper operationMapper,
            BOrderMapper bOrderMapper,
            FulfillmentRequestMapper requestMapper,
            SupplyChainOrderMapper orderMapper,
            SupplierMapper supplierMapper,
            SupplierOfferVersionMapper offerVersionMapper,
            PublishedConnectorConfigurationResolver connectorResolver,
            EnvelopeCryptoPort envelopeCrypto,
            OperationExecutorPort operationExecutorPort,
            SupplierOrderLifecycle supplierOrderLifecycle,
            ObjectMapper objectMapper,
            FulfillmentOperationTicketFactory ticketFactory,
            RechargeFrequencyLifecycle frequencyLifecycle) {
        this(operationMapper, bOrderMapper, requestMapper, orderMapper, supplierMapper,
                offerVersionMapper, connectorResolver, envelopeCrypto, operationExecutorPort,
                supplierOrderLifecycle, objectMapper, ticketFactory, frequencyLifecycle, null);
    }

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public String handle(TimerTaskContext context, JsonNode payload) {
        String operationNo = payload.path("operationNo").asText(null);
        if (operationNo == null || operationNo.isBlank()) {
            log.warn("[履约执行] Timer payload 缺少 operationNo: {}", payload);
            return "MISSING_OPERATION_NO";
        }

        log.info("[履约执行] handle 入口 operationNo={}, taskId={}, attemptNo={}",
                operationNo, context.taskId(), context.attemptNo());

        FulfillmentOperationDO operation = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .eq(FulfillmentOperationDO::getOperationNo, operationNo));
        if (operation == null) {
            log.warn("[履约执行] FulfillmentOperation 不存在 operationNo={}", operationNo);
            return "NOT_FOUND";
        }

        if (isTerminal(operation.getStatus())) {
            log.info("[履约执行] Operation 已处于终态 operationNo={}, status={}", operationNo, operation.getStatus());
            return "ALREADY_COMPLETED";
        }

        // 华昇分派：必须在旧 EXECUTING/UNKNOWN 提前跳过与普通 claim 之前
        BOrderDO bOrder = bOrderMapper.selectById(operation.getBOrderId());
        if (bOrder != null) {
            var singleStep = singleStepRunners == null ? null : singleStepRunners.getIfAvailable();
            if (singleStep != null && singleStep.supports(bOrder.getConnectorVersionId())) {
                return singleStep.runExecute(operationNo, payload.path("originTraceId").asText(null));
            }
            PublishedConnectorConfiguration connectorConfig = connectorResolver.resolve(bOrder.getConnectorVersionId());
            if (connectorConfig != null && HuashengOrderV1Metadata.TYPE.equals(connectorConfig.adapterType())) {
                HuashengFulfillmentRunner huashengRunner = huashengRunnerProvider != null ? huashengRunnerProvider.getIfAvailable() : null;
                if (huashengRunner == null) {
                    throw new IllegalStateException("华昇履约执行器未启用: " + operationNo);
                }
                String originTraceId = payload.path("originTraceId").asText(null);
                return huashengRunner.runExecute(operationNo, originTraceId);
            }
        }

        if (operation.getStatus() != null
                && (operation.getStatus() == SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode()
                || operation.getStatus() == SupplierOrderOperationStatus.EXECUTING.getCode())) {
            log.info("[履约执行] Operation 已外呼或结果未知，禁止重复 Send operationNo={}, status={}",
                    operationNo, operation.getStatus());
            return operation.getStatus() == SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode()
                    ? SupplierOrderOperationStatus.RESULT_UNKNOWN.name()
                    : "ALREADY_EXECUTING";
        }

        if (bOrder == null) {
            throw new IllegalStateException("BOrder 不存在 bOrderId=" + operation.getBOrderId());
        }
        FulfillmentRequestDO request = requestMapper.selectById(bOrder.getFulfillmentRequestId());
        if (request == null) {
            throw new IllegalStateException("FulfillmentRequest 不存在 requestId=" + bOrder.getFulfillmentRequestId());
        }
        SupplyChainOrderDO order = orderMapper.selectById(bOrder.getOrderId());
        if (order == null) {
            throw new IllegalStateException("SupplyChainOrder 不存在 orderId=" + bOrder.getOrderId());
        }
        SupplierDO supplier = supplierMapper.selectById(bOrder.getSupplierId());
        if (supplier == null) {
            throw new IllegalStateException("Supplier 不存在 supplierId=" + bOrder.getSupplierId());
        }
        SupplierOfferVersionDO offerVersion = offerVersionMapper.selectById(bOrder.getOfferVersionId());

        PublishedConnectorConfiguration connectorConfig = connectorResolver.resolve(bOrder.getConnectorVersionId());
        String traceId = context.taskId() != null ? String.valueOf(context.taskId()) : operationNo;

        ResolvedTarget target;
        try {
            target = resolveRechargeAccount(operation, request, order);
        } catch (TargetAccountUnavailableException e) {
            log.warn("[履约执行] 目标账号不可用 operationNo={}, reason={}, cause={}", operationNo, e.getReasonCode(), safeFailureSummary(e));
            try {
                if (frequencyLifecycle.requiresFrequency(operationNo, null)) { throw e; }
            } catch (RechargeFrequencyLifecycle.MissingFrequencyEntryException missing) {
                supplierOrderLifecycle.transition(operationNo, OperationOutcome.builder().outcomeClass(OperationOutcomeClass.MANUAL_REQUIRED)
                        .externalErrorCode(FREQUENCY_MIGRATION_MISSING).build(), traceId);
                return FREQUENCY_MIGRATION_MISSING;
            }
            supplierOrderLifecycle.transition(operation.getOperationNo(), OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.MANUAL_REQUIRED)
                    .externalErrorCode(e.getReasonCode())
                    .externalErrorMessage(e.getMessage())
                    .build(), traceId);
            return OperationOutcomeClass.MANUAL_REQUIRED.name();
        }

        RechargeFrequencyExecutionPermit permit = null;
        try {
            if (frequencyLifecycle.requiresFrequency(operationNo, target.accountType())) {
                permit = frequencyLifecycle.claim(operationNo);
                if (permit == null) { return "FREQUENCY_WAITING"; }
            } else if (!supplierOrderLifecycle.claimForExecution(operationNo, target.accountType())) {
                return "CLAIM_SKIPPED";
            }
        } catch (RechargeFrequencyLifecycle.MissingFrequencyEntryException exception) {
            log.warn("[履约执行] 缺少可信冻结队列，禁止发送 operationNo={}", operationNo);
            supplierOrderLifecycle.transition(operationNo, OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.MANUAL_REQUIRED)
                    .externalErrorCode(FREQUENCY_MIGRATION_MISSING).build(), traceId);
            return FREQUENCY_MIGRATION_MISSING;
        }
        OperationTicket ticket;
        String preparationFailureCode = CREDENTIAL_UNAVAILABLE;
        try {
            String secretKey = ticketFactory.resolveCredential(connectorConfig);
            preparationFailureCode = TICKET_PREPARATION_FAILED;
            ticket = ticketFactory.create(operation, bOrder, request, supplier, offerVersion, connectorConfig,
                    FulfillmentOperationKind.valueOf(operation.getOperationKind()), target.accountValue(), secretKey, traceId);
        } catch (Exception exception) {
            log.debug("[履约执行] 本地发送准备失败 operationNo={}, reason={}, cause={}",
                    operationNo, preparationFailureCode, safeFailureSummary(exception));
            if (permit != null) {
                // 凭证与全部ticket装配共用该边界；原资格仍PREPARED才可撤销，失败则保守保留占用。
                // 故障交给Timer有界重试，不计实际外呼；SEND_POSSIBLE后的异常不能进入本分支。
                var preparationFailure = new IllegalStateException(preparationFailureCode, safeCause(exception));
                try {
                    frequencyLifecycle.revokePreparedForLocalFailure(permit);
                } catch (Exception revocationFailure) {
                    // 撤销存储异常仍向Timer传播并保留安全双因果；事务自行回滚，不让它覆盖原准备故障。
                    // 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#42-认领。
                    preparationFailure.addSuppressed(safeCause(revocationFailure));
                }
                throw preparationFailure;
            }
            supplierOrderLifecycle.transition(operationNo, OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.MANUAL_REQUIRED).externalErrorCode(preparationFailureCode).build(), traceId);
            return OperationOutcomeClass.MANUAL_REQUIRED.name();
        }

        if (permit != null && !frequencyLifecycle.markSendPossible(permit)) {
            return "SEND_PERMIT_SKIPPED";
        }
        log.info("[履约执行] 调用适配器 adapterType={}, supplierCode={}, bOrderNo={}, operationNo={}",
                connectorConfig.adapterType(), supplier.getSupplierCode(), bOrder.getBOrderNo(), operationNo);

        OperationOutcome outcome;
        try {
            outcome = operationExecutorPort.execute(ticket);
        } catch (Exception e) {
            log.warn("[履约执行] 适配器调用异常 operationNo={}, traceId={}, cause={}, 转换为 RESULT_UNKNOWN",
                    operationNo, traceId, safeFailureSummary(e));
            outcome = OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.RESULT_UNKNOWN)
                    .externalErrorCode(ADAPTER_EXECUTION_FAILED).externalErrorMessage(ADAPTER_EXECUTION_FAILED)
                    .build();
        }

        supplierOrderLifecycle.confirmTrustedOutcome(operation.getOperationNo(), outcome, traceId, permit);

        log.info("[履约执行] 完成 operationNo={}, outcomeClass={}", operationNo, outcome.outcomeClass());
        return outcome.outcomeClass().name();
    }

    @Override
    public int maxAttempts() {
        return 3;
    }

    private static boolean isTerminal(Short status) {
        return status != null
                && (status == SupplierOrderOperationStatus.SUCCEEDED.getCode()
                || status == SupplierOrderOperationStatus.FAILED.getCode()
                || status == SupplierOrderOperationStatus.MANUAL.getCode());
    }

    private ResolvedTarget resolveRechargeAccount(FulfillmentOperationDO operation,
                                          FulfillmentRequestDO request,
                                          SupplyChainOrderDO order) {
        if (!FulfillmentOperationKind.FULFILL.name().equals(operation.getOperationKind())) { return new ResolvedTarget(null, null); }
        boolean sendStep = AdapterStepCode.SEND.name().equals(operation.getAdapterStepCode());
        byte[] ciphertext = request.getTargetCiphertext();
        if (ciphertext == null || ciphertext.length == 0) {
            if (sendStep) {
                throw new TargetAccountUnavailableException(TARGET_ACCOUNT_MISSING,
                        "直充缺少目标账号密文");
            }
            return new ResolvedTarget(null, null);
        }
        try {
            byte[] associatedData = (request.getBuyerId() + "|" + order.getBuyerOrderNo() + "|"
                    + request.getTargetSchemaCode() + "|" + request.getTargetSchemaVersion())
                    .getBytes(StandardCharsets.UTF_8);
            byte[] decryptedBytes = envelopeCrypto.decrypt(
                    request.getTargetCiphertext(), request.getTargetKeyVersion(), associatedData);
            JsonNode targetJson = objectMapper.readTree(decryptedBytes);
            JsonNode valuesNode = targetJson.path("values");
            String account = null;
            if (valuesNode.isObject()) {
                if (valuesNode.hasNonNull("accountValue")) {
                    account = valuesNode.get("accountValue").asText();
                } else if (valuesNode.hasNonNull("account")) {
                    account = valuesNode.get("account").asText();
                } else if (valuesNode.hasNonNull("mobile")) {
                    account = valuesNode.get("mobile").asText();
                }
            }
            if (sendStep && (account == null || account.isBlank())) {
                throw new TargetAccountUnavailableException(TARGET_ACCOUNT_UNAVAILABLE,
                        "目标密文未包含可充值账号");
            }
            return new ResolvedTarget(valuesNode.path("accountType").asText(null), account);
        } catch (TargetAccountUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new TargetAccountUnavailableException(TARGET_DECRYPT_FAILED,
                    "解密充值目标账号失败", safeCause(e));
        }
    }

    /** 安全因果只保留异常类型和首个代码位置，不包含任意异常报文、目标JSON或凭证。 */
    private static String safeFailureSummary(Throwable exception) {
        StringBuilder summary = new StringBuilder(); int depth = 0;
        for (Throwable cause = exception; cause != null && depth++ < 8; cause = cause.getCause()) {
            if (!summary.isEmpty()) { summary.append(" <- "); }
            summary.append(cause.getClass().getName());
            if (cause.getStackTrace().length > 0) { summary.append('@').append(cause.getStackTrace()[0]); }
        }
        return summary.toString();
    }

    /** 传播装配故障到Timer时保留安全因果与原代码位置，防通用重试日志打印供应商任意原始报文。 */
    private static Throwable safeCause(Throwable cause) {
        var safe = new IllegalStateException(safeFailureSummary(cause));
        safe.setStackTrace(cause.getStackTrace());
        return safe;
    }

    /** 来自已认证目标密文的实际类型与值，禁止toString泄露账号。 */
    private record ResolvedTarget(String accountType, String accountValue) {
        @Override public String toString() { return "ResolvedTarget[type=" + accountType + "]"; }
    }

    static final class TargetAccountUnavailableException extends RuntimeException {
        private final String reasonCode;

        TargetAccountUnavailableException(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }

        TargetAccountUnavailableException(String reasonCode, String message, Throwable cause) {
            super(message, cause);
            this.reasonCode = reasonCode;
        }

        String getReasonCode() {
            return reasonCode;
        }
    }
}
