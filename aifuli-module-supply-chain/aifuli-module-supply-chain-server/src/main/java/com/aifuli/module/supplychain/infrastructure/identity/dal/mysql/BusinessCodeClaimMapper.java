package com.aifuli.module.supplychain.infrastructure.identity.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject.BusinessCodeClaimDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 编号占用记录的 MyBatis Plus 映射；唯一键限定同一作用域的同一号码只能成功占用一次。
 * 设计见 {@code docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#5-存储迁移与并发}。
 */
@Mapper
public interface BusinessCodeClaimMapper extends BaseMapperX<BusinessCodeClaimDO> {
}
