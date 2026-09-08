package com.aifuli.module.supplychain.routing.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SupplierRoutingPolicyVersionMapper extends BaseMapperX<SupplierRoutingPolicyVersionDO> {

    /** 与 {@link com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService} 中 VERSION_PUBLISHED 一致。 */
    short VERSION_PUBLISHED = 10;

    default SupplierRoutingPolicyVersionDO selectLatest(Long policyId) {
        return selectOne(new LambdaQueryWrapper<SupplierRoutingPolicyVersionDO>()
                .eq(SupplierRoutingPolicyVersionDO::getPolicyId, policyId)
                .orderByDesc(SupplierRoutingPolicyVersionDO::getVersionNo)
                .last("LIMIT 1"));
    }

    default SupplierRoutingPolicyVersionDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(SupplierRoutingPolicyVersionDO::getId, id);
    }

    default List<SupplierRoutingPolicyVersionDO> selectPublished(Long policyId) {
        return selectList(new LambdaQueryWrapper<SupplierRoutingPolicyVersionDO>()
                .eq(SupplierRoutingPolicyVersionDO::getPolicyId, policyId)
                .eq(SupplierRoutingPolicyVersionDO::getStatus, VERSION_PUBLISHED)
                .orderByAsc(SupplierRoutingPolicyVersionDO::getValidFrom)
                .orderByAsc(SupplierRoutingPolicyVersionDO::getId));
    }

    default List<SupplierRoutingPolicyVersionDO> selectEffective(Long policyId, LocalDateTime businessTime) {
        return selectList(new LambdaQueryWrapper<SupplierRoutingPolicyVersionDO>()
                .eq(SupplierRoutingPolicyVersionDO::getPolicyId, policyId)
                .eq(SupplierRoutingPolicyVersionDO::getStatus, VERSION_PUBLISHED)
                .le(SupplierRoutingPolicyVersionDO::getValidFrom, businessTime)
                .and(wrapper -> wrapper.isNull(SupplierRoutingPolicyVersionDO::getValidTo)
                        .or().gt(SupplierRoutingPolicyVersionDO::getValidTo, businessTime))
                .orderByAsc(SupplierRoutingPolicyVersionDO::getValidFrom)
                .orderByAsc(SupplierRoutingPolicyVersionDO::getId));
    }
}
