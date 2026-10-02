package com.aifuli.module.supplychain.border.fulfillment.application;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
/** 仅已配置QUERY_ORDER能力允许主动查单；冻结原身份且不读取/解密目标、不新建操作或资金预占。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#6-timer-与补偿。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RechargeFrequencyQueryService {
    private final PublishedConnectorConfigurationResolver connectors;
    private final SupplierMapper suppliers;
    private final SupplierOfferVersionMapper offers;
    private final FulfillmentOperationTicketFactory tickets;
    private final OperationExecutorPort executor;
    private final SupplierOrderLifecycle lifecycle;
    @Transactional(transactionManager="supplyChainTransactionManager", propagation=Propagation.NOT_SUPPORTED)
    public void query(RechargeFrequencyRecoveryService.QueryWork work, String traceId) {
        var locked = work.locked(); var operation = locked.operation(); var bo = locked.bOrder();
        long start = System.nanoTime();
        try {
            var config = connectors.resolve(bo.getConnectorVersionId());
            if (!config.capabilities().contains(Capability.QUERY_ORDER) || !executor.supports(config.adapterType())) { return; }
            var ticket = tickets.create(operation, bo, locked.request(), suppliers.selectById(bo.getSupplierId()),
                    offers.selectById(bo.getOfferVersionId()), config, FulfillmentOperationKind.QUERY_ORDER,
                    null, tickets.resolveCredential(config), traceId);
            log.debug("[直充限频查单] operationNo={}, bOrderNo={}, connectorVersionId={}, generation={}",
                    operation.getOperationNo(), bo.getBOrderNo(), bo.getConnectorVersionId(),
                    work.permit() == null ? locked.queue().getOccupancyGeneration() : work.permit().generation());
            var outcome = executor.execute(ticket);
            log.debug("[直充限频查单] operationNo={}, outcome={}, httpStatus={}, durationMs={}", operation.getOperationNo(),
                    outcome.outcomeClass(), outcome.callDigest() == null ? null : outcome.callDigest().httpStatus(), (System.nanoTime()-start)/1_000_000L);
            // QUERY的NOT_FOUND/协议/鉴权故障不是可信终态，保留观察并继续占用。
            Integer httpStatus = outcome.callDigest() == null ? null : outcome.callDigest().httpStatus();
            boolean failedHttp = outcome.callDigest() != null && (httpStatus == null || httpStatus < 200 || httpStatus >= 300);
            if (failedHttp || (outcome.outcomeClass() != OperationOutcomeClass.SUCCEEDED && outcome.outcomeClass() != OperationOutcomeClass.REJECTED
                    && outcome.outcomeClass() != OperationOutcomeClass.ACCEPTED && outcome.outcomeClass() != OperationOutcomeClass.RESULT_UNKNOWN)) {
                outcome = OperationOutcome.builder().outcomeClass(OperationOutcomeClass.RESULT_UNKNOWN)
                        .rawObservation(outcome.rawObservation()).callDigest(outcome.callDigest()).build();
            }
            // 迁移查询无SEND permit，原风险归属及代次由结果入口重新加锁核验；迟到work只能留观察。
            // 普通查询仍携带原permit，保持原有资格校验。
            // 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
            lifecycle.confirmTrustedOutcome(operation.getOperationNo(), outcome, traceId, work.permit());
        } catch (Exception exception) {
            log.debug("[直充限频查单] operationNo={}, failureType={}, durationMs={}", operation.getOperationNo(),
                    exception.getClass().getName(), (System.nanoTime()-start)/1_000_000L);
            throw RechargeFrequencyRecoveryService.safeFailure(RechargeFrequencyRecoveryCodes.QUERY_FAILED, exception);
        }
    }
}
