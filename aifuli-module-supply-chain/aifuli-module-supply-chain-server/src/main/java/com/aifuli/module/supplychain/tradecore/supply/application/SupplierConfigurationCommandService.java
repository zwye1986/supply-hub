package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator;
import com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.StatusMappingDefinitionDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.StatusMappingDefinitionMapper;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierErrorCodeMapDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierErrorCodeMapMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SupplierConfigurationCommandService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final Set<String> SUPPLIER_TYPES = Set.of("STANDARD_SUPPLIER", "O2O_MERCHANT");
    private static final Set<String> ERROR_CLASSES = Set.of(
            "BUSINESS_REJECTED", "RETRYABLE", "RESULT_UNKNOWN", "MANUAL_REQUIRED");
    private static final Set<String> ERROR_ACTIONS = Set.of(
            "REJECT", "RETRY_SAFE", "QUERY_FIRST", "MANUAL");

    private final SupplierMapper supplierMapper;
    private final CredentialSlotMapper credentialSlotMapper;
    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorVersionMapper versionMapper;
    private final StatusMappingDefinitionMapper statusMappingMapper;
    private final SupplierErrorCodeMapMapper errorCodeMapMapper;
    private final AdapterMetadataRegistry metadataRegistry;
    private final ClosedJsonSchemaValidator schemaValidator;
    private final BusinessNoGenerator businessNoGenerator;
    private final ObjectMapper objectMapper;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createSupplier(CreateSupplierCommand command) {
        if (!SUPPLIER_TYPES.contains(command.supplierType())) {
            throw new IllegalArgumentException("Unsupported supplier type: " + command.supplierType());
        }
        LocalDateTime now = now();
        SupplierDO supplier = SupplierDO.builder()
                .supplierCode(command.supplierCode()).supplierName(command.supplierName())
                .shortName(command.shortName()).supplierType(command.supplierType())
                .legalEntityName(command.legalEntityName()).status(SupplierStatus.ACTIVE.getCode())
                .version(0L).createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        supplierMapper.insert(supplier);
        return supplier.getSupplierCode();
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createCredentialSlot(CreateCredentialSlotCommand command) {
        if (!"ENV".equals(command.providerType()) || command.secretRef() == null
                || !command.secretRef().startsWith("env:")) {
            throw new IllegalArgumentException("M1 credential slot must use an env: reference");
        }
        LocalDateTime now = now();
        CredentialSlotDO slot = CredentialSlotDO.builder()
                .slotNo(businessNoGenerator.next("CRD_")).providerType(command.providerType())
                .secretRef(command.secretRef()).maskedHint(command.maskedHint())
                .activeSecretVersion(command.activeSecretVersion()).status((short) 10).version(0L)
                .createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        credentialSlotMapper.insert(slot);
        return slot.getSlotNo();
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public DraftConnectorResult createConnectorDraft(CreateConnectorDraftCommand command) {
        SupplierDO supplier = requireActiveStandardSupplier(command.supplierCode());
        CredentialSlotDO slot = requireActiveSlot(command.credentialSlotNo());
        AdapterMetadata metadata = metadataRegistry.require(command.adapterType());
        JsonNode config = parseObject(command.adapterConfigJson());
        schemaValidator.validate(metadata.connectorSchemaResource(), config);
        metadata.validateConnectorConfig(command.adapterConfigSchemaVersion(), config);
        String normalizedConfig = normalize(config);
        LocalDateTime now = now();
        SupplierConnectorDO connector = SupplierConnectorDO.builder()
                .connectorNo(businessNoGenerator.next("CON_")).supplierId(supplier.getId())
                .connectorCode(command.connectorCode()).adapterType(command.adapterType())
                .status((short) 10).version(0L).createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        connectorMapper.insert(connector);

        byte[] digest = connectorDigest(command, connector.getId(), slot.getId(), normalizedConfig);
        SupplierConnectorVersionDO version = SupplierConnectorVersionDO.builder()
                .connectorId(connector.getId()).versionNo(1).baseUrl(command.baseUrl())
                .merchantAccount(command.merchantAccount()).credentialSlotId(slot.getId())
                .protocolVersion(command.protocolVersion()).callbackPath(command.callbackPath())
                .connectTimeoutMs(command.connectTimeoutMs()).readTimeoutMs(command.readTimeoutMs())
                .rateLimit(command.rateLimit()).concurrencyLimit(command.concurrencyLimit())
                .adapterConfigSchemaVersion(command.adapterConfigSchemaVersion())
                .adapterConfigJson(normalizedConfig).validationStatus((short) 0)
                .configDigest(digest).status(ConnectorVersionStatus.DRAFT.getCode()).version(0L)
                .createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        versionMapper.insert(version);
        return new DraftConnectorResult(connector.getConnectorNo(), version.getId(), 1);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public int initializeQnmStatusMappings(Long connectorVersionId, Long operatorId) {
        SupplierConnectorVersionDO version = versionMapper.selectById(connectorVersionId);
        if (version == null || version.getStatus() != ConnectorVersionStatus.DRAFT.getCode()) {
            throw new IllegalStateException("Only a draft connector version can initialize mappings");
        }
        SupplierConnectorDO connector = connectorMapper.selectById(version.getConnectorId());
        if (connector == null || !QnmGameOrderV1Metadata.TYPE.equals(connector.getAdapterType())) {
            throw new IllegalArgumentException("Connector version is not QNM_GAME_ORDER_V1");
        }
        if (!statusMappingMapper.selectByConnectorVersionId(connectorVersionId).isEmpty()) {
            throw new IllegalStateException("QNM status mappings have already been initialized");
        }
        LocalDateTime now = now();
        List<StatusMappingSeed> seeds = List.of(
                new StatusMappingSeed("ResCode=1", "CREATE", "ACCEPTED", false),
                new StatusMappingSeed("ResCode=OK", "CREATE", "ACCEPTED", false),
                new StatusMappingSeed("orderStatus=0", "QUERY", "ACCEPTED", false),
                new StatusMappingSeed("orderStatus=1", "QUERY", "SUCCEEDED", true),
                new StatusMappingSeed("orderStatus=2", "QUERY", "REJECTED", true),
                new StatusMappingSeed("status=1", "CALLBACK", "SUCCEEDED", true),
                new StatusMappingSeed("status=2", "CALLBACK", "REJECTED", true));
        for (StatusMappingSeed seed : seeds) {
            statusMappingMapper.insert(StatusMappingDefinitionDO.builder()
                    .connectorVersionId(connectorVersionId).mappingVersion(version.getVersionNo())
                    .externalStatus(seed.externalStatus()).externalSubStatus("")
                    .businessPhase(seed.businessPhase()).factType(seed.factType())
                    .terminalFlag((short) (seed.terminal() ? 1 : 0)).priority(0)
                    .status((short) 0).createdBy(operatorId).createdAt(now).build());
        }
        return seeds.size();
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void addErrorCodeMapping(AddErrorCodeMappingCommand command) {
        SupplierConnectorVersionDO version = versionMapper.selectById(command.connectorVersionId());
        if (version == null || version.getStatus() != ConnectorVersionStatus.DRAFT.getCode()) {
            throw new IllegalStateException("Only a draft connector version can add error mappings");
        }
        if (!ERROR_CLASSES.contains(command.errorClass()) || !ERROR_ACTIONS.contains(command.actionType())
                || ("RETRY_SAFE".equals(command.actionType()) != command.retryable())) {
            throw new IllegalArgumentException("Error mapping class, action or retryability is inconsistent");
        }
        errorCodeMapMapper.insert(SupplierErrorCodeMapDO.builder()
                .connectorVersionId(version.getId()).mappingVersion(version.getVersionNo())
                .externalErrorCode(command.externalErrorCode())
                .externalSubCode(command.externalSubCode() == null ? "" : command.externalSubCode())
                .errorClass(command.errorClass()).actionType(command.actionType())
                .retryableFlag((short) (command.retryable() ? 1 : 0)).status((short) 0)
                .createdBy(command.operatorId()).createdAt(now()).build());
    }

    private SupplierDO requireActiveStandardSupplier(String supplierCode) {
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()
                || !"STANDARD_SUPPLIER".equals(supplier.getSupplierType())) {
            throw new IllegalArgumentException("Active standard supplier does not exist: " + supplierCode);
        }
        return supplier;
    }

    private CredentialSlotDO requireActiveSlot(String slotNo) {
        CredentialSlotDO slot = credentialSlotMapper.selectByNo(slotNo);
        if (slot == null || slot.getStatus() != 10) {
            throw new IllegalArgumentException("Active credential slot does not exist: " + slotNo);
        }
        return slot;
    }

    private JsonNode parseObject(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("Adapter config must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Adapter config is invalid JSON", exception);
        }
    }

    private String normalize(JsonNode config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Adapter config cannot be normalized", exception);
        }
    }

    private static byte[] connectorDigest(CreateConnectorDraftCommand command, Long connectorId,
                                          Long slotId, String normalizedConfig) {
        return ConfigurationDigest.sha256(String.valueOf(connectorId), command.baseUrl(),
                value(command.merchantAccount()), String.valueOf(slotId), command.protocolVersion(),
                value(command.callbackPath()), String.valueOf(command.connectTimeoutMs()),
                String.valueOf(command.readTimeoutMs()), value(command.rateLimit()),
                value(command.concurrencyLimit()), String.valueOf(command.adapterConfigSchemaVersion()),
                normalizedConfig);
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    public record CreateSupplierCommand(String supplierCode, String supplierName, String shortName,
                                        String supplierType, String legalEntityName, Long operatorId) {
    }

    public record CreateCredentialSlotCommand(String providerType, String secretRef, String maskedHint,
                                              String activeSecretVersion, Long operatorId) {
    }

    public record CreateConnectorDraftCommand(String supplierCode, String connectorCode, String adapterType,
                                              String baseUrl, String merchantAccount,
                                              String credentialSlotNo, String protocolVersion,
                                              String callbackPath, int connectTimeoutMs, int readTimeoutMs,
                                              Integer rateLimit, Integer concurrencyLimit,
                                              int adapterConfigSchemaVersion, String adapterConfigJson,
                                              Long operatorId) {
    }

    public record DraftConnectorResult(String connectorNo, Long connectorVersionId, int versionNo) {
    }

    public record AddErrorCodeMappingCommand(Long connectorVersionId, String externalErrorCode,
                                             String externalSubCode, String errorClass,
                                             String actionType, boolean retryable, Long operatorId) {
    }

    private record StatusMappingSeed(String externalStatus, String businessPhase,
                                     String factType, boolean terminal) {
    }
}
