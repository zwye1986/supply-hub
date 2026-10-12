package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SensitiveAccessAuditDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SensitiveAccessAuditMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.StandardProductContext;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.List;

/**
 * 项目应用的整批不可变审计必须独立提交，业务调用方回滚不会撤销拒绝或解密记录。 设计见
 * docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#5-审计事务与数据库增量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectStandardAssetAuditService {
    private final SensitiveAccessAuditMapper mapper;
    private final BusinessNoGenerator numbers;
    private final SupplyChainDatabaseClock clock;

    /** Spring代理提交整批事件后调用方才可返回资产明文；失败继续向外抛出。 */
    @Transactional(
            transactionManager = "supplyChainTransactionManager",
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void append(
            StandardProductContext context,
            String orderNo,
            String traceId,
            List<ProjectStandardAssetAuditEvent> events) {
        log.debug(
                "[项目资产审计] projectId={}, orderNo={}, traceId={}, eventCount={}",
                context.projectId(),
                orderNo,
                traceId,
                events.size());
        var accessedAt = clock.now();
        for (var event : events)
            mapper.insert(
                    SensitiveAccessAuditDO.builder()
                            .auditNo(numbers.next(BusinessNoPrefixes.AUD))
                            .accessType(event.accessType().name())
                            .principalType(ProjectStandardAssetAuditEvent.PRINCIPAL_TYPE)
                            .principalId(context.appId())
                            .buyerId(context.buyerId())
                            .projectId(context.projectId())
                            .appKeyVersion(context.keyVersion())
                            .orderNo(orderNo)
                            .resourceType(
                                    event.accessType() == SensitiveAccessAuditAccessType.ASSET_QUERY
                                            ? ProjectStandardAssetAuditEvent.REQUEST_RESOURCE
                                            : ProjectStandardAssetAuditEvent.ASSET_RESOURCE)
                            .resourceId(event.resourceId() == null ? 0L : event.resourceId())
                            .scopeCode(context.operationCode())
                            .resultStatus(event.result().getCode())
                            .reasonCode(event.reason().name())
                            .traceId(traceId)
                            .accessedAt(accessedAt)
                            .build());
    }
}
