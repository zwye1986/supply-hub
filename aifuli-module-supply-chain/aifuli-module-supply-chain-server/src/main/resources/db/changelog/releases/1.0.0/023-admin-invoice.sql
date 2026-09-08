-- 供应商/分销商开票信息（1:1 明文档案）
-- 设计见 docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md#7-表

CREATE TABLE sc_supplier_invoice (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商开票信息内部主键',
    supplier_id BIGINT NOT NULL COMMENT '供应商内部主键，逻辑引用sc_supplier.id',
    title_type VARCHAR(16) NOT NULL COMMENT '抬头类型：ENTERPRISE企业，PERSONAL个人',
    invoice_title VARCHAR(256) NOT NULL COMMENT '开票抬头',
    social_credit_code VARCHAR(32) NULL COMMENT '统一社会信用代码',
    phone VARCHAR(32) NOT NULL COMMENT '电话',
    bank_name VARCHAR(128) NULL COMMENT '开户行',
    bank_account VARCHAR(64) NULL COMMENT '开户账号',
    invoice_address VARCHAR(512) NULL COMMENT '开票地址',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_invoice_supplier (supplier_id),
    CONSTRAINT ck_sc_supplier_invoice_title_type CHECK (title_type IN ('ENTERPRISE', 'PERSONAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商开票信息';

CREATE TABLE sc_buyer_invoice (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商开票信息内部主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    title_type VARCHAR(16) NOT NULL COMMENT '抬头类型：ENTERPRISE企业，PERSONAL个人',
    invoice_title VARCHAR(256) NOT NULL COMMENT '开票抬头',
    social_credit_code VARCHAR(32) NULL COMMENT '统一社会信用代码',
    phone VARCHAR(32) NOT NULL COMMENT '电话',
    bank_name VARCHAR(128) NULL COMMENT '开户行',
    bank_account VARCHAR(64) NULL COMMENT '开户账号',
    invoice_address VARCHAR(512) NULL COMMENT '开票地址',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_invoice_buyer (buyer_id),
    CONSTRAINT ck_sc_buyer_invoice_title_type CHECK (title_type IN ('ENTERPRISE', 'PERSONAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商开票信息';
