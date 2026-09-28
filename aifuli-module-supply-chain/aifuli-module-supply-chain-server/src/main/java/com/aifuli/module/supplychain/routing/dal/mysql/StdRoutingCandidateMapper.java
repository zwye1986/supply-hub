package com.aifuli.module.supplychain.routing.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.routing.dal.dataobject.StdRoutingCandidateDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface StdRoutingCandidateMapper extends BaseMapperX<StdRoutingCandidateDO> {

    default List<StdRoutingCandidateDO> selectByRuleId(Long ruleId) {
        return selectList(new LambdaQueryWrapper<StdRoutingCandidateDO>()
                .eq(StdRoutingCandidateDO::getRuleId, ruleId)
                .orderByAsc(StdRoutingCandidateDO::getCandidatePriority)
                .orderByAsc(StdRoutingCandidateDO::getSupplierId)
                .orderByAsc(StdRoutingCandidateDO::getId));
    }

    default List<StdRoutingCandidateDO> selectByRuleIds(Collection<Long> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapper<StdRoutingCandidateDO>()
                .in(StdRoutingCandidateDO::getRuleId, ruleIds)
                .orderByAsc(StdRoutingCandidateDO::getCandidatePriority)
                .orderByAsc(StdRoutingCandidateDO::getSupplierId)
                .orderByAsc(StdRoutingCandidateDO::getId));
    }

    default int deleteByRuleId(Long ruleId) {
        return delete(new LambdaQueryWrapper<StdRoutingCandidateDO>()
                .eq(StdRoutingCandidateDO::getRuleId, ruleId));
    }
}
