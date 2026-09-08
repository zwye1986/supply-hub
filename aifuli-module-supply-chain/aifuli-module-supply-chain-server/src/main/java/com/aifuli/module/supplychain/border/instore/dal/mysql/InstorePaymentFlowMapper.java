package com.aifuli.module.supplychain.border.instore.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/**
 * 到店购支付流水 Mapper。
 *
 * <p>遵循规范：优先 LambdaQuery/UpdateWrapper，不新增注解 SQL。
 * 支付永久幂等键为 (channelCode, trade_id)。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#42-表结构ddl-新增-012-instore-channelsql}。</p>
 */
@Mapper
public interface InstorePaymentFlowMapper extends BaseMapperX<InstorePaymentFlowDO> {

    /** 按渠道维度支付永久幂等键 (channelCode, tradeId) 查询流水。 */
    default InstorePaymentFlowDO selectByChannelTrade(String channelCode, String tradeId) {
        return selectOne(new LambdaQueryWrapper<InstorePaymentFlowDO>()
                .eq(InstorePaymentFlowDO::getChannelCode, channelCode)
                .eq(InstorePaymentFlowDO::getTradeId, tradeId));
    }

    /** 按收银台外部交易号查询流水，用于支付回调加载固化归属。 */
    default InstorePaymentFlowDO selectByOutTradeNo(String outTradeNo) {
        return selectOne(new LambdaQueryWrapper<InstorePaymentFlowDO>()
                .eq(InstorePaymentFlowDO::getOutTradeNo, outTradeNo));
    }

    /** 按渠道订单头 ID 统计已有支付流水数量，用于生成下一个 flow_sequence。 */
    default long countByInstoreOrder(Long instoreOrderId) {
        return selectCount(new LambdaQueryWrapper<InstorePaymentFlowDO>()
                .eq(InstorePaymentFlowDO::getInstoreOrderId, instoreOrderId));
    }

    /**
     * 按渠道订单头 ID 查询唯一支付流水，用于 DETAIL 入口读取 tradeId。
     * 一期生产不变量为订单头与支付流水 1:1；违反唯一性时 selectOne 直接抛错。
     */
    default InstorePaymentFlowDO selectByInstoreOrder(Long instoreOrderId) {
        return selectOne(new LambdaQueryWrapper<InstorePaymentFlowDO>()
                .eq(InstorePaymentFlowDO::getInstoreOrderId, instoreOrderId));
    }

    /** CAS 推进支付状态与资金冻结引用，用于抢占收银台。 */
    default int advanceToCashierCreating(Long id, long expectedVersion, short payStatus,
                                         String cashierReference, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstorePaymentFlowDO>()
                .set(InstorePaymentFlowDO::getPayStatus, payStatus)
                .set(cashierReference != null, InstorePaymentFlowDO::getCashierReference, cashierReference)
                .set(InstorePaymentFlowDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstorePaymentFlowDO::getId, id)
                .eq(InstorePaymentFlowDO::getVersion, expectedVersion));
    }

    /** 收银台建单成功后 CAS 回写跳转 URL 与收银台订单号，保留原 outTradeNo。 */
    default int recordCashierOrder(Long id, long expectedVersion, short payStatus,
                                   String cashierUrl, String cashierReference,
                                   LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstorePaymentFlowDO>()
                .set(InstorePaymentFlowDO::getPayStatus, payStatus)
                .set(InstorePaymentFlowDO::getCashierUrl, cashierUrl)
                .set(InstorePaymentFlowDO::getCashierReference, cashierReference)
                .set(InstorePaymentFlowDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstorePaymentFlowDO::getId, id)
                .eq(InstorePaymentFlowDO::getVersion, expectedVersion));
    }

    /** 确认支付：推进支付状态、记录实付金额、支付时间与外部流水号。 */
    default int confirmPayment(Long id, long expectedVersion, short payStatus,
                               Long buyerPaidAmount, String externalTransactionId,
                               LocalDateTime payTime, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstorePaymentFlowDO>()
                .set(InstorePaymentFlowDO::getPayStatus, payStatus)
                .set(InstorePaymentFlowDO::getBuyerPaidAmount, buyerPaidAmount)
                .set(InstorePaymentFlowDO::getExternalTransactionId, externalTransactionId)
                .set(payTime != null, InstorePaymentFlowDO::getPayTime, payTime)
                .set(InstorePaymentFlowDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstorePaymentFlowDO::getId, id)
                .eq(InstorePaymentFlowDO::getVersion, expectedVersion));
    }

    /** 取消支付流水：推进为已取消状态，CAS by version。 */
    default int cancelFlow(Long id, long expectedVersion, short payStatus, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstorePaymentFlowDO>()
                .set(InstorePaymentFlowDO::getPayStatus, payStatus)
                .set(InstorePaymentFlowDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstorePaymentFlowDO::getId, id)
                .eq(InstorePaymentFlowDO::getVersion, expectedVersion));
    }

    /** 回填支付成功后创建的固定商户供应商订单主键。 */
    default int attachSupplierOrder(Long id, long expectedVersion, Long supplierOrderId,
                                    LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstorePaymentFlowDO>()
                .set(InstorePaymentFlowDO::getSupplierOrderId, supplierOrderId)
                .set(InstorePaymentFlowDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstorePaymentFlowDO::getId, id)
                .eq(InstorePaymentFlowDO::getVersion, expectedVersion));
    }
}
