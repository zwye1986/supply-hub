package com.aifuli.module.supplychain.border.instore.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreSupplierOrderDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 到店购固定商户供应商订单 Mapper。
 *
 * <p>遵循规范：优先 LambdaQueryWrapper，不新增注解 SQL。
 * 一笔采购订单至多一张有效成功供应商订单；active_flag 与 effective_success_flag 由唯一约束保证。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#42-表结构ddl-新增-012-instore-channelsql}。</p>
 */
@Mapper
public interface InstoreSupplierOrderMapper extends BaseMapperX<InstoreSupplierOrderDO> {

    /** 按总订单 ID 查询供应商订单。 */
    default InstoreSupplierOrderDO selectByOrderId(Long orderId) {
        return selectOne(new LambdaQueryWrapper<InstoreSupplierOrderDO>()
                .eq(InstoreSupplierOrderDO::getOrderId, orderId));
    }

    /** 按触发本单的成功支付流水主键查询供应商订单。 */
    default InstoreSupplierOrderDO selectByPaymentFlowId(Long paymentFlowId) {
        return selectOne(new LambdaQueryWrapper<InstoreSupplierOrderDO>()
                .eq(InstoreSupplierOrderDO::getPaymentFlowId, paymentFlowId));
    }
}
