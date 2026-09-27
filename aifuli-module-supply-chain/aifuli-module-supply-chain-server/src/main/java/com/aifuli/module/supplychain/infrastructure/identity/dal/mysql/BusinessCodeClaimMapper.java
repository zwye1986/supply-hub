package com.aifuli.module.supplychain.infrastructure.identity.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject.BusinessCodeClaimDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BusinessCodeClaimMapper extends BaseMapperX<BusinessCodeClaimDO> {
}
