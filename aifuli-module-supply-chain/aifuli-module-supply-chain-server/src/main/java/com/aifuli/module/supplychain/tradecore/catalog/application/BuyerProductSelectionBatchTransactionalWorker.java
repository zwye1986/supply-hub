package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService.ProductLine;

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
 * 页面选品的独立代理事务worker，承担原参数整批BPD新建/复活、L2、继承/行内定价及DRAFT身份写入。
 * 调用者须在无活动事务的编排入口经本bean真实代理执行；任一行失败使全部关联及版本一起回滚，
 * 不拆成每行提交，不发布商业目录、不写账本，保留原买家锁及定价锁序、已有LST只读复用。
 * LST专用身份竞争穿出代理回滚后才由独立probe裁决重跑；BPD重复仍用原业务码且不重试，
 * 捕获真实BPD唯一异常转换时保留SQL cause，普通已ACTIVE拒绝不伪造技术原因。
 * 设计见 {@code docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#34-批次事务边界真实调用者与竞争重试}
 * §3.4「批次事务边界、真实调用者与竞争重试」及§7.1「固定锁序及禁止项」；
 * 原关联规则见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerProductSelectionBatchTransactionalWorker {

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
    private final BuyerAcceptanceCatalogIdentityWriter identityWriter;

    /**
     * 在同一个完整供应链事务执行原批次，每行身份和价格均加入本事务，只有全部成功才返回编码。
     * 专用LST竞争不在这里继续写入或重试；异常必须穿出真实代理完成整批回滚后再探测。
     * 设计见 {@code docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#34-批次事务边界真实调用者与竞争重试}
     * §3.4「批次事务边界、真实调用者与竞争重试」。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public List<String> executeBatch(String buyerCode, List<ProductLine> lines, Long operatorId) {
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

    /**
     * 既有BPD缺受理关系的独立完整修复事务，先锁真实BPD再调用同事务身份writer。
     * 已有LST仅普通读校验，不反锁/接管；A期只补DRAFT身份，不追加刷新或声称已可售。
     * 竞争同样须穿出真实代理完全回滚，不能在本事务内probe后继续写入。
     * 设计见 {@code docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#33-已有页面数据补偿}
     * §3.3「已有页面数据补偿」及§3.4「批次事务边界、真实调用者与竞争重试」。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String ensureIdentity(String buyerProductCode, Long operatorId) {
        BuyerProductDO product = productMapper.selectByCodeForUpdate(buyerProductCode);
        if (product == null) { throw exception(BUYER_PRODUCT_NOT_FOUND); }
        return identityWriter.ensure(product, operatorId, databaseClock.now());
    }

    private static void requireDisplayThreshold(Integer value) {
        if (value != null && (value < MIN_DISPLAY_THRESHOLD_PERCENT || value > MAX_DISPLAY_THRESHOLD_PERCENT)) {
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
            identityWriter.ensure(existing, operatorId, now);
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
            if (duplicate.getMessage() == null || !duplicate.getMessage().contains("uk_sc_buyer_product_sku")) { throw duplicate; }
            var businessFailure = exception(BUYER_PRODUCT_DUPLICATE);
            businessFailure.initCause(duplicate);
            throw businessFailure;
        }
        applyL2Override(product, sku, mode, line.percent(), line.amountCents(), operatorId, now);
        ensureUnpricedIdentity(product.getId(), now);
        eventPublisher.publishEvent(new BuyerProductPricingInitializationEvent(product.getId(), operatorId));
        identityWriter.ensure(product, operatorId, now);
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

}
