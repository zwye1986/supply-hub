package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectCapabilityAuditDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/** 项目能力追加审计，见 docs/superpowers/specs/2026-10-12-project-standard-product-capability-design.md#2-持久化与并发。 */
@Mapper
public interface ProjectCapabilityAuditMapper extends BaseMapperX<ProjectCapabilityAuditDO> {
    default ProjectCapabilityAuditDO selectLatest(Long projectId, String capabilityCode) {
        return selectOne(new LambdaQueryWrapper<ProjectCapabilityAuditDO>()
                .eq(ProjectCapabilityAuditDO::getProjectId, projectId)
                .eq(ProjectCapabilityAuditDO::getCapabilityCode, capabilityCode)
                .orderByDesc(ProjectCapabilityAuditDO::getCapabilityVersion).last("LIMIT 1"));
    }
}
