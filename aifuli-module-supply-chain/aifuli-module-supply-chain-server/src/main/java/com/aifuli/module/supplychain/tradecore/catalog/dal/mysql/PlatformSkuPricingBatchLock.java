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
}
