package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.InventorySourceDO;
import com.aifuli.module.supplychain.tradecore.supply.domain.InventorySourceStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InventorySourceMapper extends BaseMapperX<InventorySourceDO> {

    default InventorySourceDO selectByNo(String sourceNo) {
        return selectOne(InventorySourceDO::getSourceNo, sourceNo);
    }

    default InventorySourceDO selectByNoForUpdate(String sourceNo) {
        return selectOneForUpdate(InventorySourceDO::getSourceNo, sourceNo);
    }

    /**
     * 查同供应商 + 同平台 SKU 下已启用（{@link InventorySourceStatus#ACTIVE}）的指定类型库存来源。
     *
     * <p>c3 库存自动开通/复用（§2 不变量 8）：同 offer 已有匹配 ACTIVE 来源时复用不重建，避免多次上架堆积来源。
     * {@code sourceType} 由调用方以常量传入（不在 Mapper 内散落字面量，AGENTS.md §7）；{@code LIMIT 1} 防脏数据。
     * 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#66-上架即发布服务b1核心。</p>
     */
    default InventorySourceDO selectActiveBySupplierSkuAndType(Long supplierId, Long platformSkuId,
                                                               String sourceType) {
        return selectOne(new LambdaQueryWrapper<InventorySourceDO>()
                .eq(InventorySourceDO::getSupplierId, supplierId)
                .eq(InventorySourceDO::getPlatformSkuId, platformSkuId)
                .eq(InventorySourceDO::getSourceType, sourceType)
                .eq(InventorySourceDO::getStatus, InventorySourceStatus.ACTIVE.getCode())
                .orderByAsc(InventorySourceDO::getId)
                .last("LIMIT 1"));
    }
}
