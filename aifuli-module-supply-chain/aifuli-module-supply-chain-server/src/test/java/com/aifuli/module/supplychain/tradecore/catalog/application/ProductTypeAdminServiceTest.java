package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_TYPE_DUPLICATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_TYPE_HAS_PRODUCTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 商品类型管理应用服务单测。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#32-商品类型卡片列表与crud}。
 */
class ProductTypeAdminServiceTest {

    private final ProductTypeMapper typeMapper = mock(ProductTypeMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final BusinessNoGenerator generator = mock(BusinessNoGenerator.class);
    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);

    private final ProductTypeAdminService service = new ProductTypeAdminService(
            typeMapper, spuMapper, skuMapper, generator, clock);

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformSpuDO.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformSkuDO.class);
    }

    @Test
    void createGeneratesCodeWhenMissingAndDisallowsDuplicateName() {
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(generator.next(BusinessNoPrefixes.PT)).thenReturn("PT_123");
        when(typeMapper.insert(any(ProductTypeDO.class))).thenThrow(new DuplicateKeyException("duplicate"));

        ProductTypeAdminService.CreateCommand cmd = new ProductTypeAdminService.CreateCommand(
                null, "虚拟类型", "https://example.com/icon.png", 10, CatalogEntityStatus.ACTIVE.getCode(),
                "虚拟卡密描述", "测试", 1L);

        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_TYPE_DUPLICATE.getCode());
    }

    @Test
    void createValidatesInitialStatus() {
        ProductTypeAdminService.CreateCommand cmd = new ProductTypeAdminService.CreateCommand(
                null, "虚拟类型", "https://example.com/icon.png", 10, CatalogEntityStatus.ARCHIVED.getCode(),
                "虚拟卡密描述", "测试", 1L);

        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_STATUS_INVALID.getCode());
    }

    @Test
    void detailAggregatesBothSpuAndSkuCounts() {
        ProductTypeDO type = ProductTypeDO.builder()
                .id(1L)
                .typeCode("PT_CARD")
                .typeName("虚拟卡密")
                .iconUrl("https://example.com/card.png")
                .description("卡密类虚拟商品，如会员激活码、充值卡密码")
                .sort(1)
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .remark("系统内置")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(typeMapper.selectByCode("PT_CARD")).thenReturn(type);
        when(spuMapper.countProductAndSkuByTypeCodes(any())).thenReturn(List.of(
                new com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeCountRow("PT_CARD", 2L, 3L)));

        ProductTypeAdminService.ProductTypeView view = service.detail("PT_CARD");
        assertThat(view.typeCode()).isEqualTo("PT_CARD");
        assertThat(view.typeName()).isEqualTo("虚拟卡密");
        assertThat(view.iconUrl()).isEqualTo("https://example.com/card.png");
        assertThat(view.description()).isEqualTo("卡密类虚拟商品，如会员激活码、充值卡密码");
        assertThat(view.spuCount()).isEqualTo(2L);
        assertThat(view.productCount()).isEqualTo(2L);
        assertThat(view.skuCount()).isEqualTo(3L);
    }

    @Test
    void deleteBlockedWhenProductsReferenceType() {
        ProductTypeDO type = ProductTypeDO.builder()
                .id(1L)
                .typeCode("PT_WITH_PROD")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .build();
        when(typeMapper.selectByCodeForUpdate("PT_WITH_PROD")).thenReturn(type);
        when(spuMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.delete("PT_WITH_PROD", 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_TYPE_HAS_PRODUCTS.getCode());
    }

    @Test
    void changeStatusOnlyAllowsActiveOrInactive() {
        assertThatThrownBy(() -> service.changeStatus("PT_X", CatalogEntityStatus.ARCHIVED, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_STATUS_INVALID.getCode());
    }
}
