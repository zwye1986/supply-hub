package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.infrastructure.security.CredentialProvider;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialSlotStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/** 冻结供应商配置与凭证的统一Ticket装配；查询复用原履约身份且不解密充值目标。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#6-timer-与补偿。 */
@Component
public class FulfillmentOperationTicketFactory {
    private final CredentialSlotMapper credentialSlotMapper;
    private final List<CredentialProvider> credentialProviders;
    private final FulfillmentCallbackProperties callbackProperties;
    private final List<com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver> credentialSetResolvers;
    @org.springframework.beans.factory.annotation.Autowired
    public FulfillmentOperationTicketFactory(CredentialSlotMapper slots, List<CredentialProvider> providers,
            FulfillmentCallbackProperties callbacks,
            List<com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver> sets) {
        credentialSlotMapper = slots; credentialProviders = providers; callbackProperties = callbacks;
        credentialSetResolvers = List.copyOf(sets);
    }
    /** 兼容旧单槽测试/调用；集模式缺解析器仍失败关闭。 */
    public FulfillmentOperationTicketFactory(CredentialSlotMapper slots, List<CredentialProvider> providers,
            FulfillmentCallbackProperties callbacks) { this(slots, providers, callbacks, List.of()); }
    public String resolveCredential(PublishedConnectorConfiguration config) {
        if (config.credentialBindingMode() == com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode.CREDENTIAL_SET) {
            if (config.credentialSlotId() != null || config.credentialSetId() == null) throw new IllegalStateException("Credential set association is invalid");
            return null;
        }
        return resolveSecretKey(config.credentialSlotId());
    }
    public OperationTicket create(FulfillmentOperationDO operation, BOrderDO bOrder, FulfillmentRequestDO request,
                                  SupplierDO supplier, SupplierOfferVersionDO offerVersion,
                                  PublishedConnectorConfiguration connectorConfig, FulfillmentOperationKind kind,
                                  String rechargeAccount, String secretKey, String traceId) {
        return OperationTicket.builder()
                    .operationNo(operation.getOperationNo())
                    .bOrderNo(bOrder.getBOrderNo())
                    .billno(bOrder.getBillno() != null && !bOrder.getBillno().isBlank() ? bOrder.getBillno() : bOrder.getBOrderNo())
                    .externalOrderNo(bOrder.getExternalOrderNo())
                    .supplierCode(supplier.getSupplierCode())
                    .adapterType(connectorConfig.adapterType())
                    .connectorVersionId(bOrder.getConnectorVersionId())
                    .offerVersionId(bOrder.getOfferVersionId())
                    .operationKind(kind)
                    .adapterStepCode(AdapterStepCode.valueOf(operation.getAdapterStepCode()))
                    .sequenceNo(operation.getSequenceNo() != null ? operation.getSequenceNo() : 1)
                    .idempotencyKey(operation.getIdempotencyKey())
                    .externalSkuCode(offerVersion != null ? offerVersion.getExternalSkuCode() : null)
                    .quantity(bOrder.getRequestedQuantity() != null ? bOrder.getRequestedQuantity() : 1L)
                    .rechargeAccount(rechargeAccount)
                    .targetSchemaCode(request.getTargetSchemaCode())
                    .callbackUrl(resolveCallbackUrl(connectorConfig.callbackPath()))
                    .baseUrl(connectorConfig.baseUrl())
                    .merchantAccount(connectorConfig.merchantAccount())
                    .secretKey(connectorConfig.credentialBindingMode() == com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode.SINGLE_SLOT ? secretKey : null)
                    .credentialMaterial(resolveMaterial(connectorConfig))
                    .connectTimeoutMs(connectorConfig.connectTimeoutMs())
                    .readTimeoutMs(connectorConfig.readTimeoutMs())
                    .traceId(traceId)
                    .checkpointPayload(operation.getCheckpointCiphertext())
                    .extraParameters(connectorConfig.extraParameters())
                    .build();
    }
    /** SET 装配仍走既有 create 入口；解析全部项后仅投影两个出站材料，所有临时数组 finally 擦除。
     * 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#123-第二切片显式凭据集绑定与运行时配置。 */
    private OperationTicket.CredentialMaterial resolveMaterial(PublishedConnectorConfiguration config) {
        if (config.credentialBindingMode() != com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode.CREDENTIAL_SET) return null;
        if (config.credentialSetId() == null || config.credentialSlotId() != null) throw new IllegalStateException("Credential set association is invalid");
        var resolver = credentialSetResolvers.stream().findFirst().orElseThrow(() -> new IllegalStateException("Credential set resolver is unavailable"));
        var values = resolver.resolveSet(config.credentialSetId());
        try {
            char[] appSecret = values.get("appSecret"), privateKey = values.get("privateKey");
            if (appSecret == null || appSecret.length == 0 || privateKey == null || privateKey.length == 0) {
                throw new IllegalStateException("Outbound credential material is incomplete");
            }
            return new OperationTicket.CredentialMaterial(new String(appSecret), new String(privateKey));
        } finally { values.values().forEach(chars -> { if (chars != null) java.util.Arrays.fill(chars, '\0'); }); }
    }
    private String resolveCallbackUrl(String callbackPath) {
        String base = callbackProperties != null ? callbackProperties.getPublicCallbackBaseUrl() : null;
        if (base == null || base.isBlank() || callbackPath == null || callbackPath.isBlank()) {
            return "";
        }
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedPath = callbackPath.startsWith("/") ? callbackPath : "/" + callbackPath;
        return normalizedBase + normalizedPath;
    }

    private String resolveSecretKey(Long credentialSlotId) {
        if (credentialSlotId == null) {
            return null;
        }
        CredentialSlotDO slot = credentialSlotMapper.selectById(credentialSlotId);
        if (slot == null || slot.getStatus() != CredentialSlotStatus.ACTIVE.getCode()) {
            throw new IllegalStateException("CredentialSlot 不可用 slotId=" + credentialSlotId);
        }
        CredentialProvider provider = credentialProviders.stream()
                .filter(p -> p.supports(slot.getProviderType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到支持的 CredentialProvider: " + slot.getProviderType()));
        char[] secretChars = provider.resolve(slot);
        try {
            if (secretChars == null || secretChars.length == 0) throw new IllegalStateException("Credential material is empty");
            return new String(secretChars);
        } finally {
            if (secretChars != null) java.util.Arrays.fill(secretChars, '\0');
        }
    }

}
