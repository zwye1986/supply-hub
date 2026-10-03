package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProjectListingMapper extends BaseMapperX<ProjectListingDO> {
    /** 按不可变主键当前读锁定准入记录；设计见 docs/superpowers/specs/2026-10-03-standard-project-direct-order-price-freeze-design.md#3-定价身份与当前准入。 */
    default ProjectListingDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(ProjectListingDO::getId, id);
    }

    /**
     * 商品类型在数据库分页前过滤，避免项目详情的 total 与跨页内容失真。
     * 设计见 docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品。
     */
    IPage<ProjectListingDO> selectPageByProductTypes(IPage<ProjectListingDO> page,
                                                     @Param("projectId") Long projectId,
                                                     @Param("availableStatus") Short availableStatus,
                                                     @Param("productTypes") List<String> productTypes);

    /** 同一类型条件的不分页查询，兼容 pageSize=-1 的既有列表契约。 */
    List<ProjectListingDO> selectAllByProductTypes(@Param("projectId") Long projectId,
                                                   @Param("availableStatus") Short availableStatus,
                                                   @Param("productTypes") List<String> productTypes);

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
