package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.security.CredentialProvider;
import com.aifuli.module.supplychain.infrastructure.security.SupplierEndpointPolicy;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.Capability;
import com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator;
import com.aifuli.module.supplychain.supplieradapter.metadata.StatusMappingDefinitionDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.StatusMappingDefinitionMapper;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierErrorCodeMapDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierErrorCodeMapMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialSlotStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 在业务事务之外完成配置、凭证和连通边界验证，再交由短事务记录结果。 */
@Service
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class ConnectorValidationService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final Set<String> ALLOWED_FACTS = Set.of(
            "ACCEPTED", "SUCCEEDED", "REJECTED", "RESULT_UNKNOWN");
    private static final Set<String> ALLOWED_ERROR_ACTIONS = Set.of(
            "REJECT", "RETRY_SAFE", "QUERY_FIRST", "MANUAL");

    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorVersionMapper versionMapper;
    private final CredentialSlotMapper credentialSlotMapper;
    private final StatusMappingDefinitionMapper statusMappingMapper;
    private final SupplierErrorCodeMapMapper errorCodeMapMapper;
    private final AdapterMetadataRegistry metadataRegistry;
    private final ClosedJsonSchemaValidator schemaValidator;
    private final List<CredentialProvider> credentialProviders;
    private final SupplierEndpointPolicy endpointPolicy;
    private final ConnectorValidationRecordService recordService;
    private final ObjectMapper objectMapper;
    private final ConnectorCredentialBindingValidator credentialBindingValidator;
    private final List<com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver> credentialSetResolvers;

    @Transactional(propagation = Propagation.NEVER)
    /** 实际解析凭据并擦除临时材料，验证不能仅判断引用存在；设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#123-第二切片显式凭据集绑定与运行时配置。 */
    public ValidationResult validate(ValidateConnectorCommand command) {
        log.info("[validateConnector] connectorVersionId={} capabilities={} operatorId={} traceId={}",
                command.connectorVersionId(),command.capabilities(),command.operatorId(),command.traceId());
        SupplierConnectorVersionDO version = versionMapper.selectById(command.connectorVersionId());
        if (version == null || version.getStatus() != ConnectorVersionStatus.DRAFT.getCode()) {
            throw new IllegalStateException("Only a draft connector version can be validated");
        }
        SupplierConnectorDO connector = connectorMapper.selectById(version.getConnectorId());
        if (connector == null) {
            throw new IllegalStateException("Connector does not exist");
        }
        AdapterMetadata metadata = metadataRegistry.require(connector.getAdapterType());
        JsonNode config = parse(version.getAdapterConfigJson());
        schemaValidator.validate(metadata.connectorSchemaResource(), config);
        metadata.validateConnectorConfig(version.getAdapterConfigSchemaVersion(), config);
        endpointPolicy.validate(version.getBaseUrl());
        validateLimits(version);

        credentialBindingValidator.verify(metadata, connector.getSupplierId(), version, false);
        credentialBindingValidator.extraParameters(metadata, version.getExtraConfig());
        if (version.getCredentialSetId() != null) {
            var resolver = credentialSetResolvers.stream().findFirst().orElseThrow(
                    () -> new IllegalStateException("Credential set resolver is unavailable"));
            var values = resolver.resolveSet(version.getCredentialSetId());
            try {
                if (!values.keySet().equals(metadata.credentialKeys()) || values.values().stream()
                        .anyMatch(chars -> chars == null || chars.length == 0)) {
                    throw new IllegalStateException("Credential material is incomplete");
                }
            } finally {
                values.values().forEach(chars -> { if (chars != null) java.util.Arrays.fill(chars, '\0'); });
            }
        } else {
            CredentialSlotDO slot = credentialSlotMapper.selectById(version.getCredentialSlotId());
            var provider = credentialProviders.stream().filter(value -> value.supports(slot.getProviderType()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Credential reference cannot be resolved"));
            char[] chars = provider.resolve(slot);
            try {
                if (chars == null || chars.length == 0) throw new IllegalStateException("Credential reference cannot be resolved");
            } finally {
                if (chars != null) java.util.Arrays.fill(chars, '\0');
            }
        }

        Set<Capability> requested = command.capabilities().stream().map(Capability::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        if (!requested.equals(metadata.capabilities())) {
            throw new IllegalArgumentException("Capability declaration must exactly match the validated adapter contract");
        }
        validateMappings(version.getId());
        if (metadata instanceof com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata huasheng) {
            huasheng.validateMappings(statusMappingMapper.selectByConnectorVersionId(version.getId()),
                    errorCodeMapMapper.selectByConnectorVersionId(version.getId()));
        }

        LocalDateTime validatedAt = databaseClock.now();
        ConnectorValidationRecordService.RecordValidationCommand recordCommand =
                new ConnectorValidationRecordService.RecordValidationCommand(
                        version.getId(), version.getVersion(), version.getConfigDigest(), requested,
                        validatedAt, validatedAt.plus(command.validFor()), command.operatorId(),
                        command.reason(), command.traceId());
        recordService.record(recordCommand);
        return new ValidationResult(version.getId(), validatedAt, validatedAt.plus(command.validFor()));
    }

    private void validateMappings(Long connectorVersionId) {
        List<StatusMappingDefinitionDO> statusMappings =
                statusMappingMapper.selectByConnectorVersionId(connectorVersionId);
        if (statusMappings.isEmpty() || statusMappings.stream().anyMatch(mapping -> mapping.getStatus() != 0
                || !ALLOWED_FACTS.contains(mapping.getFactType()))) {
            throw new IllegalArgumentException("Connector requires draft mappings with closed standard facts");
        }
        List<SupplierErrorCodeMapDO> errors = errorCodeMapMapper.selectByConnectorVersionId(connectorVersionId);
        if (errors.stream().anyMatch(mapping -> mapping.getStatus() != 0
                || !ALLOWED_ERROR_ACTIONS.contains(mapping.getActionType()))) {
            throw new IllegalArgumentException("Supplier error mapping contains an unsupported action");
        }
    }

    private static void validateLimits(SupplierConnectorVersionDO version) {
        if (version.getConnectTimeoutMs() <= 0 || version.getConnectTimeoutMs() > 30_000
                || version.getReadTimeoutMs() <= 0 || version.getReadTimeoutMs() > 120_000
                || (version.getRateLimit() != null && version.getRateLimit() <= 0)
                || (version.getConcurrencyLimit() != null && version.getConcurrencyLimit() <= 0)) {
            throw new IllegalArgumentException("Connector timeout or limit is outside the platform boundary");
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Stored adapter config is invalid JSON", exception);
        }
    }

    public record ValidateConnectorCommand(Long connectorVersionId, Set<String> capabilities,
                                           Duration validFor, Long operatorId,
                                           String reason, String traceId) {
        public ValidateConnectorCommand {
            capabilities = Set.copyOf(capabilities);
            if (validFor == null || validFor.isNegative() || validFor.isZero()
                    || validFor.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("Validation validity must be within one hour");
            }
        }
    }

    public record ValidationResult(Long connectorVersionId, LocalDateTime validatedAt,
                                   LocalDateTime validationExpiresAt) {
    }
}
