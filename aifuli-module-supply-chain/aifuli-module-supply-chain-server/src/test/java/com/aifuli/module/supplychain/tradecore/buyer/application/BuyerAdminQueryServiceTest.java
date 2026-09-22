package com.aifuli.module.supplychain.tradecore.buyer.application;

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
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    private final ProductTypeMapper productTypeMapper = mock(ProductTypeMapper.class);
    private final EnvelopeCryptoPort envelopeCrypto = mock(EnvelopeCryptoPort.class);
    private BuyerAdminQueryService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), BuyerProjectDO.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), BuyerProductDO.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProductTypeDO.class);
        service = new BuyerAdminQueryService(
                buyerMapper, contactMapper, integrationModeMapper, cooperationModeMapper,
                categoryScopeMapper, fileRefMapper, accountMapper, projectMapper, productMapper,
                productTypeMapper, envelopeCrypto);
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
        when(buyerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(buyer));
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
        assertThat(row.primaryContactMobile()).isEqualTo("13800138000");
        assertThat(row.children()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageFallsBackToCreatedAtWhenCooperationStartedAtIsEpoch() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 21, 15, 0);
        BuyerDO buyer = BuyerDO.builder()
                .id(22L)
                .buyerCode("BYR_EPOCH")
                .buyerName("纪元合作时间")
                .distributionLevel((short) 1)
                .status(BuyerStatus.ACTIVE.getCode())
                .cooperationStartedAt(LocalDateTime.of(1970, 1, 1, 8, 0))
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
        when(buyerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(buyer));
        when(categoryScopeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(cooperationModeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(contactMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(projectMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var result = service.page(new BuyerAdminQueryService.BuyerPageQuery(
                null, null, List.of(), List.of(), List.of(), List.of(), null, null, 1, 20));

        assertThat(result.getList().getFirst().cooperationTime()).isEqualTo(createdAt);
        assertThat(result.getList().getFirst().children()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageNestsDescendantsAndRollsUpVisibleSubtree() {
        stubHierarchy();

        var result = service.page(pageQuery(null, null, List.of(), List.of(), null, null, 1, 20));

        assertThat(result.getTotal()).isEqualTo(3L);
        assertThat(result.getList()).extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_L1", "BYR_OTHER", "BYR_LEGACY");
        var root = result.getList().getFirst();
        assertThat(root.distributionLevel()).isEqualTo(1);
        assertThat(root.children()).extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_L2");
        var levelTwo = root.children().getFirst();
        assertThat(levelTwo.children()).extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_L3_A", "BYR_L3_B");
        assertThat(levelTwo.children().getFirst().children()).isEmpty();
        assertThat(levelTwo.projectCount()).isEqualTo(5L);
        assertThat(levelTwo.skuCount()).isEqualTo(6L);
        assertThat(levelTwo.cooperationModes()).containsExactly("AGENT", "DIRECT", "CHANNEL");
        assertThat(levelTwo.sellableCategories()).containsExactly("EQUITY", "PHYSICAL", "MOVIE", "CATERING");
        assertThat(levelTwo.productTypes()).containsExactly("EQUITY_PRODUCT", "MOVIE", "FOOD", "DINING");
        assertThat(root.projectCount()).isEqualTo(6L);
        assertThat(root.skuCount()).isEqualTo(10L);
        assertThat(root.cooperationModes()).containsExactly("AGENT", "DIRECT", "CHANNEL");
        assertThat(root.sellableCategories()).containsExactly("EQUITY", "JD", "MOVIE", "PHYSICAL", "CATERING");
        assertThat(root.productTypes()).containsExactly("DINING", "EQUITY_PRODUCT", "MOVIE", "FOOD");
        assertThat(root.primaryContactName()).isEqualTo("赵四");
        assertThat(levelTwo.primaryContactName()).isEqualTo("李伟");
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageKeepsMatchedBranchUnderLevelOneAncestor() {
        stubHierarchy();

        var byName = service.page(pageQuery("孙级甲", null, List.of(), List.of(), null, null, 1, 20));
        assertThat(byName.getTotal()).isEqualTo(1L);
        var root = byName.getList().getFirst();
        assertThat(root.buyerCode()).isEqualTo("BYR_L1");
        assertThat(root.status()).isEqualTo("SUSPENDED");
        assertThat(root.children()).extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_L2");
        assertThat(root.children().getFirst().children())
                .extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_L3_A");
        assertThat(root.projectCount()).isEqualTo(3L);
        assertThat(root.skuCount()).isEqualTo(5L);
        assertThat(root.children().getFirst().productTypes()).containsExactly("EQUITY_PRODUCT", "MOVIE", "FOOD");
        assertThat(root.productTypes()).containsExactly("DINING", "EQUITY_PRODUCT", "MOVIE", "FOOD");

        var byMode = service.page(pageQuery(null, null, List.of(), List.of("CHANNEL"), null, null, 1, 20));
        assertThat(byMode.getList().getFirst().children().getFirst().children())
                .extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_L3_A");

        var byCategory = service.page(pageQuery(null, null, List.of(), List.of(), List.of("CATERING"), null, 1, 20));
        assertThat(byCategory.getList().getFirst().children().getFirst().children())
                .extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_L3_B");

        var detached = service.page(pageQuery(null, "BYR_ORPHAN", List.of(), List.of(), null, null, 1, 20));
        assertThat(detached.getTotal()).isZero();
        assertThat(detached.getList()).isEmpty();
        var floating = service.page(pageQuery("游离三级", null, List.of(), List.of(), null, null, 1, 20));
        assertThat(floating.getTotal()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagePaginatesLevelOneRootsAndFiltersByLevel() {
        stubHierarchy();

        var firstPage = service.page(pageQuery(null, null, List.of(), List.of(), null, null, 1, 1));
        assertThat(firstPage.getTotal()).isEqualTo(3L);
        assertThat(firstPage.getList()).extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_L1");
        assertThat(firstPage.getList().getFirst().children()).hasSize(1);

        var secondPage = service.page(pageQuery(null, null, List.of(), List.of(), null, null, 2, 1));
        assertThat(secondPage.getTotal()).isEqualTo(3L);
        assertThat(secondPage.getList()).extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_OTHER");
        assertThat(secondPage.getList().getFirst().children()).isEmpty();
        assertThat(secondPage.getList().getFirst().projectCount()).isEqualTo(1L);

        var levelThree = service.page(pageQuery(null, null, List.of(3), List.of(), null, null, 1, 20));
        assertThat(levelThree.getTotal()).isEqualTo(1L);
        assertThat(levelThree.getList().getFirst().children().getFirst().children())
                .extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_L3_A", "BYR_L3_B");

        var levelOne = service.page(pageQuery(null, null, List.of(1), List.of(), null, null, 1, 20));
        assertThat(levelOne.getList()).extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_L1", "BYR_OTHER", "BYR_LEGACY");
        assertThat(levelOne.getList().getFirst().children()).hasSize(1);

        var active = service.page(pageQuery(null, null, List.of(), List.of(), null,
                List.of(BuyerStatus.ACTIVE), 1, 20));
        assertThat(active.getList()).extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_L1", "BYR_OTHER", "BYR_LEGACY");
        assertThat(active.getList().getFirst().status()).isEqualTo("SUSPENDED");

        var recent = service.page(new BuyerAdminQueryService.BuyerPageQuery(
                null, null, List.of(), List.of(), List.of(), List.of(),
                LocalDateTime.of(2026, 9, 19, 0, 0), null, 1, 20));
        assertThat(recent.getList()).extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_L1");
        assertThat(recent.getList().getFirst().children().getFirst().children())
                .extracting(BuyerAdminQueryService.BuyerListItem::buyerCode)
                .containsExactly("BYR_L3_A", "BYR_L3_B");
    }

    @Test
    void getFallsBackToCreatedAtWhenCooperationStartedAtIsMissing() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 3, 18, 45);
        BuyerDO buyer = BuyerDO.builder()
                .id(23L)
                .buyerCode("BYR_NO_START")
                .buyerName("未填合作时间")
                .distributionLevel((short) 1)
                .status(BuyerStatus.ACTIVE.getCode())
                .createdAt(createdAt)
                .build();
        when(buyerMapper.selectByBuyerCode("BYR_NO_START")).thenReturn(buyer);
        when(contactMapper.selectByBuyerId(23L)).thenReturn(List.of());
        when(fileRefMapper.selectByBuyerId(23L)).thenReturn(List.of());
        when(integrationModeMapper.selectByBuyerId(23L)).thenReturn(List.of());
        when(cooperationModeMapper.selectByBuyerId(23L)).thenReturn(List.of());
        when(categoryScopeMapper.selectByBuyerId(23L)).thenReturn(List.of());

        assertThat(service.get("BYR_NO_START").cooperationStartedAt()).isEqualTo(createdAt);
    }

    @Test
    void getReturnsSellableCategoriesAndProductTypesFromIndependentScopeRows() {
        BuyerDO buyer = BuyerDO.builder()
                .id(21L)
                .buyerCode("BYR_DETAIL")
                .buyerName("详情分销商")
                .distributionLevel((short) 1)
                .status(BuyerStatus.ACTIVE.getCode())
                .legalPersonMobileCipher("13900139000".getBytes(StandardCharsets.UTF_8))
                .bankAccountCipher("6222021234567890".getBytes(StandardCharsets.UTF_8))
                .bankAccountKeyVersion("v1")
                .build();
        when(buyerMapper.selectByBuyerCode("BYR_DETAIL")).thenReturn(buyer);
        when(contactMapper.selectByBuyerId(21L)).thenReturn(List.of(
                BuyerContactDO.builder().id(2L).buyerId(21L).sortNo(0)
                        .contactName("李四").contactMobile("13700137000").contactEmail("li@example.com").build()));
        when(fileRefMapper.selectByBuyerId(21L)).thenReturn(List.of());
        when(integrationModeMapper.selectByBuyerId(21L)).thenReturn(List.of());
        when(cooperationModeMapper.selectByBuyerId(21L)).thenReturn(List.of());
        when(categoryScopeMapper.selectByBuyerId(21L)).thenReturn(List.of(
                BuyerCategoryScopeDO.builder().buyerId(21L)
                        .scopeKind(BuyerScopeKind.SELLABLE.name()).scopeCode("MOVIE").build(),
                BuyerCategoryScopeDO.builder().buyerId(21L)
                        .scopeKind(BuyerScopeKind.PRODUCT_TYPE.name()).scopeCode("MOVIE").build()));
        when(productTypeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                ProductTypeDO.builder().id(3L).typeCode("MOVIE").typeName("电影票").build()));
        when(envelopeCrypto.decrypt(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var detail = service.get("BYR_DETAIL");

        assertThat(detail.sellableCategories()).containsExactly("MOVIE");
        assertThat(detail.productTypes()).extracting(
                        BuyerAdminQueryService.ProductTypeItem::id,
                        BuyerAdminQueryService.ProductTypeItem::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("MOVIE", "电影票"));
        assertThat(detail.legalPersonMobile()).isEqualTo("13900139000");
        assertThat(detail.bankAccount()).isEqualTo("6222021234567890");
        assertThat(detail.contacts()).extracting(BuyerAdminCommandService.ContactLine::mobile)
                .containsExactly("13700137000");
        assertThat(detail.primaryContactMobile()).isEqualTo("13700137000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void childrenTreatsHistoricalNullDistributionLevelAsLevelOne() {
        BuyerDO legacyRoot = BuyerDO.builder()
                .id(30L)
                .buyerCode("BYR_LEGACY_ROOT")
                .buyerName("历史一级分销商")
                .distributionLevel(null)
                .status(BuyerStatus.ACTIVE.getCode())
                .build();
        BuyerDO child = BuyerDO.builder()
                .id(31L)
                .buyerCode("BYR_CHILD")
                .buyerName("二级分销商")
                .distributionLevel((short) 2)
                .status(BuyerStatus.ACTIVE.getCode())
                .createdAt(LocalDateTime.of(2026, 9, 11, 13, 30))
                .build();
        when(buyerMapper.selectByBuyerCode("BYR_LEGACY_ROOT")).thenReturn(legacyRoot);
        when(buyerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(child), List.of());
        when(cooperationModeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(projectMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var result = service.children("BYR_LEGACY_ROOT");

        assertThat(result).extracting(BuyerAdminQueryService.BuyerChildItem::buyerCode)
                .containsExactly("BYR_CHILD");
        verify(buyerMapper, times(2)).selectList(any(LambdaQueryWrapper.class));
    }

    @SuppressWarnings("unchecked")
    private void stubHierarchy() {
        LocalDateTime day = LocalDateTime.of(2026, 9, 1, 0, 0);
        when(buyerMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                buyer(1L, "BYR_L1", "三江汇海", (short) 1, null, BuyerStatus.SUSPENDED, day.plusDays(19)),
                buyer(2L, "BYR_L2", "华东福利", (short) 2, 1L, BuyerStatus.ACTIVE, day.plusDays(20)),
                buyer(3L, "BYR_L3_A", "孙级甲", (short) 3, 2L, BuyerStatus.ACTIVE, day.plusDays(18)),
                buyer(4L, "BYR_L3_B", "孙级乙", (short) 3, 2L, BuyerStatus.ACTIVE, day.plusDays(17)),
                buyer(5L, "BYR_OTHER", "大润发", (short) 1, null, BuyerStatus.ACTIVE, day),
                buyer(6L, "BYR_LEGACY", "历史一级", null, null, BuyerStatus.ACTIVE, day.minusDays(10)),
                buyer(7L, "BYR_ORPHAN", "孤儿二级", (short) 2, 999L, BuyerStatus.ACTIVE, day.plusDays(21)),
                buyer(8L, "BYR_FLOAT", "游离三级", (short) 3, 998L, BuyerStatus.ACTIVE, day.plusDays(16))));
        when(categoryScopeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                scope(1L, 1L, BuyerScopeKind.SELLABLE, "EQUITY"),
                scope(2L, 1L, BuyerScopeKind.SELLABLE, "JD"),
                scope(3L, 1L, BuyerScopeKind.SELLABLE, "MOVIE"),
                scope(4L, 1L, BuyerScopeKind.SELLABLE, "PHYSICAL"),
                scope(5L, 1L, BuyerScopeKind.PRODUCT_TYPE, "DINING"),
                scope(6L, 1L, BuyerScopeKind.PRODUCT_TYPE, "EQUITY_PRODUCT"),
                scope(7L, 1L, BuyerScopeKind.PRODUCT_TYPE, "MOVIE"),
                scope(8L, 2L, BuyerScopeKind.SELLABLE, "EQUITY"),
                scope(9L, 2L, BuyerScopeKind.SELLABLE, "PHYSICAL"),
                scope(10L, 2L, BuyerScopeKind.PRODUCT_TYPE, "EQUITY_PRODUCT"),
                scope(11L, 2L, BuyerScopeKind.PRODUCT_TYPE, "MOVIE"),
                scope(12L, 3L, BuyerScopeKind.SELLABLE, "MOVIE"),
                scope(13L, 3L, BuyerScopeKind.PRODUCT_TYPE, "FOOD"),
                scope(14L, 4L, BuyerScopeKind.SELLABLE, "CATERING"),
                scope(15L, 4L, BuyerScopeKind.PRODUCT_TYPE, "DINING")));
        when(cooperationModeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                mode(1L, 1L, "AGENT"),
                mode(2L, 1L, "DIRECT"),
                mode(3L, 2L, "AGENT"),
                mode(4L, 2L, "DIRECT"),
                mode(5L, 3L, "CHANNEL"),
                mode(6L, 4L, "DIRECT")));
        when(contactMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                BuyerContactDO.builder().id(1L).buyerId(1L).sortNo(0).contactName("赵四").contactMobile("18300000000").build(),
                BuyerContactDO.builder().id(2L).buyerId(2L).sortNo(0).contactName("李伟").contactMobile("13900000000").build()));
        when(projectMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                project(1L, 1L),
                project(2L, 3L),
                project(3L, 3L),
                project(4L, 4L),
                project(5L, 4L),
                project(6L, 4L),
                project(7L, 5L)));
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                sku(1L, 1L), sku(2L, 1L), sku(3L, 1L), sku(4L, 1L),
                sku(5L, 3L),
                sku(6L, 4L), sku(7L, 4L), sku(8L, 4L), sku(9L, 4L), sku(10L, 4L)));
    }

    private static BuyerAdminQueryService.BuyerPageQuery pageQuery(String buyerName, String buyerCode,
                                                                   List<Integer> distributionLevels,
                                                                   List<String> cooperationModes,
                                                                   List<String> sellableCategories,
                                                                   List<BuyerStatus> statuses,
                                                                   Integer pageNo, Integer pageSize) {
        return new BuyerAdminQueryService.BuyerPageQuery(
                buyerName, buyerCode, distributionLevels,
                cooperationModes == null ? List.of() : cooperationModes,
                sellableCategories == null ? List.of() : sellableCategories,
                statuses == null ? List.of() : statuses,
                null, null, pageNo, pageSize);
    }

    private static BuyerDO buyer(long id, String code, String name, Short level, Long parentId,
                                 BuyerStatus status, LocalDateTime updatedAt) {
        return BuyerDO.builder()
                .id(id)
                .buyerCode(code)
                .buyerName(name)
                .distributionLevel(level)
                .parentBuyerId(parentId)
                .status(status.getCode())
                .updatedAt(updatedAt)
                .build();
    }

    private static BuyerCategoryScopeDO scope(long id, long buyerId, BuyerScopeKind kind, String code) {
        return BuyerCategoryScopeDO.builder()
                .id(id)
                .buyerId(buyerId)
                .scopeKind(kind.name())
                .scopeCode(code)
                .build();
    }

    private static BuyerCooperationModeDO mode(long id, long buyerId, String cooperationMode) {
        return BuyerCooperationModeDO.builder()
                .id(id)
                .buyerId(buyerId)
                .cooperationMode(cooperationMode)
                .build();
    }

    private static BuyerProjectDO project(long id, long buyerId) {
        return BuyerProjectDO.builder().id(id).buyerId(buyerId).build();
    }

    private static BuyerProductDO sku(long id, long buyerId) {
        return BuyerProductDO.builder()
                .id(id)
                .buyerId(buyerId)
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .build();
    }
}
