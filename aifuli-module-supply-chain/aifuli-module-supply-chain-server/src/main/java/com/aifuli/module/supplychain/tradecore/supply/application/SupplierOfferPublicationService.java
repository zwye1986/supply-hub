package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.Capability;
import com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.InventorySourceDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierExternalSkuBindingDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.InventorySourceMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierExternalSkuBindingMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.InventorySourceStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SupplierOfferPublicationService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String OUTBOX_TARGET = "supply-availability-recalculation";
    private static final Set<String> COMPLETION_MODES = Set.of(
            "SYNCHRONOUS", "QUERY", "CALLBACK", "QUERY_OR_CALLBACK");
    private static final Set<String> IDEMPOTENCY_MODES = Set.of(
            "NONE", "DEDUP_ONLY", "REPLAYABLE_RESULT");
    private static final Set<String> UNKNOWN_POLICIES = Set.of(
            "QUERY_ORDER", "WAIT_CALLBACK", "REPLAY_SAME_KEY", "MANUAL");
    private static final Set<String> PARTIAL_MODES = Set.of("FORBIDDEN", "ALLOWED");
    private static final Set<String> MULTIPLICITIES = Set.of("NONE", "SINGLE", "MULTIPLE");

    private final SupplierMapper supplierMapper;
    private final PlatformSkuMapper skuMapper;
    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorVersionMapper connectorVersionMapper;
    private final SupplierConnectorCapabilityMapper capabilityMapper;
    private final InventorySourceMapper inventorySourceMapper;
    private final SupplierOfferMapper offerMapper;
    private final SupplierOfferVersionMapper offerVersionMapper;
    private final SupplierExternalSkuBindingMapper externalSkuBindingMapper;
    private final AdapterMetadataRegistry metadataRegistry;
    private final ClosedJsonSchemaValidator schemaValidator;
    private final BusinessNoGenerator businessNoGenerator;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createRemoteOnDemandInventory(CreateInventorySourceCommand command) {
        SupplierDO supplier = requireActiveSupplier(command.supplierCode());
        PlatformSkuDO sku = requireActiveSku(command.platformSkuCode());
        LocalDateTime now = now();
        InventorySourceDO source = InventorySourceDO.builder()
                .sourceNo(businessNoGenerator.next("INV_")).supplierId(supplier.getId())
                .platformSkuId(sku.getId()).sourceType("REMOTE_ON_DEMAND")
                .externalSourceCode(command.externalSourceCode()).ownerType("SUPPLIER")
                .status(InventorySourceStatus.DRAFT.getCode()).version(0L)
                .createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        inventorySourceMapper.insert(source);
        return source.getSourceNo();
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void activateInventorySource(String sourceNo, long expectedVersion, Long operatorId) {
        InventorySourceDO source = inventorySourceMapper.selectByNoForUpdate(sourceNo);
        if (source == null || source.getStatus() != InventorySourceStatus.DRAFT.getCode()
                || source.getVersion() != expectedVersion) {
            throw new IllegalStateException("Inventory source is not an expected draft");
        }
        source.setStatus(InventorySourceStatus.ACTIVE.getCode());
        source.setUpdatedBy(operatorId);
        source.setUpdatedAt(now());
        if (inventorySourceMapper.updateById(source) != 1) {
            throw new IllegalStateException("Inventory source activation lost optimistic lock");
        }
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createOffer(CreateOfferCommand command) {
        SupplierDO supplier = requireActiveSupplier(command.supplierCode());
        PlatformSkuDO sku = requireActiveSku(command.platformSkuCode());
        LocalDateTime now = now();
        SupplierOfferDO offer = SupplierOfferDO.builder()
                .offerNo(businessNoGenerator.next("OFR_")).offerCode(command.offerCode())
                .supplierId(supplier.getId()).platformSkuId(sku.getId())
                .status(OfferStatus.DRAFT.getCode()).version(0L)
                .createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        offerMapper.insert(offer);
        return offer.getOfferNo();
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public PublishResult publish(PublishOfferCommand command) {
        validateCommercialTerms(command);
        SupplierOfferDO offer = offerMapper.selectByNoForUpdate(command.offerNo());
        if (offer == null || offer.getVersion() != command.expectedOfferVersion()
                || offer.getStatus() == OfferStatus.RETIRED.getCode()) {
            throw new IllegalStateException("Supplier offer is not publishable at the expected version");
        }
        SupplierDO supplier = supplierMapper.selectById(offer.getSupplierId());
        PlatformSkuDO sku = skuMapper.selectById(offer.getPlatformSkuId());
        if (supplier == null || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()
                || sku == null || sku.getStatus() != CatalogEntityStatus.ACTIVE.getCode()) {
            throw new IllegalStateException("Offer supplier or platform SKU is not active");
        }

        SupplierConnectorVersionDO connectorVersion = connectorVersionMapper.selectById(command.connectorVersionId());
        SupplierConnectorDO connector = connectorVersion == null ? null
                : connectorMapper.selectById(connectorVersion.getConnectorId());
        if (connectorVersion == null || connector == null
                || connectorVersion.getStatus() != ConnectorVersionStatus.PUBLISHED.getCode()
                || !connectorVersion.getId().equals(connector.getCurrentVersionId())
                || !connector.getSupplierId().equals(offer.getSupplierId())) {
            throw new IllegalStateException("Offer must use the supplier's current published connector version");
        }

        InventorySourceDO source = inventorySourceMapper.selectByNo(command.inventorySourceNo());
        if (source == null || source.getStatus() != InventorySourceStatus.ACTIVE.getCode()
                || !source.getSupplierId().equals(offer.getSupplierId())
                || !source.getPlatformSkuId().equals(offer.getPlatformSkuId())
                || !"REMOTE_ON_DEMAND".equals(source.getSourceType())) {
            throw new IllegalStateException("Offer inventory source is not an active matching remote source");
        }
        Capability capability = Capability.valueOf(command.primaryCapabilityCode());
        requireCapability(connectorVersion.getId(), capability);
        requireCapabilityMatchesSku(sku, capability);
        requireExecutionCapabilities(connectorVersion.getId(), command);

        AdapterMetadata metadata = metadataRegistry.require(connector.getAdapterType());
        JsonNode config = parse(command.fulfillmentConfigJson());
        schemaValidator.validate(metadata.offerSchemaResource(), config);
        metadata.validateOfferConfig(command.fulfillmentConfigSchemaVersion(), config);
        String normalizedConfig = normalize(config);

        SupplierOfferVersionDO latest = offerVersionMapper.selectLatest(offer.getId());
        int versionNo = latest == null ? 1 : latest.getVersionNo() + 1;
        LocalDateTime now = now();
        SupplierOfferVersionDO version = SupplierOfferVersionDO.builder()
                .offerId(offer.getId()).versionNo(versionNo).externalSkuCode(command.externalSkuCode())
                .supplierSettlementUnitPrice(command.supplierSettlementUnitPrice()).currency("CNY")
                .connectorVersionId(connectorVersion.getId()).inventorySourceId(source.getId())
                .minQuantity(command.minQuantity()).maxQuantity(command.maxQuantity())
                .quantityStep(command.quantityStep()).primaryCapabilityCode(capability.name())
                .completionMode(command.completionMode())
                .createIdempotencyMode(command.createIdempotencyMode())
                .unknownResultPolicy(command.unknownResultPolicy())
                .partialDeliveryMode(command.partialDeliveryMode())
                .resultMultiplicity(command.resultMultiplicity())
                .fulfillmentConfigSchemaVersion(command.fulfillmentConfigSchemaVersion())
                .fulfillmentConfigJson(normalizedConfig).validFrom(command.validFrom())
                .validTo(command.validTo()).publishReason(command.publishReason())
                .publishedBy(command.operatorId()).publishedAt(now).createdAt(now).build();
        offerVersionMapper.insert(version);
        upsertExternalSkuBinding(offer, version, connector, now);

        long nextAggregateVersion = offer.getVersion() + 1;
        int updated = offerMapper.update(null, new LambdaUpdateWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getId, offer.getId())
                .eq(SupplierOfferDO::getVersion, offer.getVersion())
                .set(SupplierOfferDO::getCurrentVersionId, version.getId())
                .set(SupplierOfferDO::getStatus, OfferStatus.PUBLISHED.getCode())
                .set(SupplierOfferDO::getUpdatedBy, command.operatorId())
                .set(SupplierOfferDO::getUpdatedAt, now)
                .set(SupplierOfferDO::getVersion, nextAggregateVersion));
        if (updated != 1) {
            throw new IllegalStateException("Offer publication lost optimistic lock");
        }
        outboxRepository.appendLocal("SUPPLIER_OFFER", offer.getOfferNo(), nextAggregateVersion,
                "SUPPLIER_OFFER_PUBLISHED", OUTBOX_TARGET,
                new OfferPublishedPayload(offer.getOfferNo(), version.getId(), versionNo,
                        offer.getPlatformSkuId(), offer.getSupplierId()),
                command.traceId(), command.correlationId(), command.causationId(), now);
        return new PublishResult(offer.getOfferNo(), version.getId(), versionNo, nextAggregateVersion);
    }

    private void upsertExternalSkuBinding(SupplierOfferDO offer, SupplierOfferVersionDO version,
                                          SupplierConnectorDO connector, LocalDateTime now) {
        SupplierExternalSkuBindingDO conflict = externalSkuBindingMapper.selectByConnectorAndExternalSku(
                connector.getId(), version.getExternalSkuCode());
        if (conflict != null && !conflict.getOfferId().equals(offer.getId())) {
            throw new IllegalStateException("External SKU is already bound to another offer");
        }
        SupplierExternalSkuBindingDO binding = externalSkuBindingMapper.selectByOfferIdForUpdate(offer.getId());
        if (binding == null) {
            externalSkuBindingMapper.insert(SupplierExternalSkuBindingDO.builder()
                    .connectorId(connector.getId()).externalSkuCode(version.getExternalSkuCode())
                    .offerId(offer.getId()).offerVersionId(version.getId())
                    .version(0L).updatedAt(now).build());
            return;
        }
        binding.setConnectorId(connector.getId());
        binding.setExternalSkuCode(version.getExternalSkuCode());
        binding.setOfferVersionId(version.getId());
        binding.setUpdatedAt(now);
        if (externalSkuBindingMapper.updateById(binding) != 1) {
            throw new IllegalStateException("External SKU binding update lost optimistic lock");
        }
    }

    private void requireCapability(Long connectorVersionId, Capability capability) {
        boolean supported = capabilityMapper.selectByConnectorVersionId(connectorVersionId).stream()
                .anyMatch(row -> row.getEnabled() == 1 && row.getCapabilityCode().equals(capability.name()));
        if (!supported) {
            throw new IllegalStateException("Connector version does not enable capability: " + capability);
        }
    }

    private void requireExecutionCapabilities(Long connectorVersionId, PublishOfferCommand command) {
        if (Set.of("QUERY", "QUERY_OR_CALLBACK").contains(command.completionMode())
                || "QUERY_ORDER".equals(command.unknownResultPolicy())) {
            requireCapability(connectorVersionId, Capability.QUERY_ORDER);
        }
        if (Set.of("CALLBACK", "QUERY_OR_CALLBACK").contains(command.completionMode())
                || "WAIT_CALLBACK".equals(command.unknownResultPolicy())) {
            requireCapability(connectorVersionId, Capability.RECEIVE_CALLBACK);
        }
    }

    private static void requireCapabilityMatchesSku(PlatformSkuDO sku, Capability capability) {
        Capability required = switch (FulfillmentType.fromCode(sku.getFulfillmentType())) {
            case DIRECT_RECHARGE -> Capability.CREATE_RECHARGE;
            case VOUCHER_CODE, CARD_CODE_SECRET, VOUCHER_URL -> Capability.ISSUE_ASSET;
            case ACCOUNT_BOUND_BENEFIT -> Capability.BIND_BENEFIT;
        };
        if (required != capability) {
            throw new IllegalArgumentException("Primary capability does not match platform SKU fulfillment type");
        }
    }

    private static void validateCommercialTerms(PublishOfferCommand command) {
        if (command.supplierSettlementUnitPrice() < 0 || command.minQuantity() <= 0
                || command.maxQuantity() < command.minQuantity() || command.quantityStep() <= 0
                || command.validFrom() == null
                || (command.validTo() != null && !command.validTo().isAfter(command.validFrom()))
                || !COMPLETION_MODES.contains(command.completionMode())
                || !IDEMPOTENCY_MODES.contains(command.createIdempotencyMode())
                || !UNKNOWN_POLICIES.contains(command.unknownResultPolicy())
                || !PARTIAL_MODES.contains(command.partialDeliveryMode())
                || !MULTIPLICITIES.contains(command.resultMultiplicity())) {
            throw new IllegalArgumentException("Offer commercial terms or execution profile is invalid");
        }
        if ("REPLAY_SAME_KEY".equals(command.unknownResultPolicy())
                && !"REPLAYABLE_RESULT".equals(command.createIdempotencyMode())) {
            throw new IllegalArgumentException("REPLAY_SAME_KEY requires REPLAYABLE_RESULT");
        }
    }

    private SupplierDO requireActiveSupplier(String supplierCode) {
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()
                || !"STANDARD_SUPPLIER".equals(supplier.getSupplierType())) {
            throw new IllegalArgumentException("Active standard supplier does not exist: " + supplierCode);
        }
        return supplier;
    }

    private PlatformSkuDO requireActiveSku(String skuCode) {
        PlatformSkuDO sku = skuMapper.selectByCode(skuCode);
        if (sku == null || sku.getStatus() != CatalogEntityStatus.ACTIVE.getCode()) {
            throw new IllegalArgumentException("Active platform SKU does not exist: " + skuCode);
        }
        return sku;
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Fulfillment config is invalid JSON", exception);
        }
    }

    private String normalize(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Fulfillment config cannot be normalized", exception);
        }
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    public record CreateInventorySourceCommand(String supplierCode, String platformSkuCode,
                                               String externalSourceCode, Long operatorId) {
    }

    public record CreateOfferCommand(String supplierCode, String platformSkuCode,
                                     String offerCode, Long operatorId) {
    }

    public record PublishOfferCommand(String offerNo, long expectedOfferVersion,
                                      String externalSkuCode, long supplierSettlementUnitPrice,
                                      Long connectorVersionId, String inventorySourceNo,
                                      long minQuantity, long maxQuantity, long quantityStep,
                                      String primaryCapabilityCode, String completionMode,
                                      String createIdempotencyMode, String unknownResultPolicy,
                                      String partialDeliveryMode, String resultMultiplicity,
                                      int fulfillmentConfigSchemaVersion, String fulfillmentConfigJson,
                                      LocalDateTime validFrom, LocalDateTime validTo,
                                      String publishReason, Long operatorId, String traceId,
                                      String correlationId, String causationId) {
    }

    public record PublishResult(String offerNo, Long offerVersionId,
                                int offerVersionNo, long aggregateVersion) {
    }

    public record OfferPublishedPayload(String offerNo, Long offerVersionId, int offerVersionNo,
                                        Long platformSkuId, Long supplierId) {
    }
}
