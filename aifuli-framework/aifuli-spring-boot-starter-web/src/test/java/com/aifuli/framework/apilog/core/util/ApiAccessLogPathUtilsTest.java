package com.aifuli.framework.apilog.core.util;

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
    }
}
