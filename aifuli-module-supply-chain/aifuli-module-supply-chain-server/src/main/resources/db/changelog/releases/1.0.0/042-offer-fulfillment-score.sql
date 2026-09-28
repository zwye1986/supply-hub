-- changeset: 042-offer-fulfillment-score
-- 为 sc_supplier_offer 增加人工履约能力评分身份列。未评分时两列同为 NULL，不等于 0。
-- 供应商合作商品与商品列表履约能力弹窗读写同一事实；路由引擎本期不读分。
-- 设计见 docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md#3-存储

ALTER TABLE sc_supplier_offer
    ADD COLUMN stability_score TINYINT NULL COMMENT '稳定性评分，人工录入，0-100'
    AFTER draft_min_quantity,
    ADD COLUMN timeliness_score TINYINT NULL COMMENT '时效性评分，人工录入，0-100'
    AFTER stability_score;

ALTER TABLE sc_supplier_offer
    ADD CONSTRAINT ck_sc_offer_fulfillment_score CHECK (
        (stability_score IS NULL AND timeliness_score IS NULL)
        OR (stability_score BETWEEN 0 AND 100 AND timeliness_score BETWEEN 0 AND 100)
    );
