package com.aifuli.module.supplychain.infrastructure.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 自动目录后台扫描与投影评估的供应链专用技术配置。
 * 最大评估有效期：成功评估的投影 valid_until = min(未来真实事实边界, evaluatedAt + 最大评估有效期)，
 * 默认 30 秒；未配置或非正数回退默认值（配置合法性校验与扫描参数见 Task 7）。
 * 设计见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#64-投影陈旧安全。
 */
@Component
@ConfigurationProperties(prefix = "aifuli.supply-chain.catalog")
public class BuyerSelectionCatalogProperties {

    /** 最大评估有效期秒数；缺省 30。 */
    private Integer maxEvaluationAgeSeconds;

    /** 后台扫描是否启用；缺省 true。关闭时不承诺保存后自动重试收敛。 */
    private Boolean sweepEnabled;

    /** 扫描批大小上限；缺省 100。 */
    private Integer sweepBatchSize;

    /** 扫描固定延迟秒数；缺省 30。 */
    private Integer sweepDelaySeconds;

    public Integer getMaxEvaluationAgeSeconds() {
        return maxEvaluationAgeSeconds;
    }

    public void setMaxEvaluationAgeSeconds(Integer maxEvaluationAgeSeconds) {
        this.maxEvaluationAgeSeconds = maxEvaluationAgeSeconds;
    }

    public Boolean getSweepEnabled() {
        return sweepEnabled;
    }

    public void setSweepEnabled(Boolean sweepEnabled) {
        this.sweepEnabled = sweepEnabled;
    }

    public Integer getSweepBatchSize() {
        return sweepBatchSize;
    }

    public void setSweepBatchSize(Integer sweepBatchSize) {
        this.sweepBatchSize = sweepBatchSize;
    }

    public Integer getSweepDelaySeconds() {
        return sweepDelaySeconds;
    }

    public void setSweepDelaySeconds(Integer sweepDelaySeconds) {
        this.sweepDelaySeconds = sweepDelaySeconds;
    }

    /** 生效的最大评估有效期秒数：未配置或非正数回退默认 30 秒。 */
    public int effectiveMaxEvaluationAgeSeconds() {
        return maxEvaluationAgeSeconds == null || maxEvaluationAgeSeconds <= 0
                ? 30 : maxEvaluationAgeSeconds;
    }

    /** 扫描是否启用：未配置回退默认启用。 */
    public boolean isSweepEnabled() {
        return sweepEnabled == null || sweepEnabled;
    }
}
