package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformSpuMapper extends BaseMapperX<PlatformSpuDO> {
    default PlatformSpuDO selectByCode(String code) {
        return selectOne(PlatformSpuDO::getSpuCode, code);
    }

    default PlatformSpuDO selectByCodeForUpdate(String code) {
        return selectOneForUpdate(PlatformSpuDO::getSpuCode, code);
    }
}
