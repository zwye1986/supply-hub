package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_TYPE_INACTIVE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_TYPE_NOT_FOUND;

/**
 * 商品类型运行时准入的唯一入口。
 *
 * <p>新增 SPU、供应商范围或分销商范围时必须锁定类型行，保证校验和类型停用串行化。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#5-跨模块依赖与供销对接机制保证}。</p>
 */
@Service
@RequiredArgsConstructor
public class ProductTypeValidationService {

    private final ProductTypeMapper productTypeMapper;

    public ProductTypeDO requireActiveForUpdate(String typeCode) {
        ProductTypeDO type = productTypeMapper.selectByCodeForUpdate(normalize(typeCode));
        if (type == null || type.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
            throw exception(PRODUCT_TYPE_NOT_FOUND);
        }
        if (type.getStatus() != CatalogEntityStatus.ACTIVE.getCode()) {
            throw exception(PRODUCT_TYPE_INACTIVE);
        }
        return type;
    }

    public ProductTypeDO requireExisting(String typeCode) {
        ProductTypeDO type = productTypeMapper.selectByCode(normalize(typeCode));
        if (type == null || type.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
            throw exception(PRODUCT_TYPE_NOT_FOUND);
        }
        return type;
    }

    private static String normalize(String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            throw exception(PRODUCT_TYPE_NOT_FOUND);
        }
        return typeCode.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
