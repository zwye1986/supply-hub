package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 执行及结果收敛行锁，调用方须先取得QUEUE/ENTRY锁，再按Request→Order→BOrder→Operation取得本组锁。
 * 不取得商品限购锁或直接访问账户账本；设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#42-认领。
 */
@Mapper
public interface RechargeFrequencyExecutionMapper {
    java.util.List<BOrderDO> lockBOrders(@Param("orderId") Long orderId);
    java.util.List<FulfillmentOperationDO> lockOperations(@Param("orderId") Long orderId);
    FulfillmentRequestDO lockRequest(@Param("id") Long id);
    SupplyChainOrderDO lockOrder(@Param("id") Long id);
}
