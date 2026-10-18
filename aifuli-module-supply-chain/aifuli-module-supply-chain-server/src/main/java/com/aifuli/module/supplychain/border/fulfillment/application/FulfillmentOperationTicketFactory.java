package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialSlotIdentity;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.border.fulfillment.domain.ResolvedCredentialMaterial;
import com.aifuli.module.supplychain.infrastructure.security.CredentialProvider;
import com.aifuli.module.supplychain.infrastructure.security.FrozenCredentialSetResolver;
import com.aifuli.module.supplychain.infrastructure.security.FrozenCredentialSlotResolver;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialSlotStatus;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpointCodec;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengExecutionPermit;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import com.aifuli.module.supplychain.border.fulfillment.application.singlestep.SingleStepCheckpointCodec;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentIdempotencyKeys;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 冻结供应商配置与凭证的统一Ticket装配；旧查询保持原语义，新单步入口认证原目标及完整冻结合同。
 * 设计见 docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#32-ticket扩展。
 */
@Component
public class FulfillmentOperationTicketFactory {
    private final CredentialSlotMapper credentialSlotMapper;
    private final List<CredentialProvider> credentialProviders;
    private final FulfillmentCallbackProperties callbackProperties;
    private final List<com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver> credentialSetResolvers;
    private final HuashengCheckpointCodec checkpointCodec;
    private final FrozenCredentialSlotResolver frozenSlotResolver;
    private final FrozenCredentialSetResolver frozenSetResolver;
    private final SingleStepCheckpointCodec singleStepCodec;
    private final PublishedConnectorConfigurationResolver singleStepResolver;
    private final EnvelopeCryptoPort targetCrypto;
    private final HmacFingerprintPort targetFingerprint;
    private final FulfillmentTargetSchemaRegistry targetSchemas;
    private static final com.fasterxml.jackson.databind.ObjectMapper TARGET_JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder(
            com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(6).maxStringLength(4096).build()).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    @org.springframework.beans.factory.annotation.Autowired
    public FulfillmentOperationTicketFactory(CredentialSlotMapper slots, List<CredentialProvider> providers,
            FulfillmentCallbackProperties callbacks,
            List<com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver> sets,
            ObjectProvider<HuashengCheckpointCodec> checkpointCodecProvider,
            ObjectProvider<FrozenCredentialSlotResolver> frozenSlots,
            ObjectProvider<FrozenCredentialSetResolver> frozenSets,
            ObjectProvider<SingleStepCheckpointCodec> singleSteps,
            ObjectProvider<PublishedConnectorConfigurationResolver> configurations,
            ObjectProvider<EnvelopeCryptoPort> targets, ObjectProvider<HmacFingerprintPort> fingerprints,
            ObjectProvider<FulfillmentTargetSchemaRegistry> schemas) {
        this(slots, providers, callbacks, sets, checkpointCodecProvider.getIfAvailable(),
                frozenSlots.getIfAvailable(), frozenSets.getIfAvailable(), singleSteps.getIfAvailable(),
                configurations.getIfAvailable(), targets.getIfAvailable(), fingerprints.getIfAvailable(), schemas.getIfAvailable());
    }

    public FulfillmentOperationTicketFactory(CredentialSlotMapper slots, List<CredentialProvider> providers,
            FulfillmentCallbackProperties callbacks,
            List<com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver> sets,
            ObjectProvider<HuashengCheckpointCodec> checkpointCodecProvider) {
        this(slots, providers, callbacks, sets,
                checkpointCodecProvider != null ? checkpointCodecProvider.getIfAvailable() : null);
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
        this(slots, providers, callbacks, sets, checkpointCodec, null, null);
    }
    public FulfillmentOperationTicketFactory(CredentialSlotMapper slots, List<CredentialProvider> providers,
            FulfillmentCallbackProperties callbacks,
            List<com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver> sets,
            HuashengCheckpointCodec checkpointCodec, FrozenCredentialSlotResolver frozenSlotResolver,
            FrozenCredentialSetResolver frozenSetResolver) {
        this(slots, providers, callbacks, sets, checkpointCodec, frozenSlotResolver, frozenSetResolver, null, null, null, null, null);
    }

    public FulfillmentOperationTicketFactory(CredentialSlotMapper slots, List<CredentialProvider> providers,
            FulfillmentCallbackProperties callbacks,
            List<com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver> sets,
            HuashengCheckpointCodec checkpointCodec, FrozenCredentialSlotResolver frozenSlotResolver,
            FrozenCredentialSetResolver frozenSetResolver, SingleStepCheckpointCodec singleStepCodec,
            PublishedConnectorConfigurationResolver singleStepResolver, EnvelopeCryptoPort targetCrypto,
            HmacFingerprintPort targetFingerprint, FulfillmentTargetSchemaRegistry targetSchemas) {
        this.credentialSlotMapper = slots;
        this.credentialProviders = providers;
        this.callbackProperties = callbacks;
        this.credentialSetResolvers = List.copyOf(sets);
        this.checkpointCodec = checkpointCodec;
        this.frozenSlotResolver = frozenSlotResolver;
        this.frozenSetResolver = frozenSetResolver;
        this.singleStepCodec = singleStepCodec;
        this.singleStepResolver = singleStepResolver;
        this.targetCrypto = targetCrypto;
        this.targetFingerprint = targetFingerprint;
        this.targetSchemas = targetSchemas;
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

    /**
     * 显式新材料入口：只按Metadata声明键解析原冻结身份，成功由Ticket接管，装配失败立即擦除。
     * 不持有订单/资金锁调用；旧协议仍走原入口。供价只读BOrder冻结值，禁止Offer最新价兜底。
     * 本基础入口不承担检查点认证；完整单步骤原单必须使用createForSingleStep，后续Runner不得绕过该认证入口。
     * 材料合同见 docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#32-ticket扩展。
     */
    public OperationTicket createWithFrozenCredentials(FulfillmentOperationDO operation, BOrderDO bOrder,
            FulfillmentRequestDO request, SupplierDO supplier, SupplierOfferVersionDO offerVersion,
            PublishedConnectorConfiguration config, AdapterMetadata metadata, FrozenCredentialSlotIdentity frozenSlot,
            FulfillmentOperationKind kind, String rechargeAccount, String traceId) {
        if (!Objects.equals(metadata.adapterType(), config.adapterType())
                || metadata.credentialBindingMode() != config.credentialBindingMode()
                || !Objects.equals(bOrder.getConnectorVersionId(), config.connectorVersionId())) {
            throw new IllegalStateException("冻结配置与适配器或原单绑定不一致");
        }
        if (bOrder.getSupplierSettlementUnitPrice() == null) throw new IllegalStateException("原单冻结供价缺失");
        var price = new Money(bOrder.getSupplierSettlementUnitPrice(), bOrder.getCurrency());
        var material = resolveFrozenMaterial(config, metadata.credentialKeys(), frozenSlot);
        try {
            return ticketBuilder(operation, bOrder, request, supplier, offerVersion, config, kind, rechargeAccount, traceId)
                    .settlementUnitPriceMinorUnits(price.cents()).currency(price.currency())
                    .resolvedCredentialMaterial(material)
                    .credentialSetId(config.credentialSetId()).frozenCredentialItems(config.frozenCredentialItems())
                    .executionGeneration(operation.getExecutionGeneration() != null ? operation.getExecutionGeneration() : 0L)
                    // 本切片不解密检查点，更不能把数据库信封密文当作适配器可消费的明文。
                    .checkpointPayload(null)
                    .build();
        } catch (RuntimeException | Error failure) {
            material.close();
            throw failure;
        }
    }

    private ResolvedCredentialMaterial resolveFrozenMaterial(PublishedConnectorConfiguration config,
            Set<String> requiredKeys, FrozenCredentialSlotIdentity frozenSlot) {
        requiredKeys = Set.copyOf(requiredKeys);
        if (config.credentialBindingMode() == CredentialBindingMode.SINGLE_SLOT) {
            if (frozenSlot == null || !Objects.equals(config.credentialSlotId(), frozenSlot.slotId())
                    || config.credentialSetId() != null || !config.frozenCredentialItems().isEmpty()
                    || requiredKeys.size() != 1 || frozenSlotResolver == null) {
                throw new IllegalStateException("冻结凭据槽绑定或解析器不可用");
            }
            String key = requiredKeys.iterator().next();
            return ResolvedCredentialMaterial.takeOwnership(requiredKeys, Map.of(key, frozenSlotResolver.resolve(frozenSlot)));
        }
        Set<String> frozenKeys = config.frozenCredentialItems().stream().map(item -> item.itemKey()).collect(Collectors.toSet());
        if (config.credentialBindingMode() != CredentialBindingMode.CREDENTIAL_SET || frozenSlot != null
                || config.credentialSlotId() != null || config.credentialSetId() == null || frozenSetResolver == null
                || !frozenKeys.equals(requiredKeys)) {
            throw new IllegalStateException("冻结凭据集绑定或解析器不可用");
        }
        return ResolvedCredentialMaterial.takeOwnership(requiredKeys,
                frozenSetResolver.resolve(config.credentialSetId(), config.frozenCredentialItems(), requiredKeys));
    }

    /**
     * 持久原单的完整认证装配：先认证原SEND检查点、归属和内容摘要，再解密目标/解析凭据。
     * 禁止在聚合事务内准备材料；本入口不认领、不执行HTTP、不改订单或资金。
     * QUERY仅改变执行端点，不改变原SEND的AAD；回调/供价/四时限均来自原合同，绝无最新值兜底。
     * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#32-ticket扩展。
     */
    public OperationTicket createForSingleStep(FulfillmentOperationDO operation, BOrderDO bOrder,
            FulfillmentRequestDO request, SupplyChainOrderDO order,
            SupplierDO supplier, FulfillmentOperationKind kind, String traceId) {
        return createForSingleStep(operation, bOrder, request, order, supplier, kind, traceId,
                operation == null || operation.getExecutionGeneration() == null ? 0 : operation.getExecutionGeneration());
    }

    /** 事务外预装配拟认领代次；该值本身不是资格，必须再以原代次/冻结绑定竞争已提交permit。 */
    public OperationTicket createForSingleStep(FulfillmentOperationDO operation, BOrderDO bOrder,
            FulfillmentRequestDO request, SupplyChainOrderDO order,
            SupplierDO supplier, FulfillmentOperationKind kind, String traceId, long proposedGeneration) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("单步骤材料装配不得位于订单或资金事务内");
        }
        if (singleStepCodec == null || singleStepResolver == null || targetCrypto == null || targetFingerprint == null || targetSchemas == null) {
            throw new IllegalStateException("单步骤原单装配依赖不可用");
        }
        if (operation == null || bOrder == null || request == null || order == null || supplier == null
                || kind != FulfillmentOperationKind.FULFILL && kind != FulfillmentOperationKind.QUERY_ORDER
                || !Objects.equals(operation.getBOrderId(), bOrder.getId())
                || !Objects.equals(bOrder.getFulfillmentRequestId(), request.getId())
                || !Objects.equals(bOrder.getOrderId(), order.getId()) || !Objects.equals(request.getOrderId(), order.getId())
                || !Objects.equals(request.getBuyerId(), order.getBuyerId()) || !Objects.equals(bOrder.getSupplierId(), supplier.getId())
                || !FulfillmentOperationKind.FULFILL.name().equals(operation.getOperationKind())
                || !AdapterStepCode.SEND.name().equals(operation.getAdapterStepCode())
                || !Objects.equals(operation.getSequenceNo(), 1)
                || !Objects.equals(operation.getIdempotencyKey(), FulfillmentIdempotencyKeys.operation(bOrder.getBOrderNo(), 1))
                || operation.getConfigDigest() == null || operation.getConfigDigest().length != 32) {
            throw new IllegalStateException("单步骤原单绑定无效");
        }
        var metadata = singleStepResolver.singleStepMetadata(bOrder.getConnectorVersionId());
        var binding = new SingleStepCheckpoint.Binding(operation.getOperationNo(), bOrder.getId(), metadata.adapterType(),
                AdapterStepCode.SEND, supplier.getId(), bOrder.getConnectorVersionId(), bOrder.getOfferVersionId(),
                HexFormat.of().formatHex(operation.getConfigDigest()));
        var checkpoint = singleStepCodec.decrypt(new SingleStepCheckpointCodec.Stored(operation.getCheckpointSchemaVersion(),
                operation.getCheckpointKeyVersion(), operation.getCheckpointCiphertext()), binding);
        var business = checkpoint.business();
        var target = business.protectedTargetReference();
        if (!business.billno().equals(bOrder.getBillno()) || !Objects.equals(bOrder.getRequestedQuantity(), business.quantity())
                || !Objects.equals(request.getRequestedQuantity(), business.quantity()) || !Objects.equals(order.getQuantity(), business.quantity())
                || !Objects.equals(bOrder.getSupplierSettlementUnitPrice(), business.settlementUnitPriceMinorUnits())
                || !business.currency().equals(bOrder.getCurrency()) || !business.targetSchemaCode().equals(request.getTargetSchemaCode())
                || !Objects.equals(target.requestId(), request.getId()) || !target.requestNo().equals(request.getRequestNo())
                || !Objects.equals(target.targetSchemaVersion(), request.getTargetSchemaVersion())
                || !target.targetKeyVersion().equals(request.getTargetKeyVersion()) || request.getTargetDigest() == null
                || !MessageDigest.isEqual(HexFormat.of().parseHex(target.targetDigest()), request.getTargetDigest())) {
            throw new IllegalStateException("单步骤原单业务内容或目标引用不一致");
        }
        var frozen = singleStepResolver.resolveForExistingOrder(checkpoint);
        if (!Objects.equals(bOrder.getOfferId(), frozen.offer().getOfferId())) throw new IllegalStateException("原Offer归属不一致");
        String mobile = singleStepMobile(request, order);
        byte[] plaintext = singleStepCodec.encodePlaintext(checkpoint);
        ResolvedCredentialMaterial material = null;
        try {
            material = resolveFrozenMaterial(frozen.connector(), metadata.credentialKeys(), checkpoint.credentialIdentity().slot());
            return ticketBuilder(operation, bOrder, request, supplier, frozen.offer(), frozen.connector(), kind, mobile, traceId)
                    .billno(business.billno()).externalSkuCode(business.externalSkuCode()).quantity(business.quantity())
                    .callbackUrl(business.callbackUrl()).checkpointPayload(plaintext)
                    .adapterStepCode(kind == FulfillmentOperationKind.QUERY_ORDER ? AdapterStepCode.QUERY_ORDER : AdapterStepCode.SEND)
                    .connectTimeoutMs(checkpoint.recoveryContract().connectTimeoutMs()).readTimeoutMs(checkpoint.recoveryContract().readWriteTimeoutMs())
                    .settlementUnitPriceMinorUnits(business.settlementUnitPriceMinorUnits()).currency(business.currency())
                    .credentialSetId(checkpoint.credentialIdentity().setId()).frozenCredentialItems(checkpoint.credentialIdentity().items())
                    .executionGeneration(proposedGeneration)
                    .resolvedCredentialMaterial(material).build();
        } catch (RuntimeException | Error failure) {
            if (material != null) material.close();
            throw failure;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** 只认受保护目标的封闭Schema和规范MOBILE；HMAC不符/坏密文不可借用订单普通手机号字段兜底。 */
    private String singleStepMobile(FulfillmentRequestDO request, SupplyChainOrderDO order) {
        byte[] plaintext = null;
        try {
            byte[] aad = (request.getBuyerId() + "|" + order.getBuyerOrderNo() + "|" + request.getTargetSchemaCode()
                    + "|" + request.getTargetSchemaVersion()).getBytes(StandardCharsets.UTF_8);
            plaintext = targetCrypto.decrypt(request.getTargetCiphertext(), request.getTargetKeyVersion(), aad);
            if (plaintext == null || plaintext.length == 0 || plaintext.length > 4096
                    || !MessageDigest.isEqual(targetFingerprint.fingerprint(FulfillmentTargetFields.FINGERPRINT_PURPOSE, plaintext), request.getTargetDigest())) {
                throw new IllegalArgumentException();
            }
            var json = TARGET_JSON.readTree(plaintext);
            var fields = new java.util.HashSet<String>(); json.fieldNames().forEachRemaining(fields::add);
            var values = json.path("values");
            if (!fields.equals(Set.of("schemaCode", "schemaVersion", "values"))
                    || !json.path("schemaCode").isTextual() || !request.getTargetSchemaCode().equals(json.path("schemaCode").textValue())
                    || !json.path("schemaVersion").isIntegralNumber() || !json.path("schemaVersion").canConvertToInt()
                    || request.getTargetSchemaVersion() != json.path("schemaVersion").intValue() || !values.isObject()) {
                throw new IllegalArgumentException();
            }
            Map<String, String> target = new java.util.HashMap<>();
            values.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) throw new IllegalArgumentException();
                target.put(entry.getKey(), entry.getValue().textValue());
            });
            targetSchemas.validate(request.getTargetSchemaCode(), request.getTargetSchemaVersion(), target);
            String mobile = FulfillmentTargetFields.mobileAccountValue(target);
            if (mobile == null || !mobile.equals(target.get(FulfillmentTargetFields.ACCOUNT_VALUE))) throw new IllegalArgumentException();
            return mobile;
        } catch (java.io.IOException | RuntimeException invalid) {
            // Jackson/密文异常可能包含明文，不把任意原始诊断带入Timer的通用异常路径。
            throw new IllegalStateException("单步骤原目标认证或MOBILE校验失败");
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    public OperationTicket create(FulfillmentOperationDO operation, BOrderDO bOrder, FulfillmentRequestDO request,
                                  SupplierDO supplier, SupplierOfferVersionDO offerVersion,
                                  PublishedConnectorConfiguration connectorConfig, FulfillmentOperationKind kind,
                                  String rechargeAccount, String secretKey, String traceId) {
        return ticketBuilder(operation, bOrder, request, supplier, offerVersion, connectorConfig, kind, rechargeAccount, traceId)
                .secretKey(connectorConfig.credentialBindingMode() == com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode.SINGLE_SLOT ? secretKey : null)
                .credentialMaterial(resolveMaterial(connectorConfig))
                .build();
    }

    private OperationTicket.OperationTicketBuilder ticketBuilder(FulfillmentOperationDO operation, BOrderDO bOrder,
            FulfillmentRequestDO request, SupplierDO supplier, SupplierOfferVersionDO offerVersion,
            PublishedConnectorConfiguration connectorConfig, FulfillmentOperationKind kind,
            String rechargeAccount, String traceId) {
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
                    .connectTimeoutMs(connectorConfig.connectTimeoutMs())
                    .readTimeoutMs(connectorConfig.readTimeoutMs())
                    .traceId(traceId)
                    .checkpointPayload(operation.getCheckpointCiphertext())
                    .extraParameters(connectorConfig.extraParameters());
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
