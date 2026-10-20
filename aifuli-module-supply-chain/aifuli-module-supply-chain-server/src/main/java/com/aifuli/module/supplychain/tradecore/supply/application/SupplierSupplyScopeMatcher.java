package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCategoryCoverageService.CoverageResolver;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;

import java.util.List;

/**
 * 供应商范围唯一匹配算法，供合作商品写入口与两个选品入口复用。
 *
 * <p>只比较商品类型与商品分类，不读取可售品类或主类目ID。分类归属由catalog解析器提供，
 * 本类不查询数据库、不加载类目树、不记录日志，也不决定HTTP错误。最具体配置优先；最深层多条
 * 命中为歧义，不能依赖Mapper返回顺序任取一条，也不能回退到较浅配置。</p>
 * 设计见 {@code docs/superpowers/specs/2026-10-20-supplier-supply-scope-matching-design.md#2-唯一匹配算法}。
 */
public final class SupplierSupplyScopeMatcher {

    private SupplierSupplyScopeMatcher() {
    }

    /** 唯一配置为非空scope；无范围和歧义均无scope，写入口通过conflict区分两类拒绝。 */
    public record Match(SupplierSupplyScopeDO scope, boolean conflict) {
        public boolean matched() {
            return scope != null;
        }
    }

    /** 使用一次请求内共享的类目快照解析，归档类目不断链，枚举分类仅认全等。 */
    public static Match match(List<SupplierSupplyScopeDO> scopes, PlatformSpuDO spu, CoverageResolver coverage) {
        if (spu == null || !hasText(spu.getProductType()) || !hasText(spu.getProductClassification())) {
            return new Match(null, false);
        }
        SupplierSupplyScopeDO matched = null;
        int matchedDepth = -1;
        boolean conflict = false;
        for (SupplierSupplyScopeDO scope : scopes) {
            if (!spu.getProductType().equals(scope.getProductType())
                    || !coverage.covers(scope.getProductClassification(), spu.getProductClassification())) {
                continue;
            }
            int depth = coverage.depth(scope.getProductClassification());
            if (depth > matchedDepth) {
                matched = scope;
                matchedDepth = depth;
                conflict = false;
            } else if (depth == matchedDepth) {
                conflict = true;
            }
        }
        return new Match(conflict ? null : matched, conflict);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
