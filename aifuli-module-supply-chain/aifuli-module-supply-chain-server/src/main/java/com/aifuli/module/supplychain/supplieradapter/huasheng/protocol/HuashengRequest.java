package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

/**
 * 华昇通信协议请求顶级密封接口。
 *
 * <p>职责：定义华昇五端点请求模型的统一类型契约，封闭实现子类，杜绝非受控请求类型扩展。
 *
 * <p>不变量：密封接口仅允许五种受控实现：
 * <ul>
 *   <li>{@link HuashengTokenRequest}：鉴权 Token 请求；</li>
 *   <li>{@link HuashengPlaceRequest}：预下单请求；</li>
 *   <li>{@link HuashengConfirmRequest}：确认下单请求；</li>
 *   <li>{@link HuashengQueryRequest}：订单查询请求；</li>
 *   <li>{@link HuashengTakeRequest}：主动取券请求。</li>
 * </ul>
 * 任何实现均绑定唯一的 {@link HuashengEndpoint}，不可伪造或跨端点混用。
 *
 * <p>关键决策：顶层密封接口；所有具体实现均为不可变类，拒绝意外 Jackson 序列化。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#42-端点与封闭请求类型、§16.2。
 */
public sealed interface HuashengRequest permits
        HuashengTokenRequest,
        HuashengPlaceRequest,
        HuashengConfirmRequest,
        HuashengQueryRequest,
        HuashengTakeRequest {

    /**
     * 获取请求绑定的封闭端点。
     *
     * <p>职责：返回与该请求类型精确对应的端点枚举，用于出站 URL 路由与协议规则校验。
     *
     * <p>不变量：恒定非 null。
     *
     * @return 绑定的华昇端点枚举
     */
    HuashengEndpoint endpoint();
}
