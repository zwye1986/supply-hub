package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BOrderMapper extends BaseMapperX<BOrderDO> {
}
