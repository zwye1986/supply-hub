package com.aifuli.module.supplychain.tradecore.catalog.dal.read;

import com.aifuli.module.supplychain.tradecore.catalog.readmodel.BuyerCatalogReadRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 买家目录多表读模型，分页与计数在 XML 中共用可用性条件；不承担发布或刷新。
 * 复杂 JOIN 与 JSON 来源无法用单实体 Lambda 表达，按开发规范第8条使用 XML。
 * 设计见 {@code docs/superpowers/specs/2026-10-12-catalog-source-freshness-correction-design.md#5-实现责任边界与影响范围}。
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
