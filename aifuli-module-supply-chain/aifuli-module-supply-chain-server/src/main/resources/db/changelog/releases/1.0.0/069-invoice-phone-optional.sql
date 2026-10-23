-- 禅道 Bug #168：供应商与分销商开票电话统一选填，不改写历史业务数据。
-- 设计见 docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md#7-表。

ALTER TABLE sc_supplier_invoice
    MODIFY COLUMN phone VARCHAR(32) NULL COMMENT '电话';

ALTER TABLE sc_buyer_invoice
    MODIFY COLUMN phone VARCHAR(32) NULL COMMENT '电话';
