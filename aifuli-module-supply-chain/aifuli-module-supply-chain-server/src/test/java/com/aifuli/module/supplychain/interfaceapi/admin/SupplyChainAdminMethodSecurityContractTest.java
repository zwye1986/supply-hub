package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.biz.system.permission.PermissionCommonApi;
import com.aifuli.framework.security.core.LoginUser;
import com.aifuli.framework.security.core.service.SecurityFrameworkService;
import com.aifuli.framework.security.core.service.SecurityFrameworkServiceImpl;
import com.aifuli.module.supplychain.border.order.application.OrderAdminQueryService;
import com.aifuli.module.supplychain.border.order.application.SupplierOrderAdminQueryService;
import com.aifuli.module.supplychain.interfaceapi.admin.vo.RoutingRuleSaveReqVO;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminCommandService;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.PriorityView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.RoutingRuleView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.WeightView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleRouteMode;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.NormalizedRulePatch;
import com.aifuli.module.supplychain.routing.application.RoutingRuleScopeMode;
import com.aifuli.module.supplychain.routing.application.RoutingRuleStatus;
import com.aifuli.module.supplychain.routing.application.RoutingRuleStrategy;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminQueryService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerInvoiceService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService;
import com.aifuli.module.supplychain.tradecore.supply.application.OfferFulfillmentScoreService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierInvoiceService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileQueryService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierSupplyScopeService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ADMIN;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BUYER_QUERY;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ORDER_QUERY;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_QUERY;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_UPDATE;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_QUERY;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_UPDATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 启用真实 Spring Method Security 的管理端权限契约测试。
 *
 * <p>Controller 注解文本由 {@link SupplyChainAdminPermissionContractTest} 做全量映射检查；本类使用
 * stub {@link PermissionCommonApi} 穿透 {@code @ss} 表达式，防止只校验字符串而没有执行鉴权。
 * Method Security 拦截后抛出 {@link AccessDeniedException}，HTTP 层再映射为 403。
 * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#9-测试门禁}。</p>
 */
@SpringJUnitConfig(SupplyChainAdminMethodSecurityContractTest.Config.class)
class SupplyChainAdminMethodSecurityContractTest {

    private static final AtomicLong USER_SEQUENCE = new AtomicLong(1000L);
    private static final ThreadLocal<Set<String>> GRANTED_PERMISSIONS =
            ThreadLocal.withInitial(Collections::emptySet);

    @Resource
    private SupplierAdminController supplierController;
    @Resource
    private ProductAdminController productController;
    @Resource
    private BuyerAdminController buyerController;
    @Resource
    private PlatformSkuAdminController platformSkuController;
    @Resource
    private OrderAdminController orderController;
    @Resource
    private SupplierOrderAdminController supplierOrderController;
    @Resource
    private RoutingRuleAdminController routingRuleController;

    @BeforeEach
    void setUp() {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(USER_SEQUENCE.incrementAndGet());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        GRANTED_PERMISSIONS.remove();
        SecurityContextHolder.clearContext();
    }

    @Test
    void methodSecurityProxyIsActive() {
        assertThat(AopUtils.isAopProxy(supplierController)).isTrue();
        assertThat(AopUtils.isAopProxy(productController)).isTrue();
        assertThat(AopUtils.isAopProxy(buyerController)).isTrue();
        assertThat(AopUtils.isAopProxy(platformSkuController)).isTrue();
        assertThat(AopUtils.isAopProxy(orderController)).isTrue();
        assertThat(AopUtils.isAopProxy(supplierOrderController)).isTrue();
        assertThat(AopUtils.isAopProxy(routingRuleController)).isTrue();
    }

    @Test
    void supplierQueryPermissionCanInvokeSupplierQuery() {
        grant(SUPPLIER_QUERY);

        assertThatCode(this::querySuppliers).doesNotThrowAnyException();
    }

    @Test
    void legacyAdminCanInvokeFinePermissionEndpoint() {
        grant(ADMIN);

        assertThatCode(this::querySuppliers).doesNotThrowAnyException();
        assertThatCode(this::patchSupplierStatus).doesNotThrowAnyException();
        assertThatCode(this::getBuyer).doesNotThrowAnyException();
    }

    @Test
    void wrongResourcePermissionCannotInvokeSupplierQuery() {
        grant(BUYER_QUERY);

        assertThatThrownBy(this::querySuppliers).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void noPermissionCannotInvokeSupplierQuery() {
        grant();

        assertThatThrownBy(this::querySuppliers).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void supplierQueryCannotPatchSupplierStatus() {
        grant(SUPPLIER_QUERY);

        assertThatThrownBy(this::patchSupplierStatus).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void supplierQueryCannotGetBuyer() {
        grant(SUPPLIER_QUERY);

        assertThatThrownBy(this::getBuyer).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void buyerQueryCanGetBuyer() {
        grant(BUYER_QUERY);

        assertThatCode(this::getBuyer).doesNotThrowAnyException();
    }

    @Test
    void supplierQueryCanInvokeSkuQueryForSupplierResource() {
        grant(SUPPLIER_QUERY);

        assertThatCode(() -> queryPlatformSkus("SPL_1", null)).doesNotThrowAnyException();
    }

    @Test
    void skuQueryRequiresEverySpecifiedResourcePermission() {
        grant(SUPPLIER_QUERY);

        assertThatThrownBy(() -> queryPlatformSkus("SPL_1", "BYR_1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminCanQuerySkuWithMultipleResourceCodes() {
        grant(ADMIN);

        assertThatCode(() -> queryPlatformSkus("SPL_1", "BYR_1")).doesNotThrowAnyException();
    }

    @Test
    void noPermissionCannotQuerySku() {
        grant();

        assertThatThrownBy(() -> queryPlatformSkus(null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void productQueryCanReadSingleSkuPricing() {
        grant(PRODUCT_QUERY);

        assertThatCode(() -> platformSkuController.getPricing("SKU_1")).doesNotThrowAnyException();
    }

    @Test
    void supplierUpdateCanSaveFulfillmentScores() {
        grant(SUPPLIER_UPDATE);

        assertThatCode(this::saveSupplierFulfillmentScores).doesNotThrowAnyException();
    }

    @Test
    void supplierQueryCannotSaveFulfillmentScores() {
        grant(SUPPLIER_QUERY);

        assertThatThrownBy(this::saveSupplierFulfillmentScores).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void productQueryCanReadSkuFulfillmentScores() {
        grant(PRODUCT_QUERY);

        assertThatCode(this::getSkuFulfillmentScores).doesNotThrowAnyException();
    }

    @Test
    void productQueryCannotSaveSkuFulfillmentScores() {
        grant(PRODUCT_QUERY);

        assertThatThrownBy(this::saveSkuFulfillmentScores).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void productUpdateCanSaveSkuFulfillmentScores() {
        grant(PRODUCT_UPDATE);

        assertThatCode(this::saveSkuFulfillmentScores).doesNotThrowAnyException();
    }

    @Test
    void buyerQueryCannotReadSingleSkuPricing() {
        grant(BUYER_QUERY);

        assertThatThrownBy(() -> platformSkuController.getPricing("SKU_1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void orderQueryCanReadOrders() {
        grant(ORDER_QUERY);
        assertThatCode(this::queryOrders).doesNotThrowAnyException();
    }

    @Test
    void legacyAdminCanReadOrders() {
        grant(ADMIN);
        assertThatCode(this::queryOrders).doesNotThrowAnyException();
    }

    @Test
    void wrongPermissionCannotReadOrders() {
        grant(BUYER_QUERY);
        assertThatThrownBy(this::queryOrders).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminCanReadRoutingRules() {
        grant(ADMIN);
        assertThatCode(() -> routingRuleController.page(null, null, null, null, null, null,
                null, null, null, 1, 20)).doesNotThrowAnyException();
        assertThatCode(() -> routingRuleController.get("RR_1")).doesNotThrowAnyException();
    }

    @Test
    void adminCanWriteRoutingRules() {
        grant(ADMIN);
        assertThatCode(() -> routingRuleController.create(fixedCreateRequest()))
                .doesNotThrowAnyException();
        assertThatCode(() -> routingRuleController.update("RR_1", fixedCreateRequest()))
                .doesNotThrowAnyException();
        assertThatCode(() -> routingRuleController.delete("RR_1")).doesNotThrowAnyException();
        RoutingRuleAdminController.ChangeStatusReqVO statusRequest =
                new RoutingRuleAdminController.ChangeStatusReqVO();
        statusRequest.setStatus("DISABLED");
        assertThatCode(() -> routingRuleController.changeStatus("RR_1", statusRequest))
                .doesNotThrowAnyException();
    }

    @Test
    void wrongPermissionCannotReadRoutingRules() {
        grant(BUYER_QUERY);
        assertThatThrownBy(() -> routingRuleController.page(null, null, null, null, null, null,
                null, null, null, 1, 20)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void wrongPermissionCannotWriteRoutingRules() {
        grant(BUYER_QUERY);
        assertThatThrownBy(() -> routingRuleController.create(fixedCreateRequest()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> routingRuleController.update("RR_1", fixedCreateRequest()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> routingRuleController.delete("RR_1"))
                .isInstanceOf(AccessDeniedException.class);
        RoutingRuleAdminController.ChangeStatusReqVO statusRequest =
                new RoutingRuleAdminController.ChangeStatusReqVO();
        statusRequest.setStatus("DISABLED");
        assertThatThrownBy(() -> routingRuleController.changeStatus("RR_1", statusRequest))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static RoutingRuleSaveReqVO fixedCreateRequest() {
        RoutingRuleSaveReqVO request = new RoutingRuleSaveReqVO();
        request.setName("话费固定规则");
        request.setDistributorCodes(List.of("BYR_1"));
        request.setProjectCodes(List.of("PRJ_1"));
        request.setProductTypeCodes(List.of("TELCO"));
        request.setRouteMode("FIXED");
        request.setPrimarySupplierCode("SPL_A");
        request.setSupplierMode("SPECIFIED");
        request.setSupplierCodes(List.of("SPL_A"));
        request.setStatus("ENABLED");
        request.setPriorities(List.of(
                priority("1", "STABLE"), priority("2", "PRICE"), priority("3", "COMPOSITE")));
        RoutingRuleSaveReqVO.WeightReqVO weights = new RoutingRuleSaveReqVO.WeightReqVO();
        weights.setStability(40);
        weights.setPrice(30);
        weights.setTimeliness(30);
        request.setWeights(weights);
        return request;
    }

    private static RoutingRuleSaveReqVO.PriorityItemReqVO priority(String id, String strategy) {
        RoutingRuleSaveReqVO.PriorityItemReqVO item = new RoutingRuleSaveReqVO.PriorityItemReqVO();
        item.setId(id);
        item.setStrategy(strategy);
        return item;
    }

    private static RoutingRuleView sampleRoutingRuleView() {
        return new RoutingRuleView(
                "RR_1", "话费固定规则", false,
                RoutingRuleScopeMode.SPECIFIED, List.of("BYR_1"), List.of("分销商1"),
                RoutingRuleScopeMode.SPECIFIED, List.of("PRJ_1"), List.of("项目1"),
                RoutingRuleScopeMode.SPECIFIED, List.of("TELCO"), List.of("话费"),
                RoutingRuleRouteMode.FIXED, RoutingRuleScopeMode.SPECIFIED,
                List.of("SPL_A"), List.of("供应商A"), "SPL_A", "供应商A",
                List.of(
                        new PriorityView("STABLE", RoutingRuleStrategy.STABLE),
                        new PriorityView("PRICE", RoutingRuleStrategy.PRICE),
                        new PriorityView("COMPOSITE", RoutingRuleStrategy.COMPOSITE)),
                new WeightView(40, 30, 30),
                RoutingRuleStatus.ENABLED, "1001", LocalDateTime.now());
    }

    private void querySuppliers() {
        supplierController.page(null, null, null, null, null,
                null, null, null, null, null);
    }

    private void patchSupplierStatus() {
        SupplierAdminController.PatchSupplierReqVO request = new SupplierAdminController.PatchSupplierReqVO();
        request.setStatus("SUSPENDED");
        supplierController.patch("SPL_1", request);
    }

    private void getBuyer() {
        buyerController.get("BYR_1");
    }

    private void queryPlatformSkus(String supplierCode, String buyerCode) {
        platformSkuController.page(null, null, null, null,
                supplierCode, buyerCode, null, null, null);
    }

    private void queryOrders() {
        orderController.page(null, null, null, null, null, null, null, null,
                null, null, 1, 20);
        orderController.get("SC_1");
        orderController.getRoutingRecords("SC_1");
        supplierOrderController.page(null, null, null, null, null, null, null, 1, 20);
        supplierOrderController.get("BO_1");
    }

    private void saveSupplierFulfillmentScores() {
        SupplierAdminController.FulfillmentScoreSaveReqVO request =
                new SupplierAdminController.FulfillmentScoreSaveReqVO();
        request.setStabilityScore(80);
        request.setTimelinessScore(70);
        supplierController.saveFulfillmentScores("SPL_1", request);
    }

    private void getSkuFulfillmentScores() {
        productController.getSkuFulfillmentScores("SKU_1");
    }

    private void saveSkuFulfillmentScores() {
        ProductAdminController.SkuFulfillmentScoreSaveReqVO request =
                new ProductAdminController.SkuFulfillmentScoreSaveReqVO();
        ProductAdminController.SkuFulfillmentScoreItemReqVO item =
                new ProductAdminController.SkuFulfillmentScoreItemReqVO();
        item.setSupplierCode("SPL_1");
        item.setStabilityScore(80);
        item.setTimelinessScore(70);
        request.setItems(List.of(item));
        productController.saveSkuFulfillmentScores("SKU_1", request);
    }

    private static void grant(String... permissions) {
        GRANTED_PERMISSIONS.set(Set.of(permissions));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {

        @Bean
        PermissionCommonApi permissionCommonApi() {
            PermissionCommonApi api = mock(PermissionCommonApi.class);
            when(api.hasAnyPermissions(anyLong(), any(String[].class))).thenAnswer(invocation -> {
                Set<String> granted = GRANTED_PERMISSIONS.get();
                List<String> requested = new ArrayList<>();
                Object[] args = invocation.getArguments();
                for (int i = 1; i < args.length; i++) {
                    if (args[i] instanceof String[] strings) {
                        requested.addAll(Arrays.asList(strings));
                    } else if (args[i] instanceof String value) {
                        requested.add(value);
                    }
                }
                return success(requested.stream().anyMatch(granted::contains));
            });
            return api;
        }

        @Bean("ss")
        SecurityFrameworkService securityFrameworkService(PermissionCommonApi permissionCommonApi) {
            return new SecurityFrameworkServiceImpl(permissionCommonApi);
        }

        @Bean("supplyChainAdminPermission")
        SupplyChainAdminPermissionService supplyChainAdminPermissionService(
                SecurityFrameworkService securityFrameworkService) {
            return new SupplyChainAdminPermissionService(securityFrameworkService);
        }

        @Bean
        SupplierAdminController supplierAdminController() {
            return new SupplierAdminController(
                    mock(SupplierProfileCommandService.class),
                    mock(SupplierProfileQueryService.class),
                    mock(SupplierSupplyScopeService.class),
                    mock(SupplierCooperativeProductService.class),
                    mock(SupplierInvoiceService.class),
                    mock(OfferFulfillmentScoreService.class));
        }

        @Bean
        ProductAdminController productAdminController() {
            return new ProductAdminController(
                    mock(ProductCatalogQueryService.class),
                    mock(ProductCatalogCommandService.class),
                    mock(OfferFulfillmentScoreService.class));
        }

        @Bean
        BuyerAdminController buyerAdminController() {
            return new BuyerAdminController(
                    mock(BuyerAdminCommandService.class),
                    mock(BuyerAdminQueryService.class),
                    mock(BuyerProductCommandService.class),
                    mock(BuyerProductQueryService.class),
                    mock(com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.class),
                    mock(BuyerInvoiceService.class));
        }

        @Bean
        PlatformSkuAdminController platformSkuAdminController() {
            return new PlatformSkuAdminController(
                    mock(PlatformSkuQueryService.class),
                    mock(com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.class));
        }

        @Bean
        OrderAdminController orderAdminController() {
            return new OrderAdminController(mock(OrderAdminQueryService.class));
        }

        @Bean
        SupplierOrderAdminController supplierOrderAdminController() {
            return new SupplierOrderAdminController(mock(SupplierOrderAdminQueryService.class));
        }

        @Bean
        RoutingRuleAdminCommandService routingRuleAdminCommandService() {
            RoutingRuleAdminCommandService commandService = mock(RoutingRuleAdminCommandService.class);
            RoutingRuleView view = sampleRoutingRuleView();
            when(commandService.create(any(NormalizedRulePatch.class), any())).thenReturn(view);
            when(commandService.update(anyString(), any(NormalizedRulePatch.class), any()))
                    .thenReturn(view);
            when(commandService.changeStatus(anyString(), any(RoutingRuleStatus.class), any()))
                    .thenReturn(view);
            return commandService;
        }

        @Bean
        RoutingRuleAdminController routingRuleAdminController(
                RoutingRuleAdminCommandService routingRuleAdminCommandService) {
            return new RoutingRuleAdminController(
                    routingRuleAdminCommandService,
                    mock(RoutingRuleAdminQueryService.class));
        }
    }
}
