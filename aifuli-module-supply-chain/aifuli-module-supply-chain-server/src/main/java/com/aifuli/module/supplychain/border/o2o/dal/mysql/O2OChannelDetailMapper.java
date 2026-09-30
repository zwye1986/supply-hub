package com.aifuli.module.supplychain.border.o2o.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.o2o.dal.dataobject.O2OChannelDetailDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/**
 * O2O 渠道订单详情 Mapper。
 *
 * <p>遵循规范：优先 LambdaQuery/UpdateWrapper，不新增注解 SQL。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#9.2-O2OChannelDetail}。</p>
 */
@Mapper
public interface O2OChannelDetailMapper extends BaseMapperX<O2OChannelDetailDO> {

    /** 按渠道维度永久唯一键 (channelCode, transactionId) 查询详情。 */
    default O2OChannelDetailDO selectByChannelTransaction(String channelCode, String transactionId) {
        return selectOne(new LambdaQueryWrapper<O2OChannelDetailDO>()
                .eq(O2OChannelDetailDO::getChannelCode, channelCode)
                .eq(O2OChannelDetailDO::getTransactionId, transactionId));
    }

    /** 按收银台外部交易号查询详情，用于支付回调加载固化归属。 */
    default O2OChannelDetailDO selectByOutTradeNo(String outTradeNo) {
        return selectOne(new LambdaQueryWrapper<O2OChannelDetailDO>()
                .eq(O2OChannelDetailDO::getOutTradeNo, outTradeNo));
    }

    /** 按总订单 ID 查询详情。 */
    default O2OChannelDetailDO selectByOrderId(Long orderId) {
        return selectOne(new LambdaQueryWrapper<O2OChannelDetailDO>()
                .eq(O2OChannelDetailDO::getOrderId, orderId));
    }

    /** CAS 推进支付状态与资金状态，用于抢占收银台。 */
    default int advanceToCashierCreating(Long id, long expectedVersion,
                                          short paymentStatus, short fundStatus,
                                          LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<O2OChannelDetailDO>()
                .set(O2OChannelDetailDO::getPaymentStatus, paymentStatus)
                .set(O2OChannelDetailDO::getFundStatus, fundStatus)
                .set(O2OChannelDetailDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(O2OChannelDetailDO::getId, id)
                .eq(O2OChannelDetailDO::getVersion, expectedVersion));
    }

    /** 记录收银台结果引用。 */
    default int recordCashierReference(Long id, long expectedVersion,
                                       String cashierReference, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<O2OChannelDetailDO>()
                .set(O2OChannelDetailDO::getCashierReference, cashierReference)
                .set(O2OChannelDetailDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(O2OChannelDetailDO::getId, id)
                .eq(O2OChannelDetailDO::getVersion, expectedVersion));
    }

    /** 确认支付：推进支付/资金状态、记录实付金额。 */
    default int confirmPayment(Long id, long expectedVersion, short paymentStatus,
                               short fundStatus, Long buyerPaidAmount,
                               String cashierReference, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<O2OChannelDetailDO>()
                .set(O2OChannelDetailDO::getPaymentStatus, paymentStatus)
                .set(O2OChannelDetailDO::getFundStatus, fundStatus)
                .set(O2OChannelDetailDO::getBuyerPaidAmount, buyerPaidAmount)
                .set(O2OChannelDetailDO::getCashierReference, cashierReference)
                .set(O2OChannelDetailDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(O2OChannelDetailDO::getId, id)
                .eq(O2OChannelDetailDO::getVersion, expectedVersion));
    }
}
