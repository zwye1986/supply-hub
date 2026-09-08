package com.aifuli.module.supplychain.interfaceapi.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 类目管理端 OpenAPI 契约：必须有 Tag/Operation，且无 /invoice、无 /tree。
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
                assertThat(paths.paths()).noneMatch(path -> path.contains("invoice") || path.contains("tree"));
            } else if (mapping != null) {
                assertThat(method.getAnnotation(Operation.class)).isNotNull();
                assertThat(Arrays.asList(mapping.value()))
                        .noneMatch(path -> path.contains("invoice") || path.contains("tree"));
            }
        }
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
