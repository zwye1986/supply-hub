package com.aifuli.module.supplychain.framework.rpc.config;

import com.aifuli.module.member.api.profile.MemberProfileResolveApi;
import com.aifuli.module.member.api.sso.MemberSsoTokenApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * 供应链出站 Feign 注册：只注册会员资料解析和 SSO 发令牌两个专用 API，不注册暴露
 * 内部会员模型的通用会员 API。设计见
 * {@code docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#43-supply-chain-server}。
 */
@Configuration(value = "supplyChainRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {MemberProfileResolveApi.class, MemberSsoTokenApi.class})
public class RpcConfiguration {
}
