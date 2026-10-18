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
import org.springframework.transaction.annotation.Isolation;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_ALREADY_HAS_PUBLISHED_CONNECTOR;

import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingDependencyChangedEvent;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.context.ApplicationEventPublisher;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class ConnectorPublicationService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String OUTBOX_TARGET = "supplier-config-cache-invalidation";

    private final SupplierMapper supplierMapper;
    private final SupplierOfferMapper offerMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorVersionMapper versionMapper;
    private final StatusMappingDefinitionMapper statusMappingMapper;
    private final SupplierErrorCodeMapMapper errorCodeMapMapper;
    private final SupplierConfigPublishRecordMapper publishRecordMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final OutboxEventRepository outboxRepository;
    private final com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSetMapper credentialSetMapper;
    private final com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry metadataRegistry;
    private final ConnectorCredentialBindingValidator credentialBindingValidator;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public PublishResult publish(PublishConnectorCommand command) {
        SupplierLifecycleTransactions.requireReadCommitted();
        log.info("[publishConnector][连接器发布请求] connectorNo={}, connectorVersionId={}, expectedConnectorVersion={}, operatorId={}, traceId={}",
                command.connectorNo(),command.connectorVersionId(),command.expectedConnectorVersion(),command.operatorId(),command.traceId());
        // 只读定位集后按集→连接器→版本→项锁序重读；追加项同样集→版本，避免锁序倒置。
        // 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#123-第二切片显式凭据集绑定与运行时配置。
        SupplierConnectorVersionDO located = versionMapper.selectById(command.connectorVersionId());
        if (located == null) throw new IllegalArgumentException("Connector version does not exist");
        SupplierConnectorDO locatedConnector = connectorMapper.selectByNo(command.connectorNo());
        if (locatedConnector == null || supplierMapper.selectByIdForUpdate(locatedConnector.getSupplierId()) == null) {
            throw new IllegalArgumentException("Connector supplier does not exist");
        }
        Long setId = located.getCredentialSetId();
        if (setId != null && credentialSetMapper.selectByIdForUpdate(setId) == null) {
            throw new IllegalArgumentException("Credential set does not exist");
        }
        SupplierConnectorDO connector = connectorMapper.selectByNoForUpdate(command.connectorNo());
        SupplierConnectorVersionDO version = versionMapper.selectByIdForUpdate(command.connectorVersionId());
        if (connector == null || version == null || !Objects.equals(connector.getSupplierId(), locatedConnector.getSupplierId())
                || !connector.getId().equals(version.getConnectorId())) {
            throw new IllegalArgumentException("Connector or connector version does not exist");
        }
        if (!java.util.Objects.equals(setId, version.getCredentialSetId())) {
            throw new IllegalStateException("Connector credential association changed during publication");
        }
        var metadata = metadataRegistry.require(connector.getAdapterType());
        credentialBindingValidator.verify(metadata, connector.getSupplierId(), version, true);
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
        // 发布前重核适配器协议合同，不能仅信任早先的验证结果；
        // 设计见 docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#22-发布合同。
        metadata.validateMappings(statuses, errorCodeMapMapper.selectByConnectorVersionId(version.getId()));
        if (metadata instanceof com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata huasheng) {
            credentialBindingValidator.extraParameters(huasheng, version.getExtraConfig());
        }
        // D1 供应商级唯一守卫：同供应商已存在其它已发布连接器（current_version_id 非空的其它行）时，
        // 先于 DB 唯一键 uk_sc_connector_supplier_published 抛清晰业务异常，避免裸 DuplicateKeyException。
        // 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#55-d1-published_flag-唯一键变更集-056。
        if (connectorMapper.existsOtherPublishedBySupplier(connector.getSupplierId(), connector.getId())) {
            throw exception(SUPPLIER_ALREADY_HAS_PUBLISHED_CONNECTOR);
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
        notifyPricingDependencyChanged(connector.getSupplierId());
        return new PublishResult(connector.getConnectorNo(), version.getId(), version.getVersionNo(),
                nextAggregateVersion);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void retire(RetireConnectorVersionCommand command) {
        SupplierLifecycleTransactions.requireReadCommitted();
        SupplierConnectorDO locatedConnector = connectorMapper.selectByNo(command.connectorNo());
        if (locatedConnector == null || supplierMapper.selectByIdForUpdate(locatedConnector.getSupplierId()) == null) {
            throw new IllegalArgumentException("Connector supplier does not exist");
        }
        SupplierConnectorDO connector = connectorMapper.selectByNoForUpdate(command.connectorNo());
        SupplierConnectorVersionDO version = versionMapper.selectByIdForUpdate(command.connectorVersionId());
        if (connector == null || version == null || !Objects.equals(connector.getSupplierId(), locatedConnector.getSupplierId())
                || !connector.getId().equals(version.getConnectorId())
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
        notifyPricingDependencyChanged(connector.getSupplierId());
    }

    private void notifyPricingDependencyChanged(Long supplierId) {
        if (supplierId == null) {
            return;
        }
        offerMapper.selectPublishedBySupplier(supplierId).stream()
                .map(SupplierOfferDO::getPlatformSkuId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(skuId -> eventPublisher.publishEvent(new PricingDependencyChangedEvent(skuId)));
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
