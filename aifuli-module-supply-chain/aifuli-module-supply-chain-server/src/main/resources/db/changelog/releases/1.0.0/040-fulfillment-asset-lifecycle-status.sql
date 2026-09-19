-- 将 sc_fulfillment_asset.lifecycle_status 与领域枚举 FulfillmentAssetStatus / 详细设计 §7.2.1 对齐。
-- 10 可交付 AVAILABLE；20 已交付 DELIVERED；30 已作废 VOIDED；40 已解绑 UNBOUND；50 已过期 EXPIRED；90 隔离不可交付 QUARANTINED。
-- 一期表尚未写入生产资产行，不迁移历史码值。

ALTER TABLE sc_fulfillment_asset
    DROP CHECK ck_sc_fulfillment_asset_status;

ALTER TABLE sc_fulfillment_asset
    MODIFY COLUMN lifecycle_status SMALLINT NOT NULL
        COMMENT '资产生命周期：10可交付，20已交付，30已作废，40已解绑，50已过期，90隔离不可交付';

ALTER TABLE sc_fulfillment_asset
    ADD CONSTRAINT ck_sc_fulfillment_asset_status
        CHECK (lifecycle_status IN (10, 20, 30, 40, 50, 90));
