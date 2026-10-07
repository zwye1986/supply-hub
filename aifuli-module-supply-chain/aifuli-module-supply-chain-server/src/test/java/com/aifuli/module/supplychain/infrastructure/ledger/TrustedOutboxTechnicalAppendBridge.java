package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.OutboxAppendIdentityMapper;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventMapper;

import javax.sql.DataSource;

/**
 * 仅 src/test 的同模块技术追加反射桥（R3-03 用户裁定 B，rev2）：复用既有
 * {@link TrustedOutboxAppendCoreBridge} 的 newCore／newCommand／append（N16 最小性），
 * 以固定技术协议（targetType=LOCAL、comparisonProtocol=LEGACY_JSON_V1、
 * correlationId/causationId=null）构造 {@code VerifiedAppendCommand} 并驱动同一真实 main
 * 私有底座 {@code TrustedOutboxAppendCore.append}——不复制算法、不进入 main 资源／jar。
 * JDK21 同 unnamed module，无需 add-opens。
 */
final class TrustedOutboxTechnicalAppendBridge {

    private TrustedOutboxTechnicalAppendBridge() {
    }

    /** 构造真实 main 私有底座（复用既有桥）。 */
    static Object newCore(OutboxEventMapper sourceMapper, BusinessNoGenerator businessNoGenerator,
                          OutboxAppendIdentityMapper ledgerMapper, DataSource supplyChainDataSource) {
        return TrustedOutboxAppendCoreBridge.newCore(sourceMapper, businessNoGenerator, ledgerMapper,
                supplyChainDataSource);
    }

    /**
     * 按冻结冻结计划构造技术追加命令：targetType=LOCAL、comparisonProtocol=LEGACY_JSON_V1、
     * correlationId/causationId=null；payloadVersion 与完整载荷直接来自生产冻结计划。
     */
    static Object technicalCommand(LocalLedgerIntentCollector.OutboxGroupPlan plan) {
        return TrustedOutboxAppendCoreBridge.newCommand(
                plan.aggregateType(), plan.aggregateId(), plan.aggregateVersion(), plan.eventType(),
                plan.targetType(), plan.targetName(), plan.payloadVersion(), plan.comparisonProtocol(),
                plan.payloadJson(), plan.traceId(), null, null, plan.occurredAt());
    }

    /** 驱动真实底座执行（复用既有桥）。 */
    static String append(Object core, Object command) {
        return TrustedOutboxAppendCoreBridge.append(core, command);
    }
}
