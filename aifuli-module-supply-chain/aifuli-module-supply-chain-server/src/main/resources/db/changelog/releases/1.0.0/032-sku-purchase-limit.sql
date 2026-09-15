-- SKU 限购配置与标准履约项目归属。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#210-sku-限购与标准履约项目归属032-changeset
ALTER TABLE sc_platform_sku
    ADD COLUMN user_limit_enabled TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否启用每人限购：1启用，0停用' AFTER max_quantity,
    ADD COLUMN user_limit_period VARCHAR(16) NULL
        COMMENT '每人限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天' AFTER user_limit_enabled,
    ADD COLUMN user_limit_quantity BIGINT NULL
        COMMENT '每人每周期最多购买数量' AFTER user_limit_period,
    ADD COLUMN order_limit_enabled TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否启用每订单限购：1启用，0停用' AFTER user_limit_quantity,
    ADD CONSTRAINT ck_sc_sku_user_limit CHECK (
        (user_limit_enabled = 0 AND user_limit_period IS NULL AND user_limit_quantity IS NULL)
        OR (user_limit_enabled = 1
            AND user_limit_period IN ('LIFETIME', 'YEAR', 'MONTH', 'WEEK', 'DAY')
            AND user_limit_quantity > 0)),
    ADD CONSTRAINT ck_sc_sku_order_limit_enabled CHECK (order_limit_enabled IN (0, 1));

-- 历史回执无法从采购商或外部租户无损推导项目，故保留 NULL；新受理由应用层强制写入。
ALTER TABLE sc_fulfillment_acceptance_receipt
    ADD COLUMN project_id BIGINT NULL COMMENT '采购项目内部主键；历史受理回执可为空' AFTER buyer_id,
    ADD KEY idx_sc_fulfillment_receipt_buyer_project (buyer_id, project_id, created_at);

-- CARD 新订单写入项目主键；失败、取消或全额退款后由当前订单状态自然退出限购统计。
ALTER TABLE sc_supply_chain_order
    ADD COLUMN project_id BIGINT NULL COMMENT '采购项目内部主键；历史订单及非项目场景可为空' AFTER buyer_id,
    ADD KEY idx_sc_order_purchase_limit (project_id, platform_sku_id, buyer_user_id, created_at);
