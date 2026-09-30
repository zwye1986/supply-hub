ALTER TABLE sc_supplier_supply_scope
    DROP CHECK ck_sc_supplier_scope_qty,
    MODIFY COLUMN min_quantity BIGINT NOT NULL COMMENT '允许最低起订量，预留字段',
    ADD CONSTRAINT ck_sc_supplier_scope_qty CHECK (max_quantity >= 1 AND min_quantity >= 1);
