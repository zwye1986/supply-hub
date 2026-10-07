package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * 华昇预下单请求值对象。
 *
 * <p>职责：封装预下单请求所需业务参数，执行采购数量恒为 1、有效时间范围、回调地址与手机号等前置守卫。
 *
 * <p>不变量：
 * <ul>
 *   <li>outOrderSn 非空非空白，无首尾空格与控制字符，UTF-8 字节长度 <= 128；</li>
 *   <li>couponId 严格为正整数 long (> 0)；</li>
 *   <li>num 必须严格恒为 1，非 1 抛出 {@link IllegalArgumentException}；</li>
 *   <li>expireTime 缺省规范化为 300，显式传值必须在 [60, 1800] 之间，超限抛出 {@link IllegalArgumentException}，严禁静默钳制；</li>
 *   <li>notifyUrl 必须为绝对 HTTP/HTTPS 地址、无 userinfo/fragment、非私网/本地字面量、UTF-8 字节长度 <= 2048；</li>
 *   <li>outMobile 可选字符串，存在时非空白且 UTF-8 字节长度 <= 64，无控制字符，{@link #toString()} 与日志严格脱敏；</li>
 *   <li>类级标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class)。</li>
 * </ul>
 *
 * <p>关键决策：不可变值对象；快速失败严禁静默截断或钳制。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#42-端点与封闭请求类型、§16.2。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengPlaceRequest implements HuashengRequest {

    private final String outOrderSn;
    private final long couponId;
    private final int num;
    private final int expireTime;
    private final String notifyUrl;
    private final String outMobile;

    /**
     * 6 参全量构造器。
     *
     * @param outOrderSn 本地业务外部单号，非空非空白，<=128 字节
     * @param couponId 华昇卡券规格 ID，> 0
     * @param num 采购数量，严格恒为 1
     * @param expireTime 订单过期时间秒数，严格在 [60, 1800]
     * @param notifyUrl 异步通知回调地址，绝对 HTTP/HTTPS 非私网，<=2048 字节
     * @param outMobile 充值手机号，可选，<=64 字节
     * @throws IllegalArgumentException 当任何入参违反安全约束时抛出
     */
    public HuashengPlaceRequest(String outOrderSn, long couponId, int num, int expireTime, String notifyUrl, String outMobile) {
        validateOrderSn("outOrderSn", outOrderSn);
        if (couponId <= 0L) {
            throw new IllegalArgumentException("couponId必须为正整数: " + couponId);
        }
        if (num != 1) {
            throw new IllegalArgumentException("num必须严格等于1: " + num);
        }
        if (expireTime < 60 || expireTime > 1800) {
            throw new IllegalArgumentException("expireTime必须在[60, 1800]范围内，严禁钳制: " + expireTime);
        }
        validateNotifyUrl(notifyUrl);
        if (outMobile != null) {
            if (outMobile.isBlank()) {
                throw new IllegalArgumentException("outMobile不能全空白");
            }
            if (outMobile.getBytes(StandardCharsets.UTF_8).length > 64) {
                throw new IllegalArgumentException("outMobile UTF-8字节长度不能超过64");
            }
            if (outMobile.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("outMobile不能包含控制字符");
            }
        }
        this.outOrderSn = outOrderSn;
        this.couponId = couponId;
        this.num = num;
        this.expireTime = expireTime;
        this.notifyUrl = notifyUrl;
        this.outMobile = outMobile;
    }

    /**
     * 5 参构造器（缺省手机号）。
     *
     * @param outOrderSn 外部单号
     * @param couponId 卡券 ID
     * @param num 采购数量，恒为 1
     * @param expireTime 过期时间秒数
     * @param notifyUrl 回调地址
     */
    public HuashengPlaceRequest(String outOrderSn, long couponId, int num, int expireTime, String notifyUrl) {
        this(outOrderSn, couponId, num, expireTime, notifyUrl, null);
    }

    /**
     * 4 参构造器（缺省过期时间 300 秒与手机号）。
     *
     * @param outOrderSn 外部单号
     * @param couponId 卡券 ID
     * @param num 采购数量，恒为 1
     * @param notifyUrl 回调地址
     */
    public HuashengPlaceRequest(String outOrderSn, long couponId, int num, String notifyUrl) {
        this(outOrderSn, couponId, num, 300, notifyUrl, null);
    }

    public String outOrderSn() {
        return outOrderSn;
    }

    public long couponId() {
        return couponId;
    }

    public int num() {
        return num;
    }

    public int expireTime() {
        return expireTime;
    }

    public String notifyUrl() {
        return notifyUrl;
    }

    public Optional<String> outMobile() {
        return Optional.ofNullable(outMobile);
    }

    @Override
    public HuashengEndpoint endpoint() {
        return HuashengEndpoint.PLACE;
    }

    @Override
    public String toString() {
        return "HuashengPlaceRequest[outOrderSn=" + outOrderSn
                + ", couponId=" + couponId
                + ", num=" + num
                + ", expireTime=" + expireTime
                + ", notifyUrl=" + notifyUrl
                + ", outMobile=" + (outMobile != null ? "[REDACTED]" : "empty") + "]";
    }

    private static void validateOrderSn(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空或全空白");
        }
        if (value.length() != value.strip().length()) {
            throw new IllegalArgumentException(name + "不能包含首尾空白字符");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + "不能包含控制字符");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw new IllegalArgumentException(name + " UTF-8字节长度不能超过128");
        }
    }

    private static void validateNotifyUrl(String notifyUrl) {
        if (notifyUrl == null || notifyUrl.isBlank()) {
            throw new IllegalArgumentException("notifyUrl不能为空或全空白");
        }
        if (notifyUrl.getBytes(StandardCharsets.UTF_8).length > 2048) {
            throw new IllegalArgumentException("notifyUrl UTF-8字节长度不能超过2048");
        }
        URI uri;
        try {
            uri = URI.create(notifyUrl);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("notifyUrl不是合法URI", e);
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalArgumentException("notifyUrl必须为绝对URL");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("notifyUrl必须使用HTTP或HTTPS协议");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("notifyUrl不能包含userinfo");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("notifyUrl不能包含fragment");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (isLocalOrPrivateLiteral(host)) {
            throw new IllegalArgumentException("notifyUrl不能为本地或私网地址: " + host);
        }
    }

    private static boolean isLocalOrPrivateLiteral(String host) {
        if (host.equals("localhost") || host.equals("0.0.0.0") || host.equals("::1") || host.equals("169.254.169.254")) {
            return true;
        }
        if (host.contains(":")) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 10 || first == 127 || first == 0 || first >= 224
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
