package com.aifuli.module.supplychain.integration.saasmall;

import com.aifuli.module.member.api.sso.MemberSsoTokenApi;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierStubAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 收银台 Bean 装配测试：真实 RPC Adapter 存在时旧 stub 必须退位。
 *
 * <p>详细设计：docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#43-supply-chain-server</p>
 */
class SaaSCashierAdapterConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(MemberSsoTokenApi.class, () -> mock(MemberSsoTokenApi.class))
            .withUserConfiguration(SaaSCashierAdapterConfiguration.class, BuyerCashierStubAdapter.class);

    @Test
    void realRpcAdapterReplacesStubAsOnlyCashierPort() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(BuyerCashierPort.class);
            assertThat(context).hasBean("buyerCashierRpcAdapter");
            assertThat(context.getBean(BuyerCashierPort.class)).isInstanceOf(BuyerCashierRpcAdapter.class);
            assertThat(context).doesNotHaveBean(BuyerCashierStubAdapter.class);
        });
    }
}
