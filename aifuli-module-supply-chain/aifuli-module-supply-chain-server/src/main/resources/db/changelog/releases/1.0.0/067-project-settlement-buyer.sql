-- 项目独立结算方；设计：docs/superpowers/specs/2026-10-14-project-settlement-buyer-design.md#3-数据与资金
ALTER TABLE sc_buyer_project
    ADD COLUMN settlement_buyer_id BIGINT NULL COMMENT '结算方分销商内部主键，逻辑引用sc_buyer.id' AFTER settlement_party_type;
UPDATE sc_buyer_project SET settlement_buyer_id = buyer_id WHERE settlement_buyer_id IS NULL;
ALTER TABLE sc_fulfillment_request
    ADD COLUMN settlement_buyer_id BIGINT NULL COMMENT '结算方分销商内部主键，逻辑引用sc_buyer.id' AFTER buyer_id;
UPDATE sc_fulfillment_request SET settlement_buyer_id = buyer_id WHERE settlement_buyer_id IS NULL;
