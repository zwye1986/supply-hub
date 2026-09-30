package com.aifuli.module.supplychain.integration.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.integration.buyer.dal.dataobject.BuyerIntegrationProfileVersionDO;
import org.apache.ibatis.annotations.Mapper;

/** 采购项目集成档案版本 Mapper。 */
@Mapper
public interface BuyerIntegrationProfileVersionMapper
        extends BaseMapperX<BuyerIntegrationProfileVersionDO> {
}
