package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.List;
import java.util.Objects;

/**
 * 华昇 TAKE 端点成功响应不可变数据载荷。
 *
 * <p>职责：封装从供应商 TAKE 端点解析得到的卡券交付列表与订单数量事实。
 *
 * <p>不变量：包可见构造，唯一生产所有者为协议解析层；orderSn 与 num 非空；卡片列表防御性不可变复制；
 * 严格不包含任何结算金额或 totalPrice 字段；toString 严格脱敏；类级禁止意外 JSON 序列化。
 *
 * <p>关键决策：普通 final 类；TAKE 端点响应中的额外 totalPrice 属性被完全忽略，绝不合成或升级为结算事实。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengTakeResponse {
    private final String orderSn;
    private final int num;
    private final List<HuashengProtocolCard> list;

    HuashengTakeResponse(String orderSn, int num, List<HuashengProtocolCard> list) {
        this.orderSn = Objects.requireNonNull(orderSn, "orderSn must not be null");
        this.num = num;
        this.list = List.copyOf(Objects.requireNonNull(list, "list must not be null"));
    }

    /**
     * 获取已提卡的供应商订单号。
     *
     * <p>职责：返回与本次提卡卡券关联的华昇订单号。
     * <p>不变量：保证非 null 非空白，且与请求期望单号完全一致。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return 供应商订单号
     */
    public String orderSn() {
        return orderSn;
    }

    /**
     * 获取提卡卡券数量事实。
     *
     * <p>职责：返回供应商声明的提卡总数。
     * <p>不变量：保证大于等于 1，且与期望采购数量及卡片列表长度精确一致。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return 卡券数量
     */
    public int num() {
        return num;
    }

    /**
     * 获取不可变卡券列表。
     *
     * <p>职责：提供已通过协议严格校验的卡券事实集合。
     * <p>不变量：返回不可变列表；保证非 null、非空，列表大小恒等于 {@link #num()}；内部元素均为强类型 {@link HuashengProtocolCard}。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @return 不可变卡券事实列表
     */
    public List<HuashengProtocolCard> list() {
        return list;
    }

    /**
     * 返回严格脱敏的字符串表达，仅输出数量与条数，杜绝泄漏卡密或凭证。
     *
     * @return 安全脱敏文本
     */
    @Override
    public String toString() {
        return "HuashengTakeResponse[num=" + num + ",cardCount=" + list.size() + ",[redacted]]";
    }
}
