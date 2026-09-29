package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.interfaceapi.admin.BuyerAdminController.BuyerPageResponse;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminQueryService.BuyerChildItem;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminQueryService.BuyerDetail;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminQueryService.BuyerListItem;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminQueryService.ProductTypeItem;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductQueryService.BuyerProductListItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分销商管理端 REST：名词资源、开票为 /{buyerCode}/invoice、无 create/page/get/status/batch 路径、无 projects 子资源。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}
 * 与 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md}。
 */
class BuyerAdminRestContractTest {

    /** 项目选品搜索框与类目下拉均由服务端分页接口接收。 */
    @Test
    void buyerProductPageAcceptsKeywordAndCategoryCode() {
        Method method = Arrays.stream(BuyerAdminController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("pageProducts"))
                .findFirst().orElseThrow();
        assertThat(Arrays.stream(method.getParameters()).map(parameter -> parameter.getName()).toList())
                .contains("keyword", "categoryCode");
    }

    private static final Set<String> FORBIDDEN_FRAGMENTS =
            Set.of("create", "page", "get", "status", "batch", "projects");
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "", "/{buyerCode}", "/{buyerCode}/children",
            "/{buyerCode}/products", "/{buyerCode}/products/{buyerProductCode}",
            "/{buyerCode}/products/pricing/preview", "/{buyerCode}/products/pricing",
            "/{buyerCode}/invoice");

    @Test
    void mappingsAreNounsUnderBuyers() {
        assertThat(BuyerAdminController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/supply-chain/buyers");
        Tag tag = BuyerAdminController.class.getAnnotation(Tag.class);
        assertThat(tag).isNotNull();
        assertThat(tag.name()).isEqualTo("管理后台 - 供应链分销商");
        boolean invoiceGet = false;
        boolean invoicePut = false;
        for (Method method : BuyerAdminController.class.getDeclaredMethods()) {
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
            if (!path.isEmpty()) {
                boolean allowed = ALLOWED_PREFIXES.contains(path);
                assertThat(allowed)
                        .as("%s 路径 %s 必须是允许的名词子资源", method.getName(), path)
                        .isTrue();
            }
            if ("/{buyerCode}/invoice".equals(path)) {
                if (method.getAnnotation(GetMapping.class) != null) {
                    invoiceGet = true;
                }
                if (method.getAnnotation(PutMapping.class) != null) {
                    invoicePut = true;
                }
            }
        }
        assertThat(invoiceGet).isTrue();
        assertThat(invoicePut).isTrue();
    }

    @Test
    void listJsonExposesFrontendAggregationFields() {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> propertyNames = mapper.getSerializationConfig()
                .introspect(mapper.constructType(BuyerListItem.class))
                .findProperties().stream()
                .map(property -> property.getName())
                .collect(Collectors.toSet());

        assertThat(propertyNames).contains(
                "sellableCategories", "productTypes", "projectCount", "skuCount", "cooperationTime", "children");
    }

    /** 项目选品新增销售建议价与 B 端基价时，旧 L1 字段仍留在 JSON 契约中。 */
    @Test
    void productPageKeepsLegacyPriceAndExposesSeparateSaleAndPlatformBasePrices() {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> propertyNames = mapper.getSerializationConfig()
                .introspect(mapper.constructType(BuyerProductListItem.class))
                .findProperties().stream().map(property -> property.getName())
                .collect(Collectors.toSet());

        assertThat(propertyNames).contains("suggestPriceCents", "suggestedSalePriceCents",
                "platformBasePriceCents", "distPriceCents");
    }

    @Test
    void descendantsJsonExposesDistributionHierarchyFields() {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> propertyNames = mapper.getSerializationConfig()
                .introspect(mapper.constructType(BuyerChildItem.class))
                .findProperties().stream()
                .map(property -> property.getName())
                .collect(Collectors.toSet());

        assertThat(propertyNames).containsExactlyInAnyOrder(
                "buyerCode", "buyerName", "distributionLevel", "cooperationModes",
                "projectCount", "status", "createdAt");
    }

    @Test
    void detailJsonUsesOriginalSensitiveFieldNames() {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> propertyNames = mapper.getSerializationConfig()
                .introspect(mapper.constructType(BuyerDetail.class))
                .findProperties().stream()
                .map(property -> property.getName())
                .collect(Collectors.toSet());

        assertThat(propertyNames).contains(
                "legalPersonMobile", "bankAccount", "contacts", "primaryContactMobile");
        assertThat(propertyNames).doesNotContain("legalPersonMobileMasked", "bankAccountMasked");
    }

    @Test
    void detailProductTypesExposeIdAndName() {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> propertyNames = mapper.getSerializationConfig()
                .introspect(mapper.constructType(ProductTypeItem.class))
                .findProperties().stream()
                .map(property -> property.getName())
                .collect(Collectors.toSet());

        assertThat(propertyNames).containsExactlyInAnyOrder("id", "name");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void pageDocumentsConcreteBuyerListResponseForSwagger() {
        Method pageMethod = Arrays.stream(BuyerAdminController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("page"))
                .findFirst()
                .orElseThrow();
        Operation operation = pageMethod.getAnnotation(Operation.class);
        assertThat(operation.responses()).hasSize(1);
        assertThat(operation.responses()[0].content()).hasSize(1);
        assertThat(operation.responses()[0].content()[0].schema().implementation())
                .isEqualTo(BuyerPageResponse.class);

        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(BuyerPageResponse.class);
        Schema<?> responseSchema = schemas.get("BuyerPageResponse");
        assertThat(responseSchema).isNotNull();
        Schema<?> pageSchema = referencedSchema(schemas, responseSchema.getProperties().get("data"));
        Schema<?> itemSchema = referencedSchema(schemas, pageSchema.getProperties().get("list").getItems());

        assertThat(itemSchema.getProperties()).containsKeys(
                "productTypes", "projectCount", "skuCount", "cooperationTime", "children");
        assertThat(itemSchema.getProperties().get("productTypes").getDescription()).isEqualTo("本级及可见下级商品类型并集");
        assertThat(itemSchema.getProperties().get("projectCount").getDescription()).isEqualTo("本级及可见下级未归档项目数合计");
        assertThat(itemSchema.getProperties().get("skuCount").getDescription()).isEqualTo("本级及可见下级有效分销商品数合计");
        assertThat(itemSchema.getProperties().get("children").getDescription())
                .isEqualTo("直属下级。一级包含二级，二级包含三级，三级为空列表");
        assertThat(itemSchema.getProperties().get("cooperationTime").getDescription())
                .isEqualTo("合作时间，取档案合作开始时间；未填或年份不晚于1970时回落创建时间，Asia/Shanghai");
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
