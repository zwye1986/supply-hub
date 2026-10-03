package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.security.AdapterTypeAllowList;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.Capability;
import com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator;
import com.aifuli.module.supplychain.supplieradapter.metadata.CompletionMode;
import com.aifuli.module.supplychain.supplieradapter.metadata.CreateIdempotencyMode;
import com.aifuli.module.supplychain.supplieradapter.metadata.ExecutionProfileDefaults;
import com.aifuli.module.supplychain.supplieradapter.metadata.PartialDeliveryMode;
import com.aifuli.module.supplychain.supplieradapter.metadata.ResultMultiplicity;
import com.aifuli.module.supplychain.supplieradapter.metadata.UnknownResultPolicy;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductSellabilityService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingDependencyChangedEvent;
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
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferFulfillmentScore;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.OFFER_CAPABILITY_MISSING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NO_PUBLISHED_CONNECTOR;

@Service
@Slf4j
@RequiredArgsConstructor
public class SupplierOfferPublicationService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String OUTBOX_TARGET = "supply-availability-recalculation";
    /** 远程按需库存来源类型（c3 自动开通/复用与发布校验共用，消除散落字面量，AGENTS.md §7）。 */
    private static final String SOURCE_TYPE_REMOTE_ON_DEMAND = "REMOTE_ON_DEMAND";
    /** 上架即发布默认数量步长：1 表示不设阶梯，任意数量可下单。 */
    private static final long DEFAULT_QUANTITY_STEP = 1L;
    /** 上架即发布的默认发布原因（前端只传 offerNo + 可选 validTo，§6.6）。 */
    private static final String LIST_ON_SHELF_REASON = "LIST_ON_SHELF";

    private final SupplierMapper supplierMapper;
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
    private final ProductSellabilityService productSellabilityService;
    private final ApplicationEventPublisher eventPublisher;
    /** adapterType 白名单灰度闸门（§6.8），仅 gate 上架步骤 2 的 C3 连接器候选。 */
    private final AdapterTypeAllowList adapterTypeAllowList;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createRemoteOnDemandInventory(CreateInventorySourceCommand command) {
        SupplierDO supplier = requireActiveSupplier(command.supplierCode());
        PlatformSkuDO sku = requireActiveSku(command.platformSkuCode());
        LocalDateTime now = now();
        InventorySourceDO source = InventorySourceDO.builder()
                .sourceNo(businessNoGenerator.next("INV_")).supplierId(supplier.getId())
                .platformSkuId(sku.getId()).sourceType(SOURCE_TYPE_REMOTE_ON_DEMAND)
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
                .stabilityScore(OfferFulfillmentScore.DEFAULT)
                .timelinessScore(OfferFulfillmentScore.DEFAULT)
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
        if (supplier == null || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()) {
            throw new IllegalStateException("Offer supplier is not active");
        }
        PlatformSkuDO sku = productSellabilityService.requireSellableById(offer.getPlatformSkuId()).sku();

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
                || !SOURCE_TYPE_REMOTE_ON_DEMAND.equals(source.getSourceType())) {
            throw new IllegalStateException("Offer inventory source is not an active matching remote source");
        }
        Capability capability = Capability.valueOf(command.primaryCapabilityCode());
        requireCapability(connectorVersion.getId(), capability);
        requireCapabilityMatchesSku(sku, capability);
        requireExecutionCapabilities(connectorVersion.getId(), command);

        AdapterMetadata metadata = metadataRegistry.require(connector.getAdapterType());
        int schemaVersion = metadata.offerSchemaVersion();
        JsonNode config = parse(command.fulfillmentConfigJson());
        schemaValidator.validate(metadata.offerSchemaResource(), config);
        metadata.validateOfferConfig(schemaVersion, config);
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
                .fulfillmentConfigSchemaVersion(schemaVersion)
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
        eventPublisher.publishEvent(new PricingDependencyChangedEvent(offer.getPlatformSkuId()));
        return new PublishResult(offer.getOfferNo(), version.getId(), versionNo, nextAggregateVersion);
    }

    /**
     * B1 上架即发布：把一条合作商品一步发布为可履约供给版本（合并原「新增即上架」与「逐字段发布」）。
     *
     * <p>设计见 {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#66-上架即发布服务b1核心}。
     * 前端只传 {@code offerNo}（+ 可选 {@code validTo}），其余服务端派生：</p>
     * <ol>
     *   <li>校验身份行：{@code externalSkuCode} 非空（f1）、供应商 ACTIVE、SKU 可售；</li>
     *   <li>C3 自动引用该供应商唯一已发布连接器版本，叠加 §6.8 adapterType 白名单；无候选→
     *       {@code SUPPLIER_NO_PUBLISHED_CONNECTOR}（167）；</li>
     *   <li>能力缺口：SKU {@code fulfillmentType}→{@code primaryCapability}（§9），连接器版本未启用→
     *       {@code OFFER_CAPABILITY_MISSING}（168，落实要求 #2 的明确提示）；</li>
     *   <li>c3 库存自动开通/复用（§2 不变量 8）；</li>
     *   <li>组装发布参数：结算价←{@code draft_unit_price}（g3）、执行画像←{@code defaultExecutionProfile()}（d2）、
     *       数量←身份行草稿、{@code validFrom=now}/{@code validTo} 入参（k1，空＝长期）；</li>
     *   <li>委托 {@link #publish} 写版本行 + upsert binding + 置 {@code current_version_id}/{@code version+1} + outbox。</li>
     * </ol>
     *
     * <p>本方法与 {@link #publish} 同一事务（自调用，REQUIRED 语义合并）：C3/能力守卫先于 publish 的通用
     * {@code IllegalStateException} 抛出专用业务错误码，保证前端拿到明确文案。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public PublishResult listOnShelf(ListOnShelfCommand command) {
        log.info("合作商品上架 supplierCode={} offerNo={} validTo={} operatorId={}",
                command.supplierCode(), command.offerNo(), command.validTo(), command.operatorId());
        SupplierOfferDO offer = offerMapper.selectByNoForUpdate(command.offerNo());
        if (offer == null || offer.getStatus() == OfferStatus.RETIRED.getCode()) {
            throw new IllegalStateException("Supplier offer is not listable: " + command.offerNo());
        }
        if (offer.getExternalSkuCode() == null || offer.getExternalSkuCode().isBlank()) {
            throw new IllegalArgumentException("Offer external SKU code is required before list-on-shelf");
        }
        SupplierDO supplier = supplierMapper.selectByCode(command.supplierCode());
        if (supplier == null || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()) {
            throw new IllegalStateException("Offer supplier is not active");
        }
        if (!offer.getSupplierId().equals(supplier.getId())) {
            throw new IllegalArgumentException("Offer does not belong to supplier: " + command.supplierCode());
        }
        PlatformSkuDO sku = productSellabilityService.requireSellableById(offer.getPlatformSkuId()).sku();

        SupplierConnectorDO connector = connectorMapper.selectPublishedBySupplier(offer.getSupplierId());
        if (connector == null || connector.getCurrentVersionId() == null
                || !adapterTypeAllowList.isAllowed(connector.getAdapterType())) {
            throw exception(SUPPLIER_NO_PUBLISHED_CONNECTOR, supplier.getSupplierCode());
        }
        SupplierConnectorVersionDO connectorVersion =
                connectorVersionMapper.selectById(connector.getCurrentVersionId());
        if (connectorVersion == null
                || connectorVersion.getStatus() != ConnectorVersionStatus.PUBLISHED.getCode()) {
            throw exception(SUPPLIER_NO_PUBLISHED_CONNECTOR, supplier.getSupplierCode());
        }

        Capability capability = derivePrimaryCapability(sku.getFulfillmentType());
        boolean capabilityEnabled = capabilityMapper.selectByConnectorVersionId(connectorVersion.getId()).stream()
                .anyMatch(row -> row.getEnabled() == 1 && row.getCapabilityCode().equals(capability.name()));
        if (!capabilityEnabled) {
            throw exception(OFFER_CAPABILITY_MISSING, supplier.getSupplierCode(), capability.name());
        }

        String inventorySourceNo = ensureActiveRemoteInventory(offer, command.operatorId());

        if (offer.getDraftUnitPrice() == null) {
            // TODO 非 FIXED 价模式（DISCOUNT/REALTIME）的结算价派生未在 §6.6 步骤 5（g3）定义，Phase 1 仅支持
            //  身份行 draft_unit_price；扩展前须先补设计。见
            //  docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#66-上架即发布服务b1核心。
            throw new IllegalArgumentException("Offer draft unit price is required for list-on-shelf publication");
        }
        if (offer.getDraftMinQuantity() == null || offer.getDraftMaxQuantity() == null) {
            throw new IllegalArgumentException("Offer draft quantity is required for list-on-shelf publication");
        }
        AdapterMetadata metadata = metadataRegistry.require(connector.getAdapterType());
        ExecutionProfileDefaults profile = metadata.defaultExecutionProfile();
        PublishOfferCommand publishCommand = new PublishOfferCommand(
                offer.getOfferNo(), offer.getVersion(),
                offer.getExternalSkuCode(), offer.getDraftUnitPrice(),
                connectorVersion.getId(), inventorySourceNo,
                offer.getDraftMinQuantity(), offer.getDraftMaxQuantity(), DEFAULT_QUANTITY_STEP,
                capability.name(),
                profile.completionMode().code(), profile.createIdempotencyMode().code(),
                profile.unknownResultPolicy().code(), profile.partialDeliveryMode().code(),
                profile.resultMultiplicity().code(), profile.fulfillmentConfigJson(),
                now(), command.validTo(), LIST_ON_SHELF_REASON,
                command.operatorId(), command.traceId(), command.correlationId(), command.causationId());
        return publish(publishCommand);
    }

    /**
     * c3 库存自动开通/复用（§2 不变量 8）：同供应商 + 同 SKU 已有 ACTIVE 的 {@code REMOTE_ON_DEMAND} 来源则复用，
     * 否则新建 DRAFT 并立即启用（复用 {@link #activateInventorySource} 的乐观锁激活路径），避免多次上架堆积来源。
     */
    private String ensureActiveRemoteInventory(SupplierOfferDO offer, Long operatorId) {
        InventorySourceDO existing = inventorySourceMapper.selectActiveBySupplierSkuAndType(
                offer.getSupplierId(), offer.getPlatformSkuId(), SOURCE_TYPE_REMOTE_ON_DEMAND);
        if (existing != null) {
            return existing.getSourceNo();
        }
        LocalDateTime now = now();
        InventorySourceDO source = InventorySourceDO.builder()
                .sourceNo(businessNoGenerator.next("INV_"))
                .supplierId(offer.getSupplierId())
                .platformSkuId(offer.getPlatformSkuId())
                .sourceType(SOURCE_TYPE_REMOTE_ON_DEMAND)
                .externalSourceCode(offer.getExternalSkuCode())
                .ownerType("SUPPLIER")
                .status(InventorySourceStatus.DRAFT.getCode()).version(0L)
                .createdBy(operatorId).createdAt(now)
                .updatedBy(operatorId).updatedAt(now).build();
        inventorySourceMapper.insert(source);
        activateInventorySource(source.getSourceNo(), 0L, operatorId);
        return source.getSourceNo();
    }

    private void upsertExternalSkuBinding(SupplierOfferDO offer, SupplierOfferVersionDO version,
                                          SupplierConnectorDO connector, LocalDateTime now) {
        SupplierExternalSkuBindingDO conflict = externalSkuBindingMapper.selectByConnectorAndExternalSku(
                connector.getId(), version.getExternalSkuCode());
        if (conflict != null && !conflict.getOfferId().equals(offer.getId())) {
            // 退役不可恢复，且 057 唯一键保证同供应商只有一个未退役身份占用外部编码。
            // 仅回收已退役身份的路由绑定；历史身份及版本保留供在途订单和审计使用。
            // 删除与新版本发布共用供应链事务，发布失败会恢复旧绑定。设计见
            // docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#154-退役商品释放外部编码。
            SupplierOfferDO previousOffer = offerMapper.selectById(conflict.getOfferId());
            if (previousOffer == null || previousOffer.getStatus() == null || previousOffer.getStatus() != OfferStatus.RETIRED.getCode()) {
                throw new IllegalStateException("External SKU is already bound to another offer");
            }
            if (externalSkuBindingMapper.deleteById(conflict.getId()) != 1) {
                throw new IllegalStateException("Retired external SKU binding changed during publication");
            }
            log.info("回收退役商品绑定 connectorId={} retiredOfferId={} replacementOfferId={} offerVersionId={}",
                    connector.getId(), previousOffer.getId(), offer.getId(), version.getId());
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
        CompletionMode completionMode = CompletionMode.fromCode(command.completionMode());
        UnknownResultPolicy unknownResultPolicy = UnknownResultPolicy.fromCode(command.unknownResultPolicy());
        if (completionMode == CompletionMode.QUERY || completionMode == CompletionMode.QUERY_OR_CALLBACK
                || unknownResultPolicy == UnknownResultPolicy.QUERY_ORDER) {
            requireCapability(connectorVersionId, Capability.QUERY_ORDER);
        }
        if (completionMode == CompletionMode.CALLBACK || completionMode == CompletionMode.QUERY_OR_CALLBACK
                || unknownResultPolicy == UnknownResultPolicy.WAIT_CALLBACK) {
            requireCapability(connectorVersionId, Capability.RECEIVE_CALLBACK);
        }
    }

    private static void requireCapabilityMatchesSku(PlatformSkuDO sku, Capability capability) {
        Capability required = derivePrimaryCapability(sku.getFulfillmentType());
        if (required != capability) {
            throw new IllegalArgumentException("Primary capability does not match platform SKU fulfillment type");
        }
    }

    /**
     * fulfillmentType→primaryCapability 单一映射（B1 上架即发布自动选定主能力，与
     * {@link #requireCapabilityMatchesSku} 共用，消除双份漂移）。设计见
     * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#9-测试}：
     * DIRECT_RECHARGE→CREATE_RECHARGE；VOUCHER_CODE/CARD_CODE_SECRET/VOUCHER_URL→ISSUE_ASSET；
     * ACCOUNT_BOUND_BENEFIT→BIND_BENEFIT；未知类型由 {@link FulfillmentType#fromCode} 抛
     * IllegalArgumentException。
     */
    public static Capability derivePrimaryCapability(String fulfillmentTypeCode) {
        return switch (FulfillmentType.fromCode(fulfillmentTypeCode)) {
            case DIRECT_RECHARGE -> Capability.CREATE_RECHARGE;
            case VOUCHER_CODE, CARD_CODE_SECRET, VOUCHER_URL -> Capability.ISSUE_ASSET;
            case ACCOUNT_BOUND_BENEFIT -> Capability.BIND_BENEFIT;
        };
    }

    private static void validateCommercialTerms(PublishOfferCommand command) {
        if (command.supplierSettlementUnitPrice() < 0 || command.minQuantity() <= 0
                || command.maxQuantity() < command.minQuantity() || command.quantityStep() <= 0
                || command.validFrom() == null
                || (command.validTo() != null && !command.validTo().isAfter(command.validFrom()))) {
            throw new IllegalArgumentException("Offer commercial terms or execution profile is invalid");
        }
        // 执行画像五维度取封闭枚举校验：枚举即前后端允许值的单一来源（T1 GET 暴露给前端下拉），消除漂移。
        CompletionMode.fromCode(command.completionMode());
        CreateIdempotencyMode createIdempotencyMode = CreateIdempotencyMode.fromCode(command.createIdempotencyMode());
        UnknownResultPolicy unknownResultPolicy = UnknownResultPolicy.fromCode(command.unknownResultPolicy());
        PartialDeliveryMode.fromCode(command.partialDeliveryMode());
        ResultMultiplicity.fromCode(command.resultMultiplicity());
        if (unknownResultPolicy == UnknownResultPolicy.REPLAY_SAME_KEY
                && createIdempotencyMode != CreateIdempotencyMode.REPLAYABLE_RESULT) {
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
        return productSellabilityService.requireSellableByCode(skuCode).sku();
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
                                      String fulfillmentConfigJson,
                                      LocalDateTime validFrom, LocalDateTime validTo,
                                      String publishReason, Long operatorId, String traceId,
                                      String correlationId, String causationId) {
    }

    public record PublishResult(String offerNo, Long offerVersionId,
                                int offerVersionNo, long aggregateVersion) {
    }

    /**
     * B1 上架即发布入参：前端只传 {@code offerNo}（+ 可选 {@code validTo}，空＝长期有效，k1），其余服务端派生。
     * {@code supplierCode} 来自 REST 路径，用于校验 offer 归属，防跨供应商误操作。
     * 设计见 {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#66-上架即发布服务b1核心}。
     */
    public record ListOnShelfCommand(String supplierCode, String offerNo, LocalDateTime validTo,
                                     Long operatorId, String traceId, String correlationId,
                                     String causationId) {
    }

    public record OfferPublishedPayload(String offerNo, Long offerVersionId, int offerVersionNo,
                                        Long platformSkuId, Long supplierId) {
    }
}
