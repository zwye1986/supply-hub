package com.aifuli.module.supplychain.border.fulfillment.application.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.SingleStepSupplierFact;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证后单步回调的内部事务外入口；不提供HTTP认证或外呼资格。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#77-t04回调公共核心切片2026-10-18。
 */
@Service
@RequiredArgsConstructor
@Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.NOT_SUPPORTED)
public class SingleStepCallbackApplicationService {
    private static final int MAX_CLAIM_ATTEMPTS = 3;
    private final FulfillmentOperationMapper operations;
    private final SingleStepCheckpointCodec codec;
    private final PublishedConnectorConfigurationResolver connectors;
    private final SingleStepCallbackInbox inbox;
    private final SingleStepFulfillmentLifecycle lifecycle;

    /** 原冻结配置核验在锁外；成功保护先提交，只有Inbox插入争用才重试整个结果事务。设计同§7.7。 */
    public void handle(SingleStepSupplierFact fact, String traceId) {
        if (fact.source() != SingleStepSupplierFact.Source.CALLBACK
                || fact.verification() != SingleStepSupplierFact.Verification.VERIFIED
                || fact.observation() == SingleStepSupplierFact.Observation.NOT_FOUND
                || fact.reportedSettlementAmount() != null && fact.reportedSettlementAmount() < 0
                || fact.zeroDeliveryConfirmed() && fact.observation() != SingleStepSupplierFact.Observation.FAILURE) {
            throw new IllegalArgumentException("单步骤回调认证事实无效");
        }
        var op = operations.selectOne(FulfillmentOperationDO::getOperationNo, fact.binding().operationNo());
        if (op == null || op.getBusinessSendPossibleAt() == null || op.getExecutionGeneration() <= 0) {
            throw new IllegalArgumentException("单步骤原单尚未取得发送资格");
        }
        var runtime = codec.decryptRuntime(new SingleStepCheckpointCodec.Stored(op.getCheckpointSchemaVersion(),
                op.getCheckpointKeyVersion(), op.getCheckpointCiphertext()), fact.binding());
        var frozen = runtime.frozen();
        if (!frozen.business().billno().equals(fact.billno()) || frozen.business().quantity() != fact.quantity()
                || !frozen.business().protectedTargetReference().targetDigest().equals(fact.targetDigest())) {
            throw new IllegalArgumentException("单步骤回调原单内容不符");
        }
        var config = connectors.resolveForExistingOrder(frozen);
        var prepared = inbox.prepare(fact, config.connector().merchantAccount());
        lifecycle.observeCallbackSuccess(prepared.fact(), traceId);
        for (int attempt = 1; ; attempt++) {
            try {
                lifecycle.applyCallback(prepared, traceId);
                return;
            } catch (SingleStepCallbackInbox.ClaimRace race) {
                if (attempt >= MAX_CLAIM_ATTEMPTS) throw race;
            }
        }
    }
}
