-- 供应链七类主数据的新编号占用，包含已归档对象；作用域行锁跨实例串行化同一范围。
-- 设计见 docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#5-存储迁移与并发。
CREATE TABLE sc_business_code_scope (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '业务编码作用域内部主键',
    code_kind VARCHAR(32) NOT NULL COMMENT '业务编码类型',
    owner_id BIGINT NOT NULL COMMENT '父对象内部主键；根作用域为0',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_business_code_scope_kind_owner (code_kind, owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应链业务编码分配作用域';

CREATE TABLE sc_business_code_claim (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '业务编码占号内部主键',
    scope_id BIGINT NOT NULL COMMENT '业务编码作用域内部主键，逻辑引用sc_business_code_scope.id',
    serial_no SMALLINT NOT NULL COMMENT '三位流水号数值，范围1至999',
    created_at DATETIME(3) NOT NULL COMMENT '占号时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_business_code_claim_scope_no (scope_id, serial_no),
    CONSTRAINT ck_sc_business_code_claim_no CHECK (serial_no BETWEEN 1 AND 999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应链业务编码永久占号';

ALTER TABLE sc_platform_spu
    ADD COLUMN code_scope_no SMALLINT NULL COMMENT '平台SPU内部全局短序号，供SKU编码父段引用',
    ADD UNIQUE KEY uk_sc_platform_spu_code_scope_no (code_scope_no),
    ADD CONSTRAINT ck_sc_platform_spu_code_scope_no CHECK (code_scope_no IS NULL OR code_scope_no BETWEEN 1 AND 999);

ALTER TABLE sc_buyer
    ADD COLUMN code_scope_no SMALLINT NULL COMMENT '分销商内部全局短序号，供项目编码父段引用',
    ADD UNIQUE KEY uk_sc_buyer_code_scope_no (code_scope_no),
    ADD CONSTRAINT ck_sc_buyer_code_scope_no CHECK (code_scope_no IS NULL OR code_scope_no BETWEEN 1 AND 999);

ALTER TABLE sc_product_category
    ADD COLUMN category_node_no SMALLINT NULL COMMENT '分类节点内部全局短序号，供直接子分类编码父段引用',
    ADD UNIQUE KEY uk_sc_product_category_node_no (category_node_no),
    ADD CONSTRAINT ck_sc_product_category_node_no CHECK (category_node_no IS NULL OR category_node_no BETWEEN 1 AND 999);
