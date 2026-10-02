package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.StandardPricingQuoteDO;
import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/**
 * 标准商品项目订单首次永久冻结报价的数据访问。永久键为「project_id + buyer_order_no」；
 * 来源系统/租户索引仅作调用来源记录，不作为永久键读取入口。
 * 设计见 docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#2-首次签发与重放。
 */
@Mapper
public interface StandardPricingQuoteMapper extends BaseMapperX<StandardPricingQuoteDO> {

    /** 签发路径按项目永久键读取唯一报价；首次签发由项目行锁串行，DB 唯一键提供最后一道保护。 */
    default StandardPricingQuoteDO selectByProjectOrder(Long projectId, String buyerOrderNo) {
        return selectOne(new LambdaQueryWrapper<StandardPricingQuoteDO>()
                .eq(StandardPricingQuoteDO::getProjectId, projectId)
                .eq(StandardPricingQuoteDO::getBuyerOrderNo, buyerOrderNo));
    }

    /** 受理消费路径按项目永久键行锁读取唯一报价；无匹配行时返回 null，由调用方转换为缺报价业务错误。 */
    default StandardPricingQuoteDO selectByProjectOrderForUpdate(Long projectId, String buyerOrderNo) {
        return selectOneForUpdate(new LambdaQueryWrapper<StandardPricingQuoteDO>()
                .eq(StandardPricingQuoteDO::getProjectId, projectId)
                .eq(StandardPricingQuoteDO::getBuyerOrderNo, buyerOrderNo));
    }

    /** markConsumed 在同一供应链事务内重新锁定报价行，防止并发转移消费。 */
    default StandardPricingQuoteDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(new LambdaQueryWrapper<StandardPricingQuoteDO>()
                .eq(StandardPricingQuoteDO::getId, id));
    }

    /** 过渡期按内部追踪 token 精确行锁查询，用于旧受理入口与项目永久键结果的核对。 */
    default StandardPricingQuoteDO selectByTokenForUpdate(String token) {
        return selectOneForUpdate(StandardPricingQuoteDO::getQuoteToken, token);
    }
}
