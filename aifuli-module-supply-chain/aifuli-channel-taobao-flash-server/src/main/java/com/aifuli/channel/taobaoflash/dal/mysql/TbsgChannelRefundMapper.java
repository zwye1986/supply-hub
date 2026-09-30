package com.aifuli.channel.taobaoflash.dal.mysql;

import com.aifuli.channel.taobaoflash.dal.dataobject.TbsgChannelRefundDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 渠道退款记录表 Mapper。
 *
 * <p>查询优先使用 {@code lambdaQuery()} 方法引用绑定列名，避免字符串列名漂移。
 * 详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7。</p>
 */
@Mapper
public interface TbsgChannelRefundMapper extends BaseMapper<TbsgChannelRefundDO> {

    /**
     * 按 ele.me 渠道退款号查询退款记录。
     */
    default TbsgChannelRefundDO selectByChannelRefundNo(String channelRefundNo) {
        return selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<TbsgChannelRefundDO>lambdaQuery()
                .eq(TbsgChannelRefundDO::getChannelRefundNo, channelRefundNo));
    }
}
