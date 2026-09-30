package com.aifuli.module.supplychain.integration.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.integration.buyer.dal.dataobject.BuyerIntegrationProfileDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/** 采购项目集成档案 Mapper。 */
@Mapper
public interface BuyerIntegrationProfileMapper extends BaseMapperX<BuyerIntegrationProfileDO> {

    /**
     * 按采购项目读取唯一集成档案，列绑定使用类型安全的方法引用以避免字段名漂移。
     *
     * <p>设计见
     * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#5-项目集成档案解析}。</p>
     *
     * @param projectId 采购项目内部主键
     * @return 项目集成档案，不存在时返回 {@code null}
     */
    default BuyerIntegrationProfileDO selectByProjectId(Long projectId) {
        return selectOne(new LambdaQueryWrapper<BuyerIntegrationProfileDO>()
                .eq(BuyerIntegrationProfileDO::getProjectId, projectId));
    }
}
