-- 权益商品绑定手机号限购配置，以及标准履约订单的履约手机号查询列。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#211-权益商品绑定手机号限购033-changeset
ALTER TABLE sc_platform_sku
    ADD COLUMN phone_bind_limit_enabled TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否启用绑定手机号限购：1启用，0停用' AFTER order_limit_enabled,
    ADD COLUMN phone_bind_limit_period VARCHAR(16) NULL
        COMMENT '绑定手机号限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天' AFTER phone_bind_limit_enabled,
    ADD COLUMN phone_bind_limit_quantity BIGINT NULL
        COMMENT '同一履约手机号每周期最多购买数量' AFTER phone_bind_limit_period,
    ADD CONSTRAINT ck_sc_sku_phone_bind_limit CHECK (
        (phone_bind_limit_enabled = 0 AND phone_bind_limit_period IS NULL AND phone_bind_limit_quantity IS NULL)
        OR (phone_bind_limit_enabled = 1
            AND phone_bind_limit_period IN ('LIFETIME', 'YEAR', 'MONTH', 'WEEK', 'DAY')
            AND phone_bind_limit_quantity > 0
            AND fulfillment_type IN ('DIRECT_RECHARGE', 'ACCOUNT_BOUND_BENEFIT')));

-- 直充与账户绑定权益订单写入履约目标手机号，供行锁内按履约号聚合；卡密类保持空值。
ALTER TABLE sc_supply_chain_order
    ADD COLUMN fulfillment_account_value VARCHAR(128) NULL
        COMMENT '履约目标手机号；仅直充与账户绑定权益写入，卡密类为空' AFTER buyer_user_id,
    ADD KEY idx_sc_order_phone_bind_limit (project_id, platform_sku_id, fulfillment_account_value, created_at);
