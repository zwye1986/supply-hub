package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeCountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface PlatformSpuMapper extends BaseMapperX<PlatformSpuDO> {
    default PlatformSpuDO selectByCode(String code) {
        return selectOne(PlatformSpuDO::getSpuCode, code);
    }

    default PlatformSpuDO selectByCodeForUpdate(String code) {
        return selectOneForUpdate(PlatformSpuDO::getSpuCode, code);
    }

    /**
     * 按商品类型聚合统计未归档 SPU 数量与关联未归档 SKU 数量。
     *
     * <p><b>为何使用 {@code @Select}（开发规范第 8 条例外）</b>：
     * 本查询为跨 SPU 与 SKU 的多表 LEFT JOIN GROUP BY 聚合，避免在应用层全量拉取百万级 SPU/SKU 对象造成内存溢出。
     * {@code LambdaQueryWrapper} 无法表达多表 LEFT JOIN 聚合投影。</p>
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#28-商品管理列表端模型增强与类型优化030-changeset}。
     */
    @Select("""
            <script>
            SELECT s.product_type AS productType,
                   COUNT(DISTINCT s.id) AS spuCount,
                   COUNT(k.id) AS skuCount
              FROM sc_platform_spu s
              LEFT JOIN sc_platform_sku k ON k.spu_id = s.id AND k.status != 90
             WHERE s.status != 90
               AND s.product_type IN
               <foreach item='code' collection='typeCodes' open='(' separator=',' close=')'>
                 #{code}
               </foreach>
             GROUP BY s.product_type
            </script>
            """)
    List<ProductTypeCountRow> countProductAndSkuByTypeCodes(@Param("typeCodes") Collection<String> typeCodes);
}
