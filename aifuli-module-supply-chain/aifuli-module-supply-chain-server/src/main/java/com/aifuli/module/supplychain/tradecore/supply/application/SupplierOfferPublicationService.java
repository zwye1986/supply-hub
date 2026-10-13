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
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSetMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferPriceMode;
import java.util.Objects;
import java.util.List;
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
import org.springframework.transaction.annotation.Isolation;

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
    /** 正式发布与上架共用 adapterType 白名单；见生命周期设计 §3.3。 */
    private final AdapterTypeAllowList adapterTypeAllowList;
    private final CredentialSetMapper credentialSetMapper;

    /**
     * 技术端新增库存也先锁供应商；草稿来源尚不可履约，后续启用时再同步重算。
     * 见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#34-闭环四同步重算的锁顺序与并发事务规范。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public String createRemoteOnDemandInventory(CreateInventorySourceCommand command) {
        SupplierLifecycleTransactions.requireReadCommitted();
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

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void activateInventorySource(String sourceNo, long expectedVersion, Long operatorId) {
        SupplierLifecycleTransactions.requireReadCommitted();
        InventorySourceDO probe = inventorySourceMapper.selectByNo(sourceNo);
        if (probe == null || supplierMapper.selectByIdForUpdate(probe.getSupplierId()) == null) {
            throw new IllegalStateException("Inventory source supplier is missing");
        }
        InventorySourceDO source = inventorySourceMapper.selectByNoForUpdate(sourceNo);
        if (source == null || !Objects.equals(source.getSupplierId(), probe.getSupplierId())
                || source.getStatus() != InventorySourceStatus.DRAFT.getCode()
                || source.getVersion() != expectedVersion) {
            throw new IllegalStateException("Inventory source is not an expected draft");
        }
        source.setStatus(InventorySourceStatus.ACTIVE.getCode());
        source.setUpdatedBy(operatorId);
        source.setUpdatedAt(now());
        if (inventorySourceMapper.updateById(source) != 1) {
            throw new IllegalStateException("Inventory source activation lost optimistic lock");
        }
        eventPublisher.publishEvent(new PricingDependencyChangedEvent(source.getPlatformSkuId()));
    }

    /**
     * 技术端与管理端新增身份行统一默认下架；先锁供应商以串行化该供应商所有供给写入。
     * 正式发布才能生成当前履约版本与绑定，禁止新增入口旁路生命周期。
     * 见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#1-核心业务规则。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public String createOffer(CreateOfferCommand command) {
        SupplierLifecycleTransactions.requireReadCommitted();
        SupplierDO supplier = requireActiveSupplier(command.supplierCode());
        PlatformSkuDO sku = requireActiveSku(command.platformSkuCode());
        LocalDateTime now = now();
        SupplierOfferDO offer = SupplierOfferDO.builder()
                .offerNo(businessNoGenerator.next("OFR_")).offerCode(command.offerCode())
                .supplierId(supplier.getId()).platformSkuId(sku.getId())
                .stabilityScore(OfferFulfillmentScore.DEFAULT)
                .timelinessScore(OfferFulfillmentScore.DEFAULT)
                .status(OfferStatus.SUSPENDED.getCode()).version(0L)
                .createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        offerMapper.insert(offer);
        return offer.getOfferNo();
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public PublishResult publish(PublishOfferCommand command) {
        SupplierLifecycleTransactions.requireReadCommitted();
        validateCommercialTerms(command);
        SupplierOfferDO probe = offerMapper.selectByNo(command.offerNo());
        if (probe == null) {
            throw new IllegalStateException("Supplier offer is missing");
        }
        SupplierDO supplier = supplierMapper.selectByIdForUpdate(probe.getSupplierId());
        if (supplier == null || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()) {
            throw new IllegalStateException("Offer supplier is not active");
        }
        LockedConnector locked = lockConnector(supplier, command.connectorVersionId());
        SupplierOfferDO offer = offerMapper.selectByNoForUpdate(command.offerNo());
        if (offer == null || !Objects.equals(offer.getSupplierId(), supplier.getId())
                || offer.getVersion() != command.expectedOfferVersion()
                || offer.getStatus() == OfferStatus.RETIRED.getCode()) {
            throw new IllegalStateException("Supplier offer is not publishable at the expected version");
        }
        PlatformSkuDO sku = productSellabilityService.requireSellableById(offer.getPlatformSkuId()).sku();
        requireFixedMode(offer);
        InventorySourceDO source = inventorySourceMapper.selectByNoForUpdate(command.inventorySourceNo());
        return publishLocked(command, offer, supplier, sku, locked, source);
    }

    /**
     * 调用者已按供应商→凭据集→连接器/版本/能力→Offer→库存顺序完成当前读。
     * 本方法不反向取得依赖锁，在同一RC事务内冻结新履约版本并同步重算价格。
     * 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#34-闭环四同步重算的锁顺序与并发事务规范。
     */
    private PublishResult publishLocked(PublishOfferCommand command, SupplierOfferDO offer, SupplierDO supplier,
                                        PlatformSkuDO sku, LockedConnector locked, InventorySourceDO source) {
        validateCommercialTerms(command);
        SupplierConnectorDO connector = locked.connector();
        SupplierConnectorVersionDO connectorVersion = locked.version();
        if (source == null || source.getStatus() == null || source.getStatus() != InventorySourceStatus.ACTIVE.getCode()
                || !Objects.equals(source.getSupplierId(), offer.getSupplierId())
                || !Objects.equals(source.getPlatformSkuId(), offer.getPlatformSkuId())
                || !SOURCE_TYPE_REMOTE_ON_DEMAND.equals(source.getSourceType())) {
            throw new IllegalStateException("Offer inventory source is not an active matching remote source");
        }
        Capability capability = Capability.valueOf(command.primaryCapabilityCode());
        if (!SupplierOfferEligibilityService.executionCapabilitiesEnabled(sku.getFulfillmentType(), capability.name(),
                command.completionMode(), command.unknownResultPolicy(), locked.capabilities())) {
            throw exception(OFFER_CAPABILITY_MISSING, supplier.getSupplierCode(), capability.name());
        }
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
     *   <li>在已持有的供应商与子对象锁内写不可变版本、绑定、当前指针与 outbox，并同步重算价格。</li>
     * </ol>
     *
     * <p>局部 READ_COMMITTED 事务先锁供应商，再按 CredentialSet→Connector→版本/能力→Offer→Inventory 顺序锁定。
     * 直接复用已锁对象执行发布，避免在 Offer 锁后回头锁连接器或再次调用供应商入口。
     * 见 {@code docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#34-闭环四同步重算的锁顺序与并发事务规范}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public PublishResult listOnShelf(ListOnShelfCommand command) {
        SupplierLifecycleTransactions.requireReadCommitted();
        log.info("[offerListOnShelf][合作商品发布请求] supplierCode={}, offerNo={}, validTo={}, operatorId={}",
                command.supplierCode(), command.offerNo(), command.validTo(), command.operatorId());
        SupplierDO supplier = supplierMapper.selectByCodeForUpdate(command.supplierCode());
        if (supplier == null || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()) {
            throw new IllegalStateException("Offer supplier is not active");
        }
        LockedConnector locked = lockConnector(supplier, null);
        SupplierConnectorDO connector = locked.connector();
        SupplierConnectorVersionDO connectorVersion = locked.version();
        SupplierOfferDO offer = offerMapper.selectByNoForUpdate(command.offerNo());
        if (offer == null || offer.getStatus() == OfferStatus.RETIRED.getCode()
                || !Objects.equals(offer.getSupplierId(), supplier.getId())) {
            throw new IllegalArgumentException("Offer does not belong to supplier or is retired");
        }
        requireFixedDraft(offer);
        PlatformSkuDO sku = productSellabilityService.requireSellableById(offer.getPlatformSkuId()).sku();
        Capability capability = derivePrimaryCapability(sku.getFulfillmentType());
        boolean capabilityEnabled = locked.capabilities().stream()
                .anyMatch(row -> row.getEnabled() != null && row.getEnabled() == 1
                        && capability.name().equals(row.getCapabilityCode()));
        if (!capabilityEnabled) {
            throw exception(OFFER_CAPABILITY_MISSING, supplier.getSupplierCode(), capability.name());
        }
        AdapterMetadata metadata = metadataRegistry.require(connector.getAdapterType());
        ExecutionProfileDefaults profile = metadata.defaultExecutionProfile();
        if (!SupplierOfferEligibilityService.executionCapabilitiesEnabled(sku.getFulfillmentType(), capability.name(),
                profile.completionMode().code(), profile.unknownResultPolicy().code(), locked.capabilities())) {
            throw exception(OFFER_CAPABILITY_MISSING, supplier.getSupplierCode(), capability.name());
        }
        String inventorySourceNo = ensureActiveRemoteInventory(offer, command.operatorId());
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
        InventorySourceDO source = inventorySourceMapper.selectByNoForUpdate(inventorySourceNo);
        return publishLocked(publishCommand, offer, supplier, sku, locked, source);
    }

    /**
     * c3 库存自动开通/复用（§2 不变量 8）：同供应商 + 同 SKU 已有 ACTIVE 的 {@code REMOTE_ON_DEMAND} 来源则复用，
     * 否则在当前供应商锁内直接新建 ACTIVE 来源；来源创建与 Offer 发布整体回滚，避免多次上架堆积来源。
     * 见 {@code docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#34-闭环四同步重算的锁顺序与并发事务规范}。
     */
    private String ensureActiveRemoteInventory(SupplierOfferDO offer, Long operatorId) {
        InventorySourceDO existing = inventorySourceMapper.selectActiveBySupplierSkuAndTypeForUpdate(
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
                .status(InventorySourceStatus.ACTIVE.getCode()).version(0L)
                .createdBy(operatorId).createdAt(now)
                .updatedBy(operatorId).updatedAt(now).build();
        inventorySourceMapper.insert(source);
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

    /** 先定位凭据集，再按固定顺序锁后重读与核对全部发布依赖。设计见上述设计§3.4。 */
    private LockedConnector lockConnector(SupplierDO supplier, Long requestedVersionId) {
        SupplierConnectorVersionDO requested = requestedVersionId == null ? null
                : connectorVersionMapper.selectById(requestedVersionId);
        SupplierConnectorDO located = requestedVersionId == null ? connectorMapper.selectPublishedBySupplier(supplier.getId())
                : requested == null ? null : connectorMapper.selectById(requested.getConnectorId());
        if (located == null || located.getCurrentVersionId() == null) {
            throw exception(SUPPLIER_NO_PUBLISHED_CONNECTOR, supplier.getSupplierCode());
        }
        Long versionId = requestedVersionId == null ? located.getCurrentVersionId() : requestedVersionId;
        SupplierConnectorVersionDO versionProbe = requestedVersionId == null
                ? connectorVersionMapper.selectById(versionId) : requested;
        if (versionProbe == null) {
            throw exception(SUPPLIER_NO_PUBLISHED_CONNECTOR, supplier.getSupplierCode());
        }
        Long credentialSetId = versionProbe.getCredentialSetId();
        if (credentialSetId != null && credentialSetMapper.selectByIdForUpdate(credentialSetId) == null) {
            throw new IllegalStateException("Connector credential set is missing");
        }
        SupplierConnectorDO connector = connectorMapper.selectByIdForUpdate(located.getId());
        SupplierConnectorVersionDO version = connectorVersionMapper.selectByIdForUpdate(versionId);
        if (connector == null || version == null || connector.getStatus() == null
                || connector.getStatus() != SupplierOfferEligibilityService.CONNECTOR_ACTIVE
                || version.getStatus() == null || version.getStatus() != ConnectorVersionStatus.PUBLISHED.getCode()
                || !Objects.equals(connector.getSupplierId(), supplier.getId())
                || !Objects.equals(version.getConnectorId(), connector.getId())
                || !Objects.equals(version.getId(), connector.getCurrentVersionId())
                || !Objects.equals(version.getCredentialSetId(), credentialSetId)
                || !adapterTypeAllowList.isAllowed(connector.getAdapterType())) {
            throw exception(SUPPLIER_NO_PUBLISHED_CONNECTOR, supplier.getSupplierCode());
        }
        List<SupplierConnectorCapabilityDO> capabilities = capabilityMapper.selectByConnectorVersionIdForUpdate(versionId);
        return new LockedConnector(connector, version, capabilities);
    }

    private static void requireFixedMode(SupplierOfferDO offer) {
        if (offer.getPriceMode() != null && !OfferPriceMode.FIXED.name().equals(offer.getPriceMode())) {
            throw new IllegalArgumentException("Phase one publication only supports FIXED price mode");
        }
    }

    private static void requireFixedDraft(SupplierOfferDO offer) {
        requireFixedMode(offer);
        if (!OfferPriceMode.FIXED.name().equals(offer.getPriceMode()) || offer.getDraftUnitPrice() == null
                || offer.getDraftUnitPrice() <= 0 || offer.getExternalSkuCode() == null || offer.getExternalSkuCode().isBlank()
                || offer.getDraftMinQuantity() == null || offer.getDraftMinQuantity() <= 0
                || offer.getDraftMaxQuantity() == null || offer.getDraftMaxQuantity() < offer.getDraftMinQuantity()) {
            throw new IllegalArgumentException("FIXED draft price, external SKU and quantity are required for publication");
        }
    }

    private record LockedConnector(SupplierConnectorDO connector, SupplierConnectorVersionDO version,
                                    List<SupplierConnectorCapabilityDO> capabilities) { }

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
        SupplierDO supplier = supplierMapper.selectByCodeForUpdate(supplierCode);
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
