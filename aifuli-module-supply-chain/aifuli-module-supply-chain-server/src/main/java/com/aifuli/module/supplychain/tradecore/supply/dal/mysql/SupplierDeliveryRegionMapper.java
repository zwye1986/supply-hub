package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDeliveryRegionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SupplierDeliveryRegionMapper extends BaseMapperX<SupplierDeliveryRegionDO> {
    default List<SupplierDeliveryRegionDO> selectBySupplierId(Long supplierId) {
        return selectList(new LambdaQueryWrapper<SupplierDeliveryRegionDO>()
                .eq(SupplierDeliveryRegionDO::getSupplierId, supplierId)
                .orderByAsc(SupplierDeliveryRegionDO::getAdcode));
    }

    default void deleteBySupplierId(Long supplierId) {
        delete(new LambdaQueryWrapper<SupplierDeliveryRegionDO>()
                .eq(SupplierDeliveryRegionDO::getSupplierId, supplierId));
    }
}
