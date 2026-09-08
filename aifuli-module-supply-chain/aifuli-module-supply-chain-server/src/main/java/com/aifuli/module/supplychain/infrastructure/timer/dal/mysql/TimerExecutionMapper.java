package com.aifuli.module.supplychain.infrastructure.timer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerExecutionDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TimerExecutionMapper extends BaseMapperX<TimerExecutionDO> {
}
