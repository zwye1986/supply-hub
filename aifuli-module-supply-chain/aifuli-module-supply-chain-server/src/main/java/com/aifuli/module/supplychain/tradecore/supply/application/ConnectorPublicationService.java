package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.supplieradapter.metadata.StatusMappingDefinitionDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.StatusMappingDefinitionMapper;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierErrorCodeMapDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierErrorCodeMapMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConfigPublishRecordDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConfigPublishRecordMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConnectorPublicationService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String OUTBOX_TARGET = "supplier-config-cache-invalidation";

    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorVersionMapper versionMapper;
    private final StatusMappingDefinitionMapper statusMappingMapper;
    private final SupplierErrorCodeMapMapper errorCodeMapMapper;
    private final SupplierConfigPublishRecordMapper publishRecordMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final OutboxEventRepository outboxRepository;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public PublishResult publish(PublishConnectorCommand command) {
        SupplierConnectorDO connector = connectorMapper.selectByNoForUpdate(command.connectorNo());
        SupplierConnectorVersionDO version = versionMapper.selectByIdForUpdate(command.connectorVersionId());
        if (connector == null || version == null || !connector.getId().equals(version.getConnectorId())) {
            throw new IllegalArgumentException("Connector or connector version does not exist");
        }
        LocalDateTime now = now();
        if (connector.getVersion() != command.expectedConnectorVersion()
                || version.getStatus() != ConnectorVersionStatus.VALIDATED.getCode()
                || version.getValidationStatus() != 10 || version.getValidationExpiresAt() == null
                || !version.getValidationExpiresAt().isAfter(now)) {
            throw new IllegalStateException("Connector version is not valid for publication");
        }
        List<StatusMappingDefinitionDO> statuses = statusMappingMapper.selectByConnectorVersionId(version.getId());
        if (statuses.isEmpty() || statuses.stream().anyMatch(mapping -> mapping.getStatus() != 0)) {
            throw new IllegalStateException("Connector status mappings are not publishable");
        }

        version.setStatus(ConnectorVersionStatus.PUBLISHED.getCode());
        version.setPublishedAt(now);
        version.setUpdatedBy(command.operatorId());
        version.setUpdatedAt(now);
        if (versionMapper.updateById(version) != 1) {
            throw new IllegalStateException("Connector version publication lost optimistic lock");
        }
        publishMappings(version.getId(), now);

        long nextAggregateVersion = connector.getVersion() + 1;
        int connectorUpdated = connectorMapper.update(null, new LambdaUpdateWrapper<SupplierConnectorDO>()
                .eq(SupplierConnectorDO::getId, connector.getId())
                .eq(SupplierConnectorDO::getVersion, connector.getVersion())
                .set(SupplierConnectorDO::getCurrentVersionId, version.getId())
                .set(SupplierConnectorDO::getStatus, (short) 10)
                .set(SupplierConnectorDO::getUpdatedBy, command.operatorId())
                .set(SupplierConnectorDO::getUpdatedAt, now)
                .set(SupplierConnectorDO::getVersion, nextAggregateVersion));
        if (connectorUpdated != 1) {
            throw new IllegalStateException("Connector publication lost optimistic lock");
        }
        appendAudit(connector, version, "PUBLISH", command.operatorId(), command.reason(), command.traceId(), now);
        outboxRepository.appendLocal("SUPPLIER_CONNECTOR", connector.getConnectorNo(), nextAggregateVersion,
                "SUPPLIER_CONFIG_PUBLISHED", OUTBOX_TARGET,
                new ConnectorPublishedPayload(connector.getConnectorNo(), version.getId(), version.getVersionNo()),
                command.traceId(), command.correlationId(), command.causationId(), now);
        return new PublishResult(connector.getConnectorNo(), version.getId(), version.getVersionNo(),
                nextAggregateVersion);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void retire(RetireConnectorVersionCommand command) {
        SupplierConnectorDO connector = connectorMapper.selectByNoForUpdate(command.connectorNo());
        SupplierConnectorVersionDO version = versionMapper.selectByIdForUpdate(command.connectorVersionId());
        if (connector == null || version == null || !connector.getId().equals(version.getConnectorId())
                || version.getStatus() != ConnectorVersionStatus.PUBLISHED.getCode()) {
            throw new IllegalStateException("Only a published connector version can be retired");
        }
        LocalDateTime now = now();
        version.setStatus(ConnectorVersionStatus.RETIRED.getCode());
        version.setUpdatedBy(command.operatorId());
        version.setUpdatedAt(now);
        if (versionMapper.updateById(version) != 1) {
            throw new IllegalStateException("Connector version retirement lost optimistic lock");
        }
        statusMappingMapper.update(null, new LambdaUpdateWrapper<StatusMappingDefinitionDO>()
                .eq(StatusMappingDefinitionDO::getConnectorVersionId, version.getId())
                .eq(StatusMappingDefinitionDO::getStatus, (short) 10)
                .set(StatusMappingDefinitionDO::getStatus, (short) 90));
        errorCodeMapMapper.update(null, new LambdaUpdateWrapper<SupplierErrorCodeMapDO>()
                .eq(SupplierErrorCodeMapDO::getConnectorVersionId, version.getId())
                .eq(SupplierErrorCodeMapDO::getStatus, (short) 10)
                .set(SupplierErrorCodeMapDO::getStatus, (short) 90));
        long nextAggregateVersion = connector.getVersion() + 1;
        LambdaUpdateWrapper<SupplierConnectorDO> connectorUpdate = new LambdaUpdateWrapper<SupplierConnectorDO>()
                .eq(SupplierConnectorDO::getId, connector.getId())
                .eq(SupplierConnectorDO::getVersion, connector.getVersion())
                .set(SupplierConnectorDO::getUpdatedBy, command.operatorId())
                .set(SupplierConnectorDO::getUpdatedAt, now)
                .set(SupplierConnectorDO::getVersion, nextAggregateVersion);
        if (version.getId().equals(connector.getCurrentVersionId())) {
            connectorUpdate.set(SupplierConnectorDO::getCurrentVersionId, null)
                    .set(SupplierConnectorDO::getStatus, (short) 20);
        }
        if (connectorMapper.update(null, connectorUpdate) != 1) {
            throw new IllegalStateException("Connector retirement lost optimistic lock");
        }
        appendAudit(connector, version, "RETIRE", command.operatorId(), command.reason(), command.traceId(), now);
        outboxRepository.appendLocal("SUPPLIER_CONNECTOR", connector.getConnectorNo(), nextAggregateVersion,
                "SUPPLIER_CONFIG_RETIRED", OUTBOX_TARGET,
                new ConnectorPublishedPayload(connector.getConnectorNo(), version.getId(), version.getVersionNo()),
                command.traceId(), command.correlationId(), command.causationId(), now);
    }

    private void publishMappings(Long connectorVersionId, LocalDateTime now) {
        statusMappingMapper.update(null, new LambdaUpdateWrapper<StatusMappingDefinitionDO>()
                .eq(StatusMappingDefinitionDO::getConnectorVersionId, connectorVersionId)
                .eq(StatusMappingDefinitionDO::getStatus, (short) 0)
                .set(StatusMappingDefinitionDO::getStatus, (short) 10)
                .set(StatusMappingDefinitionDO::getPublishedAt, now));
        errorCodeMapMapper.update(null, new LambdaUpdateWrapper<SupplierErrorCodeMapDO>()
                .eq(SupplierErrorCodeMapDO::getConnectorVersionId, connectorVersionId)
                .eq(SupplierErrorCodeMapDO::getStatus, (short) 0)
                .set(SupplierErrorCodeMapDO::getStatus, (short) 10)
                .set(SupplierErrorCodeMapDO::getPublishedAt, now));
    }

    private void appendAudit(SupplierConnectorDO connector, SupplierConnectorVersionDO version,
                             String action, Long operatorId, String reason, String traceId,
                             LocalDateTime now) {
        publishRecordMapper.insert(SupplierConfigPublishRecordDO.builder()
                .recordNo(businessNoGenerator.next("CPR_")).connectorId(connector.getId())
                .targetVersionId(version.getId()).actionType(action).operatorId(operatorId)
                .reason(reason).configDigest(version.getConfigDigest()).traceId(traceId)
                .createdAt(now).build());
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    public record PublishConnectorCommand(String connectorNo, Long connectorVersionId,
                                          long expectedConnectorVersion, Long operatorId,
                                          String reason, String traceId,
                                          String correlationId, String causationId) {
    }

    public record RetireConnectorVersionCommand(String connectorNo, Long connectorVersionId,
                                                Long operatorId, String reason, String traceId,
                                                String correlationId, String causationId) {
    }

    public record PublishResult(String connectorNo, Long connectorVersionId,
                                int connectorVersionNo, long aggregateVersion) {
    }

    public record ConnectorPublishedPayload(String connectorNo, Long connectorVersionId,
                                            int connectorVersionNo) {
    }
}
