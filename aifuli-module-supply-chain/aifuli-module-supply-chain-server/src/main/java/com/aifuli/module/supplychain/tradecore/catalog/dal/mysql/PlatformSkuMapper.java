package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformSkuMapper extends BaseMapperX<PlatformSkuDO> {
    default PlatformSkuDO selectByCode(String code) {
        return selectOne(PlatformSkuDO::getSkuCode, code);
    }

    default PlatformSkuDO selectByCodeForUpdate(String code) {
        return selectOneForUpdate(PlatformSkuDO::getSkuCode, code);
    }
}
