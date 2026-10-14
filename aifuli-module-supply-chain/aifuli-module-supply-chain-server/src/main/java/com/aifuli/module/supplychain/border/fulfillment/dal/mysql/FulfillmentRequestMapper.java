package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentInitialStatuses;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface FulfillmentRequestMapper extends BaseMapperX<FulfillmentRequestDO> {

    /** BOrder 激活后履约请求进入「已绑定 BOrder」状态（一期码值 20，完整状态机待后续任务扩展）。 */
    short STATUS_B_ORDER_ACTIVE = 20;

    /** 按总订单 ID 查询标准商品一对一履约详情。 */
    default FulfillmentRequestDO selectByOrderId(Long orderId) {
        return selectOne(new LambdaQueryWrapper<FulfillmentRequestDO>()
                .eq(FulfillmentRequestDO::getOrderId, orderId));
    }

    /**
     * 首次冻结事务中绑定结算方，只允许从 NULL 设置一次；失败须由调用方回滚整笔受理。
     * 设计：docs/superpowers/specs/2026-10-14-project-settlement-buyer-design.md#3-数据与资金。
     */
    default int bindSettlementBuyer(Long requestId, Long settlementBuyerId) {
        return update(null, new LambdaUpdateWrapper<FulfillmentRequestDO>()
                .set(FulfillmentRequestDO::getSettlementBuyerId, settlementBuyerId)
                .eq(FulfillmentRequestDO::getId, requestId)
                .isNull(FulfillmentRequestDO::getSettlementBuyerId));
    }

    default int activateBOrder(Long id, Long bOrderId, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<FulfillmentRequestDO>()
                .set(FulfillmentRequestDO::getActiveBOrderId, bOrderId)
                .set(FulfillmentRequestDO::getStatus, STATUS_B_ORDER_ACTIVE)
                .set(FulfillmentRequestDO::getUpdatedAt, updatedAt)
                .setSql("aggregate_version = aggregate_version + 1")
                .eq(FulfillmentRequestDO::getId, id)
                .isNull(FulfillmentRequestDO::getActiveBOrderId)
                .eq(FulfillmentRequestDO::getStatus, FulfillmentInitialStatuses.REQUEST_PENDING));
    }
}
