package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.MerchantQuotaDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/** 配置/准入共用额度行当前读；无跨模块表访问。 */
@Mapper
public interface MerchantQuotaMapper extends BaseMapperX<MerchantQuotaDO> {
    default MerchantQuotaDO lock(String platform, String account) {
        return selectOne(new LambdaQueryWrapper<MerchantQuotaDO>().eq(MerchantQuotaDO::getPlatformCode, platform)
                .eq(MerchantQuotaDO::getMerchantAccount, account).last("FOR UPDATE"));
    }
}
