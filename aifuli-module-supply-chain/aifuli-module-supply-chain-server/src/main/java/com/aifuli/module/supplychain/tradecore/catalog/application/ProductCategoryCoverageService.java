package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商品分类归属判定：供货配置分类码是否覆盖 SPU 分类码。
 *
 * <p>规则（2026-09-23 修订，取代三码全等）：</p>
 * <ul>
 *   <li>字符串相等即覆盖（封闭枚举名、类目码通用）；</li>
 *   <li>不等时仅当两边都是类目树 {@code category_code}，且配置码在 SPU 码的祖先链上才覆盖
 *       （配 L1 覆盖其下 L2/L3 的 SPU）；</li>
 *   <li>归属链基于全量类目（含归档），类目归档不断链；</li>
 *   <li>任一边是封闭枚举名或未知名 → 不覆盖，枚举仍只认全等。</li>
 * </ul>
 *
 * <p>类目树数据所有权在 catalog；supply 侧的供货命中（
 * {@code SupplierCooperativeProductService#matchScope}）经本服务委托，
 * 不直接依赖 {@link ProductCategoryMapper}，见
 * {@code SupplyChainModuleArchitectureTest#supply_matching_does_not_read_category_tree}。</p>
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#1-已确认决策}（d3）。</p>
 */
@Service
@RequiredArgsConstructor
public class ProductCategoryCoverageService {

    private final ProductCategoryMapper categoryMapper;

    /**
     * 加载全量类目（含归档）构建一次业务请求内复用的归属判定器，避免逐行重复查表。
     */
    public CoverageResolver loadResolver() {
        List<ProductCategoryDO> all = categoryMapper.selectList(new LambdaQueryWrapper<>());
        return new CoverageResolver(all);
    }

    /**
     * 一次请求内复用的归属判定器。不可变、线程安全，仅在构建时快照类目树。
     */
    public static final class CoverageResolver {

        private final Map<String, ProductCategoryDO> byCode;
        private final Map<Long, ProductCategoryDO> byId;

        /**
         * 由类目快照构建判定器。public 以便单测直接构造快照；运行期统一走
         * {@link ProductCategoryCoverageService#loadResolver()}。
         */
        public CoverageResolver(List<ProductCategoryDO> all) {
            byCode = new HashMap<>();
            byId = new HashMap<>();
            for (ProductCategoryDO category : all) {
                if (category.getCategoryCode() != null) {
                    byCode.put(category.getCategoryCode(), category);
                }
                if (category.getId() != null) {
                    byId.put(category.getId(), category);
                }
            }
        }

        /**
         * 配置分类码是否覆盖 SPU 分类码。相等即覆盖；不等时两边都必须是已知类目码，
         * 且配置类目在 SPU 类目的祖先链上。祖先链含归档节点（归档不断链），
         * 并用 visited 集合防脏数据成环导致的死循环。
         */
        public boolean covers(String scopeClassification, String spuClassification) {
            if (scopeClassification == null || spuClassification == null) {
                return false;
            }
            if (scopeClassification.equals(spuClassification)) {
                return true;
            }
            ProductCategoryDO scopeCategory = byCode.get(scopeClassification);
            ProductCategoryDO spuCategory = byCode.get(spuClassification);
            if (scopeCategory == null || spuCategory == null) {
                return false;
            }
            Set<Long> visited = new HashSet<>();
            Long cursor = spuCategory.getParentId();
            while (cursor != null && visited.add(cursor)) {
                if (cursor.equals(scopeCategory.getId())) {
                    return true;
                }
                ProductCategoryDO parent = byId.get(cursor);
                cursor = parent == null ? null : parent.getParentId();
            }
            return false;
        }

        /**
         * 命中最具体优先的比较依据：类目码返回其层级（L3 &gt; L2 &gt; L1）；
         * 封闭枚举名或未知码返回 0（它们只可能全等命中，彼此不存在祖孙关系）。
         */
        public int depth(String classification) {
            ProductCategoryDO category = classification == null ? null : byCode.get(classification);
            return category != null && category.getCategoryLevel() != null ? category.getCategoryLevel() : 0;
        }
    }
}
