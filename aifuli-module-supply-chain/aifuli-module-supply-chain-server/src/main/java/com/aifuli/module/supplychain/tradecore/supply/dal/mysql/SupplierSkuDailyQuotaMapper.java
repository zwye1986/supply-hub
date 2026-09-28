package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSkuDailyQuotaDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 供应商 SKU 每日配额配置映射器；复合业务键唯一性由数据库约束兜底。
 * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#4-每日累计配额与并发}。
 */
@Mapper
public interface SupplierSkuDailyQuotaMapper extends BaseMapperX<SupplierSkuDailyQuotaDO> {

    default SupplierSkuDailyQuotaDO selectBySupplierAndSku(Long supplierId, Long platformSkuId) {
        return selectOne(new LambdaQueryWrapper<SupplierSkuDailyQuotaDO>()
                .eq(SupplierSkuDailyQuotaDO::getSupplierId, supplierId)
                .eq(SupplierSkuDailyQuotaDO::getPlatformSkuId, platformSkuId));
    }
}
