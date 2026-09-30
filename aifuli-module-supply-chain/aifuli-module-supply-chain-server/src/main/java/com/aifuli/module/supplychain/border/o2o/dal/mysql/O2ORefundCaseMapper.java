package com.aifuli.module.supplychain.border.o2o.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.o2o.dal.dataobject.O2ORefundCaseDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * O2O 退款 Case Mapper。
 *
 * <p>遵循规范：优先 LambdaQueryWrapper，不新增注解 SQL。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#9.3-O2ORefundCase}。</p>
 */
@Mapper
public interface O2ORefundCaseMapper extends BaseMapperX<O2ORefundCaseDO> {

    /** 按渠道维度永久唯一键 (channelCode, channelRefundNo) 查询退款 Case。 */
    default O2ORefundCaseDO selectByChannelRefundNo(String channelCode, String channelRefundNo) {
        return selectOne(new LambdaQueryWrapper<O2ORefundCaseDO>()
                .eq(O2ORefundCaseDO::getChannelCode, channelCode)
                .eq(O2ORefundCaseDO::getChannelRefundNo, channelRefundNo));
    }

    /** 按中台退款号查询退款 Case，用于退款结果回调加载固化归属。 */
    default O2ORefundCaseDO selectByRefundCaseNo(String refundCaseNo) {
        return selectOne(new LambdaQueryWrapper<O2ORefundCaseDO>()
                .eq(O2ORefundCaseDO::getRefundCaseNo, refundCaseNo));
    }

    /** 查询当前活动退款 Case（buyerRefundingFlag = 1）。 */
    default O2ORefundCaseDO selectActiveByDetail(Long o2oDetailId) {
        return selectOne(new LambdaQueryWrapper<O2ORefundCaseDO>()
                .eq(O2ORefundCaseDO::getO2oDetailId, o2oDetailId)
                .eq(O2ORefundCaseDO::getBuyerRefundingFlag, (byte) 1));
    }

    /** 统计已有退款 Case 数量，用于生成下一个 refund_sequence。 */
    default long countByDetail(Long o2oDetailId) {
        return selectCount(new LambdaQueryWrapper<O2ORefundCaseDO>()
                .eq(O2ORefundCaseDO::getO2oDetailId, o2oDetailId));
    }
}
