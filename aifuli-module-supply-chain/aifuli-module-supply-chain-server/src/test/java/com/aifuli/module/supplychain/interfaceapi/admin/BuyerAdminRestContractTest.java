package com.aifuli.module.supplychain.interfaceapi.admin;

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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分销商管理端 REST：名词资源、开票为 /{buyerCode}/invoice、无 create/page/get/status/batch 路径、无 projects 子资源。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}
 * 与 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md}。
 */
class BuyerAdminRestContractTest {

    private static final Set<String> FORBIDDEN_FRAGMENTS =
            Set.of("create", "page", "get", "status", "batch", "projects");
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "", "/{buyerCode}", "/{buyerCode}/children",
            "/{buyerCode}/products", "/{buyerCode}/products/{buyerProductCode}",
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
