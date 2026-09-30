package com.aifuli.module.supplychain.infrastructure.auth.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAppCredentialVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/** 项目应用凭证版本认证只读 Mapper。 */
@Mapper
public interface ProjectAppCredentialVersionMapper extends BaseMapperX<ProjectAppCredentialVersionDO> {

    /** 按应用与密钥版本查询唯一凭证行。 */
    default ProjectAppCredentialVersionDO selectByApplicationIdAndKeyVersion(Long applicationId, int keyVersion) {
        return selectOne(new LambdaQueryWrapper<ProjectAppCredentialVersionDO>()
                .eq(ProjectAppCredentialVersionDO::getApplicationId, applicationId)
                .eq(ProjectAppCredentialVersionDO::getKeyVersion, keyVersion));
    }
}
