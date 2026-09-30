package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierIntegrationModeDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SupplierIntegrationModeMapper extends BaseMapperX<SupplierIntegrationModeDO> {
    default List<SupplierIntegrationModeDO> selectBySupplierId(Long supplierId) {
        return selectList(SupplierIntegrationModeDO::getSupplierId, supplierId);
    }

    default void deleteBySupplierId(Long supplierId) {
        delete(new LambdaQueryWrapper<SupplierIntegrationModeDO>()
                .eq(SupplierIntegrationModeDO::getSupplierId, supplierId));
    }
}
