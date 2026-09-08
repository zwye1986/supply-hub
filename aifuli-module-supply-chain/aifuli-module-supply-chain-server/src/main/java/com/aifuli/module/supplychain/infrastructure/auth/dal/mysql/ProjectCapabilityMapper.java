package com.aifuli.module.supplychain.infrastructure.auth.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectCapabilityDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/** 项目能力认证只读 Mapper。 */
@Mapper
public interface ProjectCapabilityMapper extends BaseMapperX<ProjectCapabilityDO> {

    /** 按项目与能力编码查询唯一能力行。 */
    default ProjectCapabilityDO selectByProjectIdAndCode(Long projectId, String capabilityCode) {
        return selectOne(new LambdaQueryWrapper<ProjectCapabilityDO>()
                .eq(ProjectCapabilityDO::getProjectId, projectId)
                .eq(ProjectCapabilityDO::getCapabilityCode, capabilityCode));
    }
}
