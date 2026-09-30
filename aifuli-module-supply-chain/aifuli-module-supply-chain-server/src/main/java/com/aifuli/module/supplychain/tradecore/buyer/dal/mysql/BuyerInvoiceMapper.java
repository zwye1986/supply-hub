package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerInvoiceDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分销商开票信息。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md#7-表}。
 */
@Mapper
public interface BuyerInvoiceMapper extends BaseMapperX<BuyerInvoiceDO> {

    default BuyerInvoiceDO selectByBuyerId(Long buyerId) {
        return selectOne(BuyerInvoiceDO::getBuyerId, buyerId);
    }
}
