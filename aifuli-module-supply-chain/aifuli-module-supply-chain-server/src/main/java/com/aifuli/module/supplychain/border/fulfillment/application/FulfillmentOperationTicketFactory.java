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
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpointCodec;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengExecutionPermit;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import org.springframework.beans.factory.ObjectProvider;
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
    private final HuashengCheckpointCodec checkpointCodec;

    @org.springframework.beans.factory.annotation.Autowired
    public FulfillmentOperationTicketFactory(CredentialSlotMapper slots, List<CredentialProvider> providers,
            FulfillmentCallbackProperties callbacks,
            List<com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver> sets,
            ObjectProvider<HuashengCheckpointCodec> checkpointCodecProvider) {
        credentialSlotMapper = slots; credentialProviders = providers; callbackProperties = callbacks;
        credentialSetResolvers = List.copyOf(sets);
        checkpointCodec = checkpointCodecProvider != null ? checkpointCodecProvider.getIfAvailable() : null;
    }
    public FulfillmentOperationTicketFactory(CredentialSlotMapper slots, List<CredentialProvider> providers,
            FulfillmentCallbackProperties callbacks,
            List<com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver> sets) {
        this(slots, providers, callbacks, sets, (HuashengCheckpointCodec) null);
    }
    public FulfillmentOperationTicketFactory(CredentialSlotMapper slots, List<CredentialProvider> providers,
            FulfillmentCallbackProperties callbacks,
            List<com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver> sets,
            HuashengCheckpointCodec checkpointCodec) {
        this.credentialSlotMapper = slots;
        this.credentialProviders = providers;
        this.callbackProperties = callbacks;
        this.credentialSetResolvers = List.copyOf(sets);
        this.checkpointCodec = checkpointCodec;
    }
    /** 兼容旧单槽测试/调用；集模式缺解析器仍失败关闭。 */
    public FulfillmentOperationTicketFactory(CredentialSlotMapper slots, List<CredentialProvider> providers,
            FulfillmentCallbackProperties callbacks) { this(slots, providers, callbacks, List.of(), (HuashengCheckpointCodec) null); }
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
        if (HuashengOrderV1Metadata.TYPE.equals(connectorConfig.adapterType())) {
            throw new UnsupportedOperationException("华昇必须使用专用 createForHuasheng 入口");
        }
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

    /**
     * 华昇专用出站执行凭证装配入口。
     * 解密并严格校验 checkpoint，核对冻结 billno/配置版本/摘要，绝不直传 ciphertext。
     */
    public OperationTicket createForHuasheng(FulfillmentOperationDO operation, BOrderDO bOrder,
                                            FulfillmentRequestDO request, SupplierDO supplier,
                                            SupplierOfferVersionDO offerVersion,
                                            PublishedConnectorConfiguration connectorConfig,
                                            HuashengExecutionPermit permit,
                                            OperationTicket.CredentialMaterial credentialMaterial,
                                            String traceId) {
        if (checkpointCodec == null) {
            throw new IllegalStateException("HuashengCheckpointCodec is unavailable");
        }
        java.util.Objects.requireNonNull(operation, "operation must not be null");
        java.util.Objects.requireNonNull(bOrder, "bOrder must not be null");
        java.util.Objects.requireNonNull(connectorConfig, "connectorConfig must not be null");
        java.util.Objects.requireNonNull(permit, "permit must not be null");
        java.util.Objects.requireNonNull(supplier, "supplier must not be null");

        if (!HuashengOrderV1Metadata.TYPE.equals(connectorConfig.adapterType())) {
            throw new IllegalArgumentException("createForHuasheng 仅支持 HUA_SHENG_ORDER_V1，实际: " + connectorConfig.adapterType());
        }
        if (!java.util.Objects.equals(permit.operationNo(), operation.getOperationNo())) {
            throw new IllegalStateException("permit.operationNo 与 operation.operationNo 不匹配");
        }
        if (permit.bOrderId() != bOrder.getId()) {
            throw new IllegalStateException("permit.bOrderId 与 bOrder.id 不匹配");
        }
        long opGen = operation.getExecutionGeneration() != null ? operation.getExecutionGeneration() : 0L;
        if (permit.executionGeneration() != opGen) {
            throw new IllegalStateException("permit.executionGeneration 与 operation.executionGeneration 不匹配");
        }
        if (credentialMaterial == null || credentialMaterial.appSecret() == null || credentialMaterial.appSecret().isBlank()
                || credentialMaterial.privateKeyPem() == null || credentialMaterial.privateKeyPem().isBlank()) {
            throw new IllegalStateException("华昇出站凭证材料缺失");
        }

        if (operation.getCheckpointSchemaVersion() == null || operation.getCheckpointKeyVersion() == null
                || operation.getCheckpointCiphertext() == null) {
            throw new IllegalStateException("operation checkpoint 缺失或损坏");
        }
        HuashengCheckpointCodec.Stored stored = new HuashengCheckpointCodec.Stored(
                operation.getCheckpointSchemaVersion(),
                operation.getCheckpointKeyVersion(),
                operation.getCheckpointCiphertext()
        );
        if (operation.getAdapterStepCode() == null) {
            throw new IllegalStateException("operation.adapterStepCode 不能为空");
        }
        AdapterStepCode originalStep = AdapterStepCode.valueOf(operation.getAdapterStepCode());
        HuashengCheckpoint.Binding expectedBinding = new HuashengCheckpoint.Binding(
                bOrder.getId(),
                bOrder.getBOrderNo(),
                operation.getOperationNo(),
                operation.getSequenceNo() != null ? operation.getSequenceNo() : 1,
                originalStep,
                bOrder.getConnectorVersionId(),
                bOrder.getOfferVersionId(),
                operation.getConfigDigest()
        );

        HuashengCheckpoint checkpoint = checkpointCodec.decrypt(stored, expectedBinding);

        if (bOrder.getBillno() == null || bOrder.getBillno().isBlank()) {
            throw new IllegalStateException("bOrder.billno 不能为空");
        }
        if (!java.util.Objects.equals(checkpoint.placeParameters().outOrderSn(), bOrder.getBillno())) {
            throw new IllegalStateException("bOrder.billno 与 checkpoint.placeParameters.outOrderSn 不一致");
        }
        if (bOrder.getExternalOrderNo() != null && !bOrder.getExternalOrderNo().isBlank()
                && checkpoint.orderSn() != null && !checkpoint.orderSn().isBlank()) {
            if (!java.util.Objects.equals(bOrder.getExternalOrderNo(), checkpoint.orderSn())) {
                throw new IllegalStateException("bOrder.externalOrderNo 与 checkpoint.orderSn 不一致");
            }
        }
        if (!java.util.Objects.equals(bOrder.getConnectorVersionId(), connectorConfig.connectorVersionId())) {
            throw new IllegalStateException("bOrder.connectorVersionId 与 connectorConfig.connectorVersionId 不一致");
        }

        byte[] plaintext = checkpointCodec.encodePlaintext(checkpoint);

        FulfillmentOperationKind operationKind = permit.step() == AdapterStepCode.QUERY_ORDER
                ? FulfillmentOperationKind.QUERY_ORDER
                : FulfillmentOperationKind.FULFILL;

        return OperationTicket.builder()
                .operationNo(operation.getOperationNo())
                .bOrderNo(bOrder.getBOrderNo())
                .billno(bOrder.getBillno())
                .externalOrderNo(bOrder.getExternalOrderNo() != null && !bOrder.getExternalOrderNo().isBlank()
                        ? bOrder.getExternalOrderNo() : checkpoint.orderSn())
                .supplierCode(supplier.getSupplierCode())
                .adapterType(connectorConfig.adapterType())
                .connectorVersionId(bOrder.getConnectorVersionId())
                .offerVersionId(bOrder.getOfferVersionId())
                .operationKind(operationKind)
                .adapterStepCode(permit.step())
                .sequenceNo(operation.getSequenceNo() != null ? operation.getSequenceNo() : 1)
                .idempotencyKey(operation.getIdempotencyKey())
                .externalSkuCode(offerVersion != null ? offerVersion.getExternalSkuCode() : null)
                .quantity(bOrder.getRequestedQuantity() != null ? bOrder.getRequestedQuantity() : 1L)
                .rechargeAccount(null)
                .targetSchemaCode(request != null ? request.getTargetSchemaCode() : null)
                .callbackUrl(checkpoint.placeParameters().notifyUrl())
                .baseUrl(connectorConfig.baseUrl())
                .merchantAccount(connectorConfig.merchantAccount())
                .secretKey(null)
                .credentialMaterial(credentialMaterial)
                .connectTimeoutMs(connectorConfig.connectTimeoutMs())
                .readTimeoutMs(connectorConfig.readTimeoutMs())
                .traceId(traceId != null && !traceId.isBlank() ? traceId : operation.getOperationNo())
                .checkpointPayload(plaintext)
                .extraParameters(connectorConfig.extraParameters())
                .credentialSetId(connectorConfig.credentialSetId())
                .frozenCredentialItems(connectorConfig.frozenCredentialItems())
                .executionGeneration(permit.executionGeneration())
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
