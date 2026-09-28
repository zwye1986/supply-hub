package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentExceptionCaseDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 履约异常处置单 Mapper。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#74-fulfillmentexceptioncase-与奇那梦卡密恢复}。</p>
 */
@Mapper
public interface FulfillmentExceptionCaseMapper extends BaseMapperX<FulfillmentExceptionCaseDO> {

    default FulfillmentExceptionCaseDO selectActiveByOperationId(Long operationId) {
        return selectOne(FulfillmentExceptionCaseDO::getOperationId, operationId,
                FulfillmentExceptionCaseDO::getActiveCaseFlag, 1);
    }
}
