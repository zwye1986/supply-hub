package com.aifuli.module.supplychain.interfaceapi.admin;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ADMIN_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BUYER_CREATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BUYER_DELETE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BUYER_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BUYER_UPDATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BUYER_UPDATE_STATUS_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PLATFORM_SKU_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_CREATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_DELETE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_UPDATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_UPDATE_STATUS_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.READ_OPTIONS_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_CREATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_DELETE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_UPDATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_UPDATE_STATUS_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_CREATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_UPDATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_DELETE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_UPDATE_STATUS_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.CATEGORY_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.CATEGORY_CREATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.CATEGORY_UPDATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.CATEGORY_DELETE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.CATEGORY_UPDATE_STATUS_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BRAND_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BRAND_CREATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BRAND_UPDATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BRAND_DELETE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BRAND_UPDATE_STATUS_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_TYPE_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_TYPE_CREATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_TYPE_UPDATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_TYPE_DELETE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_TYPE_UPDATE_STATUS_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ORDER_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ROUTING_RULE_CREATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ROUTING_RULE_DELETE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ROUTING_RULE_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ROUTING_RULE_UPDATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ROUTING_RULE_UPDATE_STATUS_EXPRESSION;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端 HTTP 方法与细权限映射契约。
 * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#5-方法与权限映射}。
 */
class SupplyChainAdminPermissionContractTest {

    @Test
    void supplierMethodsUseTheirDeclaredActions() {
        assertMethodPermissions(SupplierAdminController.class, Map.ofEntries(
                Map.entry("page", SUPPLIER_QUERY_EXPRESSION),
                Map.entry("get", SUPPLIER_QUERY_EXPRESSION),
                Map.entry("create", SUPPLIER_CREATE_EXPRESSION),
                Map.entry("update", SUPPLIER_UPDATE_EXPRESSION),
                Map.entry("patch", SUPPLIER_UPDATE_STATUS_EXPRESSION),
                Map.entry("patchBatch", SUPPLIER_UPDATE_STATUS_EXPRESSION),
                Map.entry("changeStatus", SUPPLIER_UPDATE_STATUS_EXPRESSION),
                Map.entry("batchStatus", SUPPLIER_UPDATE_STATUS_EXPRESSION),
                Map.entry("listScopes", SUPPLIER_QUERY_EXPRESSION),
                Map.entry("saveScopes", SUPPLIER_UPDATE_EXPRESSION),
                Map.entry("listOffers", SUPPLIER_QUERY_EXPRESSION),
                Map.entry("createOffers", SUPPLIER_UPDATE_EXPRESSION),
                Map.entry("updateOffer", SUPPLIER_UPDATE_EXPRESSION),
                Map.entry("patchOffer", SUPPLIER_UPDATE_EXPRESSION),
                // B1 上架即发布（设计 §6.6）：业务侧改写 Offer 发布态，与 createOffers/updateOffer 同用 SUPPLIER_UPDATE。
                Map.entry("listOfferOnShelf", SUPPLIER_UPDATE_EXPRESSION),
                Map.entry("deleteOffer", SUPPLIER_DELETE_EXPRESSION),
                Map.entry("createDrafts", SUPPLIER_UPDATE_EXPRESSION),
                Map.entry("retire", SUPPLIER_DELETE_EXPRESSION),
                Map.entry("getInvoice", SUPPLIER_QUERY_EXPRESSION),
                Map.entry("saveInvoice", SUPPLIER_UPDATE_EXPRESSION),
                Map.entry("saveFulfillmentScores", SUPPLIER_UPDATE_EXPRESSION)));
    }

    @Test
    void buyerMethodsUseTheirDeclaredActions() {
        assertMethodPermissions(BuyerAdminController.class, Map.ofEntries(
                Map.entry("page", BUYER_QUERY_EXPRESSION),
                Map.entry("get", BUYER_QUERY_EXPRESSION),
                Map.entry("create", BUYER_CREATE_EXPRESSION),
                Map.entry("update", BUYER_UPDATE_EXPRESSION),
                Map.entry("patch", BUYER_UPDATE_STATUS_EXPRESSION),
                Map.entry("patchBatch", BUYER_UPDATE_STATUS_EXPRESSION),
                Map.entry("children", BUYER_QUERY_EXPRESSION),
                Map.entry("pageProducts", BUYER_QUERY_EXPRESSION),
                Map.entry("addProducts", BUYER_UPDATE_EXPRESSION),
                Map.entry("patchProduct", BUYER_UPDATE_EXPRESSION),
                Map.entry("previewProductPricing", BUYER_QUERY_EXPRESSION),
                Map.entry("updateProductPricing", BUYER_UPDATE_EXPRESSION),
                Map.entry("retireProduct", BUYER_DELETE_EXPRESSION),
                Map.entry("getInvoice", BUYER_QUERY_EXPRESSION),
                Map.entry("saveInvoice", BUYER_UPDATE_EXPRESSION)));
    }

    @Test
    void projectMethodsUseTheirDeclaredActions() {
        assertMethodPermissions(ProjectAdminController.class, Map.ofEntries(
                Map.entry("page", PROJECT_QUERY_EXPRESSION),
                Map.entry("get", PROJECT_QUERY_EXPRESSION),
                Map.entry("create", PROJECT_CREATE_EXPRESSION),
                Map.entry("update", PROJECT_UPDATE_EXPRESSION),
                Map.entry("patch", PROJECT_UPDATE_STATUS_EXPRESSION),
                Map.entry("pageProducts", PROJECT_QUERY_EXPRESSION),
                Map.entry("addProducts", PROJECT_UPDATE_EXPRESSION),
                Map.entry("patchProduct", PROJECT_UPDATE_EXPRESSION),
                Map.entry("previewProductPricing", PROJECT_QUERY_EXPRESSION),
                Map.entry("updateProductPricing", PROJECT_UPDATE_EXPRESSION),
                Map.entry("retireProduct", PROJECT_DELETE_EXPRESSION)));
    }

    @Test
    void sharedReadEndpointsUseCompositeRules() {
        assertMethodPermissions(CategoryAdminController.class, Map.ofEntries(
                Map.entry("tree", READ_OPTIONS_EXPRESSION),
                Map.entry("managementTree", CATEGORY_QUERY_EXPRESSION),
                Map.entry("detail", CATEGORY_QUERY_EXPRESSION),
                Map.entry("create", CATEGORY_CREATE_EXPRESSION),
                Map.entry("update", CATEGORY_UPDATE_EXPRESSION),
                Map.entry("status", CATEGORY_UPDATE_STATUS_EXPRESSION),
                Map.entry("delete", CATEGORY_DELETE_EXPRESSION)));
        assertMethodPermissions(PlatformSkuAdminController.class, Map.of(
                "page", PLATFORM_SKU_QUERY_EXPRESSION,
                "getPricing", PRODUCT_QUERY_EXPRESSION,
                "previewPricing", PLATFORM_SKU_QUERY_EXPRESSION,
                "savePricing", PRODUCT_UPDATE_EXPRESSION));
    }

    @Test
    void productCenterMethodsUseFineGrainedPermissionsWithAdminFallback() {
        assertMethodPermissions(ProductAdminController.class, Map.ofEntries(
                Map.entry("page", PRODUCT_QUERY_EXPRESSION),
                Map.entry("managementPage", PRODUCT_QUERY_EXPRESSION),
                Map.entry("detail", PRODUCT_QUERY_EXPRESSION),
                Map.entry("create", PRODUCT_CREATE_EXPRESSION),
                Map.entry("update", PRODUCT_UPDATE_EXPRESSION),
                Map.entry("changeSpuStatus", PRODUCT_UPDATE_STATUS_EXPRESSION),
                Map.entry("batchChangeStatus", PRODUCT_UPDATE_STATUS_EXPRESSION),
                Map.entry("batchUpdateSkuTaxMetadata", PRODUCT_UPDATE_EXPRESSION),
                Map.entry("changeSkuStatus", PRODUCT_UPDATE_STATUS_EXPRESSION),
                Map.entry("deleteSpu", PRODUCT_DELETE_EXPRESSION),
                Map.entry("deleteSku", PRODUCT_DELETE_EXPRESSION),
                Map.entry("getSkuFulfillmentScores", PRODUCT_QUERY_EXPRESSION),
                Map.entry("saveSkuFulfillmentScores", PRODUCT_UPDATE_EXPRESSION)));
        assertMethodPermissions(BrandAdminController.class, Map.ofEntries(
                Map.entry("page", BRAND_QUERY_EXPRESSION), Map.entry("detail", BRAND_QUERY_EXPRESSION),
                Map.entry("create", BRAND_CREATE_EXPRESSION), Map.entry("update", BRAND_UPDATE_EXPRESSION),
                Map.entry("status", BRAND_UPDATE_STATUS_EXPRESSION), Map.entry("delete", BRAND_DELETE_EXPRESSION)));
        assertMethodPermissions(ProductTypeAdminController.class, Map.ofEntries(
                Map.entry("page", PRODUCT_TYPE_QUERY_EXPRESSION), Map.entry("detail", PRODUCT_TYPE_QUERY_EXPRESSION),
                Map.entry("create", PRODUCT_TYPE_CREATE_EXPRESSION), Map.entry("update", PRODUCT_TYPE_UPDATE_EXPRESSION),
                Map.entry("status", PRODUCT_TYPE_UPDATE_STATUS_EXPRESSION), Map.entry("delete", PRODUCT_TYPE_DELETE_EXPRESSION)));
    }

    @Test
    void orderReadMethodsUseOrderQueryPermissionWithAdminFallback() {
        assertMethodPermissions(OrderAdminController.class, Map.of(
                "page", ORDER_QUERY_EXPRESSION,
                "get", ORDER_QUERY_EXPRESSION,
                "getRoutingRecords", ORDER_QUERY_EXPRESSION));
        assertMethodPermissions(SupplierOrderAdminController.class, Map.of(
                "page", ORDER_QUERY_EXPRESSION,
                "get", ORDER_QUERY_EXPRESSION));
        assertThat(ORDER_QUERY_EXPRESSION).contains("supply-chain:admin", "supply-chain:order:query");
    }

    @Test
    void unseparatedControllersRemainAdminOnly() {
        assertThat(BuyerAccountAdminController.class.getAnnotation(PreAuthorize.class).value())
                .isEqualTo(ADMIN_EXPRESSION);
        assertThat(RoutingPolicyAdminController.class.getAnnotation(PreAuthorize.class).value())
                .isEqualTo(ADMIN_EXPRESSION);
        assertThat(SupplierConfigurationAdminController.class.getAnnotation(PreAuthorize.class).value())
                .isEqualTo(ADMIN_EXPRESSION);
        assertThat(M1MasterDataAdminController.class.getAnnotation(PreAuthorize.class).value())
                .isEqualTo(ADMIN_EXPRESSION);
    }

    /**
     * 路由规则控制器按设计改为方法级细权限（PRD 四级范围模型，设计 §6）。
     */
    @Test
    void routingRuleMethodsUseTheirDeclaredActions() {
        assertMethodPermissions(RoutingRuleAdminController.class, Map.of(
                "page", ROUTING_RULE_QUERY_EXPRESSION,
                "get", ROUTING_RULE_QUERY_EXPRESSION,
                "create", ROUTING_RULE_CREATE_EXPRESSION,
                "update", ROUTING_RULE_UPDATE_EXPRESSION,
                "delete", ROUTING_RULE_DELETE_EXPRESSION,
                "changeStatus", ROUTING_RULE_UPDATE_STATUS_EXPRESSION));
    }

    private static void assertMethodPermissions(Class<?> controller, Map<String, String> expected) {
        assertThat(controller.getAnnotation(PreAuthorize.class)).isNull();
        for (Method method : controller.getDeclaredMethods()) {
            if (!isRequestMapping(method)) {
                continue;
            }
            assertThat(expected).as("%s 中存在未登记的 HTTP 方法 %s", controller.getSimpleName(), method.getName())
                    .containsKey(method.getName());
            assertThat(method.getAnnotation(PreAuthorize.class))
                    .as("%s#%s 必须显式声明权限", controller.getSimpleName(), method.getName())
                    .isNotNull()
                    .extracting(PreAuthorize::value)
                    .isEqualTo(expected.get(method.getName()));
        }
        assertThat(expected.keySet()).allSatisfy(name -> assertThat(controller.getDeclaredMethods())
                .anySatisfy(method -> assertThat(method.getName()).isEqualTo(name)));
    }

    private static boolean isRequestMapping(Method method) {
        return method.getDeclaredAnnotationsByType(RequestMapping.class).length > 0
                || method.getDeclaredAnnotations().length > 0
                && java.util.Arrays.stream(method.getDeclaredAnnotations())
                .anyMatch(annotation -> annotation.annotationType().isAnnotationPresent(RequestMapping.class));
    }
}
