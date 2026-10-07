package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * 华昇订单解析期望上下文（前移至 A2 实现，作为订单与提卡解析的必需校验输入，A3 直接复用）。
 *
 * <p>职责：提供本地业务侧发起的订单单号、预期的供应商远端单号（若有）以及预期采购数量，用于与供应商响应严格比对。
 *
 * <p>不变量：public 构造；outOrderSn 非空非空白且 UTF-8 长度 <= 128 字节；orderSn 出现时同样非空非空白且 <= 128 字节；
 * quantity >= 1；违规抛出固定说明安全 IllegalArgumentException，不回显非法输入；toString 脱敏；类级禁止意外 JSON 序列化。
 *
 * <p>关键决策：普通 final 类；由 A3 后续直接复用，不得在 A3 重复定义。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.1.1。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengExpectedOrder {
    private final String outOrderSn;
    private final Optional<String> orderSn;
    private final int quantity;

    /**
     * 构造订单响应校验的期望上下文值对象。
     *
     * <p>职责：封装调用方发起的订单标识与预期采购数量，用于在解析订单和提卡响应时进行防篡改与一致性防御。
     *
     * <p>不变量：outOrderSn 必须非空非空白且 UTF-8 字节数 <= 128；orderSn 容器非空且若存在则其值非空白且 UTF-8 字节数 <= 128；
     * quantity 必须 >= 1。任何不变量违规抛出固定文本安全 IllegalArgumentException，不回显非法输入；类级禁止意外 JSON 序列化。
     *
     * <p>关键决策：输入前移至 A2 协议层，作为 parseOrder 与 parseTake 的强制参数，禁止在解析层放行未经验证的异单或数量漂移。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.1.1。
     *
     * @param outOrderSn 本地业务外部单号，非空非空白，<=128 字节
     * @param orderSn 预期的供应商订单号容器，非空容器；若已建单则包含供应商订单号
     * @param quantity 预期采购数量，>= 1
     * @throws IllegalArgumentException 当参数为空、超长或数量小于 1 时抛出
     */
    public HuashengExpectedOrder(String outOrderSn, Optional<String> orderSn, int quantity) {
        if (outOrderSn == null || outOrderSn.isBlank()) {
            throw new IllegalArgumentException("outOrderSn不能为空或全空白");
        }
        if (outOrderSn.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw new IllegalArgumentException("outOrderSn UTF-8字节长度不能超过128");
        }
        Objects.requireNonNull(orderSn, "orderSn容器不能为null");
        if (orderSn.isPresent()) {
            String remote = orderSn.get();
            if (remote.isBlank()) {
                throw new IllegalArgumentException("orderSn出现时不能全空白");
            }
            if (remote.getBytes(StandardCharsets.UTF_8).length > 128) {
                throw new IllegalArgumentException("orderSn UTF-8字节长度不能超过128");
            }
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity必须大于等于1");
        }
        this.outOrderSn = outOrderSn;
        this.orderSn = orderSn;
        this.quantity = quantity;
    }

    /**
     * 获取商户外部单号。
     *
     * <p>职责：返回本地发起的外部唯一订单号。
     * <p>不变量：保证非空非空白且 UTF-8 字节数 <= 128。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.1.1。
     *
     * @return 外部单号
     */
    public String outOrderSn() {
        return outOrderSn;
    }

    /**
     * 获取预期的供应商远端订单号（若已知）。
     *
     * <p>职责：提供已存在的供应商单号期望，用于 PLACE 重放、CONFIRM 或 QUERY 时比对。
     * <p>不变量：保证容器非 null；若存在则非空且 UTF-8 字节数 <= 128。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.1.1。
     *
     * @return 包含供应商订单号的 Optional，或空 Optional
     */
    public Optional<String> orderSn() {
        return orderSn;
    }

    /**
     * 获取期望采购数量。
     *
     * <p>职责：提供预期的商品购买件数，用于比对响应中的 num 及卡券条数。
     * <p>不变量：保证大于等于 1。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.1.1。
     *
     * @return 预期采购数量
     */
    public int quantity() {
        return quantity;
    }

    /**
     * 返回安全脱敏字符串表示，仅显示数量，单号信息完全隐藏。
     *
     * @return 安全脱敏文本
     */
    @Override
    public String toString() {
        return "HuashengExpectedOrder[quantity=" + quantity + ",[redacted]]";
    }
}
