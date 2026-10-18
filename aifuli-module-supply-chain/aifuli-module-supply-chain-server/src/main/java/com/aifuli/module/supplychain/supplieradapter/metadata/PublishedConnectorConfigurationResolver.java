package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.aifuli.module.supplychain.border.fulfillment.application.singlestep.SingleStepCheckpointCodec;
import com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialSlotIdentity;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint.CredentialIdentity;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint.RecoveryContract;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialSlotStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
    private final com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper offerVersionMapper;
    private final com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper offerMapper;
    private final com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper slotMapper;

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
        return resolveContent(version, connector, false);
    }

    private PublishedConnectorConfiguration resolveContent(SupplierConnectorVersionDO version, SupplierConnectorDO connector,
                                                            boolean existingOrder) {
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
        var statuses = statusMappingMapper.selectByConnectorVersionId(version.getId());
        short expectedMappingStatus = existingOrder ? version.getStatus() : ConnectorVersionStatus.PUBLISHED.getCode();
        if (capabilities.isEmpty() || statuses.stream()
                .anyMatch(mapping -> !Objects.equals(mapping.getStatus(), expectedMappingStatus))) {
            throw new IllegalStateException("Published connector metadata is incomplete");
        }
        if (metadata instanceof HuashengOrderV1Metadata huasheng) {
            if (!capabilities.equals(huasheng.capabilities())) throw new IllegalStateException("华昇能力声明不完整");
        }
        metadata.validateMappings(statuses, errorCodeMapMapper.selectByConnectorVersionId(version.getId()));
        return new PublishedConnectorConfiguration(connector.getConnectorNo(), version.getId(),
                version.getVersionNo(), connector.getAdapterType(), version.getBaseUrl(),
                version.getMerchantAccount(), version.getCredentialSlotId(), version.getCallbackPath(),
                capabilities, version.getCredentialSetId(), metadata.credentialBindingMode(),
                version.getConnectTimeoutMs(), version.getReadTimeoutMs(), extraParameters,
                credentialBindingValidator.frozenItems(version));
    }

    /** 只定位代码合同选择新路径；配置核验异常不由调用者降级为普通Operation。
     * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#31-单步骤检查点v1。 */
    public RecoveryContract singleStepRecoveryContract(Long versionId) {
        return singleStepMetadata(versionId).singleStepRecoveryContract();
    }

    /** 从原版本对应的代码注册读取适配器身份，不信任检查点密文自报身份。
     * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#33-既有单冻结配置读取。 */
    public AdapterMetadata singleStepMetadata(Long versionId) {
        var version = versionMapper.selectById(versionId);
        if (version == null) throw new IllegalStateException("连接器版本不存在");
        var connector = connectorMapper.selectById(version.getConnectorId());
        if (connector == null) throw new IllegalStateException("连接器身份不存在");
        return metadataRegistry.require(connector.getAdapterType());
    }

    public SingleStepConfiguration resolveForSingleStepAcceptance(Long connectorVersionId, Long offerVersionId) {
        return singleStepContent(connectorVersionId, offerVersionId, false);
    }

    /**
     * 只消费工厂认证后的内部原单投影，不访问border DAL，不读取当前版本指针或修复配置。
     * 原版本退役只改变新单资格；同ID内容/凭据身份漂移必须拒绝。
     * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#33-既有单冻结配置读取。
     */
    public SingleStepConfiguration resolveForExistingOrder(SingleStepCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint);
        var binding = checkpoint.binding();
        var result = singleStepContent(binding.connectorVersionId(), binding.offerVersionId(), true);
        String digest = SingleStepCheckpointCodec.contentDigest(result.content(), checkpoint.business(), result.credentials(), checkpoint.recoveryContract());
        if (!binding.adapterType().equals(result.connector().adapterType())
                || binding.supplierId() != result.content().path("supplierId").longValue()
                || !checkpoint.credentialIdentity().equals(result.credentials()) || !binding.configDigest().equals(digest)
                || !checkpoint.business().externalSkuCode().equals(result.offer().getExternalSkuCode())
                || checkpoint.business().settlementUnitPriceMinorUnits() != result.offer().getSupplierSettlementUnitPrice()
                || !checkpoint.business().currency().equals(result.offer().getCurrency())) {
            throw new IllegalStateException("原单冻结配置内容或身份不一致");
        }
        return result;
    }

    private SingleStepConfiguration singleStepContent(Long connectorVersionId, Long offerVersionId, boolean existingOrder) {
        var version = versionMapper.selectById(connectorVersionId);
        if (version == null || version.getStatus() == null || !(version.getStatus() == ConnectorVersionStatus.PUBLISHED.getCode()
                || existingOrder && version.getStatus() == ConnectorVersionStatus.RETIRED.getCode())) {
            throw new IllegalStateException("原连接器不是已发布或可恢复退役版本");
        }
        var connector = connectorMapper.selectById(version.getConnectorId());
        if (connector == null) throw new IllegalStateException("原连接器身份不存在");
        var metadata = metadataRegistry.require(connector.getAdapterType());
        if (metadata.singleStepRecoveryContract() == null) throw new IllegalStateException("适配器未启用单步骤冻结合同");
        var config = resolveContent(version, connector, existingOrder);
        if (!config.capabilities().equals(metadata.capabilities())) throw new IllegalStateException("单步骤能力不完整");
        var offer = offerVersionMapper.selectById(offerVersionId);
        var identity = offer == null ? null : offerMapper.selectById(offer.getOfferId());
        if (identity == null || offer.getPublishedAt() == null || !connector.getSupplierId().equals(identity.getSupplierId())
                || !connectorVersionId.equals(offer.getConnectorVersionId()) || !existingOrder
                && (!offerVersionId.equals(identity.getCurrentVersionId()) || identity.getStatus() != OfferStatus.PUBLISHED.getCode())) {
            throw new IllegalStateException("原Offer版本未发布或归属不一致");
        }
        if (offer.getMinQuantity() == null || offer.getMinQuantity() != 1 || offer.getMaxQuantity() == null || offer.getMaxQuantity() < 1
                || offer.getQuantityStep() == null || offer.getQuantityStep() <= 0) {
            throw new IllegalStateException("单步骤Offer必须允许quantity=1");
        }
        var offerConfig = parseContent(offer.getFulfillmentConfigJson());
        schemaValidator.validate(metadata.offerSchemaResource(), offerConfig);
        metadata.validateOfferConfig(offer.getFulfillmentConfigSchemaVersion(), offerConfig);
        var errors = errorCodeMapMapper.selectByConnectorVersionId(connectorVersionId);
        if (errors.stream().anyMatch(row -> !Objects.equals(row.getStatus(), version.getStatus()))) {
            throw new IllegalStateException("原错误映射生命周期不一致");
        }
        FrozenCredentialSlotIdentity slot = null;
        if (config.credentialBindingMode() == CredentialBindingMode.SINGLE_SLOT) {
            var row = slotMapper.selectById(config.credentialSlotId());
            if (row == null || !Objects.equals(row.getStatus(), CredentialSlotStatus.ACTIVE.getCode())) {
                throw new IllegalStateException("原凭据槽不可用");
            }
            slot = new FrozenCredentialSlotIdentity(row.getId(), row.getProviderType(), row.getActiveSecretVersion());
        }
        var credentials = new CredentialIdentity(slot, config.credentialSetId(), config.frozenCredentialItems());
        var content = objectMapper.createObjectNode().put("supplierId", connector.getSupplierId())
                .put("connectorId", connector.getId()).put("connectorNo", connector.getConnectorNo())
                .put("connectorVersionId", version.getId()).put("connectorVersionNo", version.getVersionNo())
                .put("adapterType", config.adapterType()).put("protocolVersion", version.getProtocolVersion())
                .put("baseUrl", config.baseUrl()).put("merchantAccount", config.merchantAccount()).put("callbackPath", config.callbackPath())
                .put("adapterConfigSchemaVersion", version.getAdapterConfigSchemaVersion())
                .put("connectTimeoutMs", version.getConnectTimeoutMs()).put("readTimeoutMs", version.getReadTimeoutMs());
        content.set("adapterConfig", parseContent(version.getAdapterConfigJson()));
        content.set("extraConfig", parseContent(credentialBindingValidator.normalizeExtra(metadata, version.getExtraConfig())));
        var capabilities = capabilityMapper.selectByConnectorVersionId(connectorVersionId).stream()
                .sorted(Comparator.comparing(SupplierConnectorCapabilityDO::getCapabilityCode))
                .map(row -> objectMapper.createObjectNode().put("code", row.getCapabilityCode())
                        .put("enabled", row.getEnabled()).put("metadataVersion", row.getMetadataVersion())).toList();
        content.set("capabilities", objectMapper.valueToTree(capabilities));
        var statuses = statusMappingMapper.selectByConnectorVersionId(connectorVersionId).stream()
                .map(row -> objectMapper.createObjectNode().put("mappingVersion", row.getMappingVersion())
                        .put("externalStatus", row.getExternalStatus()).put("externalSubStatus", row.getExternalSubStatus())
                        .put("businessPhase", row.getBusinessPhase()).put("factType", row.getFactType())
                        .put("terminal", row.getTerminalFlag()).put("priority", row.getPriority()))
                .sorted(Comparator.comparing(JsonNode::toString)).toList();
        content.set("statusMappings", objectMapper.valueToTree(statuses));
        content.set("errorMappings", objectMapper.valueToTree(errors.stream().map(row -> objectMapper.createObjectNode()
                .put("mappingVersion", row.getMappingVersion()).put("externalErrorCode", row.getExternalErrorCode())
                .put("externalSubCode", row.getExternalSubCode()).put("errorClass", row.getErrorClass())
                .put("actionType", row.getActionType()).put("retryable", row.getRetryableFlag()))
                .sorted(Comparator.comparing(JsonNode::toString)).toList()));
        var offerContent = content.putObject("offer").put("id", offer.getId()).put("offerId", offer.getOfferId())
                .put("versionNo", offer.getVersionNo()).put("externalSkuCode", offer.getExternalSkuCode())
                .put("supplierSettlementUnitPrice", offer.getSupplierSettlementUnitPrice()).put("currency", offer.getCurrency())
                .put("inventorySourceId", offer.getInventorySourceId()).put("minQuantity", offer.getMinQuantity())
                .put("maxQuantity", offer.getMaxQuantity()).put("quantityStep", offer.getQuantityStep())
                .put("primaryCapabilityCode", offer.getPrimaryCapabilityCode()).put("completionMode", offer.getCompletionMode())
                .put("createIdempotencyMode", offer.getCreateIdempotencyMode()).put("unknownResultPolicy", offer.getUnknownResultPolicy())
                .put("partialDeliveryMode", offer.getPartialDeliveryMode()).put("resultMultiplicity", offer.getResultMultiplicity())
                .put("fulfillmentConfigSchemaVersion", offer.getFulfillmentConfigSchemaVersion());
        offerContent.set("fulfillmentConfig", offerConfig);
        return new SingleStepConfiguration(config, content, credentials, offer, metadata);
    }

    private JsonNode parseContent(String text) {
        try { return objectMapper.readTree(text); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("冻结配置不是合法JSON"); }
    }

    public record SingleStepConfiguration(PublishedConnectorConfiguration connector, JsonNode content,
                                          CredentialIdentity credentials, SupplierOfferVersionDO offer, AdapterMetadata metadata) {}

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
