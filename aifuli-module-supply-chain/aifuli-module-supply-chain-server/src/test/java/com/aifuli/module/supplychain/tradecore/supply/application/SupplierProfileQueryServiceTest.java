package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileQueryService.SimpleSupplierItem;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileQueryService.SupplierListItem;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileQueryService.SupplierPageQuery;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeModeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierContactMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierDeliveryRegionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSettlementAccountMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeModeMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 供应商列表多值筛选、简易视图与脱敏。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#51-列表}。</p>
 */
class SupplierProfileQueryServiceTest {

    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private final SupplierContactMapper contactMapper = mock(SupplierContactMapper.class);
    private final SupplierSupplyScopeMapper scopeMapper = mock(SupplierSupplyScopeMapper.class);
    private final SupplierSupplyScopeModeMapper modeMapper = mock(SupplierSupplyScopeModeMapper.class);
    private final SupplierOfferMapper offerMapper = mock(SupplierOfferMapper.class);
    private final PlatformSkuMapper platformSkuMapper = mock(PlatformSkuMapper.class);
    private SupplierProfileQueryService service;

    @BeforeEach
    void setUp() {
        service = new SupplierProfileQueryService(
                supplierMapper, contactMapper, mock(SupplierDeliveryRegionMapper.class),
                scopeMapper, modeMapper, offerMapper, platformSkuMapper, mock(SupplierSettlementAccountMapper.class),
                mock(com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierFileRefMapper.class),
                mock(com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierIntegrationModeMapper.class),
                mock(EnvelopeCryptoPort.class));
    }

    @Test
    void countsCooperativeProductsBySkuRowsEvenWhenSameSpu() {
        SupplierOfferDO skuA1 = SupplierOfferDO.builder().platformSkuId(11L).build();
        SupplierOfferDO skuA2 = SupplierOfferDO.builder().platformSkuId(12L).build();
        SupplierOfferDO skuB1 = SupplierOfferDO.builder().platformSkuId(21L).build();

        assertThat(SupplierProfileQueryService.countLiveSkus(List.of(skuA1, skuA2, skuB1)))
                .isEqualTo(3L);
    }

    @Test
    void countsDetailCooperativeProductsByDistinctSpu() {
        List<PlatformSkuDO> skus = List.of(
                PlatformSkuDO.builder().id(11L).spuId(1L).build(),
                PlatformSkuDO.builder().id(12L).spuId(1L).build(),
                PlatformSkuDO.builder().id(21L).spuId(2L).build());

        assertThat(SupplierProfileQueryService.countDistinctSpus(skus)).isEqualTo(2L);
    }

    @Test
    void pageQueryAcceptsMultiStatusAndModes() {
        when(modeMapper.selectList(any())).thenReturn(List.of(
                SupplierSupplyScopeModeDO.builder().supplyScopeId(101L).supplyMode("BATCH").build()));
        when(scopeMapper.selectList(any())).thenReturn(List.of(
                SupplierSupplyScopeDO.builder().id(101L).supplierId(1L)
                        .sellableCategory("EQUITY").productType("VIP")
                        .productClassification("VIRTUAL_CARD").build()));
        SupplierDO batchSupplier = SupplierDO.builder()
                .id(1L).supplierCode("SPL_BATCH").supplierName("批量供应商")
                .status(SupplierStatus.ACTIVE.getCode())
                .onboardedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 1, 0, 0))
                .build();
        when(supplierMapper.selectPage(any(PageParam.class), ArgumentMatchers.any()))
                .thenReturn(new PageResult<>(List.of(batchSupplier), 1L));
        stubListItem(batchSupplier, 101L, "BATCH");

        PageResult<SupplierListItem> page = service.page(new SupplierPageQuery(
                null, null, List.of(SupplierStatus.ACTIVE), List.of("EQUITY"), List.of("BATCH"),
                null, null, 1, 20));

        assertThat(page.getList()).extracting(SupplierListItem::supplierCode).containsExactly("SPL_BATCH");
        SupplierListItem row = page.getList().getFirst();
        assertThat(row.id()).isEqualTo(1L);
        assertThat(row.sellableCategories()).containsExactly("EQUITY");
        assertThat(row.productTypes()).containsExactly("VIP");
        assertThat(row.productClassifications()).containsExactly("VIRTUAL_CARD");
        assertThat(row.supplyModes()).containsExactly("BATCH");
        assertThat(row.skuCount()).isZero();
    }

    @Test
    void listSupplyScopesReturnsViewsWithModesNotDo() {
        SupplierDO supplier = SupplierDO.builder()
                .id(1L).supplierCode("SPL_SCOPE").supplierName("范围供应商")
                .status(SupplierStatus.ACTIVE.getCode()).build();
        when(supplierMapper.selectByCode("SPL_SCOPE")).thenReturn(supplier);
        stubListItem(supplier, 101L, "API_DIRECT");
        when(modeMapper.selectByScopeIds(any())).thenReturn(List.of(
                SupplierSupplyScopeModeDO.builder().supplyScopeId(101L).supplyMode("API_DIRECT").build(),
                SupplierSupplyScopeModeDO.builder().supplyScopeId(101L).supplyMode("H5").build()));

        var views = service.listSupplyScopes("SPL_SCOPE");

        assertThat(views).hasSize(1);
        assertThat(views.getFirst().supplyModes()).containsExactly("API_DIRECT", "H5");
        assertThat(views.getFirst().sellableCategory()).isEqualTo("EQUITY");
        assertThat(views.getFirst().productType()).isEqualTo("VIP");
        assertThat(views.getFirst().productClassification()).isEqualTo("VIRTUAL_CARD");
    }

    @Test
    void simpleViewReturnsCodeAndNameOnly() {
        when(supplierMapper.selectList(any())).thenReturn(List.of(
                SupplierDO.builder().supplierCode("SPL_A").supplierName("甲供应商").build(),
                SupplierDO.builder().supplierCode("SPL_B").supplierName("乙供应商").build()));

        List<SimpleSupplierItem> items = service.listSimple("供应商", null);

        assertThat(items).containsExactly(
                new SimpleSupplierItem("SPL_A", "甲供应商"),
                new SimpleSupplierItem("SPL_B", "乙供应商"));
    }

    private void stubListItem(SupplierDO supplier, Long scopeId, String supplyMode) {
        SupplierSupplyScopeDO scope = SupplierSupplyScopeDO.builder()
                .id(scopeId).supplierId(supplier.getId())
                .sellableCategory("EQUITY").productType("VIP")
                .productClassification("VIRTUAL_CARD").build();
        when(scopeMapper.selectBySupplierId(supplier.getId())).thenReturn(List.of(scope));
        when(modeMapper.selectByScopeIds(any())).thenReturn(List.of(
                SupplierSupplyScopeModeDO.builder().supplyScopeId(scopeId).supplyMode(supplyMode).build()));
        when(offerMapper.selectCount(any())).thenReturn(0L);
        when(contactMapper.selectBySupplierId(supplier.getId())).thenReturn(List.of());
    }
}
