-- 动态建议价独立结果，不反向写SKU兼容金额；历史版本不回填。
-- 设计：docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#4-数据模型与唯一取值入口
CREATE TABLE sc_sku_suggested_price_state (
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU主键，逻辑引用sc_platform_sku.id',
    rule_digest VARCHAR(64) NOT NULL COMMENT '建议价人工公式规范化摘要，不含服务端快照',
    calculation_status VARCHAR(32) NOT NULL COMMENT '建议价计算状态：READY可用，DEPENDENCY_MISSING待计算，CALCULATION_FAILED计算失败',
    effective_price_cents BIGINT NULL COMMENT '当前有效销售建议价，单位CNY分；非READY为空',
    base_price_snapshot_cents BIGINT NULL COMMENT '本轮供应价基数快照，单位CNY分；缺失为空',
    last_valid_price_cents BIGINT NULL COMMENT '最近一次成功销售建议价，仅供历史参考，单位CNY分',
    last_valid_calculated_at DATETIME(3) NULL COMMENT '最近一次成功建议价计算时间，Asia/Shanghai',
    unavailable_reason VARCHAR(128) NULL COMMENT '建议价不可用原因编码；READY为空',
    next_check_at DATETIME(3) NULL COMMENT '候选当前供给版本下一有效期边界，Asia/Shanghai',
    calculated_at DATETIME(3) NOT NULL COMMENT '建议价计算事实时间，Asia/Shanghai',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '建议价计算状态修订号，无变化不递增',
    PRIMARY KEY (platform_sku_id),
    CONSTRAINT ck_sc_suggested_price_status CHECK (calculation_status IN ('READY','DEPENDENCY_MISSING','CALCULATION_FAILED')),
    CONSTRAINT ck_sc_suggested_price_result CHECK (
        (calculation_status = 'READY' AND effective_price_cents IS NOT NULL AND effective_price_cents >= 0 AND unavailable_reason IS NULL)
        OR (calculation_status <> 'READY' AND effective_price_cents IS NULL AND unavailable_reason IS NOT NULL)),
    CONSTRAINT ck_sc_suggested_price_amounts CHECK (
        (base_price_snapshot_cents IS NULL OR base_price_snapshot_cents >= 0)
        AND (last_valid_price_cents IS NULL OR last_valid_price_cents >= 0)),
    CONSTRAINT ck_sc_suggested_price_revision CHECK (revision >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SKU供应价基准建议价计算状态';

ALTER TABLE sc_platform_sku_pricing_version MODIFY COLUMN suggested_sale_price_cents BIGINT NULL COMMENT '计算时销售建议价快照，单位CNY分；待计算或失败为空';
ALTER TABLE sc_buyer_sku_pricing_version MODIFY COLUMN suggested_sale_price_cents BIGINT NULL COMMENT '计算时销售建议价快照，单位CNY分；待计算或失败为空';
ALTER TABLE sc_project_sku_pricing_version MODIFY COLUMN suggested_sale_price_cents BIGINT NULL COMMENT '计算时销售建议价快照，单位CNY分；待计算或失败为空';
ALTER TABLE sc_platform_sku MODIFY COLUMN suggested_sale_price BIGINT NOT NULL DEFAULT 0 COMMENT '非供应价基准销售建议价，单位CNY分；供应价规则当前金额取计算状态表';
ALTER TABLE sc_platform_sku MODIFY COLUMN suggested_sale_price_rule_json JSON NOT NULL COMMENT 'SKU销售建议价人工公式JSON；供应价规则当前结果取计算状态表';
