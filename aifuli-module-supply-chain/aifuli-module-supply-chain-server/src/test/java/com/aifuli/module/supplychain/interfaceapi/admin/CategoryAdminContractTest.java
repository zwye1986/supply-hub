package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService.CategoryDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 类目管理端 OpenAPI 契约：必须有 Tag/Operation，保留集合 GET 并仅新增 management-tree。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#36-openapiswagger}。
 */
class CategoryAdminContractTest {

    @Test
    void documentsTreeCollectionAndHasNoInvoiceOrTreePath() {
        assertThat(CategoryAdminController.class.getAnnotation(Tag.class)).isNotNull();
        assertThat(CategoryAdminController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/supply-chain/categories");
        for (Method method : CategoryAdminController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            RequestMapping mapping = method.getAnnotation(RequestMapping.class);
            GetMappingAlias paths = GetMappingAlias.from(method);
            if (paths != null) {
                assertThat(method.getAnnotation(Operation.class)).isNotNull();
                assertThat(paths.paths()).noneMatch(path -> path.contains("invoice") || path.equals("/tree"));
            } else if (mapping != null) {
                assertThat(method.getAnnotation(Operation.class)).isNotNull();
                assertThat(Arrays.asList(mapping.value()))
                        .noneMatch(path -> path.contains("invoice") || path.equals("/tree"));
            }
        }
    }

    /** 分类详情只能返回稳定只读 DTO，且所有 Path 参数及 DTO 字段都有 OpenAPI 语义。 */
    @Test
    void categoryDetailUsesDocumentedReadModel() throws NoSuchMethodException {
        Method detail = CategoryAdminController.class.getMethod("detail", String.class);
        assertThat(detail.getGenericReturnType().getTypeName())
                .contains(CategoryDetail.class.getName())
                .doesNotContain("ProductCategoryDO");

        assertThat(Arrays.stream(CategoryDetail.class.getRecordComponents()).map(component -> component.getName()))
                .containsExactly("categoryCode", "categoryName", "parentId", "categoryLevel", "sort", "visible",
                        "status", "createdAt", "updatedAt");
        Arrays.stream(CategoryDetail.class.getRecordComponents()).forEach(component -> {
            Schema schema = component.getAccessor().getAnnotation(Schema.class);
            assertThat(schema).as("CategoryDetail.%s 缺少 @Schema", component.getName()).isNotNull();
            assertThat(schema.description()).isNotBlank();
        });

        Arrays.stream(CategoryAdminController.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .filter(parameter -> parameter.isAnnotationPresent(PathVariable.class))
                .forEach(parameter -> {
                    Parameter document = parameter.getAnnotation(Parameter.class);
                    assertThat(document).as("分类 Path 参数 %s 缺少 @Parameter", parameter.getName()).isNotNull();
                    assertThat(document.description()).isNotBlank();
                });
    }

    private record GetMappingAlias(String[] paths) {
        static GetMappingAlias from(Method method) {
            org.springframework.web.bind.annotation.GetMapping get =
                    method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
            if (get == null) {
                return null;
            }
            String[] value = get.value().length > 0 ? get.value() : get.path();
            return new GetMappingAlias(value.length == 0 ? new String[]{""} : value);
        }
    }
}
