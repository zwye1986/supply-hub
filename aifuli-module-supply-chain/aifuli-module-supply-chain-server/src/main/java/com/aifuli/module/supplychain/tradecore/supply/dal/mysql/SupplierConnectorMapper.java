package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierConnectorMapper extends BaseMapperX<SupplierConnectorDO> {

    default SupplierConnectorDO selectByNo(String connectorNo) {
        return selectOne(SupplierConnectorDO::getConnectorNo, connectorNo);
    }

    default SupplierConnectorDO selectByNoForUpdate(String connectorNo) {
        return selectOneForUpdate(SupplierConnectorDO::getConnectorNo, connectorNo);
    }
}
