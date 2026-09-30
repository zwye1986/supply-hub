-- 标准商品路由每日累计供货配额；只属供应链独立库，不设跨表物理外键。
CREATE TABLE sc_supplier_sku_daily_quota (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商SKU自然日供货配额配置内部主键',
    supplier_id BIGINT NOT NULL COMMENT '供应商内部主键，逻辑引用sc_supplier.id',
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU内部主键，逻辑引用sc_platform_sku.id',
    daily_limit BIGINT NOT NULL COMMENT '供应商对该平台SKU的自然日累计供货数量上限',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_sku_daily_quota (supplier_id, platform_sku_id),
    CONSTRAINT ck_sc_supplier_sku_daily_quota_positive CHECK (daily_limit > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商SKU自然日累计供货配额配置';

ALTER TABLE sc_supply_chain_order
    ADD KEY idx_sc_order_supplier_sku_day (platform_sku_id, display_supplier_id, created_at);
