package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentCaseActionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 履约异常 Case 命令审计 Mapper。
 * 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#63-失败确认的证据与幂等。
 */
@Mapper
public interface FulfillmentCaseActionMapper extends BaseMapper<FulfillmentCaseActionDO> {

    /** 聚合锁后当前读，避免 REPEATABLE_READ 快照重读旧命令。设计 §6.2。 */
    default FulfillmentCaseActionDO selectByActionNoForUpdate(String actionNo) {
        return selectOne(new LambdaQueryWrapper<FulfillmentCaseActionDO>()
                .eq(FulfillmentCaseActionDO::getActionNo, actionNo).last("FOR UPDATE"));
    }

    default FulfillmentCaseActionDO selectByActionNo(String actionNo) {
        return selectOne(new LambdaQueryWrapper<FulfillmentCaseActionDO>()
                .eq(FulfillmentCaseActionDO::getActionNo, actionNo));
    }

    default FulfillmentCaseActionDO selectActiveIntentByCaseId(Long caseId) {
        return selectOne(new LambdaQueryWrapper<FulfillmentCaseActionDO>()
                .eq(FulfillmentCaseActionDO::getCaseId, caseId)
                .eq(FulfillmentCaseActionDO::getActiveIntentFlag, 1));
    }
}
