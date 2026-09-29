package com.aifuli.module.supplychain.infrastructure.auth.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAppCredentialAuditDO;
import org.apache.ibatis.annotations.Mapper;

/** 仅追加项目应用凭证操作审计；不提供更新或删除入口。 */
@Mapper
public interface ProjectAppCredentialAuditMapper extends BaseMapperX<ProjectAppCredentialAuditDO> {
}
