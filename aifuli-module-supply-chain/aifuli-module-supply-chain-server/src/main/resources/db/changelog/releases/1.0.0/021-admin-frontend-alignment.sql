-- 管理端 REST 对齐：五模式、分销商树、项目 CLOSED、BuyerProduct/L2/L3、L1 价格版本
-- 设计见 docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md

ALTER TABLE sc_supplier
    ADD COLUMN shipping_sla_code VARCHAR(16) NULL COMMENT '发货时效封闭枚举' AFTER aftersales_supported,
    ADD COLUMN shipping_sla_custom VARCHAR(64) NULL COMMENT '发货时效自定义说明，仅 CUSTOM 使用' AFTER shipping_sla_code,
    ADD COLUMN settlement_method VARCHAR(16) NULL COMMENT '结算方式封闭枚举' AFTER shipping_sla_custom,
    ADD CONSTRAINT ck_sc_supplier_shipping_sla CHECK (
        shipping_sla_code IS NULL OR shipping_sla_code IN ('SAME_DAY','NEXT_DAY','HOURS_48','HOURS_72','CUSTOM')),
    ADD CONSTRAINT ck_sc_supplier_settlement_method CHECK (
        settlement_method IS NULL OR settlement_method IN ('DAILY','WEEKLY','MONTHLY','QUARTERLY','PREPAID'));

CREATE TABLE sc_supplier_integration_mode (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商对接方式行主键',
    supplier_id BIGINT NOT NULL COMMENT '供应商内部主键，逻辑引用sc_supplier.id',
    integration_mode VARCHAR(16) NOT NULL COMMENT '对接方式：API、H5、MANUAL',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_integration_mode (supplier_id, integration_mode),
    CONSTRAINT ck_sc_supplier_integration_mode CHECK (integration_mode IN ('API','H5','MANUAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商对接方式多选';

CREATE TABLE sc_supplier_file_ref (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商证件文件行主键',
    supplier_id BIGINT NOT NULL COMMENT '供应商内部主键，逻辑引用sc_supplier.id',
    file_role VARCHAR(16) NOT NULL COMMENT 'LICENSE执照 QUALIFICATION资质',
    sort_no INT NOT NULL COMMENT '同一角色内排序，从0开始',
    file_cipher VARBINARY(512) NOT NULL COMMENT '文件fileKey密文',
    key_version VARCHAR(32) NOT NULL COMMENT '信封密钥版本',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_file_role_sort (supplier_id, file_role, sort_no),
    CONSTRAINT ck_sc_supplier_file_role CHECK (file_role IN ('LICENSE','QUALIFICATION')),
    CONSTRAINT ck_sc_supplier_file_sort CHECK (sort_no >= 0 AND sort_no <= 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商执照与资质文件引用';

CREATE TABLE sc_supplier_supply_scope_mode (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供货配置模式行主键',
    supply_scope_id BIGINT NOT NULL COMMENT '供货配置主键，逻辑引用sc_supplier_supply_scope.id',
    supply_mode VARCHAR(32) NOT NULL COMMENT '供货模式',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_scope_mode (supply_scope_id, supply_mode),
    KEY idx_sc_scope_mode_scope (supply_scope_id),
    CONSTRAINT ck_sc_scope_mode_value CHECK (
        supply_mode IN ('API_DIRECT','DROP_SHIP','BATCH','PICKUP','H5'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供货配置一行上的多种供货模式';

INSERT INTO sc_supplier_supply_scope_mode (supply_scope_id, supply_mode, created_at)
SELECT id, supply_mode, created_at FROM sc_supplier_supply_scope;

ALTER TABLE sc_supplier_supply_scope DROP CHECK ck_sc_supplier_scope_mode;
ALTER TABLE sc_supplier_supply_scope DROP COLUMN supply_mode;

ALTER TABLE sc_supplier_offer DROP CHECK ck_sc_offer_supply_mode;
ALTER TABLE sc_supplier_offer
    ADD CONSTRAINT ck_sc_offer_supply_mode CHECK (
        offer_supply_mode IS NULL OR offer_supply_mode IN (
            'API_DIRECT','DROP_SHIP','BATCH','PICKUP','H5'));

ALTER TABLE sc_buyer DROP CHECK ck_sc_buyer_status;
ALTER TABLE sc_buyer
    ADD COLUMN parent_buyer_id BIGINT NULL COMMENT '上级分销商主键，逻辑引用sc_buyer.id，一级为空' AFTER status,
    ADD COLUMN distribution_level SMALLINT NOT NULL DEFAULT 1 COMMENT '组织等级：1一级 2二级 3三级' AFTER parent_buyer_id,
    ADD COLUMN distributor_type VARCHAR(16) NULL COMMENT '分销商类型：ENTERPRISE企业 PERSONAL个人 PLATFORM平台' AFTER distribution_level,
    ADD COLUMN supply_preference VARCHAR(16) NULL COMMENT '供货择优倾向，不改变路由引擎' AFTER distributor_type,
    ADD COLUMN settlement_method VARCHAR(16) NULL COMMENT '结算方式封闭枚举' AFTER supply_preference,
    ADD COLUMN settlement_cycle_days INT NULL COMMENT '结算周期天数' AFTER settlement_method,
    ADD COLUMN cooperation_started_at DATETIME(3) NULL COMMENT '合作开始时间，UTC' AFTER settlement_cycle_days,
    ADD COLUMN cooperation_note VARCHAR(200) NULL COMMENT '合作说明' AFTER cooperation_started_at,
    ADD COLUMN legal_entity_name VARCHAR(256) NULL COMMENT '公司名称' AFTER cooperation_note,
    ADD COLUMN credit_code VARCHAR(32) NULL COMMENT '统一社会信用代码' AFTER legal_entity_name,
    ADD COLUMN legal_person_name VARCHAR(64) NULL COMMENT '法人姓名' AFTER credit_code,
    ADD COLUMN legal_person_mobile_cipher VARBINARY(256) NULL COMMENT '法人手机号密文' AFTER legal_person_name,
    ADD COLUMN contact_address VARCHAR(512) NULL COMMENT '联系地址' AFTER legal_person_mobile_cipher,
    ADD COLUMN bank_name VARCHAR(128) NULL COMMENT '开户行' AFTER contact_address,
    ADD COLUMN bank_account_cipher VARBINARY(512) NULL COMMENT '开户账号密文' AFTER bank_name,
    ADD COLUMN bank_account_key_version VARCHAR(32) NULL COMMENT '开户账号信封密钥版本' AFTER bank_account_cipher,
    ADD COLUMN document_key_version VARCHAR(32) NULL COMMENT '证件信封密钥版本' AFTER bank_account_key_version,
    ADD COLUMN id_front_cipher VARBINARY(512) NULL COMMENT '法人身份证正面fileKey密文' AFTER document_key_version,
    ADD COLUMN id_back_cipher VARBINARY(512) NULL COMMENT '法人身份证反面fileKey密文' AFTER id_front_cipher,
    ADD CONSTRAINT ck_sc_buyer_status CHECK (status IN (10, 20, 30, 90)),
    ADD CONSTRAINT ck_sc_buyer_level CHECK (distribution_level IN (1, 2, 3)),
    ADD CONSTRAINT ck_sc_buyer_distributor_type CHECK (
        distributor_type IS NULL OR distributor_type IN ('ENTERPRISE','PERSONAL','PLATFORM')),
    ADD CONSTRAINT ck_sc_buyer_preference CHECK (
        supply_preference IS NULL OR supply_preference IN ('STABLE','PRICE','COMPREHENSIVE')),
    ADD CONSTRAINT ck_sc_buyer_settlement_method CHECK (
        settlement_method IS NULL OR settlement_method IN ('DAILY','WEEKLY','MONTHLY','QUARTERLY','PREPAID')),
    ADD CONSTRAINT ck_sc_buyer_settlement_cycle CHECK (
        settlement_cycle_days IS NULL OR settlement_cycle_days >= 1),
    ADD UNIQUE KEY uk_sc_buyer_legal (legal_entity_name, credit_code);

CREATE TABLE sc_buyer_contact (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商联系人主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    sort_no INT NOT NULL COMMENT '排序，0为主联系人',
    contact_name VARCHAR(64) NOT NULL COMMENT '联系人姓名',
    contact_mobile VARCHAR(32) NOT NULL COMMENT '联系人手机号',
    contact_email VARCHAR(128) NULL COMMENT '联系人邮箱',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_contact_sort (buyer_id, sort_no),
    CONSTRAINT ck_sc_buyer_contact_sort CHECK (sort_no >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商运营联系人';

CREATE TABLE sc_buyer_integration_mode (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商对接方式行主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    integration_mode VARCHAR(16) NOT NULL COMMENT '对接方式',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_integration_mode (buyer_id, integration_mode),
    CONSTRAINT ck_sc_buyer_integration_mode CHECK (integration_mode IN ('API','H5','MANUAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商对接方式多选';

CREATE TABLE sc_buyer_cooperation_mode (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商合作模式行主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    cooperation_mode VARCHAR(16) NOT NULL COMMENT '合作模式',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_cooperation_mode (buyer_id, cooperation_mode),
    CONSTRAINT ck_sc_buyer_cooperation_mode CHECK (cooperation_mode IN ('DIRECT','AGENT','CHANNEL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商合作模式多选';

CREATE TABLE sc_buyer_category_scope (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商可售类目行主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    category_id BIGINT NOT NULL COMMENT '类目主键，逻辑引用sc_product_category.id',
    category_level SMALLINT NOT NULL COMMENT '1品类 2类型',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_category_scope (buyer_id, category_id),
    CONSTRAINT ck_sc_buyer_category_level CHECK (category_level IN (1, 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商可售品类与商品类型';

CREATE TABLE sc_buyer_file_ref (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商证件文件行主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    file_role VARCHAR(16) NOT NULL COMMENT 'LICENSE执照 QUALIFICATION资质',
    sort_no INT NOT NULL COMMENT '同一角色内排序',
    file_cipher VARBINARY(512) NOT NULL COMMENT '文件fileKey密文',
    key_version VARCHAR(32) NOT NULL COMMENT '信封密钥版本',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_file_role_sort (buyer_id, file_role, sort_no),
    CONSTRAINT ck_sc_buyer_file_role CHECK (file_role IN ('LICENSE','QUALIFICATION')),
    CONSTRAINT ck_sc_buyer_file_sort CHECK (sort_no >= 0 AND sort_no <= 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商执照与资质文件引用';

ALTER TABLE sc_buyer_project DROP CHECK ck_sc_buyer_project_status;
ALTER TABLE sc_buyer_project
    ADD COLUMN project_type VARCHAR(16) NULL COMMENT '项目类型封闭枚举' AFTER catalog_mode,
    ADD COLUMN start_date DATE NULL COMMENT '项目开始日' AFTER project_type,
    ADD COLUMN end_date DATE NULL COMMENT '项目结束日' AFTER start_date,
    ADD COLUMN settlement_party_type VARCHAR(16) NOT NULL DEFAULT 'DISTRIBUTOR' COMMENT '结算方，本期仅DISTRIBUTOR' AFTER end_date,
    ADD COLUMN description VARCHAR(500) NULL COMMENT '项目说明' AFTER settlement_party_type,
    ADD CONSTRAINT ck_sc_buyer_project_status CHECK (status IN (0, 10, 20, 30, 90)),
    ADD CONSTRAINT ck_sc_buyer_project_type CHECK (
        project_type IS NULL OR project_type IN ('WELFARE','BENEFIT','CAMPAIGN','SUBSIDY','GROUP')),
    ADD CONSTRAINT ck_sc_buyer_project_settlement_party CHECK (
        settlement_party_type IN ('DISTRIBUTOR')),
    ADD CONSTRAINT ck_sc_buyer_project_period CHECK (
        end_date IS NULL OR start_date IS NULL OR end_date >= start_date);

CREATE TABLE sc_platform_settlement_price (
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU主键，逻辑引用sc_platform_sku.id',
    current_version_id BIGINT NULL COMMENT '当前L1版本主键',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (platform_sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台SKU默认结算价身份';

CREATE TABLE sc_platform_settlement_price_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'L1版本主键',
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU主键',
    version_no INT NOT NULL COMMENT '从1递增',
    unit_price_cents BIGINT NOT NULL COMMENT 'L1 B端结算单价，CNY分，允许0',
    currency CHAR(3) NOT NULL COMMENT '币种，一期CNY',
    published_by BIGINT NULL COMMENT '发布人',
    published_at DATETIME(3) NOT NULL COMMENT '发布时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_l1_version (platform_sku_id, version_no),
    CONSTRAINT ck_sc_l1_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_l1_price CHECK (unit_price_cents >= 0),
    CONSTRAINT ck_sc_l1_version_no CHECK (version_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台默认定价不可变版本';

CREATE TABLE sc_buyer_product (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商品身份主键',
    buyer_product_code VARCHAR(32) NOT NULL COMMENT '分销商品稳定业务编码',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU主键',
    display_threshold_percent INT NULL COMMENT '利润空间低于该百分比时C端隐藏，本批不消费',
    status SMALLINT NOT NULL COMMENT '10有效 90已移除',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_product_code (buyer_product_code),
    UNIQUE KEY uk_sc_buyer_product_sku (buyer_id, platform_sku_id),
    KEY idx_sc_buyer_product_buyer (buyer_id, status),
    CONSTRAINT ck_sc_buyer_product_status CHECK (status IN (10, 90)),
    CONSTRAINT ck_sc_buyer_product_threshold CHECK (
        display_threshold_percent IS NULL OR display_threshold_percent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商对平台SKU的分销商品身份';

CREATE TABLE sc_buyer_product_settlement_price (
    buyer_product_id BIGINT NOT NULL COMMENT '分销商品主键',
    current_version_id BIGINT NULL COMMENT '当前模板L2版本，空表示尚无模板',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (buyer_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商品模板L2身份';

CREATE TABLE sc_buyer_product_settlement_price_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '模板L2版本主键',
    buyer_product_id BIGINT NOT NULL COMMENT '分销商品主键',
    version_no INT NOT NULL COMMENT '从1递增',
    unit_price_cents BIGINT NOT NULL COMMENT '模板L2，CNY分',
    currency CHAR(3) NOT NULL COMMENT '币种CNY',
    published_at DATETIME(3) NOT NULL COMMENT '发布时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_l2_template_version (buyer_product_id, version_no),
    CONSTRAINT ck_sc_l2t_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_l2t_price CHECK (unit_price_cents >= 0),
    CONSTRAINT ck_sc_l2t_version_no CHECK (version_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='加价模板发布的L2不可变版本';

CREATE TABLE sc_buyer_product_l2_override (
    buyer_product_id BIGINT NOT NULL COMMENT '分销商品主键',
    current_version_id BIGINT NULL COMMENT '当前手工覆盖版本，空表示跟模板',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (buyer_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商品手工L2覆盖身份';

CREATE TABLE sc_buyer_product_l2_override_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '手工L2覆盖版本主键',
    buyer_product_id BIGINT NOT NULL COMMENT '分销商品主键',
    version_no INT NOT NULL COMMENT '从1递增',
    adjust_mode VARCHAR(16) NOT NULL COMMENT 'EQUAL_L1或CUSTOM',
    percent INT NULL COMMENT 'CUSTOM时的整数百分比，100等于L1',
    amount_cents BIGINT NULL COMMENT 'CUSTOM时加减的CNY分',
    resolved_unit_price_cents BIGINT NOT NULL COMMENT '解析后的覆盖L2，CNY分',
    created_by BIGINT NULL COMMENT '操作人',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_l2o_version (buyer_product_id, version_no),
    CONSTRAINT ck_sc_l2o_mode CHECK (adjust_mode IN ('EQUAL_L1','CUSTOM')),
    CONSTRAINT ck_sc_l2o_price CHECK (resolved_unit_price_cents > 0),
    CONSTRAINT ck_sc_l2o_version_no CHECK (version_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商品手工L2覆盖不可变版本';

CREATE TABLE sc_project_listing (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目商品主键',
    listing_code VARCHAR(32) NOT NULL COMMENT '项目商品稳定业务编码',
    project_id BIGINT NOT NULL COMMENT '采购项目主键，逻辑引用sc_buyer_project.id',
    buyer_product_id BIGINT NOT NULL COMMENT '分销商品主键，逻辑引用sc_buyer_product.id',
    current_version_id BIGINT NULL COMMENT '当前已发布版本',
    status SMALLINT NOT NULL COMMENT '10可采购 90已移除',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_project_listing_code (listing_code),
    UNIQUE KEY uk_sc_project_listing_product (project_id, buyer_product_id),
    KEY idx_sc_project_listing_project (project_id, status),
    CONSTRAINT ck_sc_project_listing_status CHECK (status IN (10, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目对分销商品的采购目录项';

CREATE TABLE sc_project_listing_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目商品版本主键',
    listing_id BIGINT NOT NULL COMMENT '项目商品主键',
    version_no INT NOT NULL COMMENT '从1递增',
    buyer_settlement_unit_price BIGINT NOT NULL COMMENT '冻结的B端结算单价，CNY分',
    settlement_price_level VARCHAR(8) NOT NULL COMMENT 'L1 L2 L3',
    price_source VARCHAR(32) NOT NULL COMMENT 'INHERITED或PROJECT_OVERRIDE',
    l3_adjust_mode VARCHAR(16) NOT NULL COMMENT 'FOLLOW_L2 EQUAL_L1 CUSTOM',
    l3_percent INT NULL COMMENT 'CUSTOM百分比',
    l3_amount_cents BIGINT NULL COMMENT 'CUSTOM加减分',
    currency CHAR(3) NOT NULL COMMENT 'CNY',
    published_by BIGINT NULL COMMENT '发布人',
    published_at DATETIME(3) NOT NULL COMMENT '发布时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_plv (listing_id, version_no),
    CONSTRAINT ck_sc_plv_level CHECK (settlement_price_level IN ('L1','L2','L3')),
    CONSTRAINT ck_sc_plv_source CHECK (price_source IN ('INHERITED','PROJECT_OVERRIDE')),
    CONSTRAINT ck_sc_plv_l3_mode CHECK (l3_adjust_mode IN ('FOLLOW_L2','EQUAL_L1','CUSTOM')),
    CONSTRAINT ck_sc_plv_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_plv_price CHECK (buyer_settlement_unit_price >= 0),
    CONSTRAINT ck_sc_plv_version_no CHECK (version_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目商品不可变结算价版本';
