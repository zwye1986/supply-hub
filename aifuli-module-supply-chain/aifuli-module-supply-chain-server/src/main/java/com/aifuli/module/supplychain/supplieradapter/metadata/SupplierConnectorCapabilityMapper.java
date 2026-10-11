package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SupplierConnectorCapabilityMapper extends BaseMapperX<SupplierConnectorCapabilityDO> {
    /** 发布准入锁后当前读；设计见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#34-闭环四同步重算的锁顺序与并发事务规范。 */
    default List<SupplierConnectorCapabilityDO> selectByConnectorVersionIdForUpdate(Long versionId) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SupplierConnectorCapabilityDO>()
                .eq(SupplierConnectorCapabilityDO::getConnectorVersionId, versionId)
                .orderByAsc(SupplierConnectorCapabilityDO::getId).last("FOR UPDATE"));
    }
    default List<SupplierConnectorCapabilityDO> selectByConnectorVersionId(Long connectorVersionId) {
        return selectList(SupplierConnectorCapabilityDO::getConnectorVersionId, connectorVersionId);
    }
}
