package com.aifuli.module.supplychain.infrastructure.identity.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject.OrderNoSegmentDO;
import org.apache.ibatis.annotations.Mapper;

/** 永久号段记录只插入，不更新或回收。设计见 {@code docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#41-mysql永久预留与所有权}。 */
@Mapper
public interface OrderNoSegmentMapper extends BaseMapperX<OrderNoSegmentDO> { }
