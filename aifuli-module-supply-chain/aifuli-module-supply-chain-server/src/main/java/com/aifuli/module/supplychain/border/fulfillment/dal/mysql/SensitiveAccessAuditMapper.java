package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SensitiveAccessAuditDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敏感数据访问审计 Mapper。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}。</p>
 */
@Mapper
public interface SensitiveAccessAuditMapper extends BaseMapperX<SensitiveAccessAuditDO> {
}
