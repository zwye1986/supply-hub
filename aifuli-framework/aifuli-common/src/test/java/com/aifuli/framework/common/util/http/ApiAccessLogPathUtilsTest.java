package com.aifuli.framework.common.util.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAccessLogPathUtilsTest {

    /** 项目应用认证四组路径都可能携带凭据或业务正文，非生产访问日志必须整体省略参数。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "/rpc-api/supply-chain/instore/channel-command/createPaymentOrder",
            "/rpc-api/supply-chain/instore/channel-member/resolve",
            "/rpc-api/supply-chain/instore/buyer-entry/home",
            "/rpc-api/supply-chain/buyer/instore/payment/confirmPayment"
    })
    void projectAppAuthProtectedPathsAreSensitive(String requestUri) {
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(requestUri)).isTrue();
    }

    /** requestURI 带部署 context path 时，必须先剥离 context path 再匹配受保护前缀。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "/rpc-api/supply-chain/instore/channel-command/createPaymentOrder",
            "/rpc-api/supply-chain/instore/channel-member/resolve",
            "/rpc-api/supply-chain/instore/buyer-entry/home",
            "/rpc-api/supply-chain/buyer/instore/payment/confirmPayment"
    })
    void projectAppAuthProtectedPathsAreSensitiveWithContextPath(String servletPath) {
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(
                "/supply-chain" + servletPath, "/supply-chain")).isTrue();
    }

    /** 只遮蔽四个精确前缀，不把供应链普通 RPC 或相似字符串一并隐藏。 */
    @Test
    void adjacentSupplyChainPathsRemainNonSensitive() {
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(
                "/rpc-api/supply-chain/o2o/channel-command/preparePayment")).isFalse();
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(
                "/rpc-api/supply-chain/instore/channel-commandary/example")).isFalse();
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(
                "/supply-chain/rpc-api/supply-chain/instore/channel-commandary/example",
                "/supply-chain")).isFalse();
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(
                "/edge/supply-chain/rpc-api/supply-chain/instore/channel-command/createPaymentOrder",
                "/supply-chain")).isFalse();
        // Spec-3: orders 相似前缀（orders-other）不得被 isSensitivePlatformPath 误判
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(
                "/open-api/supply-chain/project/v1/orders-other")).isFalse();
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(
                "/open-api/supply-chain/project/v1/ordersfoo")).isFalse();
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(
                "/supply-chain/open-api/supply-chain/project/v1/orders-other",
                "/supply-chain")).isFalse();
        // 标准订单根/子路径仍必须保护
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(
                "/open-api/supply-chain/project/v1/orders")).isTrue();
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(
                "/open-api/supply-chain/project/v1/orders/query")).isTrue();
    }

    /** Spec-1: 安全异常处理判断方法必须与 isStandardOrdersPath / isRechargeFrequencyAdminPath 同口径。 */
    @Test
    void isSensitiveProtectionPathAlignsWithStandardAndFrequency() {
        assertThat(ApiAccessLogPathUtils.isSensitiveProtectionPath(
                "/open-api/supply-chain/project/v1/orders", "")).isTrue();
        assertThat(ApiAccessLogPathUtils.isSensitiveProtectionPath(
                "/open-api/supply-chain/project/v1/orders/query", "")).isTrue();
        assertThat(ApiAccessLogPathUtils.isSensitiveProtectionPath(
                "/supply-chain/recharge-frequency/page", "")).isTrue();
        assertThat(ApiAccessLogPathUtils.isSensitiveProtectionPath(
                "/admin-api/supply-chain/recharge-frequency/page", "")).isTrue();
        // 普通路径不支持安全异常处理
        assertThat(ApiAccessLogPathUtils.isSensitiveProtectionPath(
                "/admin-api/system/user/page", "")).isFalse();
        // Spec-3: orders 相似前缀不支持安全异常处理
        assertThat(ApiAccessLogPathUtils.isSensitiveProtectionPath(
                "/open-api/supply-chain/project/v1/orders-other", "")).isFalse();
        // null 安全
        assertThat(ApiAccessLogPathUtils.isSensitiveProtectionPath(null, "")).isFalse();
    }

    @Test
    void standardOrdersPathsAreIdentifiedAndSanitized() {
        assertThat(ApiAccessLogPathUtils.isStandardOrdersPath("/open-api/supply-chain/project/v1/orders", "")).isTrue();
        assertThat(ApiAccessLogPathUtils.isStandardOrdersPath("/open-api/supply-chain/project/v1/orders/query", "")).isTrue();
        assertThat(ApiAccessLogPathUtils.isStandardOrdersPath("/open-api/supply-chain/project/v1/orders/assets/query", "")).isTrue();
        assertThat(ApiAccessLogPathUtils.isStandardOrdersPath("/open-api/supply-chain/project/v1/orders/unknown", "")).isTrue();
        assertThat(ApiAccessLogPathUtils.isStandardOrdersPath("/supply-chain/open-api/supply-chain/project/v1/orders", "/supply-chain")).isTrue();

        assertThat(ApiAccessLogPathUtils.isStandardOrdersPath("/open-api/supply-chain/project/v1/orders-other", "")).isFalse();
        assertThat(ApiAccessLogPathUtils.isStandardOrdersPath("/open-api/supply-chain/project/v1/other", "")).isFalse();

        assertThat(ApiAccessLogPathUtils.safeStandardOrderUrl("/open-api/supply-chain/project/v1/orders", "")).isEqualTo("/open-api/supply-chain/project/v1/orders");
        assertThat(ApiAccessLogPathUtils.safeStandardOrderUrl("/open-api/supply-chain/project/v1/orders/query", "")).isEqualTo("/open-api/supply-chain/project/v1/orders/query");
        assertThat(ApiAccessLogPathUtils.safeStandardOrderUrl("/open-api/supply-chain/project/v1/orders/assets/query", "")).isEqualTo("/open-api/supply-chain/project/v1/orders/assets/query");
        assertThat(ApiAccessLogPathUtils.safeStandardOrderUrl("/open-api/supply-chain/project/v1/orders/13800138000", "")).isEqualTo("/open-api/supply-chain/project/v1/orders/[protected]");
        assertThat(ApiAccessLogPathUtils.safeStandardOrderUrl("/supply-chain/open-api/supply-chain/project/v1/orders/13800138000", "/supply-chain")).isEqualTo("/open-api/supply-chain/project/v1/orders/[protected]");
    }
    /** 草稿 extraConfig 为 JSON 字符串，整条写入路径必须复用秘密日志保护，不能靠递归 JSON 脱敏。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "/supply-chain/supplier-config/connectors/drafts",
            "/admin-api/supply-chain/supplier-config/connectors/drafts"
    })
    void connectorDraftsProtectExactRootChildrenAndLeadingContext(String root) {
        for (String path : new String[]{root, root + "/", root + "/child"}) {
            assertThat(ApiAccessLogPathUtils.isSupplierCredentialWritePath(path, "")).isTrue();
            assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(path)).isTrue();
            assertThat(ApiAccessLogPathUtils.isSensitiveProtectionPath("/sc" + path, "/sc")).isTrue();
        }
        for (String path : new String[]{root + "-other", root + "foo", "/edge/sc" + root}) {
            assertThat(ApiAccessLogPathUtils.isSupplierCredentialWritePath(path, "/sc")).isFalse();
            assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(path, "/sc")).isFalse();
            assertThat(ApiAccessLogPathUtils.isSensitiveProtectionPath(path, "/sc")).isFalse();
        }
    }

}
