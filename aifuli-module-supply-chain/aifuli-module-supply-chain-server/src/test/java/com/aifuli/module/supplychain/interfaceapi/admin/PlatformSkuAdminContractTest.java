package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService.SkuListItem;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.PlatformPricingDetail;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

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
                "productClassification", "suggestPriceCents", "marketPriceCents",
                "categoryName", "supplyPriceCents", "suggestedSalePriceStatus", "suggestedSalePriceUnavailableReason",
                "lastValidSuggestedSalePrice", "lastValidSuggestedSalePriceCalculatedAt");
        properties.forEach((name, property) -> {
            assertThat(property.getDescription())
                    .as("OpenAPI schema SkuListItem.%s 缺少字段说明", name)
                    .isNotBlank();
        });
    }

    /** 单条商品默认定价必须提供稳定路径，并完整公开当前配置、计算快照和版本元数据。 */
    @Test
    @SuppressWarnings("rawtypes")
    void documentsSingleSkuCurrentPricing() throws Exception {
        Method method = PlatformSkuAdminController.class.getDeclaredMethod("getPricing", String.class);
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/{skuCode}/pricing");
        assertThat(method.getAnnotation(Operation.class)).isNotNull();
        assertThat(method.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        assertThat(method.getParameters()[0].getAnnotation(Parameter.class).description()).isNotBlank();

        Map<String, io.swagger.v3.oas.models.media.Schema> schemas =
                ModelConverters.getInstance().readAll(PlatformPricingDetail.class);
        io.swagger.v3.oas.models.media.Schema<?> detailSchema = schemas.get("PlatformPricingDetail");
        assertThat(detailSchema).as("未生成 PlatformPricingDetail OpenAPI schema").isNotNull();
        Map<String, io.swagger.v3.oas.models.media.Schema> properties = detailSchema.getProperties();
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "platformSkuCode", "currentVersionId", "versionNo", "pricingStatus", "unavailableReason",
                "distributionRule", "salesRule", "serviceFeeRule", "hideRule", "taxMetadata",
                "marketPriceCents", "suggestedSalePriceCents", "supplyPriceCents",
                "distributionPriceCents", "salesPriceCents", "distributionMarginPercent",
                "effectiveHideThresholdPercent", "currency", "publishedBy", "publishedAt");
        properties.forEach((name, property) -> {
            if (property.get$ref() == null) {
                assertThat(property.getDescription())
                        .as("OpenAPI schema PlatformPricingDetail.%s 缺少字段说明", name).isNotBlank();
            }
        });
        assertThat(properties.get("distributionRule").get$ref()).endsWith("/FormulaRule");
        assertThat(properties.get("salesRule").get$ref()).endsWith("/FormulaRule");
        assertThat(properties.get("serviceFeeRule").get$ref()).endsWith("/ServiceFeeRule");
        assertThat(properties.get("hideRule").get$ref()).endsWith("/HideRule");
        assertThat(properties.get("taxMetadata").get$ref()).endsWith("/TaxMetadata");
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
