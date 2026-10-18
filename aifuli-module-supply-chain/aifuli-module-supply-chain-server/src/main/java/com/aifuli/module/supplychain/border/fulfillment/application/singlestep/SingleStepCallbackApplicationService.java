package com.aifuli.module.supplychain.border.fulfillment.application.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationTicketFactory;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.renyue.RenyueCallbackParser;
import com.aifuli.module.supplychain.supplieradapter.renyue.RenyueSupplierAdapter;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.HexFormat;
import static com.aifuli.module.supplychain.border.fulfillment.domain.SingleStepCallbackException.Code.*;

/**
 * 单步回调事务外编排：仁跃原始报文经冻结原单材料认证，再委派认证后事实的Inbox/资金核心。
 * 不认领外呼、不占查询预算；成功保护与结果分别提交，HTTP边界只能在本入口正常返回后ACK。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#513-临时本地认证与http闭环合同2026-10-18用户批准
 * 与同文档§7.7「T04回调公共核心切片」。
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
    private final BOrderMapper bOrders;
    private final FulfillmentRequestMapper requests;
    private final SupplyChainOrderMapper orders;
    private final SupplierMapper suppliers;
    private final FulfillmentOperationTicketFactory tickets;
    private final SupplyChainDatabaseClock clock;

    /**
     * 仁跃原始回调事务外入口：路径只作有限原单定位，不是认证凭证；禁止全库扫描或猜其它适配器。
     * 先核已提交发送marker及原冻结材料，再认证双号/目标；不认领外呼、无补发且不占查询预算。
     * 所有材料在聚合锁外准备，用毕关闭；只有内部结果事务提交后正常返回。
     * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#513-临时本地认证与http闭环合同2026-10-18用户批准。
     */
    public void handleRenyue(Long connectorVersionId, java.io.InputStream body, String traceId) {
        if (connectorVersionId == null || connectorVersionId <= 0) throw new SingleStepCallbackException(INVALID_CALLBACK);
        var fields = RenyueCallbackParser.readForm(body);
        var candidates = bOrders.selectList(new LambdaQueryWrapper<BOrderDO>()
                .eq(BOrderDO::getConnectorVersionId, connectorVersionId).eq(BOrderDO::getBillno, fields.get("StoreNo")).last("LIMIT 2"));
        if (candidates.size() != 1) throw new SingleStepCallbackException(CALLBACK_NOT_FOUND);
        var bo = candidates.getFirst();
        if (!RenyueSupplierAdapter.TYPE.equals(connectors.singleStepMetadata(connectorVersionId).adapterType())) {
            throw new SingleStepCallbackException(CALLBACK_NOT_FOUND);
        }
        var original = operations.selectList(new LambdaQueryWrapper<FulfillmentOperationDO>()
                .eq(FulfillmentOperationDO::getBOrderId, bo.getId())
                .eq(FulfillmentOperationDO::getOperationKind, FulfillmentOperationKind.FULFILL.name())
                .eq(FulfillmentOperationDO::getAdapterStepCode, AdapterStepCode.SEND.name()).last("LIMIT 2"));
        if (original.size() != 1) throw new SingleStepCallbackException(CALLBACK_NOT_FOUND);
        var op = original.getFirst();
        if (op.getBusinessSendPossibleAt() == null || op.getExecutionGeneration() == null || op.getExecutionGeneration() <= 0) {
            throw new SingleStepCallbackException(CALLBACK_PROCESSING_FAILED);
        }
        try (var ticket = tickets.createForSingleStep(op, bo, requests.selectById(bo.getFulfillmentRequestId()),
                orders.selectById(bo.getOrderId()), suppliers.selectById(bo.getSupplierId()), FulfillmentOperationKind.FULFILL, traceId)) {
            var binding = new SingleStepCheckpoint.Binding(op.getOperationNo(), bo.getId(), ticket.adapterType(), AdapterStepCode.SEND,
                    bo.getSupplierId(), connectorVersionId, bo.getOfferVersionId(), HexFormat.of().formatHex(op.getConfigDigest()));
            var frozen = codec.decrypt(new SingleStepCheckpointCodec.Stored(op.getCheckpointSchemaVersion(),
                    op.getCheckpointKeyVersion(), op.getCheckpointCiphertext()), binding);
            handle(RenyueCallbackParser.parse(fields, ticket, frozen, clock.now()), traceId);
        }
    }

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
