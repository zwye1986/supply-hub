package com.aifuli.module.supplychain.framework.rpc.config;

import com.aifuli.module.member.api.profile.MemberProfileResolveApi;
import com.aifuli.module.member.api.sso.MemberSsoTokenApi;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.EnableFeignClients;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feign 注册合同测试：供应链只能注册会员资料解析和 SSO 发令牌专用 API，
 * 防止回退为暴露内部会员模型的通用会员 API。设计见
 * {@code docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#33-supply-chain-server}。
 */
class RpcConfigurationTest {

    @Test
    void registersOnlyDedicatedMemberIntegrationApis() {
        EnableFeignClients annotation = RpcConfiguration.class.getAnnotation(EnableFeignClients.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.clients()).containsExactly(MemberProfileResolveApi.class, MemberSsoTokenApi.class);
    }
}
