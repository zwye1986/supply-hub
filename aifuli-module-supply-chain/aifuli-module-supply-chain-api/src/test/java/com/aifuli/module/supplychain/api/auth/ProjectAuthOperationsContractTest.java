package com.aifuli.module.supplychain.api.auth;

import com.aifuli.framework.common.util.http.ApiAccessLogPathUtils;
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
        for (ProjectAuthOperations.Operation operation : instoreOperations()) {
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
        assertThat(instoreOperations()).containsExactly(
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

    @Test
    void registryRegistersThreeExactStandardOrderPaths() {
        for (String suffix : List.of("", "/query", "/assets/query")) {
            String path = "/open-api/supply-chain/project/v1/orders" + suffix;
            assertThat(ProjectAuthOperations.findByMethodAndPath("POST", path)).isPresent();
            assertThat(ProjectAuthOperations.findByMethodAndPath("GET", path)).isEmpty();
            assertThat(ProjectAuthOperations.findByMethodAndPath("POST", path + "/unknown")).isEmpty();
        }
    }

    /**
     * common 层 {@link ApiAccessLogPathUtils} 的标准订单路径常量无法反向依赖供应链模块，
     * 与 {@link ProjectAuthOperations} 的登记路径存在双侧同步义务；本测试以契约锁定两侧一致，
     * 任一侧漂移即失败。设计见
     * docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#4-共享web保护与影响范围。
     */
    @Test
    void standardOrdersPathConstantStaysAlignedWithSharedLogPathUtils() {
        assertThat(ApiAccessLogPathUtils.STANDARD_ORDERS_PATH)
                .isEqualTo(ProjectAuthOperations.STANDARD_ORDERS_PATH);
        for (String suffix : List.of("", "/query", "/assets/query")) {
            String path = ProjectAuthOperations.STANDARD_ORDERS_PATH + suffix;
            assertThat(ApiAccessLogPathUtils.isStandardOrdersPath(path, ""))
                    .as("注册路径必须被日志保护工具识别: %s", path)
                    .isTrue();
            assertThat(ApiAccessLogPathUtils.safeStandardOrderUrl(path, ""))
                    .isEqualTo(path);
        }
        assertThat(ApiAccessLogPathUtils.safeStandardOrderUrl(
                ProjectAuthOperations.STANDARD_ORDERS_PATH + "/unknown", ""))
                .isEqualTo(ProjectAuthOperations.STANDARD_ORDERS_PATH + "/[protected]");
    }

    @Test
    void standardContractsAndRegistryIdentityAreClosedAndImmutable() {
        assertThat(ProjectAuthOperations.OPERATIONS.stream()
                .filter(op -> ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT.equals(op.requiredCapability()))
                .toList()).containsExactly(
                new ProjectAuthOperations.Operation(ProjectAuthOperations.STANDARD_ORDER_CREATE, "POST",
                        ProjectAuthOperations.STANDARD_ORDERS_PATH, ProjectAuthOperations.ScopeType.NONE,
                        "1.0.0", ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT,
                        ProjectAuthOperations.ProjectStatePolicy.ACTIVE_ONLY),
                new ProjectAuthOperations.Operation(ProjectAuthOperations.STANDARD_ORDER_QUERY, "POST",
                        ProjectAuthOperations.STANDARD_ORDERS_PATH + "/query", ProjectAuthOperations.ScopeType.NONE,
                        "1.0.0", ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT,
                        ProjectAuthOperations.ProjectStatePolicy.ACTIVE_OR_SUSPENDED),
                new ProjectAuthOperations.Operation(ProjectAuthOperations.STANDARD_ORDER_ASSET_QUERY, "POST",
                        ProjectAuthOperations.STANDARD_ORDERS_PATH + "/assets/query", ProjectAuthOperations.ScopeType.NONE,
                        "1.0.0", ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT,
                        ProjectAuthOperations.ProjectStatePolicy.ACTIVE_ONLY));
        assertThat(ProjectAuthOperations.OPERATIONS).hasSize(13);
        assertThat(ProjectAuthOperations.OPERATIONS.stream().map(ProjectAuthOperations.Operation::operationCode)
                .distinct()).hasSize(13);
        assertThat(ProjectAuthOperations.OPERATIONS.stream().map(op -> op.httpMethod() + " " + op.path())
                .distinct()).hasSize(13);
        assertThatThrownBy(() -> ProjectAuthOperations.OPERATIONS.clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ProjectAuthOperations.PROTECTED_PREFIXES.clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(instoreOperations()).allSatisfy(op -> assertThat(op.projectStatePolicy())
                .isEqualTo(ProjectAuthOperations.ProjectStatePolicy.ACTIVE_ONLY));
    }

    @Test
    void operationRejectsMissingContractFieldsAndDefaultsOldConstructorsToActiveOnly() {
        assertThat(new ProjectAuthOperations.Operation("CUSTOM", "POST", "/custom",
                ProjectAuthOperations.ScopeType.NONE, "1.0.0", "CUSTOM_CAPABILITY").projectStatePolicy())
                .isEqualTo(ProjectAuthOperations.ProjectStatePolicy.ACTIVE_ONLY);
        assertThat(operation("CUSTOM", "/custom", ProjectAuthOperations.ScopeType.NONE).requiredCapability())
                .isEqualTo("INSTORE");
        for (String invalid : java.util.Arrays.asList(null, "", " ")) {
            assertThatThrownBy(() -> operation(invalid, "/custom", ProjectAuthOperations.ScopeType.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> operation("CUSTOM", invalid, ProjectAuthOperations.ScopeType.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ProjectAuthOperations.Operation("CUSTOM", invalid, "/custom",
                    ProjectAuthOperations.ScopeType.NONE, "1.0.0"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ProjectAuthOperations.Operation("CUSTOM", "POST", "/custom",
                    ProjectAuthOperations.ScopeType.NONE, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ProjectAuthOperations.Operation("CUSTOM", "POST", "/custom",
                    ProjectAuthOperations.ScopeType.NONE, "1.0.0", invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> operation("CUSTOM", "/custom", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProjectAuthOperations.Operation("CUSTOM", "POST", "/custom",
                ProjectAuthOperations.ScopeType.NONE, "1.0.0", "INSTORE", null))
                .isInstanceOf(NullPointerException.class);
    }

    private static List<ProjectAuthOperations.Operation> instoreOperations() {
        return ProjectAuthOperations.OPERATIONS.stream()
                .filter(operation -> "INSTORE".equals(operation.requiredCapability())).toList();
    }

    private static ProjectAuthOperations.Operation operation(
            String operationCode, String path, ProjectAuthOperations.ScopeType scopeType) {
        return new ProjectAuthOperations.Operation(operationCode, "POST", path, scopeType, "1.0.0");
    }
}
