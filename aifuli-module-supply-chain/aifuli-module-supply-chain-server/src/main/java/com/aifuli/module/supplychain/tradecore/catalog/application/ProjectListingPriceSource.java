package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 项目旧 L3 接口的 B 端取价入口：当前分销商通用版本优先，历史行才读取旧 L1/L2。
 * 平台基价必须取分销商版本引用的平台版本，避免同一冻结价混用不同版本。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
 */
@Service
@RequiredArgsConstructor
public class ProjectListingPriceSource {

    private final BuyerSkuPricingMapper buyerPricingMapper;
    private final BuyerSkuPricingVersionMapper buyerPricingVersionMapper;
    private final PlatformSkuPricingVersionMapper platformPricingVersionMapper;
    private final PlatformSettlementPriceMapper l1Mapper;
    private final PlatformSettlementPriceVersionMapper l1VersionMapper;
    private final BuyerProductL2OverrideMapper overrideMapper;
    private final BuyerProductL2OverrideVersionMapper overrideVersionMapper;
    private final ObjectMapper objectMapper;

    /**
     * 当前通用身份有版本指针时绝不退回旧价；缺失的金额由命令层按所需模式拒绝，查询层保留空值。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#6-管理端接口}。
     */
    public PriceBasis resolve(BuyerProductDO product) {
        BuyerSkuPricingDO identity = buyerPricingMapper.selectByBuyerProductId(product.getId());
        if (identity != null && identity.getCurrentVersionId() != null) {
            BuyerSkuPricingVersionDO version = buyerPricingVersionMapper.selectById(identity.getCurrentVersionId());
            if (version == null) {
                return new PriceBasis(null, null, null, true);
            }
            PlatformSkuPricingVersionDO platformVersion = version.getPlatformPricingVersionId() == null
                    ? null : platformPricingVersionMapper.selectById(version.getPlatformPricingVersionId());
            if (platformVersion == null) {
                return new PriceBasis(null, null, null, true);
            }
            boolean inherited = distributionInherited(version.getOverrideJson());
            return new PriceBasis(platformVersion.getDistributionPriceCents(), version.getDistributionPriceCents(),
                    inherited ? PriceSource.L1 : PriceSource.L2, true);
        }
        Long l1 = loadL1(product.getPlatformSkuId());
        Long l2 = loadL2(product.getId());
        return new PriceBasis(l1, l2 == null ? l1 : l2,
                l2 != null ? PriceSource.L2 : l1 != null ? PriceSource.L1 : null, false);
    }

    /** 只供兼容字段回显；通用定价决策不得调用此入口。 */
    public Long legacyPlatformPrice(Long skuId) {
        return loadL1(skuId);
    }

    private boolean distributionInherited(String overrideJson) {
        if (overrideJson == null || overrideJson.isBlank()) {
            return true;
        }
        try {
            ProjectOverrides overrides = objectMapper.readValue(overrideJson, ProjectOverrides.class);
            return overrides.distributionInherited();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("buyer pricing override deserialization failed", ex);
        }
    }

    private Long loadL1(Long skuId) {
        if (skuId == null) {
            return null;
        }
        PlatformSettlementPriceDO identity = l1Mapper.selectById(skuId);
        if (identity == null || identity.getCurrentVersionId() == null) {
            return null;
        }
        PlatformSettlementPriceVersionDO version = l1VersionMapper.selectById(identity.getCurrentVersionId());
        return version == null ? null : version.getUnitPriceCents();
    }

    private Long loadL2(Long buyerProductId) {
        BuyerProductL2OverrideDO identity = overrideMapper.selectByBuyerProductId(buyerProductId);
        if (identity == null || identity.getCurrentVersionId() == null) {
            return null;
        }
        BuyerProductL2OverrideVersionDO version = overrideVersionMapper.selectById(identity.getCurrentVersionId());
        return version == null ? null : version.getResolvedUnitPriceCents();
    }

    public record PriceBasis(Long platformBaseCents, Long distributionCents,
                             PriceSource distributionSource, boolean generic) {
    }
}
