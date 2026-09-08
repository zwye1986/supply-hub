package com.aifuli.module.supplychain.integration.saasmall;

import com.aifuli.module.member.api.sso.MemberSsoTokenApi;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * SaaS 商城收银台适配器装配。
 *
 * <p>显式注册名为 {@code buyerCashierRpcAdapter} 的生产 Bean，使旧 stub 条件 Bean 退位。
 * 详细设计见
 * {@code docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#43-supply-chain-server}。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BuyerCashierProperties.class)
public class SaaSCashierAdapterConfiguration {

    @Bean(name = "buyerCashierRpcAdapter")
    @Primary
    public BuyerCashierPort buyerCashierRpcAdapter(
            MemberSsoTokenApi memberSsoTokenApi,
            BuyerCashierProperties properties,
            @Value("${aifuli.supply-chain.o2o.cashier.stub-launch-descriptor:stub://cashier/launch}")
            String legacyStubLaunchDescriptor) {
        return new BuyerCashierRpcAdapter(memberSsoTokenApi, properties, legacyStubLaunchDescriptor);
    }
}
