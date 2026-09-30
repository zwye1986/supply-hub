package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SupplierConnectorCapabilityMapper extends BaseMapperX<SupplierConnectorCapabilityDO> {
    default List<SupplierConnectorCapabilityDO> selectByConnectorVersionId(Long connectorVersionId) {
        return selectList(SupplierConnectorCapabilityDO::getConnectorVersionId, connectorVersionId);
    }
}
