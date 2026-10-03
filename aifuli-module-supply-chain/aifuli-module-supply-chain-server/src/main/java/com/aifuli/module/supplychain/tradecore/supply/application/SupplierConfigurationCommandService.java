package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService;
import com.aifuli.module.supplychain.infrastructure.identity.SystemAssignedBusinessCodeGuard;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator;
import com.aifuli.module.supplychain.supplieradapter.metadata.MappingTemplates;
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
import java.util.Set;

@Service
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class SupplierConfigurationCommandService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final Set<String> SUPPLIER_TYPES = Set.of("STANDARD_SUPPLIER", "O2O_MERCHANT");
    private static final Set<String> ERROR_CLASSES = Set.of(
            "BUSINESS_REJECTED", "RETRYABLE", "RESULT_UNKNOWN", "MANUAL_REQUIRED");
    private static final Set<String> ERROR_ACTIONS = Set.of(
            "REJECT", "RETRY_SAFE", "QUERY_FIRST", "MANUAL");
    private static final Set<String> STATUS_FACTS = Set.of(
            "ACCEPTED", "SUCCEEDED", "REJECTED", "RESULT_UNKNOWN");
    private static final Set<String> BUSINESS_PHASES = Set.of("CREATE", "QUERY", "CALLBACK");

    private final SupplierMapper supplierMapper;
    private final CredentialSlotMapper credentialSlotMapper;
    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorVersionMapper versionMapper;
    private final StatusMappingDefinitionMapper statusMappingMapper;
    private final SupplierErrorCodeMapMapper errorCodeMapMapper;
    private final AdapterMetadataRegistry metadataRegistry;
    private final ClosedJsonSchemaValidator schemaValidator;
    private final BusinessNoGenerator businessNoGenerator;
    private final ScopedBusinessCodeService scopedBusinessCodeService;
    private final ObjectMapper objectMapper;
    private final ConnectorCredentialBindingValidator credentialBindingValidator;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createSupplier(CreateSupplierCommand command) {
        SystemAssignedBusinessCodeGuard.rejectSubmitted(ScopedBusinessCodeService.Kind.SPL,
                command.supplierCode() != null);
        if (!SUPPLIER_TYPES.contains(command.supplierType())) {
            throw new IllegalArgumentException("Unsupported supplier type: " + command.supplierType());
        }
        LocalDateTime now = now();
        SupplierDO supplier = SupplierDO.builder()
                .supplierCode(scopedBusinessCodeService.allocateRoot(ScopedBusinessCodeService.Kind.SPL,
                        null, command.operatorId()).code())
                .supplierName(command.supplierName())
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
    /** 创建非秘密配置并冻结显式引用；设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#123-第二切片显式凭据集绑定与运行时配置。 */
    public DraftConnectorResult createConnectorDraft(CreateConnectorDraftCommand command) {
        log.info("[createConnectorDraft] supplierCode={} connectorCode={} adapterType={} credentialSlotNo={} credentialSetCode={} connectTimeoutMs={} readTimeoutMs={} operatorId={}",
                command.supplierCode(),command.connectorCode(),command.adapterType(),command.credentialSlotNo(),command.credentialSetCode(),
                command.connectTimeoutMs(),command.readTimeoutMs(),command.operatorId());
        SupplierDO supplier = requireActiveStandardSupplier(command.supplierCode());
        AdapterMetadata metadata = metadataRegistry.require(command.adapterType());
        ConnectorCredentialBindingValidator.Binding binding = credentialBindingValidator.bindDraft(
                metadata, supplier.getId(), command.credentialSlotNo(), command.credentialSetCode());
        String extra = credentialBindingValidator.normalizeExtra(metadata, command.extraConfig());
        int schemaVersion = metadata.connectorSchemaVersion();
        JsonNode config = parseObject(command.adapterConfigJson());
        schemaValidator.validate(metadata.connectorSchemaResource(), config);
        metadata.validateConnectorConfig(schemaVersion, config);
        String normalizedConfig = normalize(config);
        LocalDateTime now = now();
        SupplierConnectorDO connector = SupplierConnectorDO.builder()
                .connectorNo(businessNoGenerator.next("CON_")).supplierId(supplier.getId())
                .connectorCode(command.connectorCode()).adapterType(command.adapterType())
                .status((short) 10).version(0L).createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        connectorMapper.insert(connector);

        byte[] digest = connectorDigest(command, connector.getId(), binding, metadata.credentialBindingMode(), schemaVersion, normalizedConfig, extra);
        SupplierConnectorVersionDO version = SupplierConnectorVersionDO.builder()
                .connectorId(connector.getId()).versionNo(1).baseUrl(command.baseUrl())
                .merchantAccount(command.merchantAccount()).credentialSlotId(binding.credentialSlotId())
                .credentialSetId(binding.credentialSetId()).credentialItemSnapshot(binding.itemSnapshot()).extraConfig(extra)
                .protocolVersion(command.protocolVersion()).callbackPath(command.callbackPath())
                .connectTimeoutMs(command.connectTimeoutMs()).readTimeoutMs(command.readTimeoutMs())
                .rateLimit(command.rateLimit()).concurrencyLimit(command.concurrencyLimit())
                .adapterConfigSchemaVersion(schemaVersion)
                .adapterConfigJson(normalizedConfig).validationStatus((short) 0)
                .configDigest(digest).status(ConnectorVersionStatus.DRAFT.getCode()).version(0L)
                .createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        versionMapper.insert(version);
        return new DraftConnectorResult(connector.getConnectorNo(), version.getId(), 1);
    }

    /**
     * 从适配器 {@link AdapterMetadata#mappingTemplates()} 为草稿连接器版本批量初始化状态映射与错误码映射（G2）。
     *
     * <p>适配器无关：协议知识单一来源于各适配器元数据，新增适配器无需改动此方法。仅当版本仍为草稿、
     * 且尚未初始化过映射时执行；模板列表顺序即插入顺序。设计见
     * docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#65-映射g2。</p>
     *
     * @return 初始化的映射总行数（状态映射 + 错误码映射）
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public int initializeMappings(Long connectorVersionId, Long operatorId) {
        SupplierConnectorVersionDO version = versionMapper.selectById(connectorVersionId);
        if (version == null || version.getStatus() != ConnectorVersionStatus.DRAFT.getCode()) {
            throw new IllegalStateException("Only a draft connector version can initialize mappings");
        }
        SupplierConnectorDO connector = connectorMapper.selectById(version.getConnectorId());
        if (connector == null) {
            throw new IllegalStateException("Connector does not exist");
        }
        if (!statusMappingMapper.selectByConnectorVersionId(connectorVersionId).isEmpty()) {
            throw new IllegalStateException("Connector mappings have already been initialized");
        }
        MappingTemplates templates = metadataRegistry.require(connector.getAdapterType()).mappingTemplates();
        LocalDateTime now = now();
        int count = 0;
        for (MappingTemplates.StatusMappingTemplate seed : templates.statusMappings()) {
            statusMappingMapper.insert(StatusMappingDefinitionDO.builder()
                    .connectorVersionId(connectorVersionId).mappingVersion(version.getVersionNo())
                    .externalStatus(seed.externalStatus())
                    .externalSubStatus(seed.externalSubStatus() == null ? "" : seed.externalSubStatus())
                    .businessPhase(seed.businessPhase()).factType(seed.factType())
                    .terminalFlag((short) (seed.terminal() ? 1 : 0)).priority(0)
                    .status((short) 0).createdBy(operatorId).createdAt(now).build());
            count++;
        }
        for (MappingTemplates.ErrorCodeMappingTemplate seed : templates.errorCodeMappings()) {
            errorCodeMapMapper.insert(SupplierErrorCodeMapDO.builder()
                    .connectorVersionId(connectorVersionId).mappingVersion(version.getVersionNo())
                    .externalErrorCode(seed.externalErrorCode())
                    .externalSubCode(seed.externalSubCode() == null ? "" : seed.externalSubCode())
                    .errorClass(seed.errorClass()).actionType(seed.actionType())
                    .retryableFlag((short) (seed.retryable() ? 1 : 0)).status((short) 0)
                    .createdBy(operatorId).createdAt(now).build());
            count++;
        }
        return count;
    }

    /**
     * 向草稿连接器版本追加单条状态映射（G2：模板初始化后运营按需补充）。
     *
     * <p>事实类型与业务阶段限定在封闭集合内，避免散落魔法值（AGENTS.md §7）。设计见
     * docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#65-映射g2。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void addStatusMapping(AddStatusMappingCommand command) {
        SupplierConnectorVersionDO version = versionMapper.selectById(command.connectorVersionId());
        if (version == null || version.getStatus() != ConnectorVersionStatus.DRAFT.getCode()) {
            throw new IllegalStateException("Only a draft connector version can add status mappings");
        }
        if (!STATUS_FACTS.contains(command.factType()) || !BUSINESS_PHASES.contains(command.businessPhase())) {
            throw new IllegalArgumentException(
                    "Status mapping fact type or business phase is outside the closed set");
        }
        statusMappingMapper.insert(StatusMappingDefinitionDO.builder()
                .connectorVersionId(version.getId()).mappingVersion(version.getVersionNo())
                .externalStatus(command.externalStatus())
                .externalSubStatus(command.externalSubStatus() == null ? "" : command.externalSubStatus())
                .businessPhase(command.businessPhase()).factType(command.factType())
                .terminalFlag((short) (command.terminal() ? 1 : 0)).priority(0)
                .status((short) 0).createdBy(command.operatorId()).createdAt(now()).build());
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
                                          ConnectorCredentialBindingValidator.Binding binding,
                                          com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode mode,
                                          int schemaVersion, String normalizedConfig, String extra) {
        return ConfigurationDigest.sha256(String.valueOf(connectorId), command.baseUrl(),
                value(command.merchantAccount()), mode.name(), value(binding.credentialSlotId()), value(binding.credentialSetId()),
                value(binding.itemSnapshot()), extra, command.protocolVersion(),
                value(command.callbackPath()), String.valueOf(command.connectTimeoutMs()),
                String.valueOf(command.readTimeoutMs()), value(command.rateLimit()),
                value(command.concurrencyLimit()), String.valueOf(schemaVersion),
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
                                              String adapterConfigJson,
                                              Long operatorId, String credentialSetCode, String extraConfig) {
        /** 保留旧单槽调用，不推断集身份；设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#123-第二切片显式凭据集绑定与运行时配置。 */
        public CreateConnectorDraftCommand(String supplierCode, String connectorCode, String adapterType,
                String baseUrl, String merchantAccount, String credentialSlotNo, String protocolVersion,
                String callbackPath, int connectTimeoutMs, int readTimeoutMs, Integer rateLimit,
                Integer concurrencyLimit, String adapterConfigJson, Long operatorId) {
            this(supplierCode,connectorCode,adapterType,baseUrl,merchantAccount,credentialSlotNo,protocolVersion,
                    callbackPath,connectTimeoutMs,readTimeoutMs,rateLimit,concurrencyLimit,adapterConfigJson,operatorId,null,null);
        }
    }

    public record DraftConnectorResult(String connectorNo, Long connectorVersionId, int versionNo) {
    }

    public record AddErrorCodeMappingCommand(Long connectorVersionId, String externalErrorCode,
                                             String externalSubCode, String errorClass,
                                             String actionType, boolean retryable, Long operatorId) {
    }

    public record AddStatusMappingCommand(Long connectorVersionId, String externalStatus,
                                          String externalSubStatus, String businessPhase,
                                          String factType, boolean terminal, Long operatorId) {
    }
}
