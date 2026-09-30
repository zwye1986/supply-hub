package com.aifuli.module.supplychain.api.auth;

import com.aifuli.module.supplychain.api.instore.InstoreBuyerCashierCallbackApi;
import com.aifuli.module.supplychain.api.instore.InstoreBuyerEntryApi;
import com.aifuli.module.supplychain.api.instore.InstoreChannelCommandApi;
import com.aifuli.module.supplychain.api.instore.InstoreChannelMemberApi;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册表契约测试：四组 INSTORE API 的全部 @PostMapping 方法必须被
 * {@link ProjectAuthOperations} 精确登记，防止新增端点漏登记造成保护缺口。
 * 详见 docs/superpowers/specs/2026-08-23-project-app-auth-design.md#9-测试门禁。
 */
class ProjectAuthOperationsContractTest {

    @Test
    void registryCoversEveryInstorePostMappingExactlyOnce() {
        Set<String> registeredPaths = new HashSet<>();
        Set<String> registeredOperationCodes = new HashSet<>();
        for (ProjectAuthOperations.Operation operation : ProjectAuthOperations.OPERATIONS) {
            assertThat(registeredOperationCodes.add(operation.operationCode()))
                    .as("注册表 operationCode 必须唯一: %s", operation.operationCode())
                    .isTrue();
            assertThat(registeredPaths.add(operation.path()))
                    .as("注册表路径必须唯一: %s", operation.path())
                    .isTrue();
        }
        Set<String> apiPaths = new HashSet<>();
        for (Class<?> api : List.of(InstoreChannelCommandApi.class, InstoreChannelMemberApi.class,
                InstoreBuyerEntryApi.class, InstoreBuyerCashierCallbackApi.class)) {
            for (Method method : api.getDeclaredMethods()) {
                PostMapping mapping = method.getAnnotation(PostMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = mapping.value()[0];
                assertThat(apiPaths.add(path)).isTrue();
                assertThat(registeredPaths).as("INSTORE 端点未登记: %s", path).contains(path);
            }
        }
        assertThat(registeredPaths).isEqualTo(apiPaths);
        assertThat(apiPaths).hasSize(10);
    }

    @Test
    void registryLocksMethodVersionOperationPathAndScopeForAllTenOperations() {
        assertThat(ProjectAuthOperations.OPERATIONS).containsExactly(
                operation("INSTORE_CREATE_PAYMENT_ORDER",
                        "/rpc-api/supply-chain/instore/channel-command/createPaymentOrder",
                        ProjectAuthOperations.ScopeType.CHANNEL_CODE),
                operation("INSTORE_QUERY_PAYMENT_STATUS",
                        "/rpc-api/supply-chain/instore/channel-command/queryPaymentStatus",
                        ProjectAuthOperations.ScopeType.CHANNEL_CODE),
                operation("INSTORE_CANCEL_PAYMENT_FLOW",
                        "/rpc-api/supply-chain/instore/channel-command/cancelPaymentFlow",
                        ProjectAuthOperations.ScopeType.CHANNEL_CODE),
                operation("INSTORE_ACCEPT_REFUND",
                        "/rpc-api/supply-chain/instore/channel-command/acceptRefund",
                        ProjectAuthOperations.ScopeType.CHANNEL_CODE),
                operation("INSTORE_QUERY_REFUND_STATUS",
                        "/rpc-api/supply-chain/instore/channel-command/queryRefundStatus",
                        ProjectAuthOperations.ScopeType.CHANNEL_CODE),
                operation("INSTORE_RESOLVE_MEMBER",
                        "/rpc-api/supply-chain/instore/channel-member/resolve",
                        ProjectAuthOperations.ScopeType.CHANNEL_CODE),
                operation("INSTORE_HOME_ENTRY",
                        "/rpc-api/supply-chain/instore/buyer-entry/home",
                        ProjectAuthOperations.ScopeType.BUYER_USER_ID),
                operation("INSTORE_ORDER_DETAIL_ENTRY",
                        "/rpc-api/supply-chain/instore/buyer-entry/detail",
                        ProjectAuthOperations.ScopeType.BUYER_USER_ID),
                operation("INSTORE_CONFIRM_PAYMENT_CALLBACK",
                        "/rpc-api/supply-chain/buyer/instore/payment/confirmPayment",
                        ProjectAuthOperations.ScopeType.NONE),
                operation("INSTORE_REPORT_REFUND_RESULT_CALLBACK",
                        "/rpc-api/supply-chain/buyer/instore/payment/reportRefundResult",
                        ProjectAuthOperations.ScopeType.NONE));
    }

    @Test
    void protectedPrefixesCoverAllRegisteredPaths() {
        for (ProjectAuthOperations.Operation operation : ProjectAuthOperations.OPERATIONS) {
            assertThat(ProjectAuthOperations.PROTECTED_PREFIXES.stream().anyMatch(operation.path()::startsWith))
                    .as("注册表路径必须落在受保护前缀内: %s", operation.path())
                    .isTrue();
        }
    }

    @Test
    void findByMethodAndPathIsCaseInsensitiveOnMethod() {
        assertThat(ProjectAuthOperations.findByMethodAndPath("post",
                "/rpc-api/supply-chain/instore/channel-member/resolve")).isPresent();
        assertThat(ProjectAuthOperations.findByMethodAndPath("GET",
                "/rpc-api/supply-chain/instore/channel-member/resolve")).isEmpty();
    }

    @Test
    void findByOperationCodeResolvesEveryRegistrationAndRejectsUnknown() {
        for (ProjectAuthOperations.Operation operation : ProjectAuthOperations.OPERATIONS) {
            assertThat(ProjectAuthOperations.findByOperationCode(operation.operationCode()))
                    .containsSame(operation);
        }

        assertThat(ProjectAuthOperations.findByOperationCode("UNKNOWN_OPERATION")).isEmpty();
        assertThat(ProjectAuthOperations.findByOperationCode(null)).isEmpty();
    }

    @Test
    void operationCodeIndexIsImmutableAndRejectsDuplicates() {
        ProjectAuthOperations.Operation first = operation("DUPLICATE_OPERATION", "/first",
                ProjectAuthOperations.ScopeType.NONE);
        ProjectAuthOperations.Operation duplicate = operation("DUPLICATE_OPERATION", "/second",
                ProjectAuthOperations.ScopeType.CHANNEL_CODE);

        assertThatThrownBy(() -> ProjectAuthOperations.indexByOperationCode(List.of(first, duplicate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operationCode");

        Map<String, ProjectAuthOperations.Operation> index =
                ProjectAuthOperations.indexByOperationCode(List.of(first));
        assertThatThrownBy(() -> index.put("OTHER", duplicate))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ProjectAuthOperations.Operation operation(
            String operationCode, String path, ProjectAuthOperations.ScopeType scopeType) {
        return new ProjectAuthOperations.Operation(operationCode, "POST", path, scopeType, "1.0.0");
    }
}
