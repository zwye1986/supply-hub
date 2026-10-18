package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminQueryService.ProjectDetail;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminQueryService.ProjectListItem;
import com.aifuli.module.supplychain.interfaceapi.admin.ProjectAdminController.ProjectProductsPageData;
import com.aifuli.module.supplychain.interfaceapi.admin.ProjectAdminController.ProjectProductsPageResponse;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingQueryService.ProjectListingListItem;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingQueryService.ProjectPricingConfigView;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FormulaRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.HideRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.ServiceFeeRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.springdoc.core.utils.SpringDocAnnotationsUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 项目管理端 REST：名词资源、无开票/create/page、详情不返回 app 密钥。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#71-档案}。
 */
class ProjectAdminRestContractTest {

    /** 项目商品的类型筛选必须由服务端分页接口接收，不能只过滤当前页。 */
    @Test
    void projectProductsAcceptProductTypeFilter() {
        Method method = Arrays.stream(ProjectAdminController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("pageProducts"))
                .findFirst().orElseThrow();
        assertThat(method.getParameters()).anySatisfy(parameter -> {
            assertThat(parameter.getType()).isEqualTo(List.class);
            assertThat(parameter.getAnnotation(RequestParam.class)).isNotNull();
        });
    }

    private static final Set<String> FORBIDDEN_FRAGMENTS =
            Set.of("invoice", "create", "page", "appKey");
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "", "/{projectCode}",
            "/{projectCode}/products", "/{projectCode}/products/{listingCode}",
            "/{projectCode}/products/pricing/preview", "/{projectCode}/products/pricing");

    @Test
    void mappingsAreNounsUnderProjects() {
        assertThat(ProjectAdminController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/supply-chain/projects");
        Tag tag = ProjectAdminController.class.getAnnotation(Tag.class);
        assertThat(tag).isNotNull();
        assertThat(tag.name()).isEqualTo("管理后台 - 供应链项目");
        for (Method method : ProjectAdminController.class.getDeclaredMethods()) {
            if (method.isSynthetic() || !hasHttpMapping(method)) {
                continue;
            }
            assertThat(method.getAnnotation(Operation.class))
                    .as("%s 必须有 @Operation", method.getName())
                    .isNotNull();
            String path = joinedPaths(method);
            for (String fragment : FORBIDDEN_FRAGMENTS) {
                assertThat(path)
                        .as("%s 路径不得含 %s", method.getName(), fragment)
                        .doesNotContain(fragment);
            }
            assertThat(path).doesNotContain("buyers");
            if (!path.isEmpty()) {
                assertThat(ALLOWED_PREFIXES)
                        .as("%s 路径 %s 必须是允许的名词子资源", method.getName(), path)
                        .contains(path);
            }
        }
        boolean hasBuyerProjects = Arrays.stream(ProjectAdminController.class.getDeclaredMethods())
                .anyMatch(method -> joinedPaths(method).contains("/buyers/"));
        assertThat(hasBuyerProjects).isFalse();
    }

    @Test
    void detailJsonDoesNotContainAppSecrets() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ProjectDetail detail = new ProjectDetail(
                1L, "PRJ_1", "福利项目", "ACTIVE", "BYR_A", "一级",
                "EMPLOYEE_BENEFIT", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                "DISTRIBUTOR", "说明", "MANAGED", 0, 0, List.of(), "BYR_A", "一级");
        String json = mapper.writeValueAsString(detail);
        assertThat(json).doesNotContain("appKey", "appId");
        assertThat(json).contains("projectCode").contains("skuCount").contains("projectPriceSkuCount");
        assertThat(json).doesNotContain("invoice").doesNotContain("\"create\"").doesNotContain("\"page\"");
    }

    @Test
    void projectPageRowsExposeSkuAndPricingCoverageCounts() {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> propertyNames = mapper.getSerializationConfig()
                .introspect(mapper.constructType(ProjectListItem.class))
                .findProperties().stream().map(property -> property.getName())
                .collect(Collectors.toSet());
        assertThat(propertyNames).contains("skuCount", "projectPriceSkuCount");
    }

    /** 项目查询权限即可获取关联分销商的类型按钮选项。 */
    @Test
    void projectDetailExposesBuyerProductTypes() {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> propertyNames = mapper.getSerializationConfig()
                .introspect(mapper.constructType(ProjectDetail.class))
                .findProperties().stream().map(property -> property.getName())
                .collect(Collectors.toSet());
        assertThat(propertyNames).contains("productTypes");
    }

    /** 项目商品列表将 SKU 销售建议价与 B 端项目调价基数分开回显。 */
    @Test
    void productPageHasDistinctSaleSuggestionAndPlatformBaseFields() {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> propertyNames = mapper.getSerializationConfig()
                .introspect(mapper.constructType(ProjectListingListItem.class))
                .findProperties().stream().map(property -> property.getName())
                .collect(Collectors.toSet());

        assertThat(propertyNames).contains("spuCode", "spuName", "productType", "productTypeName",
                "productClassification", "categoryName", "marketPriceCents",
                "suggestPriceCents", "suggestedSalePriceCents",
                "platformBasePriceCents", "distPriceCents", "finalPriceCents", "priceSourceName", "projectPricingConfig");
    }

    /** 验证 Knife4j 能从接口响应一路展开到五组配置及子字段，且文档字段与实际 JSON 一致。 */
    @Test
    @SuppressWarnings("rawtypes")
    void productPageDocumentsConcreteResponseAndEveryNestedField() throws Exception {
        Method method = ProjectAdminController.class.getDeclaredMethod("pageProducts",
                String.class, Integer.class, Integer.class, List.class);
        Operation operation = method.getAnnotation(Operation.class);
        assertThat(operation.responses()).hasSize(1);
        assertThat(operation.responses()[0].content()[0].schema().implementation())
                .isEqualTo(ProjectProductsPageResponse.class);

        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(ProjectProductsPageResponse.class);
        Schema<?> response = schemas.get("ProjectProductsPageResponse");
        assertDescriptions(schemas, response, "code", "msg", "data");
        Schema<?> page = referencedSchema(schemas, response.getProperties().get("data"));
        assertDescriptions(schemas, page, "total", "list");
        Schema<?> row = referencedSchema(schemas, page.getProperties().get("list").getItems());
        assertThat(row.getProperties()).containsKeys("listingCode", "platformSkuCode", "skuName",
                "spuCode", "spuName", "productType", "productTypeName",
                "productClassification", "categoryName", "marketPriceCents",
                "suggestPriceCents", "suggestedSalePriceCents", "platformBasePriceCents",
                "distPriceCents", "projectPriceCents", "finalPriceCents", "priceSource", "priceSourceName",
                "projectPriceCovered", "projectPricingConfig");
        assertThat(row.getProperties().get("priceSourceName").getType()).isEqualTo("string");
        assertThat(row.getProperties().get("priceSourceName").getDescription()).contains("项目", "分销商");
        assertThat(row.getProperties().keySet())
                .containsExactlyInAnyOrderElementsOf(jsonProperties(ProjectListingListItem.class));
        row.getProperties().forEach((name, property) -> assertDescriptions(schemas, row, name));

        Schema<?> config = referencedSchema(schemas, row.getProperties().get("projectPricingConfig"));
        assertThat(row.getProperties().get("projectPricingConfig").getType()).isNotEqualTo("null");
        assertThat(config.getDescription()).contains("Inherited=true", "null", "覆盖规则");
        assertThat(Arrays.stream(ProjectListingListItem.class.getRecordComponents())
                .filter(component -> component.getName().equals("projectPricingConfig"))
                .findFirst().orElseThrow().getType()).isEqualTo(ProjectPricingConfigView.class);
        assertDescriptions(schemas, config, "distributionInherited", "distributionRule", "salesInherited", "salesRule",
                "serviceFeeInherited", "serviceFeeRule", "hideInherited", "hideRule",
                "taxInherited", "taxMetadata");
        assertThat(((Schema<?>) config.getProperties().get("distributionInherited")).getDescription())
                .contains("true", "false", "distributionRule");
        assertThat(referencedSchema(schemas, config.getProperties().get("taxMetadata")).getDescription())
                .contains("null", "不参与价格计算");
        assertThat(config.getProperties().keySet())
                .containsExactlyInAnyOrderElementsOf(jsonProperties(ProjectOverrides.class));
        for (String field : List.of("distributionRule", "salesRule", "serviceFeeRule", "hideRule", "taxMetadata")) {
            assertThat(config.getProperties().get(field).getType()).as(field + " OpenAPI type")
                    .isNotEqualTo("null");
            Schema<?> nested = referencedSchema(schemas, config.getProperties().get(field));
            nested.getProperties().forEach((name, property) -> assertDescriptions(schemas, nested, name));
            Class<?> persistedType = switch (field) {
                case "distributionRule", "salesRule" -> FormulaRule.class;
                case "serviceFeeRule" -> ServiceFeeRule.class;
                case "hideRule" -> HideRule.class;
                case "taxMetadata" -> TaxMetadata.class;
                default -> throw new IllegalStateException("unexpected pricing group " + field);
            };
            assertThat(nested.getProperties().keySet())
                    .containsExactlyInAnyOrderElementsOf(jsonProperties(persistedType));
        }
        Schema<?> formula = referencedSchema(schemas, config.getProperties().get("distributionRule"));
        assertThat(formula.getDescription()).contains("percentage", "operator", "operand", "DIRECT_MARKET_PRICE");
        assertThat(((Schema<?>) formula.getProperties().get("base")).getDescription())
                .contains("MARKET_PRICE", "SUGGESTED_SALE_PRICE", "SUPPLY_PRICE");
        assertThat(((Schema<?>) formula.getProperties().get("operand")).getDescription())
                .contains("元", "系数", "DIVIDE");
        Schema<?> serviceFee = referencedSchema(schemas, config.getProperties().get("serviceFeeRule"));
        assertThat(serviceFee.getDescription()).contains("商品小计");
        assertThat(((Schema<?>) serviceFee.getProperties().get("hideCopy")).getDescription())
                .contains("不影响费用金额");
        Schema<?> hide = referencedSchema(schemas, config.getProperties().get("hideRule"));
        assertThat(((Schema<?>) hide.getProperties().get("distributionThresholdPercent")).getDescription())
                .contains("0 到 100");
        Schema<?> tax = referencedSchema(schemas, config.getProperties().get("taxMetadata"));
        assertThat(((Schema<?>) tax.getProperties().get("taxRateCode")).getDescription())
                .contains("supply_chain_tax_rate", "NON_TAXABLE");
        assertThat(schemas).containsKey(ProjectPricingConfigView.class.getSimpleName());
    }

    /** 单份 schema.example 逐层覆盖实际 JSON 属性，兼容 Knife4j 4.5.0 的响应示例渲染。 */
    @Test
    void productPageResponseExampleContainsEveryField() throws Exception {
        Method method = ProjectAdminController.class.getDeclaredMethod("pageProducts",
                String.class, Integer.class, Integer.class, List.class);
        var content = method.getAnnotation(Operation.class).responses()[0].content()[0];
        assertThat(content.mediaType()).isEqualTo("application/json");
        assertThat(content.examples()).isEmpty();
        String example = content.schema().example();
        assertThat(example).isNotBlank();
        var generatedContent = SpringDocAnnotationsUtils.getContent(
                method.getAnnotation(Operation.class).responses()[0].content(),
                new String[]{"application/json"}, new String[]{"application/json"},
                null, new Components(), null, true).orElseThrow();
        assertThat(generatedContent.get("application/json").getExamples()).isNull();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode response = mapper.readTree(example);
        Object generatedExample = generatedContent.get("application/json").getSchema().getExample();
        assertThat(generatedExample).isNotNull();
        JsonNode renderedExample = generatedExample instanceof String json
                ? mapper.readTree(json) : mapper.valueToTree(generatedExample);
        assertThat(renderedExample).isEqualTo(response);
        assertJsonFields(response, ProjectProductsPageResponse.class);
        JsonNode page = response.get("data");
        assertJsonFields(page, ProjectProductsPageData.class);
        assertThat(page.get("total").asLong()).isEqualTo(1);
        assertThat(page.get("list").size()).isEqualTo(1);
        JsonNode row = page.get("list").get(0);
        assertThat(row.path("priceSourceName").asText()).isEqualTo("福利项目");
        assertJsonFields(row, ProjectListingListItem.class);
        JsonNode config = row.get("projectPricingConfig");
        assertJsonFields(config, ProjectPricingConfigView.class);
        assertThat(mapper.treeToValue(response, ProjectProductsPageResponse.class).data().list()).hasSize(1);

        for (String group : List.of("distribution", "sales", "serviceFee", "hide", "tax")) {
            assertThat(config.get(group + "Inherited").asBoolean()).isFalse();
        }
        Map<String, Class<?>> rules = Map.of(
                "distributionRule", FormulaRule.class, "salesRule", FormulaRule.class,
                "serviceFeeRule", ServiceFeeRule.class, "hideRule", HideRule.class,
                "taxMetadata", TaxMetadata.class);
        rules.forEach((field, type) -> assertJsonFields(config.get(field), type));
    }

    private static void assertJsonFields(JsonNode node, Class<?> type) {
        assertThat(node).as("%s example", type.getSimpleName()).isNotNull();
        assertThat(node.isObject()).as("%s example is an object", type.getSimpleName()).isTrue();
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).as("%s example fields", type.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(jsonProperties(type));
    }

    private static Set<String> jsonProperties(Class<?> type) {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.getSerializationConfig().introspect(mapper.constructType(type))
                .findProperties().stream().map(property -> property.getName())
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("rawtypes")
    private static void assertDescriptions(Map<String, Schema> schemas, Schema<?> schema, String... names) {
        assertThat(schema).isNotNull();
        assertThat(schema.getProperties()).containsKeys(names);
        for (String name : names) {
            Schema<?> property = (Schema<?>) schema.getProperties().get(name);
            String description = property.getDescription();
            if ((description == null || description.isBlank()) && property.get$ref() != null) {
                description = referencedSchema(schemas, property).getDescription();
            }
            assertThat(description)
                    .as("%s.%s", schema.getName(), name).isNotBlank();
        }
    }

    @SuppressWarnings("rawtypes")
    private static Schema<?> referencedSchema(Map<String, Schema> schemas, Schema<?> reference) {
        assertThat(reference).isNotNull();
        assertThat(reference.get$ref()).isNotBlank();
        String name = reference.get$ref().substring(reference.get$ref().lastIndexOf('/') + 1);
        Schema<?> schema = schemas.get(name);
        assertThat(schema).as("OpenAPI schema %s", name).isNotNull();
        return schema;
    }

    private static boolean hasHttpMapping(Method method) {
        return method.getAnnotation(RequestMapping.class) != null
                || method.getAnnotation(GetMapping.class) != null
                || method.getAnnotation(PostMapping.class) != null
                || method.getAnnotation(PatchMapping.class) != null
                || method.getAnnotation(PutMapping.class) != null
                || method.getAnnotation(DeleteMapping.class) != null;
    }

    private static String joinedPaths(Method method) {
        GetMapping get = method.getAnnotation(GetMapping.class);
        if (get != null) {
            return String.join(",", get.value());
        }
        PostMapping post = method.getAnnotation(PostMapping.class);
        if (post != null) {
            return String.join(",", post.value());
        }
        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        if (patch != null) {
            return String.join(",", patch.value());
        }
        PutMapping put = method.getAnnotation(PutMapping.class);
        if (put != null) {
            return String.join(",", put.value());
        }
        DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
        if (delete != null) {
            return String.join(",", delete.value());
        }
        return "";
    }
}
