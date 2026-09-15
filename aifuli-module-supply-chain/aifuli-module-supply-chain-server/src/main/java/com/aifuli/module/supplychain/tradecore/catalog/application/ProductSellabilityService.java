package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SKU_NOT_SALEABLE;

/**
 * 平台商品有效可售状态校验，统一执行 SPU 与 SKU 双亲判断。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#31-spu-与-sku-状态解耦与有效可售计算}。
 */
@Service
@RequiredArgsConstructor
public class ProductSellabilityService {

    private final PlatformSkuMapper skuMapper;
    private final PlatformSpuMapper spuMapper;

    public SellableProduct requireSellableByCode(String skuCode) {
        PlatformSkuDO sku = skuMapper.selectByCode(skuCode);
        return requireSellable(sku);
    }

    public SellableProduct requireSellableById(Long skuId) {
        PlatformSkuDO sku = skuId == null ? null : skuMapper.selectById(skuId);
        return requireSellable(sku);
    }

    /**
     * 校验调用方已加载的平台 SKU，避免需要悲观锁的交易链路重新读取 SKU 并破坏锁后快照顺序。
     *
     * <p>设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。</p>
     */
    public SellableProduct requireSellable(PlatformSkuDO sku) {
        return requireSellableInternal(sku);
    }

    public boolean isSellable(PlatformSkuDO sku, PlatformSpuDO spu) {
        return sku != null && spu != null
                && sku.getStatus() != null && sku.getStatus() == CatalogEntityStatus.ACTIVE.getCode()
                && spu.getStatus() != null && spu.getStatus() == CatalogEntityStatus.ACTIVE.getCode();
    }

    private SellableProduct requireSellableInternal(PlatformSkuDO sku) {
        PlatformSpuDO spu = sku == null || sku.getSpuId() == null ? null : spuMapper.selectById(sku.getSpuId());
        if (!isSellable(sku, spu)) {
            throw exception(SKU_NOT_SALEABLE);
        }
        return new SellableProduct(sku, spu);
    }

    public record SellableProduct(PlatformSkuDO sku, PlatformSpuDO spu) {
    }
}
