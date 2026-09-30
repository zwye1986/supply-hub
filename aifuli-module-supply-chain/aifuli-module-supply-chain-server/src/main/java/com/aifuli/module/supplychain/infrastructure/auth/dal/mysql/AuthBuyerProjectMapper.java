package com.aifuli.module.supplychain.infrastructure.auth.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.AuthBuyerProjectDO;
import org.apache.ibatis.annotations.Mapper;

/** 认证链路采购项目只读 Mapper。 */
@Mapper
public interface AuthBuyerProjectMapper extends BaseMapperX<AuthBuyerProjectDO> {
}
