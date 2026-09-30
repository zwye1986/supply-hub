package com.aifuli.module.supplychain.infrastructure.auth.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectApplicationDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/** 项目应用身份认证只读 Mapper。 */
@Mapper
public interface ProjectApplicationMapper extends BaseMapperX<ProjectApplicationDO> {

    /** 按全局唯一 appId 查询应用身份。 */
    default ProjectApplicationDO selectByAppId(String appId) {
        return selectOne(new LambdaQueryWrapper<ProjectApplicationDO>()
                .eq(ProjectApplicationDO::getAppId, appId));
    }
}
