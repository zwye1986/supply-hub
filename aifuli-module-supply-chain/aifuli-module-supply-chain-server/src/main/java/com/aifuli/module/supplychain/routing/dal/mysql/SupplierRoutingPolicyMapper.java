package com.aifuli.module.supplychain.routing.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SupplierRoutingPolicyMapper extends BaseMapperX<SupplierRoutingPolicyDO> {

    /** 与 {@link com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService} 中 POLICY_ACTIVE 一致。 */
    short POLICY_ACTIVE = 10;

    default SupplierRoutingPolicyDO selectByCode(String policyCode) {
        return selectOne(SupplierRoutingPolicyDO::getPolicyCode, policyCode);
    }

    default SupplierRoutingPolicyDO selectByCodeForUpdate(String policyCode) {
        return selectOneForUpdate(SupplierRoutingPolicyDO::getPolicyCode, policyCode);
    }

    default SupplierRoutingPolicyDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(SupplierRoutingPolicyDO::getId, id);
    }

    default List<SupplierRoutingPolicyDO> selectActiveByType(String routingType) {
        return selectList(new LambdaQueryWrapper<SupplierRoutingPolicyDO>()
                .eq(SupplierRoutingPolicyDO::getRoutingType, routingType)
                .eq(SupplierRoutingPolicyDO::getStatus, POLICY_ACTIVE)
                .eq(SupplierRoutingPolicyDO::getActiveFlag, (short) 1));
    }
}
