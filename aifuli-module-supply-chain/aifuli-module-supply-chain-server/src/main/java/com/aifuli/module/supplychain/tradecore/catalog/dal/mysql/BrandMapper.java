package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BrandDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BrandMapper extends BaseMapperX<BrandDO> {
    default BrandDO selectByCode(String code) {
        return selectOne(BrandDO::getBrandCode, code);
    }

    default BrandDO selectByCodeForUpdate(String code) {
        return selectOneForUpdate(BrandDO::getBrandCode, code);
    }
}
