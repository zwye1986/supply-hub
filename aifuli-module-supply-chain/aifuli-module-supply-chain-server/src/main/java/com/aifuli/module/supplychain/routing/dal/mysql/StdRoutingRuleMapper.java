package com.aifuli.module.supplychain.routing.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.routing.dal.dataobject.StdRoutingRuleDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface StdRoutingRuleMapper extends BaseMapperX<StdRoutingRuleDO> {

    default List<StdRoutingRuleDO> selectByPolicyVersionId(Long policyVersionId) {
        return selectList(new LambdaQueryWrapper<StdRoutingRuleDO>()
                .eq(StdRoutingRuleDO::getPolicyVersionId, policyVersionId)
                .orderByAsc(StdRoutingRuleDO::getRulePriority)
                .orderByAsc(StdRoutingRuleDO::getRuleCode)
                .orderByAsc(StdRoutingRuleDO::getId));
    }
}
