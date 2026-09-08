package com.aifuli.module.supplychain.routing.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.routing.dal.dataobject.StandardProductRoutingCandidateDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface StandardProductRoutingCandidateMapper extends BaseMapperX<StandardProductRoutingCandidateDO> {

    default List<StandardProductRoutingCandidateDO> selectByRuleId(Long ruleId) {
        return selectList(new LambdaQueryWrapper<StandardProductRoutingCandidateDO>()
                .eq(StandardProductRoutingCandidateDO::getRuleId, ruleId)
                .orderByAsc(StandardProductRoutingCandidateDO::getCandidatePriority)
                .orderByAsc(StandardProductRoutingCandidateDO::getSupplierId)
                .orderByAsc(StandardProductRoutingCandidateDO::getId));
    }
}
