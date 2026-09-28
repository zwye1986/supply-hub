package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;

import java.util.List;

@Mapper
public interface ProjectListingMapper extends BaseMapperX<ProjectListingDO> {
    default ProjectListingDO selectByCode(String listingCode) {
        return selectOne(ProjectListingDO::getListingCode, listingCode);
    }

    default ProjectListingDO selectByCodeForUpdate(String listingCode) {
        return selectOneForUpdate(ProjectListingDO::getListingCode, listingCode);
    }

    default ProjectListingDO selectByProjectAndBuyerProduct(Long projectId, Long buyerProductId) {
        return selectOne(ProjectListingDO::getProjectId, projectId,
                ProjectListingDO::getBuyerProductId, buyerProductId);
    }

    default List<ProjectListingDO> selectAvailableByBuyerProductId(Long buyerProductId) {
        return selectList(new LambdaQueryWrapper<ProjectListingDO>()
                .eq(ProjectListingDO::getBuyerProductId, buyerProductId)
                .eq(ProjectListingDO::getStatus, ProjectListingStatus.AVAILABLE.getCode()));
    }
}
