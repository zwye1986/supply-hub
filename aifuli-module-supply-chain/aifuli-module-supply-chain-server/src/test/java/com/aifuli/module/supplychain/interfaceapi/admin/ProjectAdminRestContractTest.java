package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminQueryService.ProjectDetail;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingQueryService.ProjectListingListItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 项目管理端 REST：名词资源、无开票/create/page、详情不返回 app 密钥。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#71-档案}。
 */
class ProjectAdminRestContractTest {

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
                "DISTRIBUTOR", "说明", "MANAGED", 0, 0);
        String json = mapper.writeValueAsString(detail);
        assertThat(json).doesNotContain("appKey", "appId");
        assertThat(json).contains("projectCode").contains("skuCount").contains("projectPriceSkuCount");
        assertThat(json).doesNotContain("invoice").doesNotContain("\"create\"").doesNotContain("\"page\"");
    }

    /** 项目商品列表将 SKU 销售建议价与 B 端项目调价基数分开回显。 */
    @Test
    void productPageHasDistinctSaleSuggestionAndPlatformBaseFields() {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> propertyNames = mapper.getSerializationConfig()
                .introspect(mapper.constructType(ProjectListingListItem.class))
                .findProperties().stream().map(property -> property.getName())
                .collect(Collectors.toSet());

        assertThat(propertyNames).contains("suggestPriceCents", "suggestedSalePriceCents",
                "platformBasePriceCents", "distPriceCents", "finalPriceCents");
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
