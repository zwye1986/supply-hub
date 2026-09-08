package com.aifuli.module.member.framework.rpc.config;

import com.aifuli.module.system.api.logger.LoginLogApi;
import com.aifuli.module.system.api.sms.SmsCodeApi;
import com.aifuli.module.system.api.social.SocialClientApi;
import com.aifuli.module.system.api.social.SocialUserApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * 会员服务 Feign 客户端注册。只声明 system 侧短信、登录日志与社交账号，不再注册 trade 平台订单/支付。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md#33-member-裁剪d5}。
 */
@Configuration(value = "memberRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {SmsCodeApi.class, LoginLogApi.class, SocialUserApi.class, SocialClientApi.class})
public class RpcConfiguration {
}
