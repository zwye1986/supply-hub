package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyMigrationGateDO;

import org.apache.ibatis.annotations.Mapper;

/**
 * 单行发布门禁普通读取及乐观锁CAS；见
 * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
 */
@Mapper
public interface RechargeFrequencyMigrationGateMapper
        extends BaseMapperX<RechargeFrequencyMigrationGateDO> {}
