package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RawStatusObservationDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 供应商原始状态观察 Mapper。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#10-adapter-元数据调用审计与敏感资产}。</p>
 */
@Mapper
public interface RawStatusObservationMapper extends BaseMapperX<RawStatusObservationDO> {
}
