package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BrandDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BRAND_HAS_PRODUCTS;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BRAND_NAME_DUPLICATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 品牌管理应用服务单测。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#34-商品品牌管理}。
 */
class BrandAdminServiceTest {

    private final BrandMapper brandMapper = mock(BrandMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final BusinessNoGenerator generator = mock(BusinessNoGenerator.class);
    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);

    private final BrandAdminService service = new BrandAdminService(
            brandMapper, spuMapper, generator, clock);

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformSpuDO.class);
    }

    @Test
    void createBrandDisallowsDuplicateName() {
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(generator.next(BusinessNoPrefixes.BRD)).thenReturn("BRD_001");
        when(brandMapper.insert(any(BrandDO.class))).thenThrow(new DuplicateKeyException("duplicate"));

        BrandAdminService.CreateCommand cmd = new BrandAdminService.CreateCommand(
                null, "重复品牌", "简称", null, "https://example.com/logo.png",
                "品牌描述", 0, CatalogEntityStatus.ACTIVE.getCode(), 1L);

        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BRAND_NAME_DUPLICATE.getCode());
    }

    @Test
    void createValidatesInitialStatus() {
        BrandAdminService.CreateCommand cmd = new BrandAdminService.CreateCommand(
                null, "测试品牌", "简称", null, "https://example.com/logo.png",
                "品牌描述", 0, CatalogEntityStatus.ARCHIVED.getCode(), 1L);

        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_STATUS_INVALID.getCode());
    }

    @Test
    void detailProjectsShortNameDescriptionAndSpuCount() {
        BrandDO brand = BrandDO.builder()
                .id(10L)
                .brandCode("BRD_TEST")
                .brandName("腾讯视频")
                .shortName("腾讯")
                .englishName("Tencent Video")
                .logoUrl("https://example.com/tencent.png")
                .description("在线视频平台")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .sort(5)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(brandMapper.selectByCode("BRD_TEST")).thenReturn(brand);
        when(spuMapper.selectList(any())).thenReturn(List.of(
                com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO.builder()
                        .brandId(10L).build(),
                com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO.builder()
                        .brandId(10L).build()));

        BrandAdminService.BrandView view = service.detail("BRD_TEST");
        assertThat(view.brandCode()).isEqualTo("BRD_TEST");
        assertThat(view.brandName()).isEqualTo("腾讯视频");
        assertThat(view.shortName()).isEqualTo("腾讯");
        assertThat(view.description()).isEqualTo("在线视频平台");
        assertThat(view.logoUrl()).isEqualTo("https://example.com/tencent.png");
        assertThat(view.productCount()).isEqualTo(2L);
        assertThat(view.spuCount()).isEqualTo(2L);
    }

    @Test
    void deleteBlockedWhenSpuReferencesBrand() {
        BrandDO brand = BrandDO.builder()
                .id(20L)
                .brandCode("BRD_IN_USE")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .build();
        when(brandMapper.selectByCodeForUpdate("BRD_IN_USE")).thenReturn(brand);
        when(spuMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> service.delete("BRD_IN_USE", 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BRAND_HAS_PRODUCTS.getCode());
    }
}
