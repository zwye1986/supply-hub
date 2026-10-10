package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogSaveRespVO;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理保存契约：四个POST/PUT保留原URL、HTTP方法与权限表达式，只把响应从数组/布尔改为结构化保存结果；
 * 领域服务签名与旧L2/L3 PATCH不变。设计 §5.1「HTTP 返回」。
 */
class BuyerSelectionCatalogAdminContractTest {

    @Test
    void buyerProductEndpointsKeepUrlMethodAndPermissionWithStructuredResult() throws Exception {
        assertEndpoint(BuyerAdminController.class, "addProducts",
                "/{buyerCode}/products", RequestMethod.POST);
        assertEndpoint(BuyerAdminController.class, "updateProductPricing",
                "/{buyerCode}/products/pricing", RequestMethod.PUT);
    }

    @Test
    void projectProductEndpointsKeepUrlMethodAndPermissionWithStructuredResult() throws Exception {
        assertEndpoint(ProjectAdminController.class, "addProducts",
                "/{projectCode}/products", RequestMethod.POST);
        assertEndpoint(ProjectAdminController.class, "updateProductPricing",
                "/{projectCode}/products/pricing", RequestMethod.PUT);
    }

    @Test
    void legacyPatchEndpointsAreUntouched() throws Exception {
        Method patchL2 = BuyerAdminController.class.getMethod("patchProduct",
                String.class, String.class, BuyerAdminController.PatchBuyerProductReqVO.class);
        assertThat(patchL2.getAnnotation(PutMapping.class)).isNull();
        assertThat(patchL2.getAnnotation(PostMapping.class)).isNull();
        assertThat(patchL2.getGenericReturnType().getTypeName())
                .contains("CommonResult<java.lang.Boolean>");
        Method patchL3 = ProjectAdminController.class.getMethod("patchProduct",
                String.class, String.class, ProjectAdminController.PatchProjectListingReqVO.class);
        assertThat(patchL3.getAnnotation(PutMapping.class)).isNull();
        assertThat(patchL3.getAnnotation(PostMapping.class)).isNull();
    }

    @Test
    void saveResultCarriesSaveStateCodesAndCatalogResults() {
        BuyerSelectionCatalogSaveRespVO response = new BuyerSelectionCatalogSaveRespVO();
        response.setSaveState("COMMITTED");
        response.setAutomaticRecoveryEnabled(true);
        BuyerSelectionCatalogSaveRespVO.CatalogRefreshResultVO item =
                new BuyerSelectionCatalogSaveRespVO.CatalogRefreshResultVO();
        item.setRefreshOutcome("UPDATED");
        item.setReasonCode("1050001176");
        item.setTraceId("trace-1");
        assertThat(response.getSaveState()).isEqualTo("COMMITTED");
        assertThat(response.isAutomaticRecoveryEnabled()).isTrue();
        assertThat(item.getTraceId()).isEqualTo("trace-1");
    }

    private static void assertEndpoint(Class<?> controller, String methodName, String pathSuffix,
                                       RequestMethod method) throws Exception {
        Method handler = findMethod(controller, methodName);
        String classPath = controller.getAnnotation(RequestMapping.class).value()[0];
        if (method == RequestMethod.POST) {
            assertThat(classPath + handler.getAnnotation(PostMapping.class).value()[0])
                    .isEqualTo(classPath + pathSuffix);
        } else {
            assertThat(classPath + handler.getAnnotation(PutMapping.class).value()[0])
                    .isEqualTo(classPath + pathSuffix);
        }
        assertThat(handler.isAnnotationPresent(PreAuthorize.class))
                .as(methodName + " 必须保留权限表达式").isTrue();
        assertThat(handler.getParameters()[0].isAnnotationPresent(PathVariable.class))
                .as(methodName + " 身份仍用路径变量").isTrue();
        assertThat(handler.getGenericReturnType().getTypeName())
                .as(methodName + " 响应改为结构化保存结果")
                .contains("BuyerSelectionCatalogSaveRespVO");
    }

    private static Method findMethod(Class<?> controller, String methodName) {
        for (Method method : controller.getMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new AssertionError("missing handler " + methodName);
    }
}
