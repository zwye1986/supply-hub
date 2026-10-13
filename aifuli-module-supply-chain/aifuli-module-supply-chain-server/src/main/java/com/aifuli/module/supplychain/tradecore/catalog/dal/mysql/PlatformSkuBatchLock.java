package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 多 SKU 写批次的统一预锁工具：先只读解析主键，再严格按主键升序逐行 FOR UPDATE。
 * 不依赖数据库对带 ORDER BY 的复合查询实际取得行锁的次序，不在 Mapper 工具层记录业务事实。
 * 返回的是锁后新快照；业务准入、缺失/归档错误及原请求顺序由各应用入口继续负责。
 * 设计见 {@code docs/superpowers/specs/2026-10-11-platform-product-batch-shelf-sync-design.md#41-复审修正显式-null-与相交多-sku-批次锁顺序}。
 */
public final class PlatformSkuBatchLock {
    private PlatformSkuBatchLock() { }

    /** 编码预解析阶段不持子锁；调用方必须已开启所属供应链事务。 */
    public static Map<String, PlatformSkuDO> lockByCodes(PlatformSkuMapper mapper, Collection<String> codes) {
        List<PlatformSkuDO> snapshots = codes.stream().map(mapper::selectByCode).filter(Objects::nonNull).toList();
        return lockExistingRows(mapper, snapshots);
    }

    /** 父商品已锁定的复合编辑复用已有成员范围，锁后重新加载而不使用旧状态写入。 */
    public static Map<String, PlatformSkuDO> lockExistingRows(PlatformSkuMapper mapper, Collection<PlatformSkuDO> snapshots) {
        Map<String, PlatformSkuDO> locked = new LinkedHashMap<>();
        snapshots.stream().map(PlatformSkuDO::getId).distinct().sorted(Comparator.naturalOrder()).forEach(id -> {
            PlatformSkuDO row = mapper.selectByIdForUpdate(id);
            if (row != null) locked.put(row.getSkuCode(), row);
        });
        return locked;
    }
}
