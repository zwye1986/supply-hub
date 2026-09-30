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

/** 运行时按冻结版本 ID 解析已发布配置；不解析凭证明文。 */
@Component
@RequiredArgsConstructor
public class PublishedConnectorConfigurationResolver {

    private final SupplierConnectorVersionMapper versionMapper;
    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorCapabilityMapper capabilityMapper;
    private final StatusMappingDefinitionMapper statusMappingMapper;
    private final AdapterMetadataRegistry metadataRegistry;
    private final ClosedJsonSchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;

    public PublishedConnectorConfiguration resolve(Long connectorVersionId) {
        SupplierConnectorVersionDO version = versionMapper.selectById(connectorVersionId);
        if (version == null || version.getStatus() != ConnectorVersionStatus.PUBLISHED.getCode()) {
            throw new IllegalStateException("Connector version is not published");
        }
        SupplierConnectorDO connector = connectorMapper.selectById(version.getConnectorId());
        if (connector == null) {
            throw new IllegalStateException("Connector identity does not exist");
        }
        AdapterMetadata metadata = metadataRegistry.require(connector.getAdapterType());
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
        return new PublishedConnectorConfiguration(connector.getConnectorNo(), version.getId(),
                version.getVersionNo(), connector.getAdapterType(), version.getBaseUrl(),
                version.getMerchantAccount(), version.getCredentialSlotId(), version.getCallbackPath(),
                capabilities);
    }

    public record PublishedConnectorConfiguration(String connectorNo, Long connectorVersionId,
                                                   int versionNo, String adapterType, String baseUrl,
                                                   String merchantAccount, Long credentialSlotId,
                                                   String callbackPath,
                                                   Set<Capability> capabilities) {
        public PublishedConnectorConfiguration {
            capabilities = Set.copyOf(capabilities);
        }
    }
}
