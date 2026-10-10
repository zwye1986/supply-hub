package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BuyerListingVersionMapper extends BaseMapperX<BuyerListingVersionDO> {

    default BuyerListingVersionDO selectByListingAndVersionNo(Long listingId, int versionNo) {
        return selectOne(BuyerListingVersionDO::getListingId, listingId,
                BuyerListingVersionDO::getVersionNo, versionNo);
    }

    default BuyerListingVersionDO selectLatest(Long listingId) {
        return selectOne(new LambdaQueryWrapper<BuyerListingVersionDO>()
                .eq(BuyerListingVersionDO::getListingId, listingId)
                .orderByDesc(BuyerListingVersionDO::getVersionNo)
                .last("LIMIT 1"));
    }

    /**
     * 某商业来源的全部快照版本（新→旧）：首个用于沿用已冻结业务有效期，全量用于唯一键同摘要复用。
     * 见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#task-3-实施接口与事务边界。
     */
    default List<BuyerListingVersionDO> selectByListingAndSource(Long listingId, Long sourceBuyerPricingVersionId) {
        return selectList(new LambdaQueryWrapper<BuyerListingVersionDO>()
                .eq(BuyerListingVersionDO::getListingId, listingId)
                .eq(BuyerListingVersionDO::getSourceBuyerPricingVersionId, sourceBuyerPricingVersionId)
                .orderByDesc(BuyerListingVersionDO::getVersionNo));
    }
}
