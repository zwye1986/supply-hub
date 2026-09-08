package com.aifuli.module.supplychain.border.o2o.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.o2o.dal.dataobject.O2OChannelUserBindingDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * O2O 渠道用户绑定 Mapper。
 *
 * <p>遵循规范：优先 LambdaQueryWrapper，不新增注解 SQL。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#9.1-O2OChannelUserBinding}。</p>
 */
@Mapper
public interface O2OChannelUserBindingMapper extends BaseMapperX<O2OChannelUserBindingDO> {

    /** 按 (channelCode, channelUserId) 解析启用绑定；缺失、歧义或停用由调用方裁决。 */
    default O2OChannelUserBindingDO selectActiveByChannelUser(String channelCode, String channelUserId) {
        return selectOne(new LambdaQueryWrapper<O2OChannelUserBindingDO>()
                .eq(O2OChannelUserBindingDO::getChannelCode, channelCode)
                .eq(O2OChannelUserBindingDO::getChannelUserId, channelUserId)
                .eq(O2OChannelUserBindingDO::getStatus, (short) 10));
    }

    /** 按 (buyerId, buyerUserId, channelCode) 查询绑定，用于 H5 入口读取或创建。 */
    default O2OChannelUserBindingDO selectByBuyerUser(Long buyerId, String buyerUserId, String channelCode) {
        return selectOne(new LambdaQueryWrapper<O2OChannelUserBindingDO>()
                .eq(O2OChannelUserBindingDO::getBuyerId, buyerId)
                .eq(O2OChannelUserBindingDO::getBuyerUserId, buyerUserId)
                .eq(O2OChannelUserBindingDO::getChannelCode, channelCode));
    }
}
