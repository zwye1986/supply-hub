package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierContactDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SupplierContactMapper extends BaseMapperX<SupplierContactDO> {
    default List<SupplierContactDO> selectBySupplierId(Long supplierId) {
        return selectList(new LambdaQueryWrapper<SupplierContactDO>()
                .eq(SupplierContactDO::getSupplierId, supplierId)
                .orderByAsc(SupplierContactDO::getSortNo));
    }

    default void deleteBySupplierId(Long supplierId) {
        delete(new LambdaQueryWrapper<SupplierContactDO>()
                .eq(SupplierContactDO::getSupplierId, supplierId));
    }
}
