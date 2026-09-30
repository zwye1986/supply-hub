package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BuyerProductMapper extends BaseMapperX<BuyerProductDO> {
    /**
     * 项目选品的名称/编号及主类目筛选涉及 SKU、SPU 与项目目录，LambdaQueryWrapper 无法表达多表关联。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    IPage<BuyerProductDO> selectPageForProjectSelection(IPage<BuyerProductDO> page,
            @Param("buyerId") Long buyerId, @Param("activeStatus") Short activeStatus,
            @Param("productTypes") List<String> productTypes, @Param("keyword") String keyword,
            @Param("categoryIds") List<Long> categoryIds, @Param("projectId") Long projectId,
            @Param("availableStatus") Short availableStatus);

    /** 与分页查询共用过滤 SQL，兼容 pageSize=-1。 */
    List<BuyerProductDO> selectAllForProjectSelection(
            @Param("buyerId") Long buyerId, @Param("activeStatus") Short activeStatus,
            @Param("productTypes") List<String> productTypes, @Param("keyword") String keyword,
            @Param("categoryIds") List<Long> categoryIds, @Param("projectId") Long projectId,
            @Param("availableStatus") Short availableStatus);

    default BuyerProductDO selectByCode(String buyerProductCode) {
        return selectOne(BuyerProductDO::getBuyerProductCode, buyerProductCode);
    }

    default BuyerProductDO selectByCodeForUpdate(String buyerProductCode) {
        return selectOneForUpdate(BuyerProductDO::getBuyerProductCode, buyerProductCode);
    }

    default BuyerProductDO selectByBuyerAndSku(Long buyerId, Long platformSkuId) {
        return selectOne(BuyerProductDO::getBuyerId, buyerId,
                BuyerProductDO::getPlatformSkuId, platformSkuId);
    }

    default List<BuyerProductDO> selectActiveByPlatformSkuId(Long platformSkuId) {
        return selectList(new LambdaQueryWrapper<BuyerProductDO>()
                .eq(BuyerProductDO::getPlatformSkuId, platformSkuId)
                .eq(BuyerProductDO::getStatus, BuyerProductStatus.ACTIVE.getCode()));
    }
}
