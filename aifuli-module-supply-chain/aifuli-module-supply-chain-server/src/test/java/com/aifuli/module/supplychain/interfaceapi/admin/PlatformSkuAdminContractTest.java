package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService.SkuListItem;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标品 SKU 管理端 OpenAPI 契约：必须有 Tag/Operation，且无 /invoice、无 /page。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#36-openapiswagger}。
 */
class PlatformSkuAdminContractTest {

    @Test
    void documentsSkuCollectionAndHasNoInvoiceOrPagePath() {
        assertThat(PlatformSkuAdminController.class.getAnnotation(Tag.class)).isNotNull();
        assertThat(PlatformSkuAdminController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/supply-chain/products/skus");
        for (Method method : PlatformSkuAdminController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            RequestMapping mapping = method.getAnnotation(RequestMapping.class);
            GetMappingAlias paths = GetMappingAlias.from(method);
            if (paths != null) {
                assertThat(method.getAnnotation(Operation.class)).isNotNull();
                assertThat(paths.paths()).noneMatch(path -> path.contains("invoice") || path.contains("page"));
            } else if (mapping != null) {
                assertThat(method.getAnnotation(Operation.class)).isNotNull();
                assertThat(Arrays.asList(mapping.value()))
                        .noneMatch(path -> path.contains("invoice") || path.contains("page"));
            }
        }
    }

    /** 标品池筛选参数与列表字段必须具备可读的 OpenAPI 描述。 */
    @Test
    @SuppressWarnings("rawtypes")
    void documentsFiltersAndListItemFields() {
        Arrays.stream(PlatformSkuAdminController.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .filter(parameter -> parameter.isAnnotationPresent(RequestParam.class))
                .forEach(parameter -> {
                    Parameter document = parameter.getAnnotation(Parameter.class);
                    assertThat(document).as("标品池 Query 参数 %s 缺少 @Parameter", parameter.getName()).isNotNull();
                    assertThat(document.description()).isNotBlank();
                });

        Map<String, io.swagger.v3.oas.models.media.Schema> schemas =
                ModelConverters.getInstance().readAll(SkuListItem.class);
        io.swagger.v3.oas.models.media.Schema<?> skuSchema = schemas.get("SkuListItem");
        assertThat(skuSchema).as("未生成 SkuListItem OpenAPI schema").isNotNull();
        Map<String, io.swagger.v3.oas.models.media.Schema> properties = skuSchema.getProperties();
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "id", "skuCode", "skuName", "spuCode", "spuName", "sellableCategory", "productType",
                "productClassification", "suggestPriceCents", "marketPriceCents");
        properties.forEach((name, property) -> {
            assertThat(property.getDescription())
                    .as("OpenAPI schema SkuListItem.%s 缺少字段说明", name)
                    .isNotBlank();
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
