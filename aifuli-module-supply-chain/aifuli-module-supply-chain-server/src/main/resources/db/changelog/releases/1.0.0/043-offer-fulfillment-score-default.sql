-- changeset: 043-offer-fulfillment-score-default
-- 把履约能力评分从「未评分=NULL」改为默认 80。禁止回改 042。
-- 存量 NULL 行回填为 80；列改为 NOT NULL DEFAULT 80。
-- 设计见 docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md#3-存储

UPDATE sc_supplier_offer
SET stability_score = 80, timeliness_score = 80
WHERE stability_score IS NULL OR timeliness_score IS NULL;

ALTER TABLE sc_supplier_offer
    DROP CHECK ck_sc_offer_fulfillment_score;

ALTER TABLE sc_supplier_offer
    MODIFY COLUMN stability_score TINYINT NOT NULL DEFAULT 80 COMMENT '稳定性评分，人工录入，0-100，默认80',
    MODIFY COLUMN timeliness_score TINYINT NOT NULL DEFAULT 80 COMMENT '时效性评分，人工录入，0-100，默认80';

ALTER TABLE sc_supplier_offer
    ADD CONSTRAINT ck_sc_offer_fulfillment_score CHECK (
        stability_score BETWEEN 0 AND 100 AND timeliness_score BETWEEN 0 AND 100
    );
