package com.aifuli.module.supplychain.framework.rpc.config;

import com.aifuli.framework.common.biz.system.dict.DictDataCommonApi;
import com.aifuli.module.member.api.profile.MemberProfileResolveApi;
import com.aifuli.module.member.api.sso.MemberSsoTokenApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * 供应链出站 Feign 显式注册。税率字典只通过 system RPC 校验，不允许跨库读取 system 表。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#4-税务展示属性}。
 */
@Configuration(value = "supplyChainRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {MemberProfileResolveApi.class, MemberSsoTokenApi.class, DictDataCommonApi.class})
public class RpcConfiguration {
}
