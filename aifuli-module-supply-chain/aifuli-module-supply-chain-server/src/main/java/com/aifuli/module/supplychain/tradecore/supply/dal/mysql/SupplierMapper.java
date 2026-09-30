package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SupplierMapper extends BaseMapperX<SupplierDO> {
    default SupplierDO selectByCode(String code) {
        return selectOne(SupplierDO::getSupplierCode, code);
    }

    default SupplierDO selectByCodeForUpdate(String code) {
        return selectOneForUpdate(SupplierDO::getSupplierCode, code);
    }

    /**
     * 商品列表只允许正常供应商参与候选商品和供应商展示。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d47）。
     */
    default List<Long> selectActiveIds() {
        return selectList(new LambdaQueryWrapper<SupplierDO>()
                .select(SupplierDO::getId)
                .eq(SupplierDO::getStatus, SupplierStatus.ACTIVE.getCode()))
                .stream()
                .map(SupplierDO::getId)
                .toList();
    }
}
