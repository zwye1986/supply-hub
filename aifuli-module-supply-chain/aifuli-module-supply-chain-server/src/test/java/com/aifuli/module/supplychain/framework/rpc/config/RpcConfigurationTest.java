package com.aifuli.module.supplychain.framework.rpc.config;

import com.aifuli.framework.common.biz.system.dict.DictDataCommonApi;
import com.aifuli.module.member.api.profile.MemberProfileResolveApi;
import com.aifuli.module.member.api.sso.MemberSsoTokenApi;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.EnableFeignClients;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feign 注册合同测试：供应链只显式注册会员资料、SSO令牌与系统税率字典API，禁止依赖包扫描。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#4-税务展示属性}。
 */
class RpcConfigurationTest {

    @Test
    void registersOnlyDedicatedMemberIntegrationApis() {
        EnableFeignClients annotation = RpcConfiguration.class.getAnnotation(EnableFeignClients.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.clients()).containsExactly(
                MemberProfileResolveApi.class, MemberSsoTokenApi.class, DictDataCommonApi.class);
    }
}
