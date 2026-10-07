package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.nio.charset.StandardCharsets;

/**
 * 华昇主动取券请求值对象。
 *
 * <p>职责：封装取券所需的华昇平台订单号，执行单号非空非空白、无首尾空格与控制字符等守卫。
 *
 * <p>不变量：
 * <ul>
 *   <li>orderSn 非空非空白，无首尾空格与控制字符，UTF-8 字节长度 <= 128；</li>
 *   <li>类级标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class)。</li>
 * </ul>
 *
 * <p>关键决策：不可变值对象；快速失败校验。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#42-端点与封闭请求类型、§16.2。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengTakeRequest implements HuashengRequest {

    private final String orderSn;

    /**
     * 构造取券请求。
     *
     * @param orderSn 华昇平台订单号，非空非空白，<=128 字节
     * @throws IllegalArgumentException 当单号非法时抛出
     */
    public HuashengTakeRequest(String orderSn) {
        if (orderSn == null || orderSn.isBlank()) {
            throw new IllegalArgumentException("orderSn不能为空或全空白");
        }
        if (orderSn.length() != orderSn.strip().length()) {
            throw new IllegalArgumentException("orderSn不能包含首尾空白字符");
        }
        if (orderSn.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("orderSn不能包含控制字符");
        }
        if (orderSn.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw new IllegalArgumentException("orderSn UTF-8字节长度不能超过128");
        }
        this.orderSn = orderSn;
    }

    public String orderSn() {
        return orderSn;
    }

    @Override
    public HuashengEndpoint endpoint() {
        return HuashengEndpoint.TAKE;
    }

    @Override
    public String toString() {
        return "HuashengTakeRequest[orderSn=" + orderSn + "]";
    }
}
