package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SupplierErrorCodeMapMapper extends BaseMapperX<SupplierErrorCodeMapDO> {
    default List<SupplierErrorCodeMapDO> selectByConnectorVersionId(Long connectorVersionId) {
        return selectList(SupplierErrorCodeMapDO::getConnectorVersionId, connectorVersionId);
    }
}
