package com.aifuli.module.supplychain.routing.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.routing.dal.dataobject.StdRoutingRuleScopeDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface StdRoutingRuleScopeMapper extends BaseMapperX<StdRoutingRuleScopeDO> {

    default List<StdRoutingRuleScopeDO> selectByRuleIds(Collection<Long> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapper<StdRoutingRuleScopeDO>()
                .in(StdRoutingRuleScopeDO::getRuleId, ruleIds)
                .orderByAsc(StdRoutingRuleScopeDO::getRuleId)
                .orderByAsc(StdRoutingRuleScopeDO::getScopeKind)
                .orderByAsc(StdRoutingRuleScopeDO::getScopeCode));
    }

    default int deleteByRuleId(Long ruleId) {
        return delete(new LambdaQueryWrapper<StdRoutingRuleScopeDO>()
                .eq(StdRoutingRuleScopeDO::getRuleId, ruleId));
    }
}
