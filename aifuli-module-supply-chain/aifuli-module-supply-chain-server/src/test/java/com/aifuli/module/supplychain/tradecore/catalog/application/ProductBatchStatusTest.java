package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.BatchStatusCommand;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.UnshelveMode;
import org.springframework.context.ApplicationEventPublisher;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 批量上下架命令测试。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4110-批量上下架接口put-supply-chainproductsbatch-status}。
 */
class ProductBatchStatusTest {

    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final BrandMapper brandMapper = mock(BrandMapper.class);
    private final ProductCategoryMapper categoryMapper = mock(ProductCategoryMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerListingMapper buyerListingMapper = mock(BuyerListingMapper.class);
    private final BuyerListingAvailabilityMapper availabilityMapper = mock(BuyerListingAvailabilityMapper.class);
    private final BusinessNoGenerator generator = mock(BusinessNoGenerator.class);
    private final BrandAdminService brandAdminService = mock(BrandAdminService.class);
    private final CategoryCommandService categoryCommandService = mock(CategoryCommandService.class);
    private final ProductTypeValidationService productTypeValidationService = mock(ProductTypeValidationService.class);
    private final ProductSellabilityService sellabilityService = mock(ProductSellabilityService.class);
    private final ProductDeletionValidator deletionValidator = mock(ProductDeletionValidator.class);
    private final FulfillmentTargetSchemaRegistry targetSchemaRegistry = mock(FulfillmentTargetSchemaRegistry.class);
    private final PlatformSkuSchemaRegistry skuSchemaRegistry = new PlatformSkuSchemaRegistry(targetSchemaRegistry);

    private final ProductCatalogCommandService service = new ProductCatalogCommandService(
            clock, eventPublisher, brandMapper, categoryMapper, spuMapper, skuMapper, buyerMapper, buyerListingMapper,
            availabilityMapper, generator, brandAdminService, categoryCommandService,
            productTypeValidationService, sellabilityService, deletionValidator, targetSchemaRegistry,
            skuSchemaRegistry);

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformSpuDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformSkuDO.class);
        when(clock.now()).thenReturn(LocalDateTime.now());
    }

    @Test
    void batchUnshelveSetsStatusAndManualMode() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(1L)
                .spuCode("SPU_001")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .version(0L)
                .build();
        when(spuMapper.selectByCodeForUpdate("SPU_001")).thenReturn(spu);
        when(spuMapper.updateById(any(PlatformSpuDO.class))).thenReturn(1);

        PlatformSkuDO sku = PlatformSkuDO.builder()
                .id(10L)
                .skuCode("SKU_001")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .version(0L)
                .build();
        when(skuMapper.selectByCodeForUpdate("SKU_001")).thenReturn(sku);
        when(skuMapper.updateById(any(PlatformSkuDO.class))).thenReturn(1);

        BatchStatusCommand cmd = new BatchStatusCommand(
                List.of("SPU_001"), List.of("SKU_001"), CatalogEntityStatus.INACTIVE.getCode(), null, 99L);

        service.batchChangeStatus(cmd);

        ArgumentCaptor<PlatformSpuDO> spuCaptor = ArgumentCaptor.forClass(PlatformSpuDO.class);
        verify(spuMapper).updateById(spuCaptor.capture());
        assertThat(spuCaptor.getValue().getStatus()).isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
        assertThat(spuCaptor.getValue().getUnshelveMode()).isEqualTo(UnshelveMode.MANUAL_UNSHELVE.getCode());

        ArgumentCaptor<PlatformSkuDO> skuCaptor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper).updateById(skuCaptor.capture());
        assertThat(skuCaptor.getValue().getStatus()).isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
    }

    @Test
    void batchShelveClearsUnshelveMode() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(2L)
                .spuCode("SPU_002")
                .productType("PT_CARD")
                .status(CatalogEntityStatus.INACTIVE.getCode())
                .unshelveMode(UnshelveMode.MANUAL_UNSHELVE.getCode())
                .version(0L)
                .build();
        when(spuMapper.selectByCodeForUpdate("SPU_002")).thenReturn(spu);
        when(spuMapper.updateById(any(PlatformSpuDO.class))).thenReturn(1);

        BatchStatusCommand cmd = new BatchStatusCommand(
                List.of("SPU_002"), null, CatalogEntityStatus.ACTIVE.getCode(), null, 99L);

        service.batchChangeStatus(cmd);

        ArgumentCaptor<PlatformSpuDO> spuCaptor = ArgumentCaptor.forClass(PlatformSpuDO.class);
        verify(spuMapper).updateById(spuCaptor.capture());
        assertThat(spuCaptor.getValue().getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
        assertThat(spuCaptor.getValue().getUnshelveMode()).isNull();
    }

    @Test
    void batchDisallowsInvalidTargetStatus() {
        BatchStatusCommand cmd = new BatchStatusCommand(
                List.of("SPU_003"), null, CatalogEntityStatus.ARCHIVED.getCode(), null, 99L);

        assertThatThrownBy(() -> service.batchChangeStatus(cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_STATUS_INVALID.getCode());
    }
}
