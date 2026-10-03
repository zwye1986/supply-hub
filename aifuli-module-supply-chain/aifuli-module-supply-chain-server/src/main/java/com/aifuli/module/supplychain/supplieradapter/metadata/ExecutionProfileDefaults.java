package com.aifuli.module.supplychain.supplieradapter.metadata;

/**
 * 适配器默认执行画像（封闭记录）：发布上架时由服务端注入 offer 版本，前端只读（d2）。
 *
 * <p>五个维度均取封闭枚举，保证默认组合必落在后端校验允许集内；{@code fulfillmentConfigJson}
 * 须通过对应适配器 {@code validateOfferConfig}（QNM 要求空对象 {@code "{}"}）。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#51-adaptermetadata-扩展}。</p>
 *
 * @param completionMode         完成模式
 * @param createIdempotencyMode  下单幂等模式
 * @param unknownResultPolicy    结果未知处置策略
 * @param partialDeliveryMode    部分履约模式
 * @param resultMultiplicity     结果多重性
 * @param fulfillmentConfigJson  履约私有配置 JSON（Schema 校验后冻结进版本行）
 */
public record ExecutionProfileDefaults(
        CompletionMode completionMode,
        CreateIdempotencyMode createIdempotencyMode,
        UnknownResultPolicy unknownResultPolicy,
        PartialDeliveryMode partialDeliveryMode,
        ResultMultiplicity resultMultiplicity,
        String fulfillmentConfigJson) {
}
