package com.aifuli.module.supplychain.tradecore.catalog.dal.read;

import com.aifuli.module.supplychain.tradecore.catalog.readmodel.BuyerCatalogReadRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 买家目录读模型查询。
 *
 * <p>复杂多表 JOIN 与动态筛选片段已按开发规范第 8 条落入 XML Mapper（{@code BuyerCatalogReadMapper.xml}），
 * 保证 {@code selectPage} 与 {@code countPage} 共享相同的 eligibility 条件与过滤逻辑。</p>
 *
 * <p>详见 {@code docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#7-管理可见性与显式重算}。</p>
 */
@Mapper
public interface BuyerCatalogReadMapper {

    List<BuyerCatalogReadRow> selectPage(@Param("buyerId") Long buyerId,
                                         @Param("buyerSkuCode") String buyerSkuCode,
                                         @Param("fulfillmentType") String fulfillmentType,
                                         @Param("availableOnly") boolean availableOnly,
                                         @Param("saleablePricingStatus") String saleablePricingStatus,
                                         @Param("now") LocalDateTime now,
                                         @Param("offset") long offset,
                                         @Param("limit") int limit);

    long countPage(@Param("buyerId") Long buyerId,
                   @Param("buyerSkuCode") String buyerSkuCode,
                   @Param("fulfillmentType") String fulfillmentType,
                   @Param("availableOnly") boolean availableOnly,
                   @Param("saleablePricingStatus") String saleablePricingStatus,
                   @Param("now") LocalDateTime now);

    BuyerCatalogReadRow selectByListingId(@Param("listingId") Long listingId);
}
