package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformSkuMapper extends BaseMapperX<PlatformSkuDO> {
    default PlatformSkuDO selectByCode(String code) {
        return selectOne(PlatformSkuDO::getSkuCode, code);
    }

    default PlatformSkuDO selectByCodeForUpdate(String code) {
        return selectOneForUpdate(PlatformSkuDO::getSkuCode, code);
    }

    /**
     * 悲观锁锁定平台 SKU，保障限购判断期间 SKU 状态与限额快照的一致性。
     *
     * <p>设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。</p>
     */
    default PlatformSkuDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(PlatformSkuDO::getId, id);
    }
}
