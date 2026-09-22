package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.BuyerProductPricingInitializationEvent;
import com.aifuli.module.supplychain.tradecore.profile.domain.L2AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PRODUCT_DUPLICATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PRODUCT_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.L2_OVERRIDE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SETTLEMENT_PRICE_MISSING;

/**
 * 分销商品命令：从标品关联、复活已移除行、写/清手工 L2；不发布模板、不写账本。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerProductCommandService {

    private final SupplyChainDatabaseClock databaseClock;
    private final ApplicationEventPublisher eventPublisher;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final int MAX_BATCH_PRODUCT_COUNT = 200;
    private static final int MIN_DISPLAY_THRESHOLD_PERCENT = 0;
    private static final int MAX_DISPLAY_THRESHOLD_PERCENT = 100;

    private final BuyerMapper buyerMapper;
    private final BuyerProductMapper productMapper;
    private final PlatformSettlementPriceMapper l1Mapper;
    private final PlatformSettlementPriceVersionMapper l1VersionMapper;
    private final BuyerProductL2OverrideMapper overrideMapper;
    private final BuyerProductL2OverrideVersionMapper overrideVersionMapper;
    private final BuyerSkuPricingMapper buyerPricingMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final SettlementPriceResolver settlementPriceResolver;
    private final ProductSellabilityService productSellabilityService;
    private final GenericBatchPricingService genericPricingService;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public List<String> addProducts(String buyerCode, List<ProductLine> lines, Long operatorId) {
        log.info("关联分销商品 buyerCode={} skuCount={} operatorId={}",
                buyerCode, lines == null ? 0 : lines.size(), operatorId);
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("At least one SKU is required");
        }
        BuyerDO buyer = requireBuyerForUpdate(buyerCode);
        LocalDateTime now = databaseClock.now();
        List<String> codes = new ArrayList<>();
        for (ProductLine line : lines) {
            String buyerProductCode = addOrRevive(buyer, line, operatorId, now);
            codes.add(buyerProductCode);
            if (line.pricingOverrides() != null) {
                genericPricingService.saveBuyer(buyerCode, List.of(buyerProductCode),
                        line.pricingOverrides(), operatorId);
            }
        }
        return codes;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void patchL2(String buyerCode, String buyerProductCode, L2AdjustMode mode,
                        Integer percent, Long amountCents, Integer displayThresholdPercent, Long operatorId) {
        log.info("改分销商品L2 buyerCode={} buyerProductCode={} l2AdjustMode={} operatorId={}",
                buyerCode, buyerProductCode, mode, operatorId);
        BuyerProductDO product = requireLiveProduct(buyerCode, buyerProductCode);
        requireDisplayThreshold(displayThresholdPercent);
        LocalDateTime now = databaseClock.now();
        if (displayThresholdPercent != null) {
            product.setDisplayThresholdPercent(displayThresholdPercent);
            product.setUpdatedBy(operatorId);
            product.setUpdatedAt(now);
            if (productMapper.updateById(product) != 1) {
                throw exception(BUYER_PRODUCT_NOT_FOUND);
            }
        }
        PlatformSkuDO sku = requireActiveSkuById(product.getPlatformSkuId());
        applyL2Override(product, sku, mode == null ? L2AdjustMode.FOLLOW_TEMPLATE : mode,
                percent, amountCents, operatorId, now);
    }

    /**
     * 只读试算同一分销商下的多条 L2 价格。税务元数据不进入试算。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4112-分销商与项目分层批量定价}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public List<L2PricingPreview> previewL2Batch(String buyerCode, List<String> buyerProductCodes,
                                                  L2AdjustMode mode, Integer percent, Long amountCents) {
        BuyerDO buyer = requireBuyer(buyerCode);
        L2AdjustMode resolvedMode = mode == null ? L2AdjustMode.FOLLOW_TEMPLATE : mode;
        List<L2PricingPreview> previews = new ArrayList<>();
        for (String buyerProductCode : normalizeCodes(buyerProductCodes)) {
            BuyerProductDO product = requireLiveProduct(buyer.getId(), buyerProductCode, false);
            PlatformSkuDO sku = requireActiveSkuById(product.getPlatformSkuId());
            Long l1 = loadL1Cents(sku.getId());
            Long currentOverride = loadL2OverrideCents(product.getId());
            SettlementPriceResolver.ResolvedPrice current = settlementPriceResolver.resolve(null, currentOverride, l1);
            Long previewOverride = settlementPriceResolver.applyL2(resolvedMode,
                    requireL1(l1), percent, amountCents);
            SettlementPriceResolver.ResolvedPrice preview = settlementPriceResolver.resolve(null, previewOverride, l1);
            previews.add(new L2PricingPreview(product.getBuyerProductCode(), sku.getSkuCode(), l1,
                    current.unitPriceCents(), current.source(), preview.unitPriceCents(), preview.source()));
        }
        return previews;
    }

    /**
     * 在一个本地事务内批量发布分销商商品 L2 版本，任一行失败则全部回滚。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4112-分销商与项目分层批量定价}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void patchL2Batch(String buyerCode, List<String> buyerProductCodes, L2AdjustMode mode,
                             Integer percent, Long amountCents, Integer displayThresholdPercent, Long operatorId) {
        List<String> codes = normalizeCodes(buyerProductCodes);
        log.info("批量修改分销商品L2 buyerCode={} productCount={} l2AdjustMode={} operatorId={}",
                buyerCode, codes.size(), mode, operatorId);
        BuyerDO buyer = requireBuyerForUpdate(buyerCode);
        requireDisplayThreshold(displayThresholdPercent);
        LocalDateTime now = databaseClock.now();
        L2AdjustMode resolvedMode = mode == null ? L2AdjustMode.FOLLOW_TEMPLATE : mode;
        for (String buyerProductCode : codes) {
            BuyerProductDO product = requireLiveProduct(buyer.getId(), buyerProductCode, true);
            if (displayThresholdPercent != null) {
                product.setDisplayThresholdPercent(displayThresholdPercent);
                product.setUpdatedBy(operatorId);
                product.setUpdatedAt(now);
                if (productMapper.updateById(product) != 1) {
                    throw exception(BUYER_PRODUCT_NOT_FOUND);
                }
            }
            PlatformSkuDO sku = requireActiveSkuById(product.getPlatformSkuId());
            applyL2Override(product, sku, resolvedMode, percent, amountCents, operatorId, now);
        }
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void retire(String buyerCode, String buyerProductCode, Long operatorId) {
        log.info("移除分销商品 buyerCode={} buyerProductCode={} operatorId={}",
                buyerCode, buyerProductCode, operatorId);
        BuyerProductDO product = requireLiveProduct(buyerCode, buyerProductCode);
        product.setStatus(BuyerProductStatus.REMOVED.getCode());
        product.setUpdatedBy(operatorId);
        product.setUpdatedAt(databaseClock.now());
        if (productMapper.updateById(product) != 1) {
            throw exception(BUYER_PRODUCT_NOT_FOUND);
        }
    }

    /**
     * 展示阈值省略表示不设置；传入时必须是 0～100 的整数。本批只落库，不触发 C 端隐藏。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    private static void requireDisplayThreshold(Integer displayThresholdPercent) {
        if (displayThresholdPercent == null) {
            return;
        }
        if (displayThresholdPercent < MIN_DISPLAY_THRESHOLD_PERCENT
                || displayThresholdPercent > MAX_DISPLAY_THRESHOLD_PERCENT) {
            throw exception(L2_OVERRIDE_INVALID);
        }
    }

    private String addOrRevive(BuyerDO buyer, ProductLine line, Long operatorId, LocalDateTime now) {
        requireDisplayThreshold(line.displayThresholdPercent());
        PlatformSkuDO sku = requireActiveSku(line.platformSkuCode());
        BuyerProductDO existing = productMapper.selectByBuyerAndSku(buyer.getId(), sku.getId());
        L2AdjustMode mode = line.l2AdjustMode() == null ? L2AdjustMode.FOLLOW_TEMPLATE : line.l2AdjustMode();
        if (existing != null && existing.getStatus() != null
                && existing.getStatus() == BuyerProductStatus.ACTIVE.getCode()) {
            throw exception(BUYER_PRODUCT_DUPLICATE);
        }
        if (existing != null) {
            existing.setStatus(BuyerProductStatus.ACTIVE.getCode());
            existing.setDisplayThresholdPercent(line.displayThresholdPercent());
            existing.setUpdatedBy(operatorId);
            existing.setUpdatedAt(now);
            if (productMapper.updateById(existing) != 1) {
                throw exception(BUYER_PRODUCT_NOT_FOUND);
            }
            applyL2Override(existing, sku, L2AdjustMode.FOLLOW_TEMPLATE, null, null, operatorId, now);
            if (mode != L2AdjustMode.FOLLOW_TEMPLATE) {
                applyL2Override(existing, sku, mode, line.percent(), line.amountCents(), operatorId, now);
            }
            ensureUnpricedIdentity(existing.getId(), now);
            eventPublisher.publishEvent(new BuyerProductPricingInitializationEvent(existing.getId(), operatorId));
            return existing.getBuyerProductCode();
        }
        String code = businessNoGenerator.next(BusinessNoPrefixes.BPD);
        BuyerProductDO product = BuyerProductDO.builder()
                .buyerProductCode(code)
                .buyerId(buyer.getId())
                .platformSkuId(sku.getId())
                .displayThresholdPercent(line.displayThresholdPercent())
                .status(BuyerProductStatus.ACTIVE.getCode())
                .version(0L)
                .createdBy(operatorId)
                .createdAt(now)
                .updatedBy(operatorId)
                .updatedAt(now)
                .build();
        try {
            productMapper.insert(product);
        } catch (DuplicateKeyException duplicate) {
            throw exception(BUYER_PRODUCT_DUPLICATE);
        }
        applyL2Override(product, sku, mode, line.percent(), line.amountCents(), operatorId, now);
        ensureUnpricedIdentity(product.getId(), now);
        eventPublisher.publishEvent(new BuyerProductPricingInitializationEvent(product.getId(), operatorId));
        return code;
    }

    /**
     * 新建或复活分销商品时写入通用定价身份，未配置规则前保持 UNPRICED。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#1-范围与不变量}。
     */
    private void ensureUnpricedIdentity(Long buyerProductId, LocalDateTime now) {
        if (buyerPricingMapper.selectByBuyerProductId(buyerProductId) != null) {
            return;
        }
        buyerPricingMapper.insert(BuyerSkuPricingDO.builder()
                .buyerProductId(buyerProductId)
                .pricingStatus(PricingStatus.UNPRICED.name())
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /**
     * FOLLOW_TEMPLATE 只清手工覆盖指针，不碰模板表；无 L1 时 EQUAL_L1/CUSTOM 拒绝。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#8-定价解析本批管理端}。
     */
    private void applyL2Override(BuyerProductDO product, PlatformSkuDO sku, L2AdjustMode mode,
                                 Integer percent, Long amountCents, Long operatorId, LocalDateTime now) {
        if (mode == L2AdjustMode.FOLLOW_TEMPLATE) {
            clearOverride(product.getId(), now);
            return;
        }
        Long l1 = loadL1Cents(sku.getId());
        if (l1 == null) {
            throw exception(PLATFORM_SETTLEMENT_PRICE_MISSING);
        }
        Long resolved = settlementPriceResolver.applyL2(mode, l1, percent, amountCents);
        List<BuyerProductL2OverrideVersionDO> versions = overrideVersionMapper.selectByBuyerProductId(product.getId());
        int versionNo = versions.isEmpty() ? 1 : versions.getLast().getVersionNo() + 1;
        BuyerProductL2OverrideVersionDO version = BuyerProductL2OverrideVersionDO.builder()
                .buyerProductId(product.getId())
                .versionNo(versionNo)
                .adjustMode(mode.name())
                .percent(percent)
                .amountCents(amountCents)
                .resolvedUnitPriceCents(resolved)
                .createdBy(operatorId)
                .createdAt(now)
                .build();
        overrideVersionMapper.insert(version);
        BuyerProductL2OverrideDO identity = overrideMapper.selectByBuyerProductId(product.getId());
        if (identity == null) {
            overrideMapper.insert(BuyerProductL2OverrideDO.builder()
                    .buyerProductId(product.getId())
                    .currentVersionId(version.getId())
                    .version(0L)
                    .updatedAt(now)
                    .build());
            return;
        }
        identity.setCurrentVersionId(version.getId());
        identity.setUpdatedAt(now);
        overrideMapper.updateById(identity);
    }

    private void clearOverride(Long buyerProductId, LocalDateTime now) {
        BuyerProductL2OverrideDO identity = overrideMapper.selectByBuyerProductId(buyerProductId);
        if (identity == null) {
            return;
        }
        if (overrideMapper.clearCurrentVersion(buyerProductId, identity.getVersion(), now) != 1) {
            throw exception(BUYER_PRODUCT_NOT_FOUND);
        }
    }

    private Long loadL1Cents(Long skuId) {
        PlatformSettlementPriceDO identity = l1Mapper.selectById(skuId);
        if (identity == null || identity.getCurrentVersionId() == null) {
            return null;
        }
        PlatformSettlementPriceVersionDO version = l1VersionMapper.selectById(identity.getCurrentVersionId());
        if (version == null) {
            return null;
        }
        return version.getUnitPriceCents();
    }

    private Long loadL2OverrideCents(Long buyerProductId) {
        BuyerProductL2OverrideDO identity = overrideMapper.selectByBuyerProductId(buyerProductId);
        if (identity == null || identity.getCurrentVersionId() == null) {
            return null;
        }
        BuyerProductL2OverrideVersionDO version = overrideVersionMapper.selectById(identity.getCurrentVersionId());
        return version == null ? null : version.getResolvedUnitPriceCents();
    }

    private static long requireL1(Long l1) {
        if (l1 == null) {
            throw exception(PLATFORM_SETTLEMENT_PRICE_MISSING);
        }
        return l1;
    }

    private BuyerDO requireBuyer(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode);
        if (buyer == null) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        return buyer;
    }

    private BuyerDO requireBuyerForUpdate(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCodeForUpdate(buyerCode);
        if (buyer == null) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        return buyer;
    }

    private BuyerProductDO requireLiveProduct(String buyerCode, String buyerProductCode) {
        BuyerDO buyer = requireBuyer(buyerCode);
        return requireLiveProduct(buyer.getId(), buyerProductCode, true);
    }

    private BuyerProductDO requireLiveProduct(Long buyerId, String buyerProductCode, boolean forUpdate) {
        BuyerProductDO product = forUpdate ? productMapper.selectByCodeForUpdate(buyerProductCode)
                : productMapper.selectByCode(buyerProductCode);
        if (product == null
                || !buyerId.equals(product.getBuyerId())
                || product.getStatus() == null
                || product.getStatus() != BuyerProductStatus.ACTIVE.getCode()) {
            throw exception(BUYER_PRODUCT_NOT_FOUND);
        }
        return product;
    }

    private static List<String> normalizeCodes(List<String> codes) {
        if (codes == null || codes.isEmpty() || codes.size() > MAX_BATCH_PRODUCT_COUNT) {
            throw new IllegalArgumentException("buyerProductCodes must contain 1 to 200 items");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                throw exception(BUYER_PRODUCT_NOT_FOUND);
            }
            normalized.add(code.trim());
        }
        return List.copyOf(normalized);
    }

    private PlatformSkuDO requireActiveSku(String platformSkuCode) {
        return productSellabilityService.requireSellableByCode(platformSkuCode).sku();
    }

    private PlatformSkuDO requireActiveSkuById(Long skuId) {
        return productSellabilityService.requireSellableById(skuId).sku();
    }

    /**
     * 关联一行分销商品。{@code pricingOverrides} 为空时只建立目录并初始化继承定价；
     * 非空时与目录写入处在同一事务，缺少平台默认定价则整单回滚。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    public record ProductLine(String platformSkuCode, L2AdjustMode l2AdjustMode,
                              Integer percent, Long amountCents, Integer displayThresholdPercent,
                              ProjectOverrides pricingOverrides) {
    }

    public record L2PricingPreview(
            @Schema(description = "分销商品永久业务编码") String buyerProductCode,
            @Schema(description = "平台SKU永久业务编码") String platformSkuCode,
            @Schema(description = "L1平台结算单价，单位CNY分") Long l1PriceCents,
            @Schema(description = "当前L2有效单价，单位CNY分") long currentL2PriceCents,
            @Schema(description = "当前L2有效价来源：L1或L2") PriceSource currentPriceSource,
            @Schema(description = "试算后L2有效单价，单位CNY分") long previewL2PriceCents,
            @Schema(description = "试算后L2有效价来源：L1或L2") PriceSource previewPriceSource) {
    }
}
