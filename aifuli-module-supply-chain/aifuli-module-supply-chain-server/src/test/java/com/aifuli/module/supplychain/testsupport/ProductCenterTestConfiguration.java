package com.aifuli.module.supplychain.testsupport;

import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.tradecore.catalog.application.BrandAdminService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCategoryCoverageService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductDeletionValidator;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuSchemaRegistry;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductClassificationValidationService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductSellabilityService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductTypeValidationService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 为显式列举 Bean 的真实 MySQL 测试补齐商品中心统一校验依赖。 */
@Configuration(proxyBeanMethods = false)
@Import({SupplierEligibilityTestConfiguration.class, BrandAdminService.class, CategoryCommandService.class, CategoryQueryService.class,
        ProductTypeValidationService.class, ProductSellabilityService.class,
        ProductCategoryCoverageService.class, ProductDeletionValidator.class,
        FulfillmentTargetSchemaRegistry.class,
        PlatformSkuSchemaRegistry.class, ProductClassificationValidationService.class,
        com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogResolver.class})
public class ProductCenterTestConfiguration {
}
