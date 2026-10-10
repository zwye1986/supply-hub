package com.aifuli.module.supplychain.tradecore.catalog.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自动目录刷新配置；系统操作人用于扫描／下单准备触发且无真实管理操作人时的自动发布审计。
 * 未配置或非正数视为未配置：不填0、不随机冒充，自动发布真实报未就绪。
 * 设计见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#task-3-实施接口与事务边界。
 */
@ConfigurationProperties(prefix = "aifuli.supply-chain.catalog")
public class BuyerCatalogProperties {

    /** 扫描／下单准备触发自动发布时使用的显式系统操作人ID；缺省未配置。 */
    private Long systemOperatorId;

    public Long getSystemOperatorId() {
        return systemOperatorId;
    }

    public void setSystemOperatorId(Long systemOperatorId) {
        this.systemOperatorId = systemOperatorId;
    }

    public boolean hasSystemOperator() {
        return systemOperatorId != null && systemOperatorId > 0;
    }
}
