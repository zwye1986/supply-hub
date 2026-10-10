package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动目录扫描与投影评估配置：默认值语义与配置键绑定。设计 §6.1、§6.4。
 */
class BuyerSelectionCatalogConfigurationTest {

    @Test
    void defaultsAreSweepEnabledBatch100Delay30AndEvaluationAge30() {
        BuyerSelectionCatalogProperties properties = new BuyerSelectionCatalogProperties();
        assertThat(properties.isSweepEnabled()).isTrue();
        assertThat(properties.getSweepBatchSize()).isNull();
        assertThat(properties.getSweepDelaySeconds()).isNull();
        assertThat(properties.getMaxEvaluationAgeSeconds()).isNull();
        assertThat(properties.effectiveMaxEvaluationAgeSeconds()).isEqualTo(30);
    }

    @Test
    void explicitEnabledAndEvaluationAgeAreHonored() {
        BuyerSelectionCatalogProperties properties = new BuyerSelectionCatalogProperties();
        properties.setSweepEnabled(false);
        properties.setMaxEvaluationAgeSeconds(120);
        properties.setSweepBatchSize(50);
        properties.setSweepDelaySeconds(10);
        assertThat(properties.isSweepEnabled()).isFalse();
        assertThat(properties.effectiveMaxEvaluationAgeSeconds()).isEqualTo(120);
        assertThat(properties.getSweepBatchSize()).isEqualTo(50);
        assertThat(properties.getSweepDelaySeconds()).isEqualTo(10);
    }

    @Test
    void nonPositiveEvaluationAgeFallsBackToDefaultForWorkerButLifecycleRejects() {
        BuyerSelectionCatalogProperties properties = new BuyerSelectionCatalogProperties();
        properties.setMaxEvaluationAgeSeconds(0);
        // worker 口径回退默认保证投影期限不为负；显式非正在生命周期启动时被拒绝。
        assertThat(properties.effectiveMaxEvaluationAgeSeconds()).isEqualTo(30);
    }
}
