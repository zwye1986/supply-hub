package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeModeDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 供货配置模式子表。无外键，删除范围行前必须先按 scope id 删子表。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#41-供货模式-supplymode}。
 */
@Mapper
public interface SupplierSupplyScopeModeMapper extends BaseMapperX<SupplierSupplyScopeModeDO> {

    default List<SupplierSupplyScopeModeDO> selectByScopeIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapper<SupplierSupplyScopeModeDO>()
                .in(SupplierSupplyScopeModeDO::getSupplyScopeId, ids)
                .orderByAsc(SupplierSupplyScopeModeDO::getId));
    }

    default void deleteByScopeIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        delete(new LambdaQueryWrapper<SupplierSupplyScopeModeDO>()
                .in(SupplierSupplyScopeModeDO::getSupplyScopeId, ids));
    }
}
