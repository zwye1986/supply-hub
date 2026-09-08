package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import org.apache.ibatis.annotations.Mapper;

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
}
