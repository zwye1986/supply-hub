package com.aifuli.module.supplychain.border.instore.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreRefundCaseDO;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 到店购退款 Case Mapper。
 *
 * <p>遵循规范：优先 LambdaQuery/UpdateWrapper，不新增注解 SQL。
 * 退款永久唯一键为 (channelCode, channel_refund_no)；同一支付流水最多一个活动退款，
 * 由 uk_sc_instore_refund_active (payment_flow_id, buyer_refunding_flag) 在数据库层兜底
 * （NULL 在唯一键中互不冲突）。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#42-表结构ddl-新增-012-instore-channelsql}。</p>
 */
@Mapper
public interface InstoreRefundCaseMapper extends BaseMapperX<InstoreRefundCaseDO> {

    /** 按渠道维度永久唯一键 (channelCode, channelRefundNo) 查询退款 Case。 */
    default InstoreRefundCaseDO selectByChannelRefundNo(String channelCode, String channelRefundNo) {
        return selectOne(new LambdaQueryWrapper<InstoreRefundCaseDO>()
                .eq(InstoreRefundCaseDO::getChannelCode, channelCode)
                .eq(InstoreRefundCaseDO::getChannelRefundNo, channelRefundNo));
    }

    /** 按中台退款号查询退款 Case，用于退款结果回调加载固化归属。 */
    default InstoreRefundCaseDO selectByRefundCaseNo(String refundCaseNo) {
        return selectOne(new LambdaQueryWrapper<InstoreRefundCaseDO>()
                .eq(InstoreRefundCaseDO::getRefundCaseNo, refundCaseNo));
    }

    /** 查询当前活动退款 Case（buyerRefundingFlag = 1）。 */
    default InstoreRefundCaseDO selectActiveByFlow(Long paymentFlowId) {
        return selectOne(new LambdaQueryWrapper<InstoreRefundCaseDO>()
                .eq(InstoreRefundCaseDO::getPaymentFlowId, paymentFlowId)
                .eq(InstoreRefundCaseDO::getBuyerRefundingFlag, (byte) 1));
    }

    /** 排队中(10)与退款中(20)的 Case；受理时按其总额做在途防超退校验。 */
    default List<InstoreRefundCaseDO> selectInflightByFlow(Long paymentFlowId) {
        return selectList(new LambdaQueryWrapper<InstoreRefundCaseDO>()
                .eq(InstoreRefundCaseDO::getPaymentFlowId, paymentFlowId)
                .in(InstoreRefundCaseDO::getStatus, List.of(InstoreRefundStatus.QUEUED.getCode(),
                        InstoreRefundStatus.BUYER_REFUNDING.getCode())));
    }

    /** 未收敛的 Case：排队中、退款中或人工；用于售后 PROCESSING 投影。 */
    default List<InstoreRefundCaseDO> selectOpenByFlow(Long paymentFlowId) {
        return selectList(new LambdaQueryWrapper<InstoreRefundCaseDO>()
                .eq(InstoreRefundCaseDO::getPaymentFlowId, paymentFlowId)
                .in(InstoreRefundCaseDO::getStatus, List.of(InstoreRefundStatus.QUEUED.getCode(),
                        InstoreRefundStatus.BUYER_REFUNDING.getCode(),
                        InstoreRefundStatus.MANUAL.getCode())));
    }

    /** 同一流水下一个排队中的 Case（按 refund_sequence 升序）；终态派发用。 */
    default InstoreRefundCaseDO selectNextQueuedByFlow(Long paymentFlowId) {
        List<InstoreRefundCaseDO> queued = selectList(new LambdaQueryWrapper<InstoreRefundCaseDO>()
                .eq(InstoreRefundCaseDO::getPaymentFlowId, paymentFlowId)
                .eq(InstoreRefundCaseDO::getStatus, InstoreRefundStatus.QUEUED.getCode())
                .orderByAsc(InstoreRefundCaseDO::getRefundSequence));
        return queued.isEmpty() ? null : queued.getFirst();
    }

    /** 统计已有退款 Case 数量，用于生成下一个 refund_sequence。 */
    default long countByFlow(Long paymentFlowId) {
        return selectCount(new LambdaQueryWrapper<InstoreRefundCaseDO>()
                .eq(InstoreRefundCaseDO::getPaymentFlowId, paymentFlowId));
    }

    /** 按支付流水 + 退款类型查询；切片下一流水至多一笔 CANCEL_REFUND。 */
    default InstoreRefundCaseDO selectByFlowAndType(Long paymentFlowId, String refundType) {
        return selectOne(new LambdaQueryWrapper<InstoreRefundCaseDO>()
                .eq(InstoreRefundCaseDO::getPaymentFlowId, paymentFlowId)
                .eq(InstoreRefundCaseDO::getRefundType, refundType));
    }

    /** CAS 推进退款状态与采购商退款活动标记。 */
    default int advanceStatus(Long id, long expectedVersion, short status,
                              Byte buyerRefundingFlag, String resultReasonCode,
                              LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstoreRefundCaseDO>()
                .set(InstoreRefundCaseDO::getStatus, status)
                .set(buyerRefundingFlag != null, InstoreRefundCaseDO::getBuyerRefundingFlag, buyerRefundingFlag)
                .set(resultReasonCode != null, InstoreRefundCaseDO::getResultReasonCode, resultReasonCode)
                .set(InstoreRefundCaseDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstoreRefundCaseDO::getId, id)
                .eq(InstoreRefundCaseDO::getVersion, expectedVersion));
    }

    /**
     * CAS 抢占派发：QUEUED(10) → BUYER_REFUNDING(20) 并置 buyer_refunding_flag=1。
     * uk_sc_instore_refund_active (payment_flow_id, buyer_refunding_flag) 在数据库层
     * 保证同一流水最多一个活动退款（NULL 在唯一键中互不冲突）。
     */
    default int claimDispatch(Long id, long expectedVersion, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstoreRefundCaseDO>()
                .set(InstoreRefundCaseDO::getStatus, InstoreRefundStatus.BUYER_REFUNDING.getCode())
                .set(InstoreRefundCaseDO::getBuyerRefundingFlag, (byte) 1)
                .set(InstoreRefundCaseDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstoreRefundCaseDO::getId, id)
                .eq(InstoreRefundCaseDO::getVersion, expectedVersion)
                .eq(InstoreRefundCaseDO::getStatus, InstoreRefundStatus.QUEUED.getCode()));
    }

    /**
     * CAS 完成退款 Case：回写实际退款金额、账户返还金额、收银台参考号与原因码，
     * 并清空 buyer_refunding_flag 释放串行锁（唯一键中 NULL 可重复）。
     */
    default int completeRefund(Long id, long expectedVersion, short status,
                               Long buyerActualRefundAmount, Long accountReturnAmount,
                               String cashierRefundReference, String resultReasonCode,
                               LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstoreRefundCaseDO>()
                .set(InstoreRefundCaseDO::getStatus, status)
                .set(buyerActualRefundAmount != null, InstoreRefundCaseDO::getBuyerActualRefundAmount,
                        buyerActualRefundAmount)
                .set(accountReturnAmount != null, InstoreRefundCaseDO::getAccountReturnAmount,
                        accountReturnAmount)
                .set(cashierRefundReference != null, InstoreRefundCaseDO::getCashierRefundReference,
                        cashierRefundReference)
                .set(resultReasonCode != null, InstoreRefundCaseDO::getResultReasonCode, resultReasonCode)
                .set(InstoreRefundCaseDO::getBuyerRefundingFlag, null)
                .set(InstoreRefundCaseDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstoreRefundCaseDO::getId, id)
                .eq(InstoreRefundCaseDO::getVersion, expectedVersion));
    }
}
