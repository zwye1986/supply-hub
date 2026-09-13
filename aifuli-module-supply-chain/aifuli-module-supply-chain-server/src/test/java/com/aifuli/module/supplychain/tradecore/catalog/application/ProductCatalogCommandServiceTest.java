package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BrandDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.tradecore.profile.domain.ProductClassification;
import com.aifuli.module.supplychain.tradecore.profile.domain.SellableCategory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CANNOT_DELETE_HAS_SUPPLIER_OFFER;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CONCURRENT_UPDATE;
import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 平台商品写服务单测，聚焦复合更新中「省略 SKU」的停用降级语义。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#414-spusku-复合更新putsupply-chainproductsspusspucode}。
 */
class ProductCatalogCommandServiceTest {

    private static final String SPU_CODE = "SPU_1";

    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final BrandMapper brandMapper = mock(BrandMapper.class);
    private final ProductCategoryMapper categoryMapper = mock(ProductCategoryMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerListingMapper listingMapper = mock(BuyerListingMapper.class);
    private final BuyerListingAvailabilityMapper availabilityMapper = mock(BuyerListingAvailabilityMapper.class);
    private final BusinessNoGenerator generator = mock(BusinessNoGenerator.class);
    private final BrandAdminService brandAdminService = mock(BrandAdminService.class);
    private final CategoryCommandService categoryCommandService = mock(CategoryCommandService.class);
    private final ProductTypeValidationService productTypeValidationService =
            mock(ProductTypeValidationService.class);
    private final ProductSellabilityService sellabilityService = mock(ProductSellabilityService.class);
    private final ProductDeletionValidator deletionValidator = mock(ProductDeletionValidator.class);
    private final FulfillmentTargetSchemaRegistry targetSchemaRegistry = mock(FulfillmentTargetSchemaRegistry.class);
    private final PlatformSkuSchemaRegistry skuSchemaRegistry = new PlatformSkuSchemaRegistry(targetSchemaRegistry);

    private final ProductCatalogCommandService service = new ProductCatalogCommandService(
            clock, brandMapper, categoryMapper, spuMapper, skuMapper, buyerMapper, listingMapper,
            availabilityMapper, generator, brandAdminService, categoryCommandService,
            productTypeValidationService, sellabilityService, deletionValidator, targetSchemaRegistry,
            skuSchemaRegistry);

    /** 请求中省略、且仍被下游引用的 SKU 保留原状态，不被停用，且整体更新继续成功。 */
    @Test
    void omittedSkuStillReferencedIsKeptActive() {
        PlatformSkuDO omitted = givenUpdateWithOneOmittedSku();
        doThrow(exception(PRODUCT_CANNOT_DELETE_HAS_SUPPLIER_OFFER))
                .when(deletionValidator).validateSku(omitted.getId());

        service.updateProduct(SPU_CODE, saveCommand());

        assertThat(omitted.getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
    }

    /** 省略且无引用的 SKU 被安全停用。 */
    @Test
    void omittedSkuWithoutReferenceIsDeactivated() {
        PlatformSkuDO omitted = givenUpdateWithOneOmittedSku();
        doNothing().when(deletionValidator).validateSku(omitted.getId());

        service.updateProduct(SPU_CODE, saveCommand());

        assertThat(omitted.getStatus()).isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
    }

    /**
     * 引用校验之外的失败（如并发修改）必须继续上抛，不得被当作「存在引用」而静默跳过——
     * 否则会掩盖真实的并发冲突。
     */
    @Test
    void nonReferenceFailureIsNotSwallowed() {
        PlatformSkuDO omitted = givenUpdateWithOneOmittedSku();
        doThrow(exception(PRODUCT_CONCURRENT_UPDATE)).when(deletionValidator).validateSku(omitted.getId());

        assertThatThrownBy(() -> service.updateProduct(SPU_CODE, saveCommand()))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_CONCURRENT_UPDATE.getCode());
    }

    /** 构造：SPU 下挂 keep 与 omitted 两个启用 SKU，更新请求只保留 keep。 */
    private PlatformSkuDO givenUpdateWithOneOmittedSku() {
        LocalDateTime now = LocalDateTime.now();
        when(clock.now()).thenReturn(now);
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1L).spuCode(SPU_CODE)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(spuMapper.selectByCodeForUpdate(SPU_CODE)).thenReturn(spu);
        when(spuMapper.updateById(any(PlatformSpuDO.class))).thenReturn(1);
        when(brandMapper.selectById(anyLong())).thenReturn(BrandDO.builder().id(2L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(categoryMapper.selectById(anyLong())).thenReturn(ProductCategoryDO.builder().id(3L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(productTypeValidationService.requireActiveForUpdate(any()))
                .thenReturn(ProductTypeDO.builder().typeCode("EQUITY_PRODUCT").build());

        PlatformSkuDO keep = PlatformSkuDO.builder().id(10L).spuId(1L).skuCode("SKU_KEEP")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuDO omitted = PlatformSkuDO.builder().id(11L).spuId(1L).skuCode("SKU_OMITTED")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(skuMapper.selectList(any())).thenReturn(List.of(keep, omitted));
        when(skuMapper.updateById(any(PlatformSkuDO.class))).thenReturn(1);
        return omitted;
    }

    private ProductCatalogCommandService.SaveSpuCommand saveCommand() {
        ProductCatalogCommandService.SaveSkuLine keepLine = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 0L, 0L, 1L, 10L,
                CatalogEntityStatus.DRAFT.getCode());
        return new ProductCatalogCommandService.SaveSpuCommand(SPU_CODE, "商品", null, 2L, 3L, null,
                SellableCategory.EQUITY.name(), "EQUITY_PRODUCT", ProductClassification.VIRTUAL_CARD.name(),
                null, null, null, null, null, 1L, 10L,
                CatalogEntityStatus.ACTIVE.getCode(), List.of(keepLine), 1L);
    }
}
