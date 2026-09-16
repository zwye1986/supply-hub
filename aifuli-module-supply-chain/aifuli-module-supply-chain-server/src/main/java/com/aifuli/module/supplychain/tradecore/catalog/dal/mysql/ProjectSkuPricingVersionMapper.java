package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectSkuPricingVersionMapper extends BaseMapperX<ProjectSkuPricingVersionDO> {
    default ProjectSkuPricingVersionDO selectLatest(Long pricingId) {
        return selectOne(new LambdaQueryWrapper<ProjectSkuPricingVersionDO>()
                .eq(ProjectSkuPricingVersionDO::getPricingId, pricingId)
                .orderByDesc(ProjectSkuPricingVersionDO::getVersionNo).last("LIMIT 1"));
    }
}
