package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.InventorySourceDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InventorySourceMapper extends BaseMapperX<InventorySourceDO> {

    default InventorySourceDO selectByNo(String sourceNo) {
        return selectOne(InventorySourceDO::getSourceNo, sourceNo);
    }

    default InventorySourceDO selectByNoForUpdate(String sourceNo) {
        return selectOneForUpdate(InventorySourceDO::getSourceNo, sourceNo);
    }
}
