package com.aifuli.module.supplychain.infrastructure.identity.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject.OrderNoSequenceDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 日期高水位映射；XML仅表达首次日期行的MySQL upsert。设计见 {@code docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#42-申请号段的短事务}。 */
@Mapper
public interface OrderNoSequenceMapper extends BaseMapperX<OrderNoSequenceDO> {
    int ensureDate(@Param("date") LocalDate date, @Param("now") LocalDateTime now);
}
