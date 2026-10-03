-- 项目直接下单使用可信身份首次冻结，真实内部RPC来源仍原样保留。
-- 设计见 docs/superpowers/specs/2026-10-03-standard-project-direct-order-price-freeze-design.md#4-增量数据契约。
ALTER TABLE sc_standard_pricing_quote
    MODIFY COLUMN source_system VARCHAR(64) NULL COMMENT '内部RPC调用来源系统编码；项目直接下单首次冻结为空',
    MODIFY COLUMN external_tenant_id VARCHAR(128) NULL COMMENT '内部RPC来源系统外部租户标识；项目直接下单首次冻结为空';
