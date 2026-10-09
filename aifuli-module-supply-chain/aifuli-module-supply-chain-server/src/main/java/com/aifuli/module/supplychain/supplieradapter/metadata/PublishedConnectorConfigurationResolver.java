package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** 运行时按冻结版本 ID 解析已发布配置；校验凭据身份与非秘密参数，不解析凭证明文。
 * 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#123-第二切片显式凭据集绑定与运行时配置。 */
@Component
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class PublishedConnectorConfigurationResolver {

    private final SupplierConnectorVersionMapper versionMapper;
    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorCapabilityMapper capabilityMapper;
    private final StatusMappingDefinitionMapper statusMappingMapper;
    private final SupplierErrorCodeMapMapper errorCodeMapMapper;
    private final AdapterMetadataRegistry metadataRegistry;
    private final ClosedJsonSchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;
    private final com.aifuli.module.supplychain.tradecore.supply.application.ConnectorCredentialBindingValidator credentialBindingValidator;

    public PublishedConnectorConfiguration resolve(Long connectorVersionId) {
        log.debug("[resolvePublishedConnector] connectorVersionId={}",connectorVersionId);
        SupplierConnectorVersionDO version = versionMapper.selectById(connectorVersionId);
        if (version == null || version.getStatus() != ConnectorVersionStatus.PUBLISHED.getCode()) {
            throw new IllegalStateException("Connector version is not published");
        }
        SupplierConnectorDO connector = connectorMapper.selectById(version.getConnectorId());
        if (connector == null) {
            throw new IllegalStateException("Connector identity does not exist");
        }
        AdapterMetadata metadata = metadataRegistry.require(connector.getAdapterType());
        credentialBindingValidator.verify(metadata, connector.getSupplierId(), version, false);
        var extraParameters = credentialBindingValidator.extraParameters(metadata, version.getExtraConfig());
        try {
            var config = objectMapper.readTree(version.getAdapterConfigJson());
            schemaValidator.validate(metadata.connectorSchemaResource(), config);
            metadata.validateConnectorConfig(version.getAdapterConfigSchemaVersion(), config);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Published connector config is invalid JSON", exception);
        }
        Set<Capability> capabilities = capabilityMapper.selectByConnectorVersionId(version.getId()).stream()
                .filter(row -> row.getEnabled() == 1)
                .map(row -> Capability.valueOf(row.getCapabilityCode()))
                .collect(Collectors.toUnmodifiableSet());
        if (capabilities.isEmpty() || statusMappingMapper.selectByConnectorVersionId(version.getId()).stream()
                .anyMatch(mapping -> mapping.getStatus() != 10)) {
            throw new IllegalStateException("Published connector metadata is incomplete");
        }
        if (metadata instanceof HuashengOrderV1Metadata huasheng) {
            if (!capabilities.equals(huasheng.capabilities())) throw new IllegalStateException("华昇能力声明不完整");
            huasheng.validateMappings(statusMappingMapper.selectByConnectorVersionId(version.getId()),
                    errorCodeMapMapper.selectByConnectorVersionId(version.getId()));
        }
        return new PublishedConnectorConfiguration(connector.getConnectorNo(), version.getId(),
                version.getVersionNo(), connector.getAdapterType(), version.getBaseUrl(),
                version.getMerchantAccount(), version.getCredentialSlotId(), version.getCallbackPath(),
                capabilities, version.getCredentialSetId(), metadata.credentialBindingMode(),
                version.getConnectTimeoutMs(), version.getReadTimeoutMs(), extraParameters,
                credentialBindingValidator.frozenItems(version));
    }

    public record PublishedConnectorConfiguration(String connectorNo, Long connectorVersionId,
                                                   int versionNo, String adapterType, String baseUrl,
                                                   String merchantAccount, Long credentialSlotId,
                                                   String callbackPath,
                                                   Set<Capability> capabilities, Long credentialSetId,
                                                   CredentialBindingMode credentialBindingMode, int connectTimeoutMs,
                                                    int readTimeoutMs, java.util.Map<String,String> extraParameters,
                                                    java.util.List<com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialItemIdentity> frozenCredentialItems) {
        /** 保留旧完整配置构造入口，不伪造冻结身份。 */
        public PublishedConnectorConfiguration(String connectorNo, Long connectorVersionId, int versionNo, String adapterType,
                String baseUrl, String merchantAccount, Long credentialSlotId, String callbackPath, Set<Capability> capabilities,
                Long credentialSetId, CredentialBindingMode credentialBindingMode, int connectTimeoutMs, int readTimeoutMs,
                java.util.Map<String,String> extraParameters) {
            this(connectorNo, connectorVersionId, versionNo, adapterType, baseUrl, merchantAccount, credentialSlotId, callbackPath,
                    capabilities, credentialSetId, credentialBindingMode, connectTimeoutMs, readTimeoutMs, extraParameters, java.util.List.of());
        }
        /** 旧单槽构造入口，绝不猜测集 ID。 */
        public PublishedConnectorConfiguration(String connectorNo, Long connectorVersionId, int versionNo,
                String adapterType, String baseUrl, String merchantAccount, Long credentialSlotId,
                String callbackPath, Set<Capability> capabilities) {
            this(connectorNo,connectorVersionId,versionNo,adapterType,baseUrl,merchantAccount,credentialSlotId,
                    callbackPath,capabilities,null,CredentialBindingMode.SINGLE_SLOT,10000,10000,java.util.Map.of());
        }
        public PublishedConnectorConfiguration {
            capabilities = Set.copyOf(capabilities);
            extraParameters = java.util.Map.copyOf(extraParameters);
            frozenCredentialItems = java.util.List.copyOf(frozenCredentialItems);
        }
    }
}
