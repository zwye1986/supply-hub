package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface StatusMappingDefinitionMapper extends BaseMapperX<StatusMappingDefinitionDO> {
    default List<StatusMappingDefinitionDO> selectByConnectorVersionId(Long connectorVersionId) {
        return selectList(StatusMappingDefinitionDO::getConnectorVersionId, connectorVersionId);
    }
}
