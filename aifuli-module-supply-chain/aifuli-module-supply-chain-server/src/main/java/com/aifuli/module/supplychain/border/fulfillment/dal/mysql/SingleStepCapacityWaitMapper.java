package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SingleStepCapacityWaitDO;
import org.apache.ibatis.annotations.Mapper;

/** 等待行由已有原单聚合锁串行化，不反向取得商户根锁。 */
@Mapper
public interface SingleStepCapacityWaitMapper extends BaseMapperX<SingleStepCapacityWaitDO> { }
