package com.aifuli.module.supplychain.infrastructure.identity.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject.BusinessCodeScopeDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 编号作用域的 MyBatis Plus 映射；先确保作用域行存在，再通过行锁串行化跨实例分配。
 * 设计见 {@code docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#5-存储迁移与并发}。
 */
@Mapper
public interface BusinessCodeScopeMapper extends BaseMapperX<BusinessCodeScopeDO> {
    /** MySQL 唯一键下原子创建作用域，随后用行锁串行化该作用域的号码分配。 */
    int ensureScope(String kind, long ownerId);

    default BusinessCodeScopeDO selectForUpdate(String kind, long ownerId) {
        return selectOneForUpdate(BusinessCodeScopeDO::getCodeKind, kind,
                BusinessCodeScopeDO::getOwnerId, ownerId);
    }
}
