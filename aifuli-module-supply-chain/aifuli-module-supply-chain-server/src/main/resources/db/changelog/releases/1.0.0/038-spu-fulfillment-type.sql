-- 变更集：038-spu-fulfillment-type.sql
-- 平台 SPU 增加履约方式字段与状态联合索引，支撑业务交付模型与高效分页筛选。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#215-spu-履约方式上移038-changeset

ALTER TABLE sc_platform_spu
    ADD COLUMN fulfillment_type VARCHAR(32) NOT NULL DEFAULT 'DIRECT_RECHARGE'
        COMMENT '履约类型：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益'
        AFTER delivery_type,
    ADD KEY idx_sc_spu_fulfillment_status (fulfillment_type, status);

UPDATE sc_platform_spu spu
    JOIN sc_platform_sku sku ON sku.spu_id = spu.id
    SET spu.fulfillment_type = sku.fulfillment_type
    WHERE sku.status != 90;
