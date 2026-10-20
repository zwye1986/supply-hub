package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingDO;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 全批次共用的平台定价身份根锁；仅锁定价身份，不反向取得商品、供应商或连接器排他锁。
 * 调用者须处于供应链RC事务，在第一条子层定价写入前收齐目标，再按主键升序加锁。
 * 无身份时保留null，由业务入口决定初始化或拒绝，不能凭锁工具重建配置。
 * 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#34-闭环四同步重算的锁顺序与并发事务规范。
 */
public final class PlatformSkuPricingBatchLock {
    private PlatformSkuPricingBatchLock() { }
    public static Map<Long, PlatformSkuPricingDO> lockByIds(PlatformSkuPricingMapper mapper, Collection<Long> skuIds) {
        Map<Long, PlatformSkuPricingDO> roots = new HashMap<>();
        skuIds.stream().distinct().sorted().forEach(id -> roots.put(id, mapper.selectBySkuIdForUpdate(id)));
        return roots;
    }

    /**
     * 收齐全批唯一根后才允许进入子层。缺行时插入UNPRICED身份，唯一键串行化并发初始化，
     * 不生成配置/价格版本，不反向锁SKU。设计见docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#52-锁与写入责任。
     */
    public static Map<Long, PlatformSkuPricingDO> lockOrCreateByIds(PlatformSkuPricingMapper mapper,
            Collection<Long> skuIds, java.time.LocalDateTime now) {
        Map<Long, PlatformSkuPricingDO> roots = new HashMap<>();
        skuIds.stream().filter(java.util.Objects::nonNull).distinct().sorted().forEach(id -> {
            var root = mapper.selectBySkuIdForUpdate(id);
            if (root == null) {
                root = PlatformSkuPricingDO.builder().platformSkuId(id)
                        .pricingStatus(com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus.UNPRICED.name())
                        .version(0L).createdAt(now).updatedAt(now).build();
                try { mapper.insert(root); }
                catch (org.springframework.dao.DuplicateKeyException concurrentInitialization) {
                    root = mapper.selectBySkuIdForUpdate(id);
                    if (root == null) throw new IllegalStateException("platform pricing root initialization failed", concurrentInitialization);
                }
            }
            roots.put(id, root);
        });
        return roots;
    }
}
