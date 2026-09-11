package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品中心管理端 OpenAPI 契约与接口治理门禁。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#36-openapiswagger}。
 */
class ProductCenterContractTest {

    @Test
    void productAdminControllerDeclaresTagAndOperations() {
        assertThat(ProductAdminController.class.getAnnotation(Tag.class)).isNotNull();
        assertThat(ProductAdminController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/supply-chain/products");

        for (Method method : ProductAdminController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            if (isHttpEndpoint(method)) {
                assertThat(method.getAnnotation(Operation.class))
                        .as("ProductAdminController#%s 缺少 @Operation", method.getName())
                        .isNotNull();
                assertThat(method.getAnnotation(PreAuthorize.class))
                        .as("ProductAdminController#%s 缺少 @PreAuthorize", method.getName())
                        .isNotNull();
            }
        }
    }

    @Test
    void brandAdminControllerDeclaresTagAndOperations() {
        assertThat(BrandAdminController.class.getAnnotation(Tag.class)).isNotNull();
        assertThat(BrandAdminController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/supply-chain/brands");

        for (Method method : BrandAdminController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            if (isHttpEndpoint(method)) {
                assertThat(method.getAnnotation(Operation.class))
                        .as("BrandAdminController#%s 缺少 @Operation", method.getName())
                        .isNotNull();
                assertThat(method.getAnnotation(PreAuthorize.class))
                        .as("BrandAdminController#%s 缺少 @PreAuthorize", method.getName())
                        .isNotNull();
            }
        }
    }

    @Test
    void productTypeAdminControllerDeclaresTagAndOperations() {
        assertThat(ProductTypeAdminController.class.getAnnotation(Tag.class)).isNotNull();
        assertThat(ProductTypeAdminController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/supply-chain/product-types");

        for (Method method : ProductTypeAdminController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            if (isHttpEndpoint(method)) {
                assertThat(method.getAnnotation(Operation.class))
                        .as("ProductTypeAdminController#%s 缺少 @Operation", method.getName())
                        .isNotNull();
                assertThat(method.getAnnotation(PreAuthorize.class))
                        .as("ProductTypeAdminController#%s 缺少 @PreAuthorize", method.getName())
                        .isNotNull();
            }
        }
    }

    @Test
    void legacyMasterDataCatalogEndpointsAreDeprecated() throws NoSuchMethodException {
        assertThat(M1MasterDataAdminController.class.getMethod("createBrand", M1MasterDataAdminController.CreateBrandReqVO.class)
                .isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(M1MasterDataAdminController.class.getMethod("createCategory", M1MasterDataAdminController.CreateCategoryReqVO.class)
                .isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(M1MasterDataAdminController.class.getMethod("createSpu", M1MasterDataAdminController.CreateSpuReqVO.class)
                .isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(M1MasterDataAdminController.class.getMethod("createSku", M1MasterDataAdminController.CreateSkuReqVO.class)
                .isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(M1MasterDataAdminController.class.getMethod("changeCatalogEntityStatus", M1MasterDataAdminController.ChangeCatalogEntityStatusReqVO.class)
                .isAnnotationPresent(Deprecated.class)).isTrue();
    }

    /**
     * 商品删除必须自带事务边界：先前实现靠自调用 {@code changeSpuStatus} 触发事务，会被 Spring 代理绕过，
     * 导致行锁、4 表引用校验与归档写入分处不同事务。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#32-删除强校验规则4-表物理主键与关联路径检查}。
     */
    @Test
    void productDeletionEntryPointsDeclareOwnTransaction() throws NoSuchMethodException {
        assertThat(ProductCatalogCommandService.class
                .getMethod("deleteSpu", String.class, Long.class)
                .getAnnotation(Transactional.class))
                .as("deleteSpu 必须自带 @Transactional，不能依赖自调用触发事务")
                .isNotNull();
        assertThat(ProductCatalogCommandService.class
                .getMethod("deleteSku", String.class, Long.class)
                .getAnnotation(Transactional.class))
                .as("deleteSku 必须自带 @Transactional，不能依赖自调用触发事务")
                .isNotNull();
    }

    /** 四类实体的状态端点共用同一请求体，且只接受启用/停用，归档必须走 DELETE 以触发引用强校验。 */
    @Test
    void statusEndpointsShareSwitchOnlyRequestBody() {
        CatalogStatusSwitchReqVO request = new CatalogStatusSwitchReqVO();
        request.setTargetStatus(CatalogEntityStatus.ACTIVE.getCode());
        assertThat(request.requireSwitchTarget()).isEqualTo(CatalogEntityStatus.ACTIVE);

        request.setTargetStatus(CatalogEntityStatus.ARCHIVED.getCode());
        assertThatThrownBy(request::requireSwitchTarget)
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_STATUS_INVALID.getCode());

        request.setTargetStatus((short) 7);
        assertThatThrownBy(request::requireSwitchTarget)
                .as("未知状态码也必须映射为业务错误码，不能抛裸 IllegalArgumentException")
                .isInstanceOf(ServiceException.class);
    }

    private static boolean isHttpEndpoint(Method method) {
        return method.isAnnotationPresent(RequestMapping.class)
                || Arrays.stream(method.getDeclaredAnnotations())
                .anyMatch(ann -> ann.annotationType().isAnnotationPresent(RequestMapping.class));
    }
}
