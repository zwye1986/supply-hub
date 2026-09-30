package com.aifuli.module.supplychain.infrastructure.auth.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAuthNonceDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/**
 * 项目应用认证 nonce Mapper。
 *
 * <p>清扫以 Lambda 方法引用绑定 {@code expire_at}，并用 MySQL {@code LIMIT} 限制单批行数。
 * 详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#64-noncestore-mysql-防重放}。</p>
 */
@Mapper
public interface ProjectAuthNonceMapper extends BaseMapperX<ProjectAuthNonceDO> {

    /**
     * 删除严格早于固定数据库本地时间截止点的过期 nonce。
     *
     * @param now 本轮清扫固定数据库本地时间截止点
     * @param limit 单批删除上限
     * @return 本批删除行数
     */
    default int deleteExpiredBefore(LocalDateTime now, int limit) {
        return delete(new LambdaQueryWrapper<ProjectAuthNonceDO>()
                .lt(ProjectAuthNonceDO::getExpireAt, now)
                .last("LIMIT " + limit));
    }
}
