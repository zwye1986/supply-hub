package com.aifuli.module.supplychain.routing.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.routing.dal.dataobject.StandardProductRoutingRuleDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface StandardProductRoutingRuleMapper extends BaseMapperX<StandardProductRoutingRuleDO> {

    default List<StandardProductRoutingRuleDO> selectByPolicyVersionId(Long policyVersionId) {
        return selectList(new LambdaQueryWrapper<StandardProductRoutingRuleDO>()
                .eq(StandardProductRoutingRuleDO::getPolicyVersionId, policyVersionId)
                .orderByAsc(StandardProductRoutingRuleDO::getRulePriority)
                .orderByAsc(StandardProductRoutingRuleDO::getRuleCode)
                .orderByAsc(StandardProductRoutingRuleDO::getId));
    }

    default List<StandardProductRoutingRuleDO> selectExactMatches(Long policyVersionId, Long buyerId,
                                                                  Long platformSkuId, String fulfillmentType) {
        return selectList(new LambdaQueryWrapper<StandardProductRoutingRuleDO>()
                .eq(StandardProductRoutingRuleDO::getPolicyVersionId, policyVersionId)
                .eq(StandardProductRoutingRuleDO::getBuyerId, buyerId)
                .eq(StandardProductRoutingRuleDO::getPlatformSkuId, platformSkuId)
                .eq(StandardProductRoutingRuleDO::getFulfillmentType, fulfillmentType)
                .orderByAsc(StandardProductRoutingRuleDO::getRulePriority)
                .orderByAsc(StandardProductRoutingRuleDO::getRuleCode)
                .orderByAsc(StandardProductRoutingRuleDO::getId));
    }
}
