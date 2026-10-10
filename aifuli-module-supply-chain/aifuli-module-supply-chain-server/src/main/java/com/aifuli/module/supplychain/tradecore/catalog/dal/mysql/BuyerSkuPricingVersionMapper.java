package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BuyerSkuPricingVersionMapper extends BaseMapperX<BuyerSkuPricingVersionDO> {
    /** 最终AUTO准入的当前读，避免受理早期RR快照遮蔽新提交版本。见最小业务设计 §9.1。 */
    default BuyerSkuPricingVersionDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(BuyerSkuPricingVersionDO::getId, id);
    }
    default BuyerSkuPricingVersionDO selectLatest(Long pricingId) {
        return selectOne(new LambdaQueryWrapper<BuyerSkuPricingVersionDO>()
                .eq(BuyerSkuPricingVersionDO::getPricingId, pricingId)
                .orderByDesc(BuyerSkuPricingVersionDO::getVersionNo).last("LIMIT 1"));
    }
}
