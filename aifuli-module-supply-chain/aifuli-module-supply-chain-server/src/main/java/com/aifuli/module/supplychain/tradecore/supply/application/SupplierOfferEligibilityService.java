package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.security.AdapterTypeAllowList;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityMapper;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductSellabilityService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.domain.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 供应商合作商品发布资格与有效供给（结构有效性）统一解析服务。
 * 平台定价与下单路由严格共用本结构有效性守卫；合作商品列表调用本服务获取发布资格诊断。
 * 设计见 {@code docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#32-闭环二路由与定价资格统一结构有效性}。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SupplierOfferEligibilityService {

    public static final short CONNECTOR_ACTIVE = 10;
    private static final String REMOTE_ON_DEMAND = "REMOTE_ON_DEMAND";

    private final SupplierMapper supplierMapper;
    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorVersionMapper connectorVersionMapper;
    private final SupplierConnectorCapabilityMapper capabilityMapper;
    private final InventorySourceMapper inventorySourceMapper;
    private final SupplierOfferMapper offerMapper;
    private final SupplierOfferVersionMapper offerVersionMapper;
    private final PlatformSkuMapper skuMapper;
    private final PlatformSpuMapper spuMapper;
    private final ProductSellabilityService productSellabilityService;
    private final AdapterTypeAllowList adapterTypeAllowList;
    private final AdapterMetadataRegistry metadataRegistry;

    public record PublishEligibility(boolean canPublish, String publishBlockedReason) {
        public static PublishEligibility allowed() {
            return new PublishEligibility(true, null);
        }

        public static PublishEligibility blocked(String reason) {
            return new PublishEligibility(false, reason);
        }
    }

    public record EligibleOfferCandidate(
            SupplierOfferDO offer,
            SupplierDO supplier,
            SupplierOfferVersionDO version,
            Long settlementUnitPrice
    ) {
    }

    /**
     * 校验 Offer 列表项是否满足发布前置条件（纯只读，不写数据库，不发外部网络）。
     * 供合作商品列表前端展示按钮可用性及 Tooltip 提示。
     */
    public PublishEligibility checkPublishEligibility(SupplierOfferDO offer, SupplierDO supplier,
                                                      PlatformSkuDO sku, PlatformSpuDO spu) {
        if (offer == null || offer.getStatus() == null || offer.getStatus() == OfferStatus.RETIRED.getCode()
                || supplier == null || !Objects.equals(offer.getSupplierId(), supplier.getId())) {
            return PublishEligibility.blocked("合作商品已移除或归属不匹配");
        }
        if (supplier.getStatus() == null || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()) {
            return PublishEligibility.blocked("供应商非正常营业状态");
        }
        if (sku == null || spu == null || !Objects.equals(offer.getPlatformSkuId(), sku.getId())
                || !Objects.equals(sku.getSpuId(), spu.getId()) || !productSellabilityService.isSellable(sku, spu)) {
            return PublishEligibility.blocked("平台商品或规格未上架不可售");
        }
        if (offer.getExternalSkuCode() == null || offer.getExternalSkuCode().isBlank()) {
            return PublishEligibility.blocked("缺少供应商商品编码");
        }
        if (!OfferPriceMode.FIXED.name().equals(offer.getPriceMode())) {
            return PublishEligibility.blocked("一期仅支持FIXED固定供货价上架");
        }
        if (offer.getDraftUnitPrice() == null || offer.getDraftUnitPrice() <= 0) {
            return PublishEligibility.blocked("固定供货价必须大于0");
        }
        if (offer.getDraftMinQuantity() == null || offer.getDraftMinQuantity() <= 0
                || offer.getDraftMaxQuantity() == null || offer.getDraftMaxQuantity() < offer.getDraftMinQuantity()) {
            return PublishEligibility.blocked("供货数量范围无效");
        }
        SupplierConnectorDO connector = connectorMapper.selectPublishedBySupplier(supplier.getId());
        if (connector == null || connector.getCurrentVersionId() == null) {
            return PublishEligibility.blocked("供应商未配置已发布连接器");
        }
        if (connector.getStatus() == null || connector.getStatus() != CONNECTOR_ACTIVE) {
            return PublishEligibility.blocked("供应商连接器非正常状态");
        }
        if (!adapterTypeAllowList.isAllowed(connector.getAdapterType())) {
            return PublishEligibility.blocked("连接器适配器类型未在白名单启用");
        }
        SupplierConnectorVersionDO connectorVersion =
                connectorVersionMapper.selectById(connector.getCurrentVersionId());
        if (connectorVersion == null || connectorVersion.getStatus() == null
                || connectorVersion.getStatus() != ConnectorVersionStatus.PUBLISHED.getCode()
                || !Objects.equals(connector.getSupplierId(), supplier.getId())
                || !Objects.equals(connectorVersion.getConnectorId(), connector.getId())
                || !Objects.equals(connectorVersion.getId(), connector.getCurrentVersionId())) {
            return PublishEligibility.blocked("供应商连接器当前版本未发布");
        }

        try {
            Capability requiredCapability = SupplierOfferPublicationService.derivePrimaryCapability(sku.getFulfillmentType());
            ExecutionProfileDefaults profile = metadataRegistry.require(connector.getAdapterType()).defaultExecutionProfile();
            if (!executionCapabilitiesEnabled(sku.getFulfillmentType(), requiredCapability.name(),
                    profile.completionMode().code(), profile.unknownResultPolicy().code(),
                    capabilityMapper.selectByConnectorVersionId(connectorVersion.getId()))) {
                return PublishEligibility.blocked("连接器未启用所需履约主能力或查询/回调能力");
            }
        } catch (IllegalArgumentException invalidProfile) {
            return PublishEligibility.blocked("平台SKU履约类型或连接器执行画像不受支持");
        }

        return PublishEligibility.allowed();
    }

    /**
     * 判定报价是否满足「结构有效性」（Structural Validity）。
     * 定价与路由严格共用此规则。
     */
    public boolean isStructurallyEligible(SupplierOfferDO offer,
                                         SupplierDO supplier,
                                         SupplierOfferVersionDO version,
                                         SupplierConnectorDO connector,
                                         SupplierConnectorVersionDO connectorVersion,
                                         InventorySourceDO inventory,
                                         PlatformSkuDO sku, PlatformSpuDO spu,
                                         List<SupplierConnectorCapabilityDO> capabilities,
                                         LocalDateTime at) {
        if (offer == null || offer.getStatus() == null
                || offer.getStatus() != OfferStatus.PUBLISHED.getCode()) {
            return false;
        }
        if (supplier == null || supplier.getStatus() == null
                || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()
                || !Objects.equals(offer.getSupplierId(), supplier.getId())) {
            return false;
        }
        if (version == null || offer.getCurrentVersionId() == null
                || !Objects.equals(offer.getCurrentVersionId(), version.getId())
                || !Objects.equals(offer.getId(), version.getOfferId())) {
            return false;
        }
        if (version.getValidFrom() == null || at == null
                || version.getSupplierSettlementUnitPrice() == null || version.getSupplierSettlementUnitPrice() < 0
                || version.getValidFrom().isAfter(at)) {
            return false;
        }
        if (version.getValidTo() != null && !version.getValidTo().isAfter(at)) {
            return false;
        }
        if (!Money.CURRENCY_CNY.equals(version.getCurrency())) {
            return false;
        }
        if (connector == null || connector.getStatus() == null || connector.getStatus() != CONNECTOR_ACTIVE
                || !Objects.equals(connector.getSupplierId(), supplier.getId())) {
            return false;
        }
        if (connectorVersion == null || connectorVersion.getStatus() == null
                || connectorVersion.getStatus() != ConnectorVersionStatus.PUBLISHED.getCode()
                || !Objects.equals(connectorVersion.getId(), connector.getCurrentVersionId())
                || !Objects.equals(connectorVersion.getId(), version.getConnectorVersionId())
                || !Objects.equals(connectorVersion.getConnectorId(), connector.getId())) {
            return false;
        }
        if (!adapterTypeAllowList.isAllowed(connector.getAdapterType())) {
            return false;
        }
        if (inventory == null || inventory.getStatus() == null || inventory.getStatus() != InventorySourceStatus.ACTIVE.getCode()
                || !REMOTE_ON_DEMAND.equals(inventory.getSourceType())
                || !Objects.equals(inventory.getId(), version.getInventorySourceId())
                || !Objects.equals(inventory.getSupplierId(), supplier.getId())
                || !Objects.equals(inventory.getPlatformSkuId(), offer.getPlatformSkuId())) {
            return false;
        }
        if (sku == null || spu == null || !Objects.equals(offer.getPlatformSkuId(), sku.getId())
                || !Objects.equals(sku.getSpuId(), spu.getId())
                || !productSellabilityService.isSellable(sku, spu)) {
            return false;
        }
        return executionCapabilitiesEnabled(sku.getFulfillmentType(), version.getPrimaryCapabilityCode(),
                version.getCompletionMode(), version.getUnknownResultPolicy(), capabilities);
    }

    /**
     * 主能力必须与当前SKU匹配，冻结执行画像用到的查询/回调能力必须同时启用。
     * 只判断调用者已加载的事实，不获取行锁或额外查询，定价与路由复用同一规则。
     * 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#32-闭环二路由与定价资格统一结构有效性。
     */
    public static boolean executionCapabilitiesEnabled(String fulfillmentType, String primaryCapability,
                                                        String completion, String unknown,
                                                        List<SupplierConnectorCapabilityDO> capabilities) {
        try {
            Capability required = SupplierOfferPublicationService.derivePrimaryCapability(fulfillmentType);
            if (!required.name().equals(primaryCapability)) {
                return false;
            }
            Set<String> enabled = capabilities == null ? Set.of() : capabilities.stream()
                    .filter(row -> row.getEnabled() != null && row.getEnabled() == 1)
                    .map(SupplierConnectorCapabilityDO::getCapabilityCode).collect(Collectors.toSet());
            CompletionMode mode = CompletionMode.fromCode(completion);
            UnknownResultPolicy policy = UnknownResultPolicy.fromCode(unknown);
            return enabled.contains(required.name())
                    && (!(mode == CompletionMode.QUERY || mode == CompletionMode.QUERY_OR_CALLBACK
                    || policy == UnknownResultPolicy.QUERY_ORDER) || enabled.contains(Capability.QUERY_ORDER.name()))
                    && (!(mode == CompletionMode.CALLBACK || mode == CompletionMode.QUERY_OR_CALLBACK
                    || policy == UnknownResultPolicy.WAIT_CALLBACK) || enabled.contains(Capability.RECEIVE_CALLBACK.name()));
        } catch (IllegalArgumentException invalidProfile) {
            return false;
        }
    }

    /** 共享数量口径；不改写合法起购量。设计见上述设计§3.2。 */
    public static boolean isQuantityEligible(SupplierOfferVersionDO version, long quantity) {
        return version != null && version.getMinQuantity() != null && version.getMinQuantity() > 0
                && version.getMaxQuantity() != null && version.getQuantityStep() != null
                && version.getQuantityStep() > 0 && quantity >= version.getMinQuantity()
                && quantity <= version.getMaxQuantity()
                && (quantity - version.getMinQuantity()) % version.getQuantityStep() == 0;
    }

    /**
     * 解析指定平台 SKU 的所有结构有效候选版本。
     * 供 GenericBatchPricingService 调用。
     */
    public List<EligibleOfferCandidate> resolveEligibleCandidates(Long platformSkuId, LocalDateTime now) {
        if (platformSkuId == null || now == null) {
            return List.of();
        }
        return resolveEligibleCandidates(List.of(platformSkuId), now);
    }

    /**
     * 批量解析多个平台 SKU 的最低有效供货价（批量加载避免 N+1）。
     * 供 ProductCatalogQueryService、PlatformSkuQueryService 调用。
     */
    public Map<Long, Long> resolveMinimumSupplyPrices(Collection<Long> platformSkuIds, LocalDateTime now) {
        Map<Long, Long> prices = new HashMap<>();
        for (EligibleOfferCandidate candidate : resolveEligibleCandidates(platformSkuIds, now)) {
            if (isQuantityEligible(candidate.version(), StandardPricingQuoteService.SUPPORTED_QUANTITY)) {
                prices.merge(candidate.offer().getPlatformSkuId(), candidate.settlementUnitPrice(), Math::min);
            }
        }
        return prices;
    }

    /** 批量加载供给依赖及能力，候选数增加不增加数据库查询次数。设计见上述设计§3.2。 */
    public List<EligibleOfferCandidate> resolveEligibleCandidates(Collection<Long> platformSkuIds, LocalDateTime now) {
        if (platformSkuIds == null || platformSkuIds.isEmpty() || now == null) {
            return List.of();
        }
        List<PlatformSkuDO> skus = skuMapper.selectBatchIds(platformSkuIds);
        if (skus.isEmpty()) {
            return List.of();
        }
        Set<Long> spuIds = skus.stream().map(PlatformSkuDO::getSpuId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PlatformSpuDO> spuMap = spuIds.isEmpty() ? Map.of()
                : spuMapper.selectBatchIds(spuIds).stream().collect(Collectors.toMap(PlatformSpuDO::getId, s -> s));

        Map<Long, PlatformSkuDO> skuMap = skus.stream().collect(Collectors.toMap(PlatformSkuDO::getId, sku -> sku));
        Map<Long, Boolean> skuSellableMap = new HashMap<>();
        for (PlatformSkuDO sku : skus) {
            PlatformSpuDO spu = spuMap.get(sku.getSpuId());
            skuSellableMap.put(sku.getId(), productSellabilityService.isSellable(sku, spu));
        }

        List<SupplierOfferDO> offers = offerMapper.selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .in(SupplierOfferDO::getPlatformSkuId, platformSkuIds)
                .eq(SupplierOfferDO::getStatus, OfferStatus.PUBLISHED.getCode()));
        if (offers.isEmpty()) {
            return List.of();
        }

        Set<Long> supplierIds = offers.stream().map(SupplierOfferDO::getSupplierId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> versionIds = offers.stream().map(SupplierOfferDO::getCurrentVersionId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SupplierDO> supplierMap = supplierIds.isEmpty() ? Map.of()
                : supplierMapper.selectBatchIds(supplierIds).stream().collect(Collectors.toMap(SupplierDO::getId, s -> s));
        Map<Long, SupplierOfferVersionDO> versionMap = versionIds.isEmpty() ? Map.of()
                : offerVersionMapper.selectBatchIds(versionIds).stream().collect(Collectors.toMap(SupplierOfferVersionDO::getId, v -> v));

        Set<Long> connectorVersionIds = versionMap.values().stream().map(SupplierOfferVersionDO::getConnectorVersionId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> inventorySourceIds = versionMap.values().stream().map(SupplierOfferVersionDO::getInventorySourceId).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, SupplierConnectorVersionDO> connectorVersionMap = connectorVersionIds.isEmpty() ? Map.of()
                : connectorVersionMapper.selectBatchIds(connectorVersionIds).stream().collect(Collectors.toMap(SupplierConnectorVersionDO::getId, cv -> cv));
        Set<Long> connectorIds = connectorVersionMap.values().stream().map(SupplierConnectorVersionDO::getConnectorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SupplierConnectorDO> connectorMap = connectorIds.isEmpty() ? Map.of()
                : connectorMapper.selectBatchIds(connectorIds).stream().collect(Collectors.toMap(SupplierConnectorDO::getId, c -> c));

        Map<Long, InventorySourceDO> inventoryMap = inventorySourceIds.isEmpty() ? Map.of()
                : inventorySourceMapper.selectBatchIds(inventorySourceIds).stream().collect(Collectors.toMap(InventorySourceDO::getId, i -> i));

        Map<Long, List<SupplierConnectorCapabilityDO>> capabilityMap = connectorVersionIds.isEmpty() ? Map.of()
                : capabilityMapper.selectList(new LambdaQueryWrapper<SupplierConnectorCapabilityDO>()
                .in(SupplierConnectorCapabilityDO::getConnectorVersionId, connectorVersionIds)).stream()
                .collect(Collectors.groupingBy(SupplierConnectorCapabilityDO::getConnectorVersionId));
        List<EligibleOfferCandidate> candidates = new ArrayList<>();
        for (SupplierOfferDO offer : offers) {
            Long skuId = offer.getPlatformSkuId();
            if (!Boolean.TRUE.equals(skuSellableMap.get(skuId))) {
                continue;
            }
            if (offer.getCurrentVersionId() == null) {
                continue;
            }
            SupplierDO supplier = supplierMap.get(offer.getSupplierId());
            SupplierOfferVersionDO version = versionMap.get(offer.getCurrentVersionId());
            if (supplier == null || version == null) {
                continue;
            }
            SupplierConnectorVersionDO connectorVersion = connectorVersionMap.get(version.getConnectorVersionId());
            SupplierConnectorDO connector = connectorVersion == null ? null : connectorMap.get(connectorVersion.getConnectorId());
            InventorySourceDO inventory = inventoryMap.get(version.getInventorySourceId());

            if (isStructurallyEligible(offer, supplier, version, connector, connectorVersion, inventory, skuMap.get(skuId), spuMap.get(skuMap.get(skuId).getSpuId()),
                    connectorVersion == null ? List.of() : capabilityMap.getOrDefault(connectorVersion.getId(), List.of()), now)) {
                candidates.add(new EligibleOfferCandidate(offer, supplier, version, version.getSupplierSettlementUnitPrice()));
            }
        }
        return candidates;
    }
}
