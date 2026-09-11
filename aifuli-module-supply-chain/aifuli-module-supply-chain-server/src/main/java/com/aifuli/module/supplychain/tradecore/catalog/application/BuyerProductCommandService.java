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
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.profile.domain.L2AdjustMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PRODUCT_DUPLICATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PRODUCT_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SETTLEMENT_PRICE_MISSING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SKU_NOT_SALEABLE;

/**
 * 分销商品命令：从标品关联、复活已移除行、写/清手工 L2；不发布模板、不写账本。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerProductCommandService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final BuyerMapper buyerMapper;
    private final BuyerProductMapper productMapper;
    private final PlatformSettlementPriceMapper l1Mapper;
    private final PlatformSettlementPriceVersionMapper l1VersionMapper;
    private final BuyerProductL2OverrideMapper overrideMapper;
    private final BuyerProductL2OverrideVersionMapper overrideVersionMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final SettlementPriceResolver settlementPriceResolver;
    private final ProductSellabilityService productSellabilityService;

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
            codes.add(addOrRevive(buyer, line, operatorId, now));
        }
        return codes;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void patchL2(String buyerCode, String buyerProductCode, L2AdjustMode mode,
                        Integer percent, Long amountCents, Integer displayThresholdPercent, Long operatorId) {
        log.info("改分销商品L2 buyerCode={} buyerProductCode={} l2AdjustMode={} operatorId={}",
                buyerCode, buyerProductCode, mode, operatorId);
        BuyerProductDO product = requireLiveProduct(buyerCode, buyerProductCode);
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

    private String addOrRevive(BuyerDO buyer, ProductLine line, Long operatorId, LocalDateTime now) {
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
        return code;
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

    private BuyerDO requireBuyerForUpdate(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCodeForUpdate(buyerCode);
        if (buyer == null) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        return buyer;
    }

    private BuyerProductDO requireLiveProduct(String buyerCode, String buyerProductCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode);
        if (buyer == null) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        BuyerProductDO product = productMapper.selectByCodeForUpdate(buyerProductCode);
        if (product == null
                || !buyer.getId().equals(product.getBuyerId())
                || product.getStatus() == null
                || product.getStatus() != BuyerProductStatus.ACTIVE.getCode()) {
            throw exception(BUYER_PRODUCT_NOT_FOUND);
        }
        return product;
    }

    private PlatformSkuDO requireActiveSku(String platformSkuCode) {
        return productSellabilityService.requireSellableByCode(platformSkuCode).sku();
    }

    private PlatformSkuDO requireActiveSkuById(Long skuId) {
        return productSellabilityService.requireSellableById(skuId).sku();
    }

    public record ProductLine(String platformSkuCode, L2AdjustMode l2AdjustMode,
                              Integer percent, Long amountCents, Integer displayThresholdPercent) {
    }
}
