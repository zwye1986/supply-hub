package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.profile.domain.ProductClassification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_NOT_FOUND;

/**
 * 商品分类写入准入：存量三枚举名仍接受；类目树编码按 {@code sc_product_category.category_code} 解析并原样落库。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d34）。
 */
@Service
@RequiredArgsConstructor
public class ProductClassificationValidationService {

    private final ProductCategoryMapper categoryMapper;

    /**
     * 解析可写入的商品分类供货命中码。
     *
     * <p>不变量：封闭枚举名原样返回且不查类目树；类目编码必须指向未归档分类，启用态写入还要求
     * {@code ACTIVE}；与 {@code primaryCategoryId} 同时出现时必须是同一行。未知码、归档、状态不符或
     * 与主类目不一致一律 {@code CATEGORY_NOT_FOUND}，不得回落 {@code ENUM_NOT_OPEN}。</p>
     *
     * @param expectedCategoryId 同时提交了主类目主键时传入，类目编码必须指向同一行；枚举名忽略该参数
     */
    public String requireWritable(String raw, boolean activeRequired, Long expectedCategoryId) {
        if (raw == null || raw.isBlank()) {
            throw exception(CATEGORY_NOT_FOUND);
        }
        String code = raw.trim();
        ProductClassification closed = parseClosed(code);
        if (closed != null) {
            return closed.name();
        }
        ProductCategoryDO category = categoryMapper.selectByCode(code);
        if (category == null || category.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()
                || (activeRequired && category.getStatus() != CatalogEntityStatus.ACTIVE.getCode())) {
            throw exception(CATEGORY_NOT_FOUND);
        }
        if (expectedCategoryId != null && !expectedCategoryId.equals(category.getId())) {
            throw exception(CATEGORY_NOT_FOUND);
        }
        return category.getCategoryCode();
    }

    private static ProductClassification parseClosed(String code) {
        try {
            return ProductClassification.valueOf(code);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
