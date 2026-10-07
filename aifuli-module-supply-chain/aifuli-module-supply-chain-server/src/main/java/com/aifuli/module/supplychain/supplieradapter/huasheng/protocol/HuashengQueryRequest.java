package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 华昇订单查询请求值对象。
 *
 * <p>职责：封装订单查询参数，提供 byOrderSn 与 byOutOrderSn 双静态工厂，单号二选一严格互斥。
 *
 * <p>不变量：
 * <ul>
 *   <li>orderSn 与 outOrderSn 恰有一个非空存在；</li>
 *   <li>所持有的单号非空非空白，无首尾空格与控制字符，UTF-8 字节长度 <= 128；</li>
 *   <li>出站序列化严格只输出对应的单号字段；</li>
 *   <li>类级标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class)。</li>
 * </ul>
 *
 * <p>关键决策：私有构造器 + 双静态入口；二选一强类型保证。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#42-端点与封闭请求类型、§16.2。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengQueryRequest implements HuashengRequest {

    private final String orderSn;
    private final String outOrderSn;

    private HuashengQueryRequest(String orderSn, String outOrderSn) {
        this.orderSn = orderSn;
        this.outOrderSn = outOrderSn;
    }

    /**
     * 通过华昇平台单号构造查询请求。
     *
     * @param orderSn 华昇平台订单号，非空非空白，<=128 字节
     * @return 查询请求实例
     * @throws IllegalArgumentException 当单号非法时抛出
     */
    public static HuashengQueryRequest byOrderSn(String orderSn) {
        validateSn("orderSn", orderSn);
        return new HuashengQueryRequest(orderSn, null);
    }

    /**
     * 通过外部商户单号构造查询请求。
     *
     * @param outOrderSn 外部单号，非空非空白，<=128 字节
     * @return 查询请求实例
     * @throws IllegalArgumentException 当单号非法时抛出
     */
    public static HuashengQueryRequest byOutOrderSn(String outOrderSn) {
        validateSn("outOrderSn", outOrderSn);
        return new HuashengQueryRequest(null, outOrderSn);
    }

    public Optional<String> orderSn() {
        return Optional.ofNullable(orderSn);
    }

    public Optional<String> outOrderSn() {
        return Optional.ofNullable(outOrderSn);
    }

    @Override
    public HuashengEndpoint endpoint() {
        return HuashengEndpoint.QUERY;
    }

    @Override
    public String toString() {
        return "HuashengQueryRequest[orderSn=" + (orderSn != null ? orderSn : "empty")
                + ", outOrderSn=" + (outOrderSn != null ? outOrderSn : "empty") + "]";
    }

    private static void validateSn(String name, String value) {
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
}
