package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierMapper extends BaseMapperX<SupplierDO> {
    default SupplierDO selectByCode(String code) {
        return selectOne(SupplierDO::getSupplierCode, code);
    }

    default SupplierDO selectByCodeForUpdate(String code) {
        return selectOneForUpdate(SupplierDO::getSupplierCode, code);
    }
}
