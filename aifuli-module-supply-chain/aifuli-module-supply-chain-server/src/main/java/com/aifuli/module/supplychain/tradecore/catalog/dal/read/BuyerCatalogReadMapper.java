package com.aifuli.module.supplychain.tradecore.catalog.dal.read;

import com.aifuli.module.supplychain.tradecore.catalog.readmodel.BuyerCatalogReadRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 买家目录读模型查询。
 *
 * <p><b>为何保留 {@code @Select}（开发规范第 8 条例外）</b>：
 * 本接口是跨 listing / version / sku / spu / brand / category / availability 的多表 JOIN 读模型，
 * 并带动态 {@code <script>} 条件（buyerSkuCode、fulfillmentType、availableOnly）与分页 OFFSET。
 * {@code LambdaQueryWrapper} 面向单实体表，无法等价表达该投影与动态片段；
 * 若迁出注解应优先落到 XML Mapper，而不是强行改成 Lambda。</p>
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md} 目录读模型相关章节。</p>
 */
@Mapper
public interface BuyerCatalogReadMapper {

    String READ_COLUMNS = """
            SELECT l.id AS listing_id,
                   l.buyer_sku_code,
                   l.status AS listing_status,
                   lv.version_no AS listing_version,
                   sku.sku_code AS platform_sku_code,
                   spu.spu_code AS platform_spu_code,
                   lv.display_name,
                   brand.brand_code,
                   brand.brand_name,
                   category.category_code,
                   category.category_name,
                   sku.fulfillment_type,
                   lv.buyer_settlement_unit_price,
                   lv.currency,
                   availability.availability,
                   availability.version AS availability_version,
                   l.updated_at
              FROM sc_buyer_listing l
              JOIN sc_buyer_listing_version lv ON lv.id = l.current_version_id
              JOIN sc_platform_sku sku ON sku.id = l.platform_sku_id
              JOIN sc_platform_spu spu ON spu.id = sku.spu_id
              JOIN sc_brand brand ON brand.id = spu.brand_id
              JOIN sc_product_category category ON category.id = spu.primary_category_id
              LEFT JOIN sc_buyer_listing_availability availability ON availability.listing_id = l.id
            """;

    @Select("""
            <script>
            """ + READ_COLUMNS + """
             WHERE l.buyer_id = #{buyerId}
               AND l.status IN (10, 20)
               <if test='buyerSkuCode != null and buyerSkuCode != ""'>
                 AND l.buyer_sku_code = #{buyerSkuCode}
               </if>
               <if test='fulfillmentType != null and fulfillmentType != ""'>
                 AND sku.fulfillment_type = #{fulfillmentType}
               </if>
               <if test='availableOnly'>
                 AND l.status = 10 AND availability.availability = 10
               </if>
             ORDER BY l.id ASC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<BuyerCatalogReadRow> selectPage(@Param("buyerId") Long buyerId,
                                         @Param("buyerSkuCode") String buyerSkuCode,
                                         @Param("fulfillmentType") String fulfillmentType,
                                         @Param("availableOnly") boolean availableOnly,
                                         @Param("offset") long offset,
                                         @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM sc_buyer_listing l
              JOIN sc_platform_sku sku ON sku.id = l.platform_sku_id
              LEFT JOIN sc_buyer_listing_availability availability ON availability.listing_id = l.id
             WHERE l.buyer_id = #{buyerId}
               AND l.current_version_id IS NOT NULL
               AND l.status IN (10, 20)
               <if test='buyerSkuCode != null and buyerSkuCode != ""'>
                 AND l.buyer_sku_code = #{buyerSkuCode}
               </if>
               <if test='fulfillmentType != null and fulfillmentType != ""'>
                 AND sku.fulfillment_type = #{fulfillmentType}
               </if>
               <if test='availableOnly'>
                 AND l.status = 10 AND availability.availability = 10
               </if>
            </script>
            """)
    long countPage(@Param("buyerId") Long buyerId,
                   @Param("buyerSkuCode") String buyerSkuCode,
                   @Param("fulfillmentType") String fulfillmentType,
                   @Param("availableOnly") boolean availableOnly);

    @Select(READ_COLUMNS + " WHERE l.id = #{listingId}")
    BuyerCatalogReadRow selectByListingId(@Param("listingId") Long listingId);
}
