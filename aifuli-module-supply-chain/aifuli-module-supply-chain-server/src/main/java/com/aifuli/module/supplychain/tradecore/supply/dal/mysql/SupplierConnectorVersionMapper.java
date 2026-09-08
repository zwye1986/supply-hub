package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierConnectorVersionMapper extends BaseMapperX<SupplierConnectorVersionDO> {

    default SupplierConnectorVersionDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(SupplierConnectorVersionDO::getId, id);
    }

    default SupplierConnectorVersionDO selectLatest(Long connectorId) {
        return selectOne(new LambdaQueryWrapper<SupplierConnectorVersionDO>()
                .eq(SupplierConnectorVersionDO::getConnectorId, connectorId)
                .orderByDesc(SupplierConnectorVersionDO::getVersionNo)
                .last("LIMIT 1"));
    }
}
