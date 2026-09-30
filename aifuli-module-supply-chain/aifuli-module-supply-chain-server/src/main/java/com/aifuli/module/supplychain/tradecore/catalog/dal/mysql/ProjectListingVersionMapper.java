package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ProjectListingVersionMapper extends BaseMapperX<ProjectListingVersionDO> {
    default List<ProjectListingVersionDO> selectByListingId(Long listingId) {
        return selectList(new LambdaQueryWrapper<ProjectListingVersionDO>()
                .eq(ProjectListingVersionDO::getListingId, listingId)
                .orderByAsc(ProjectListingVersionDO::getVersionNo));
    }
}
