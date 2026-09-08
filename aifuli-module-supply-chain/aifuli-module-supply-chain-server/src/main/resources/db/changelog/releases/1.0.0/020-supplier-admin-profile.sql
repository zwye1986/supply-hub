-- 供应商管理端档案 / 供货配置 / 结算账户期初 / 合作商品草稿列
-- 设计见 docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md#15-供应商管理端原型已确认

ALTER TABLE sc_supplier DROP CHECK ck_sc_supplier_status;

ALTER TABLE sc_supplier
    ADD COLUMN credit_code VARCHAR(32) NULL COMMENT '统一社会信用代码' AFTER legal_entity_name,
    ADD COLUMN delivery_region_type VARCHAR(16) NULL COMMENT '配送区域：NATIONWIDE全国，DESIGNATED指定区域' AFTER status,
    ADD COLUMN aftersales_supported TINYINT NULL COMMENT '是否支持售后：1是，0否' AFTER delivery_region_type,
    ADD COLUMN settlement_cycle_days INT NULL COMMENT '结算周期天数' AFTER aftersales_supported,
    ADD COLUMN onboarded_at DATETIME(3) NULL COMMENT '接入时间，UTC' AFTER settlement_cycle_days,
    ADD COLUMN cooperation_note VARCHAR(200) NULL COMMENT '合作说明' AFTER onboarded_at,
    ADD COLUMN legal_person_name VARCHAR(64) NULL COMMENT '法人姓名' AFTER cooperation_note,
    ADD COLUMN legal_person_mobile_cipher VARBINARY(256) NULL COMMENT '法人手机号密文' AFTER legal_person_name,
    ADD COLUMN contact_address VARCHAR(512) NULL COMMENT '联系地址' AFTER legal_person_mobile_cipher,
    ADD COLUMN bank_name VARCHAR(128) NULL COMMENT '开户行' AFTER contact_address,
    ADD COLUMN bank_account_cipher VARBINARY(512) NULL COMMENT '开户账号密文' AFTER bank_name,
    ADD COLUMN bank_account_key_version VARCHAR(32) NULL COMMENT '开户账号信封密钥版本' AFTER bank_account_cipher,
    ADD COLUMN license_file_cipher VARBINARY(512) NULL COMMENT '营业执照文件引用密文' AFTER bank_account_key_version,
    ADD COLUMN id_front_cipher VARBINARY(512) NULL COMMENT '法人身份证正面文件引用密文' AFTER license_file_cipher,
    ADD COLUMN id_back_cipher VARBINARY(512) NULL COMMENT '法人身份证反面文件引用密文' AFTER id_front_cipher,
    ADD COLUMN qualification_file_cipher VARBINARY(512) NULL COMMENT '资质文件引用密文' AFTER id_back_cipher,
    ADD COLUMN document_key_version VARCHAR(32) NULL COMMENT '证件文件引用信封密钥版本' AFTER qualification_file_cipher,
    ADD CONSTRAINT ck_sc_supplier_status CHECK (status IN (10, 20, 30, 90)),
    ADD CONSTRAINT ck_sc_supplier_delivery_region CHECK (
        delivery_region_type IS NULL OR delivery_region_type IN ('NATIONWIDE', 'DESIGNATED')),
    ADD CONSTRAINT ck_sc_supplier_aftersales CHECK (
        aftersales_supported IS NULL OR aftersales_supported IN (0, 1)),
    ADD CONSTRAINT ck_sc_supplier_settlement_cycle CHECK (
        settlement_cycle_days IS NULL OR settlement_cycle_days >= 1);

ALTER TABLE sc_supplier
    ADD UNIQUE KEY uk_sc_supplier_legal (legal_entity_name, credit_code);

ALTER TABLE sc_supplier_offer
    ADD COLUMN supply_scope_id BIGINT NULL COMMENT '命中的供货配置主键，逻辑引用sc_supplier_supply_scope.id' AFTER platform_sku_id,
    ADD COLUMN price_mode VARCHAR(16) NULL COMMENT '供应价模式：FIXED固定值，REALTIME实时，DISCOUNT折扣' AFTER supply_scope_id,
    ADD COLUMN draft_unit_price BIGINT NULL COMMENT '草稿供应价，CNY分；REALTIME为空' AFTER price_mode,
    ADD COLUMN discount_bp INT NULL COMMENT '折扣基点，10000=100%' AFTER draft_unit_price,
    ADD COLUMN offer_supply_mode VARCHAR(32) NULL COMMENT '该Offer供货模式' AFTER discount_bp,
    ADD COLUMN draft_max_quantity BIGINT NULL COMMENT '草稿供货上限' AFTER offer_supply_mode,
    ADD COLUMN draft_min_quantity BIGINT NULL COMMENT '草稿最低起购量' AFTER draft_max_quantity,
    ADD CONSTRAINT ck_sc_offer_price_mode CHECK (price_mode IS NULL OR price_mode IN ('FIXED', 'REALTIME', 'DISCOUNT')),
    ADD CONSTRAINT ck_sc_offer_supply_mode CHECK (
        offer_supply_mode IS NULL OR offer_supply_mode IN ('API_DIRECT', 'DROP_SHIP')),
    ADD CONSTRAINT ck_sc_offer_draft_qty CHECK (
        draft_min_quantity IS NULL OR (draft_min_quantity >= 1 AND draft_max_quantity >= draft_min_quantity));

CREATE TABLE sc_supplier_contact (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商联系人内部主键',
    supplier_id BIGINT NOT NULL COMMENT '供应商内部主键，逻辑引用sc_supplier.id',
    sort_no INT NOT NULL COMMENT '排序，越小越靠前，最小行为主联系人',
    contact_name VARCHAR(64) NOT NULL COMMENT '联系人姓名',
    contact_mobile VARCHAR(32) NOT NULL COMMENT '联系人手机号',
    contact_email VARCHAR(128) NULL COMMENT '联系人邮箱',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_contact_sort (supplier_id, sort_no),
    KEY idx_sc_supplier_contact_supplier (supplier_id),
    CONSTRAINT ck_sc_supplier_contact_sort CHECK (sort_no >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商运营联系人';

CREATE TABLE sc_supplier_delivery_region (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商配送区域内部主键',
    supplier_id BIGINT NOT NULL COMMENT '供应商内部主键，逻辑引用sc_supplier.id',
    adcode CHAR(6) NOT NULL COMMENT '国标行政区划代码',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_region (supplier_id, adcode),
    CONSTRAINT ck_sc_supplier_adcode CHECK (adcode REGEXP '^[0-9]{6}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商指定配送行政区划';

CREATE TABLE sc_supplier_supply_scope (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供货配置内部主键',
    supplier_id BIGINT NOT NULL COMMENT '供应商内部主键，逻辑引用sc_supplier.id',
    category_l1_id BIGINT NOT NULL COMMENT '可售品类，逻辑引用sc_product_category.id，须为一级类目',
    category_l2_id BIGINT NOT NULL COMMENT '商品类型，逻辑引用sc_product_category.id，须为品类子类目',
    category_l3_id BIGINT NOT NULL COMMENT '商品分类，逻辑引用sc_product_category.id，须为类型子类目',
    supply_mode VARCHAR(32) NOT NULL COMMENT '供货模式：API_DIRECT API直发，DROP_SHIP 一件代发',
    max_quantity BIGINT NOT NULL COMMENT '供货上限',
    min_quantity BIGINT NOT NULL COMMENT '最低起订量',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_scope_triple (supplier_id, category_l1_id, category_l2_id, category_l3_id),
    KEY idx_sc_supplier_scope_supplier (supplier_id),
    CONSTRAINT ck_sc_supplier_scope_mode CHECK (supply_mode IN ('API_DIRECT', 'DROP_SHIP')),
    CONSTRAINT ck_sc_supplier_scope_qty CHECK (min_quantity >= 1 AND max_quantity >= min_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商可供货范围与供货规则';

CREATE TABLE sc_supplier_settlement_account (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商结算账户内部主键',
    account_no VARCHAR(32) NOT NULL COMMENT '供应商结算账户稳定业务编号',
    supplier_id BIGINT NOT NULL COMMENT '供应商内部主键，逻辑引用sc_supplier.id',
    currency CHAR(3) NOT NULL COMMENT '账户币种，一期固定CNY',
    balance BIGINT NOT NULL DEFAULT 0 COMMENT '预存可用额度，单位为分',
    frozen BIGINT NOT NULL DEFAULT 0 COMMENT '已冻结金额，单位为分',
    status SMALLINT NOT NULL COMMENT '账户状态：10正常，20冻结新交易，90已关闭',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '余额并发更新乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_settlement_no (account_no),
    UNIQUE KEY uk_sc_supplier_settlement_currency (supplier_id, currency),
    CONSTRAINT ck_sc_ssa_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_ssa_amount CHECK (balance >= 0 AND frozen >= 0 AND balance >= frozen),
    CONSTRAINT ck_sc_ssa_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商预存可用额度账户，不写入采购商账本';

CREATE TABLE sc_supplier_settlement_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商结算账户流水内部主键',
    ledger_no VARCHAR(32) NOT NULL COMMENT '结算流水稳定业务编号',
    account_id BIGINT NOT NULL COMMENT '供应商结算账户主键，逻辑引用sc_supplier_settlement_account.id',
    entry_key VARCHAR(128) NOT NULL COMMENT '账户范围内业务幂等键',
    entry_type VARCHAR(32) NOT NULL COMMENT '流水类型：本期仅DEPOSIT期初',
    balance_delta BIGINT NOT NULL COMMENT '本次总余额变化金额，单位为分',
    frozen_delta BIGINT NOT NULL COMMENT '本次冻结金额变化，单位为分',
    balance_before BIGINT NOT NULL COMMENT '变更前账户总余额，单位为分',
    balance_after BIGINT NOT NULL COMMENT '变更后账户总余额，单位为分',
    frozen_before BIGINT NOT NULL COMMENT '变更前账户冻结金额，单位为分',
    frozen_after BIGINT NOT NULL COMMENT '变更后账户冻结金额，单位为分',
    operator_id BIGINT NULL COMMENT '操作人用户ID',
    evidence_ref VARCHAR(512) NULL COMMENT '期初证据引用',
    evidence_digest BINARY(32) NULL COMMENT '期初证据SHA-256摘要',
    audit_note VARCHAR(1024) NULL COMMENT '脱敏审计说明',
    trace_id VARCHAR(64) NOT NULL COMMENT '链路追踪标识',
    causation_id VARCHAR(64) NULL COMMENT '触发本流水的命令标识',
    occurred_at DATETIME(3) NOT NULL COMMENT '资金事实发生时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '流水入库时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_ssl_no (ledger_no),
    UNIQUE KEY uk_sc_ssl_entry (account_id, entry_key),
    CONSTRAINT ck_sc_ssl_balance CHECK (balance_before >= 0 AND balance_after >= 0),
    CONSTRAINT ck_sc_ssl_frozen CHECK (frozen_before >= 0 AND frozen_after >= 0),
    CONSTRAINT ck_sc_ssl_delta CHECK (
        balance_after = balance_before + balance_delta
        AND frozen_after = frozen_before + frozen_delta),
    CONSTRAINT ck_sc_ssl_type CHECK (entry_type IN ('DEPOSIT')),
    CONSTRAINT ck_sc_ssl_deposit CHECK (
        entry_type = 'DEPOSIT' AND balance_delta >= 0 AND frozen_delta = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商结算账户不可变流水';
