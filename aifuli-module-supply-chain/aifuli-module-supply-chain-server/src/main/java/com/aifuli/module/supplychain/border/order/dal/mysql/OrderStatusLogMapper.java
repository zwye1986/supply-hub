package com.aifuli.module.supplychain.border.order.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderStatusLogMapper extends BaseMapperX<OrderStatusLogDO> {
}
