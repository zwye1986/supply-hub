package com.aifuli.channel.taobaoflash.dal.mysql;

import com.aifuli.channel.taobaoflash.dal.dataobject.TbsgChannelTransactionDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 渠道交易注册表 Mapper。
 *
 * <p>查询优先使用 {@code lambdaQuery()} 方法引用绑定列名，避免字符串列名漂移。
 * 详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7。</p>
 */
@Mapper
public interface TbsgChannelTransactionMapper extends BaseMapper<TbsgChannelTransactionDO> {

    /**
     * 按 ele.me 渠道交易号查询交易记录。
     */
    default TbsgChannelTransactionDO selectByTransactionId(String transactionId) {
        return selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<TbsgChannelTransactionDO>lambdaQuery()
                .eq(TbsgChannelTransactionDO::getTransactionId, transactionId));
    }

    /**
     * 按中台 outTradeNo 查询；mock 收银台点支付时用此键加载本地交易。
     */
    default TbsgChannelTransactionDO selectByOutTradeNo(String outTradeNo) {
        return selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<TbsgChannelTransactionDO>lambdaQuery()
                .eq(TbsgChannelTransactionDO::getOutTradeNo, outTradeNo));
    }
}
