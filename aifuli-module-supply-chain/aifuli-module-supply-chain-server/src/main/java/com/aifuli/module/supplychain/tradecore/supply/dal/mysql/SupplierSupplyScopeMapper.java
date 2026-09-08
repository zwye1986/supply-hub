package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SupplierSupplyScopeMapper extends BaseMapperX<SupplierSupplyScopeDO> {
    default List<SupplierSupplyScopeDO> selectBySupplierId(Long supplierId) {
        return selectList(new LambdaQueryWrapper<SupplierSupplyScopeDO>()
                .eq(SupplierSupplyScopeDO::getSupplierId, supplierId)
                .orderByAsc(SupplierSupplyScopeDO::getId));
    }

    default void deleteBySupplierId(Long supplierId) {
        delete(new LambdaQueryWrapper<SupplierSupplyScopeDO>()
                .eq(SupplierSupplyScopeDO::getSupplierId, supplierId));
    }
}
