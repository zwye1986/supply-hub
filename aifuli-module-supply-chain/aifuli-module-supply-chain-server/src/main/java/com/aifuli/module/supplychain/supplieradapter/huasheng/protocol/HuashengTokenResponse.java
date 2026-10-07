package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.Objects;

/**
 * 华昇 TOKEN 端点成功响应不可变数据载荷。
 *
 * <p>职责：封装从供应商 TOKEN 端点解析得到的凭据信息（包含访问令牌 accessToken 与有效期秒数 expiresInSeconds）。
 *
 * <p>不变量：包可见构造，唯一生产所有者为协议解析层；accessToken 非空、<=8192 字符、无 CRLF/NUL、不 trim；
 * expiresInSeconds 为正整数；toString 严格脱敏，不输出访问凭证；类级禁止意外 JSON 序列化。
 *
 * <p>关键决策：普通 final 类；本地 7200 秒 TTL 上限校验留由应用 TokenPolicy 执行，7201 在解析器层合法通过。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#82-ttl-与并发、§15.1.2。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengTokenResponse {
    private final String accessToken;
    private final int expiresInSeconds;

    HuashengTokenResponse(String accessToken, int expiresInSeconds) {
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken must not be null");
        this.expiresInSeconds = expiresInSeconds;
    }

    /**
     * 获取供应商访问令牌凭证。
     *
     * <p>职责：返回向华昇平台发起业务调用所需的 Bearer/Token 字符串。
     * <p>不变量：保证非 null、非空白、UTF-8 字符数 <= 8192、无 CRLF/NUL 控制字符、未被 trim 篡改。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#8-tokenmanager缓存与有界-61、§15.1.2。
     *
     * @return 访问令牌原文
     */
    public String accessToken() {
        return accessToken;
    }

    /**
     * 获取访问令牌有效时长（秒）。
     *
     * <p>职责：返回供应商声明的 token 存活秒数。
     * <p>不变量：保证为大于 0 的整数；7201 边界在解析器层合法通过（本地 7200 上限由后续策略层管控）。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#82-ttl-与并发、§15.1.2。
     *
     * @return 有效期秒数
     */
    public int expiresInSeconds() {
        return expiresInSeconds;
    }

    /**
     * 返回脱敏文本，令牌原文以 [PROTECTED] 遮盖，防止日志泄漏。
     *
     * @return 安全脱敏文本
     */
    @Override
    public String toString() {
        return "HuashengTokenResponse[expiresInSeconds=" + expiresInSeconds + ",token=[PROTECTED]]";
    }
}
