package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectSkuPricingMapper extends BaseMapperX<ProjectSkuPricingDO> {
    default ProjectSkuPricingDO selectByListingId(Long listingId) {
        return selectOne(ProjectSkuPricingDO::getListingId, listingId);
    }
    default ProjectSkuPricingDO selectByListingIdForUpdate(Long listingId) {
        return selectOneForUpdate(ProjectSkuPricingDO::getListingId, listingId);
    }
}
