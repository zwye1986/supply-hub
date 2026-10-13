package com.aifuli.module.supplychain.infrastructure.auth.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectCapabilityDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/** 项目能力 Mapper：认证只读；管理在项目行锁后当前读能力。 */
@Mapper
public interface ProjectCapabilityMapper extends BaseMapperX<ProjectCapabilityDO> {

    /** 按项目与能力编码查询唯一能力行。 */
    default ProjectCapabilityDO selectByProjectIdAndCode(Long projectId, String capabilityCode) {
        return selectOne(new LambdaQueryWrapper<ProjectCapabilityDO>()
                .eq(ProjectCapabilityDO::getProjectId, projectId)
                .eq(ProjectCapabilityDO::getCapabilityCode, capabilityCode));
    }

    /** 项目→能力锁序，避免 RR 事务在并发首次开启后仍读到旧的无行快照。 */
    default ProjectCapabilityDO selectByProjectIdAndCodeForUpdate(Long projectId, String capabilityCode) {
        return selectOne(new LambdaQueryWrapper<ProjectCapabilityDO>()
                .eq(ProjectCapabilityDO::getProjectId, projectId)
                .eq(ProjectCapabilityDO::getCapabilityCode, capabilityCode).last("FOR UPDATE"));
    }
}
