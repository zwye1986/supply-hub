package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerCategoryScopeDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerContactDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerCooperationModeDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerCategoryScopeMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerContactMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerCooperationModeMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerFileRefMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerIntegrationModeMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerScopeKind;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分销商列表聚合字段与详情范围回显。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。
 */
class BuyerAdminQueryServiceTest {

    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerContactMapper contactMapper = mock(BuyerContactMapper.class);
    private final BuyerIntegrationModeMapper integrationModeMapper = mock(BuyerIntegrationModeMapper.class);
    private final BuyerCooperationModeMapper cooperationModeMapper = mock(BuyerCooperationModeMapper.class);
    private final BuyerCategoryScopeMapper categoryScopeMapper = mock(BuyerCategoryScopeMapper.class);
    private final BuyerFileRefMapper fileRefMapper = mock(BuyerFileRefMapper.class);
    private final BuyerAccountMapper accountMapper = mock(BuyerAccountMapper.class);
    private final BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
    private final BuyerProductMapper productMapper = mock(BuyerProductMapper.class);
    private final EnvelopeCryptoPort envelopeCrypto = mock(EnvelopeCryptoPort.class);
    private BuyerAdminQueryService service;

    @BeforeEach
    void setUp() {
        service = new BuyerAdminQueryService(
                buyerMapper, contactMapper, integrationModeMapper, cooperationModeMapper,
                categoryScopeMapper, fileRefMapper, accountMapper, projectMapper, productMapper, envelopeCrypto);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageReturnsProductTypesCountsAndCooperationTime() {
        LocalDateTime cooperationTime = LocalDateTime.of(2026, 9, 1, 9, 30);
        BuyerDO buyer = BuyerDO.builder()
                .id(20L)
                .buyerCode("BYR_LIST")
                .buyerName("列表分销商")
                .distributionLevel((short) 1)
                .status(BuyerStatus.ACTIVE.getCode())
                .cooperationStartedAt(cooperationTime)
                .updatedAt(LocalDateTime.of(2026, 9, 9, 10, 0))
                .build();
        when(buyerMapper.selectPage(any(PageParam.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new PageResult<>(List.of(buyer), 1L));
        when(categoryScopeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                BuyerCategoryScopeDO.builder().id(1L).buyerId(20L)
                        .scopeKind(BuyerScopeKind.SELLABLE.name()).scopeCode("EQUITY").build(),
                BuyerCategoryScopeDO.builder().id(2L).buyerId(20L)
                        .scopeKind(BuyerScopeKind.PRODUCT_TYPE.name()).scopeCode("EQUITY_PRODUCT").build()));
        when(cooperationModeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                BuyerCooperationModeDO.builder().id(1L).buyerId(20L).cooperationMode("DIRECT").build()));
        when(contactMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                BuyerContactDO.builder().id(1L).buyerId(20L).sortNo(0)
                        .contactName("张三").contactMobile("13800138000").build()));
        when(projectMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                BuyerProjectDO.builder().id(1L).buyerId(20L).build(),
                BuyerProjectDO.builder().id(2L).buyerId(20L).build()));
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                BuyerProductDO.builder().id(1L).buyerId(20L).status(CatalogEntityStatus.ACTIVE.getCode()).build(),
                BuyerProductDO.builder().id(2L).buyerId(20L).status(CatalogEntityStatus.ACTIVE.getCode()).build(),
                BuyerProductDO.builder().id(3L).buyerId(20L).status(CatalogEntityStatus.ACTIVE.getCode()).build()));

        var result = service.page(new BuyerAdminQueryService.BuyerPageQuery(
                null, null, List.of(), List.of(), List.of(), List.of(), null, null, 1, 20));

        assertThat(result.getTotal()).isEqualTo(1L);
        var row = result.getList().getFirst();
        assertThat(row.sellableCategories()).containsExactly("EQUITY");
        assertThat(row.productTypes()).containsExactly("EQUITY_PRODUCT");
        assertThat(row.cooperationModes()).containsExactly("DIRECT");
        assertThat(row.projectCount()).isEqualTo(2L);
        assertThat(row.skuCount()).isEqualTo(3L);
        assertThat(row.cooperationTime()).isEqualTo(cooperationTime);
        assertThat(row.primaryContactName()).isEqualTo("张三");
        assertThat(row.primaryContactMobile()).isEqualTo("138****8000");
    }

    @Test
    void getReturnsSellableCategoriesAndProductTypesFromIndependentScopeRows() {
        BuyerDO buyer = BuyerDO.builder()
                .id(21L)
                .buyerCode("BYR_DETAIL")
                .buyerName("详情分销商")
                .distributionLevel((short) 1)
                .status(BuyerStatus.ACTIVE.getCode())
                .build();
        when(buyerMapper.selectByBuyerCode("BYR_DETAIL")).thenReturn(buyer);
        when(contactMapper.selectByBuyerId(21L)).thenReturn(List.of());
        when(fileRefMapper.selectByBuyerId(21L)).thenReturn(List.of());
        when(integrationModeMapper.selectByBuyerId(21L)).thenReturn(List.of());
        when(cooperationModeMapper.selectByBuyerId(21L)).thenReturn(List.of());
        when(categoryScopeMapper.selectByBuyerId(21L)).thenReturn(List.of(
                BuyerCategoryScopeDO.builder().buyerId(21L)
                        .scopeKind(BuyerScopeKind.SELLABLE.name()).scopeCode("MOVIE").build(),
                BuyerCategoryScopeDO.builder().buyerId(21L)
                        .scopeKind(BuyerScopeKind.PRODUCT_TYPE.name()).scopeCode("MOVIE").build()));

        var detail = service.get("BYR_DETAIL");

        assertThat(detail.sellableCategories()).containsExactly("MOVIE");
        assertThat(detail.productTypes()).containsExactly("MOVIE");
    }
}
