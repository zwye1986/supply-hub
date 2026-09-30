package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 从服务端已校验的平台SKU解析SPU与履约类型；不接受客户端SPU、Offer或分销商品作为队列身份。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#2-现有实现与接入边界}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeFrequencyProductResolver {
    private final PlatformSkuMapper skuMapper;

    public ProductIdentity resolve(Long validatedPlatformSkuId) {
        log.debug("[直充限频] 解析商品 platformSkuId={}", validatedPlatformSkuId);
        var sku = skuMapper.selectById(validatedPlatformSkuId);
        if (sku == null || sku.getSpuId() == null || sku.getSpuId() <= 0) {
            throw new IllegalStateException("直充限频平台SKU无法解析SPU");
        }
        return new ProductIdentity(sku.getSpuId(), FulfillmentType.DIRECT_RECHARGE.code().equals(sku.getFulfillmentType()));
    }
    public record ProductIdentity(long platformSpuId, boolean directRecharge) { }
}
