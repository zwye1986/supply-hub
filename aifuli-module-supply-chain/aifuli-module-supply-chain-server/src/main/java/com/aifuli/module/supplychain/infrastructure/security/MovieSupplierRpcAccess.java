package com.aifuli.module.supplychain.infrastructure.security;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 电影配置的独立服务认证；无配置时失败关闭，不接受普通用户登录替代。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#数据库账号与渠道选择落地2026-09-29
 */
@Component("movieSupplierRpcAccess")
@RequiredArgsConstructor
public class MovieSupplierRpcAccess {
    /** 动态配置只用于独立令牌和受控传输开关。 */
    private final Environment environment;

    /** 恒定时间比较令牌；默认同时要求安全传输，避免明文响应泄露供应商密钥。 */
    public boolean allowed(String suppliedToken) {
        String expected = environment.getProperty("movie.supply.config-token", "");
        if (expected.isBlank() || expected.length() < 32 || expected.length() > 512
                || suppliedToken == null || suppliedToken.length() > 512) {
            return false;
        }
        if (environment.getProperty("movie.supply.config-require-https", Boolean.class, true)) {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
                    || !attributes.getRequest().isSecure()) {
                return false;
            }
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8));
    }
}
