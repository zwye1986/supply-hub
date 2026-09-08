package com.aifuli.module.supplychain.border.instore.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/**
 * 到店购渠道订单头 Mapper。
 *
 * <p>遵循规范：优先 LambdaQuery/UpdateWrapper，不新增注解 SQL。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#42-表结构ddl-新增-012-instore-channelsql}。</p>
 */
@Mapper
public interface InstoreChannelOrderMapper extends BaseMapperX<InstoreChannelOrderDO> {

    /** 按渠道维度永久唯一键 (channelCode, channelOrderId) 查询订单头。 */
    default InstoreChannelOrderDO selectByChannelOrder(String channelCode, String channelOrderId) {
        return selectOne(new LambdaQueryWrapper<InstoreChannelOrderDO>()
                .eq(InstoreChannelOrderDO::getChannelCode, channelCode)
                .eq(InstoreChannelOrderDO::getChannelOrderId, channelOrderId));
    }

    /** 按总订单 ID 查询订单头。 */
    default InstoreChannelOrderDO selectByOrderId(Long orderId) {
        return selectOne(new LambdaQueryWrapper<InstoreChannelOrderDO>()
                .eq(InstoreChannelOrderDO::getOrderId, orderId));
    }

    /**
     * 行锁加载订单头：退款受理与收敛必须先锁详情，串行化在途金额校验
     * （并发受理第二个事务在前者提交后才能读到其 QUEUED Case）。
     * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量} 第 4 条。
     */
    default InstoreChannelOrderDO selectByIdForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapper<InstoreChannelOrderDO>()
                .eq(InstoreChannelOrderDO::getId, id)
                .last("FOR UPDATE"));
    }

    /** CAS 推进订单头聚合状态与累计已支付金额。 */
    default int advanceStatus(Long id, long expectedVersion, short orderStatus,
                              Long totalPaidAmount, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstoreChannelOrderDO>()
                .set(InstoreChannelOrderDO::getOrderStatus, orderStatus)
                .set(totalPaidAmount != null, InstoreChannelOrderDO::getTotalPaidAmount, totalPaidAmount)
                .set(InstoreChannelOrderDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstoreChannelOrderDO::getId, id)
                .eq(InstoreChannelOrderDO::getVersion, expectedVersion));
    }

    /** 累加渠道累计退款、采购商累计退款与账户累计返还金额，CAS by version。 */
    default int accumulateRefundAmounts(Long id, long expectedVersion,
                                        long channelRefundDelta, long buyerRefundDelta,
                                        long accountReturnDelta, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstoreChannelOrderDO>()
                .setSql("total_channel_refund_amount = total_channel_refund_amount + " + channelRefundDelta)
                .setSql("total_buyer_refund_amount = total_buyer_refund_amount + " + buyerRefundDelta)
                .setSql("total_account_return_amount = total_account_return_amount + " + accountReturnDelta)
                .set(InstoreChannelOrderDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstoreChannelOrderDO::getId, id)
                .eq(InstoreChannelOrderDO::getVersion, expectedVersion));
    }

    /** CAS 维护订单头活动退款指针；caseId 为 null 表示清空（当前 Case 已终态）。 */
    default int setActiveRefundCase(Long id, long expectedVersion, Long refundCaseId,
                                    LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstoreChannelOrderDO>()
                .set(InstoreChannelOrderDO::getActiveRefundCaseId, refundCaseId)
                .set(InstoreChannelOrderDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstoreChannelOrderDO::getId, id)
                .eq(InstoreChannelOrderDO::getVersion, expectedVersion));
    }
}
