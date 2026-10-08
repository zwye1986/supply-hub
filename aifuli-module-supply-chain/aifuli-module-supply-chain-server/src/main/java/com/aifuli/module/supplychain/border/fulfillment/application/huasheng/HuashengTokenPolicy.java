package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

/**
 * 华昇 Token 策略常量与安全校验规则。
 *
 * <p>职责：
 * 冻结应用层可接受的本地 Token 有效期上限、提前刷新窗口计算与 Token 字符串合法性校验规则。
 *
 * <p>不变量：
 * <ul>
 *   <li>{@link #MAX_ACCEPTED_TTL_SECONDS} 恒为 7200 秒（华昇官方声明为 7200 秒，本地策略拒绝大于 7200 秒的返回）；</li>
 *   <li>{@link #MAX_TOKEN_LENGTH} 恒为 8192 字符；</li>
 *   <li>禁止包含 CR、LF、NUL 等控制字符；禁止自动 trim 篡改 Token 原文；</li>
 *   <li>TTL 小于等于 0 拒绝为格式非法；TTL 大于 7200（如 7201）在应用层拒绝为本地策略异常 {@code TOKEN_TTL_UNACCEPTABLE}。</li>
 * </ul>
 *
 * <p>关键决策：
 * refreshAhead 采用向下取整：{@code Math.min(300L, ttlSeconds / 10L)}；当 TTL 在 1~9 秒时，提前刷新秒数为 0，
 * 避免因硬编码 300 秒导致短 TTL Token 永不可用而触发死循环。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#182-复合身份材料指纹与缓存条目ad-a5-02--ad-a5-04、§8.2。
 */
public final class HuashengTokenPolicy {

    /** 本地应用层可接受的最大 TTL 秒数（7200 秒）。 */
    public static final long MAX_ACCEPTED_TTL_SECONDS = 7200L;

    /** Token 字符串最大允许字符长度。 */
    public static final int MAX_TOKEN_LENGTH = 8192;

    /** 本地策略拒绝大于 7200 秒时的固定错误文本。 */
    public static final String ERROR_TOKEN_TTL_UNACCEPTABLE = "TOKEN_TTL_UNACCEPTABLE";

    private HuashengTokenPolicy() {
    }

    /**
     * 计算提前刷新时间窗口（秒数）。
     *
     * @param ttlSeconds Token 的有效秒数
     * @return 提前刷新的秒数：{@code min(300, ttlSeconds / 10)}
     */
    public static long calculateRefreshAheadSeconds(long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return 0L;
        }
        return Math.min(300L, ttlSeconds / 10L);
    }

    /**
     * 基础 TTL 格式校验：必须大于 0。
     *
     * @param ttlSeconds 有效期秒数
     * @throws IllegalArgumentException 当 TTL 小于等于 0 时抛出
     */
    public static void validateTtl(long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("Token TTL 必须为正整数: " + ttlSeconds);
        }
    }

    /**
     * 判定 TTL 是否在本地策略可接受上限内（<= 7200）。
     *
     * @param ttlSeconds 有效期秒数
     * @return 若在 1..7200 范围内返回 true，否则返回 false
     */
    public static boolean isTtlAcceptable(long ttlSeconds) {
        return ttlSeconds > 0 && ttlSeconds <= MAX_ACCEPTED_TTL_SECONDS;
    }

    /**
     * 严格校验 Access Token 字符串格式。
     *
     * @param token 访问令牌原文
     * @throws IllegalArgumentException 当 token 为空、超限或包含非法字符时抛出
     */
    public static void validateAccessToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Token不能为空");
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Token长度超限: " + token.length() + " > " + MAX_TOKEN_LENGTH);
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                throw new IllegalArgumentException("Token包含CR/LF/NUL非法控制字符");
            }
        }
    }
}
