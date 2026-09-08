package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierFileRefDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SupplierFileRefMapper extends BaseMapperX<SupplierFileRefDO> {
    default List<SupplierFileRefDO> selectBySupplierId(Long supplierId) {
        return selectList(new LambdaQueryWrapper<SupplierFileRefDO>()
                .eq(SupplierFileRefDO::getSupplierId, supplierId)
                .orderByAsc(SupplierFileRefDO::getFileRole)
                .orderByAsc(SupplierFileRefDO::getSortNo));
    }

    default void deleteBySupplierId(Long supplierId) {
        delete(new LambdaQueryWrapper<SupplierFileRefDO>()
                .eq(SupplierFileRefDO::getSupplierId, supplierId));
    }
}
