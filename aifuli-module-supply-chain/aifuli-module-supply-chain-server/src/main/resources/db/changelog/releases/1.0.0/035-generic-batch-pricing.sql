-- 通用批量定价：商品默认、分销商与项目逐组继承/覆盖、不可变结果版本与15分钟报价凭证。
-- 设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md。

CREATE TABLE sc_platform_sku_pricing (
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU主键，逻辑引用sc_platform_sku.id',
    current_version_id BIGINT NULL COMMENT '当前商品默认定价版本主键，空表示未定价',
    pricing_status VARCHAR(32) NOT NULL COMMENT '定价状态：UNPRICED未定价，READY可售，RECALCULATING重算中，DEPENDENCY_MISSING依赖缺失，CALCULATION_FAILED计算失败，MARGIN_HIDDEN利润隐藏',
    unavailable_reason VARCHAR(128) NULL COMMENT '用户端不可售原因编码',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，Asia/Shanghai',
    PRIMARY KEY (platform_sku_id),
    KEY idx_sc_platform_sku_pricing_status (pricing_status),
    CONSTRAINT ck_sc_platform_sku_pricing_status CHECK (pricing_status IN ('UNPRICED','READY','RECALCULATING','DEPENDENCY_MISSING','CALCULATION_FAILED','MARGIN_HIDDEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台SKU通用默认定价身份';

CREATE TABLE sc_platform_sku_pricing_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '平台SKU默认定价版本主键',
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU主键，逻辑引用sc_platform_sku.id',
    version_no INT NOT NULL COMMENT '定价版本号，从1递增',
    effective_config_json JSON NOT NULL COMMENT '商品五组默认定价完整配置JSON',
    market_price_cents BIGINT NOT NULL COMMENT '计算时市场价快照，单位CNY分',
    suggested_sale_price_cents BIGINT NOT NULL COMMENT '计算时销售建议价快照，单位CNY分',
    supply_price_cents BIGINT NULL COMMENT '计算时最低有效供货价快照，单位CNY分',
    distribution_price_cents BIGINT NULL COMMENT '计算后默认商品分销单价，单位CNY分',
    sales_price_cents BIGINT NULL COMMENT '计算后默认商品销售单价，单位CNY分',
    distribution_margin_percent DECIMAL(12,4) NULL COMMENT '单品分销利润率百分数快照',
    effective_hide_threshold_percent DECIMAL(5,2) NULL COMMENT '实际生效的利润隐藏阈值百分数',
    pricing_status VARCHAR(32) NOT NULL COMMENT '该版本定价状态',
    unavailable_reason VARCHAR(128) NULL COMMENT '该版本用户端不可售原因编码',
    currency CHAR(3) NOT NULL COMMENT '币种，一期固定CNY',
    published_by BIGINT NULL COMMENT '发布人用户ID；依赖重算可为空',
    published_at DATETIME(3) NOT NULL COMMENT '发布时间，Asia/Shanghai',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_platform_sku_pricing_version (platform_sku_id, version_no),
    CONSTRAINT ck_sc_platform_sku_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_sc_platform_sku_version_prices CHECK ((distribution_price_cents IS NULL OR distribution_price_cents >= 0) AND (sales_price_cents IS NULL OR sales_price_cents >= 0)),
    CONSTRAINT ck_sc_platform_sku_version_currency CHECK (currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台SKU通用默认定价不可变版本';

CREATE TABLE sc_buyer_sku_pricing (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商SKU定价身份主键',
    buyer_product_id BIGINT NOT NULL COMMENT '分销商品主键，逻辑引用sc_buyer_product.id',
    current_version_id BIGINT NULL COMMENT '当前通用定价版本主键，空表示未定价',
    pricing_status VARCHAR(32) NOT NULL COMMENT '定价状态：UNPRICED未定价，READY可售，RECALCULATING重算中，DEPENDENCY_MISSING依赖缺失，CALCULATION_FAILED计算失败，MARGIN_HIDDEN利润隐藏',
    unavailable_reason VARCHAR(128) NULL COMMENT '用户端不可售原因编码',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_sku_pricing_product (buyer_product_id),
    KEY idx_sc_buyer_sku_pricing_status (pricing_status),
    CONSTRAINT ck_sc_buyer_sku_pricing_status CHECK (pricing_status IN ('UNPRICED','READY','RECALCULATING','DEPENDENCY_MISSING','CALCULATION_FAILED','MARGIN_HIDDEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商SKU通用定价身份';

CREATE TABLE sc_buyer_sku_pricing_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商SKU通用定价版本主键',
    pricing_id BIGINT NOT NULL COMMENT '分销商SKU定价身份主键，逻辑引用sc_buyer_sku_pricing.id',
    version_no INT NOT NULL COMMENT '定价版本号，从1递增',
    platform_pricing_version_id BIGINT NOT NULL COMMENT '解析时引用的商品默认定价版本主键',
    override_json JSON NOT NULL COMMENT '分销商五组继承开关及覆盖规则JSON',
    effective_config_json JSON NOT NULL COMMENT '继承合并后的完整有效配置JSON',
    distribution_rule_json JSON NOT NULL COMMENT '分销价规则快照JSON',
    sales_rule_json JSON NOT NULL COMMENT '销售价规则快照JSON',
    service_fee_rule_json JSON NOT NULL COMMENT '服务费规则与文案快照JSON',
    hide_rule_json JSON NOT NULL COMMENT '分销区和销售区利润隐藏阈值快照JSON',
    tax_metadata_json JSON NOT NULL COMMENT '税务展示属性快照JSON',
    market_price_cents BIGINT NOT NULL COMMENT '计算时市场价快照，单位CNY分',
    suggested_sale_price_cents BIGINT NOT NULL COMMENT '计算时销售建议价快照，单位CNY分',
    supply_price_cents BIGINT NULL COMMENT '计算时最低有效供货价快照，单位CNY分；无有效报价时为空',
    distribution_price_cents BIGINT NULL COMMENT '计算后的商品分销单价，单位CNY分；依赖缺失时为空',
    sales_price_cents BIGINT NULL COMMENT '计算后的商品销售单价，单位CNY分；依赖缺失时为空',
    distribution_margin_percent DECIMAL(12,4) NULL COMMENT '单品分销利润率百分数快照',
    effective_hide_threshold_percent DECIMAL(5,2) NULL COMMENT '实际生效的利润隐藏阈值百分数',
    pricing_status VARCHAR(32) NOT NULL COMMENT '该版本定价状态',
    unavailable_reason VARCHAR(128) NULL COMMENT '该版本用户端不可售原因编码',
    currency CHAR(3) NOT NULL COMMENT '币种，一期固定CNY',
    published_by BIGINT NULL COMMENT '发布人用户ID；依赖重算可为空',
    published_at DATETIME(3) NOT NULL COMMENT '发布时间，Asia/Shanghai',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_sku_pricing_version (pricing_id, version_no),
    CONSTRAINT ck_sc_buyer_sku_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_sc_buyer_sku_version_prices CHECK ((distribution_price_cents IS NULL OR distribution_price_cents >= 0) AND (sales_price_cents IS NULL OR sales_price_cents >= 0)),
    CONSTRAINT ck_sc_buyer_sku_version_currency CHECK (currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商SKU通用定价不可变版本';

CREATE TABLE sc_project_sku_pricing (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目SKU定价身份主键',
    listing_id BIGINT NOT NULL COMMENT '项目商品主键，逻辑引用sc_project_listing.id',
    current_version_id BIGINT NULL COMMENT '当前项目综合定价版本主键',
    pricing_status VARCHAR(32) NOT NULL COMMENT '项目SKU定价状态',
    unavailable_reason VARCHAR(128) NULL COMMENT '用户端不可售原因编码',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_project_sku_pricing_listing (listing_id),
    KEY idx_sc_project_sku_pricing_status (pricing_status),
    CONSTRAINT ck_sc_project_sku_pricing_status CHECK (pricing_status IN ('UNPRICED','READY','RECALCULATING','DEPENDENCY_MISSING','CALCULATION_FAILED','MARGIN_HIDDEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目SKU通用定价身份';

CREATE TABLE sc_project_sku_pricing_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目SKU综合定价版本主键',
    pricing_id BIGINT NOT NULL COMMENT '项目SKU定价身份主键，逻辑引用sc_project_sku_pricing.id',
    version_no INT NOT NULL COMMENT '定价版本号，从1递增',
    buyer_pricing_version_id BIGINT NOT NULL COMMENT '继承时引用的分销商SKU定价版本主键',
    override_json JSON NOT NULL COMMENT '项目五组覆盖开关及覆盖规则JSON',
    effective_config_json JSON NOT NULL COMMENT '继承合并后的完整有效配置JSON',
    market_price_cents BIGINT NOT NULL COMMENT '计算时市场价快照，单位CNY分',
    suggested_sale_price_cents BIGINT NOT NULL COMMENT '计算时销售建议价快照，单位CNY分',
    supply_price_cents BIGINT NULL COMMENT '计算时最低有效供货价快照，单位CNY分',
    distribution_price_cents BIGINT NULL COMMENT '项目有效商品分销单价，单位CNY分',
    sales_price_cents BIGINT NULL COMMENT '项目有效商品销售单价，单位CNY分',
    distribution_margin_percent DECIMAL(12,4) NULL COMMENT '单品分销利润率百分数快照',
    effective_hide_threshold_percent DECIMAL(5,2) NULL COMMENT '实际生效的利润隐藏阈值百分数',
    pricing_status VARCHAR(32) NOT NULL COMMENT '该版本项目定价状态',
    unavailable_reason VARCHAR(128) NULL COMMENT '该版本用户端不可售原因编码',
    currency CHAR(3) NOT NULL COMMENT '币种，一期固定CNY',
    published_by BIGINT NULL COMMENT '发布人用户ID；依赖重算可为空',
    published_at DATETIME(3) NOT NULL COMMENT '发布时间，Asia/Shanghai',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_project_sku_pricing_version (pricing_id, version_no),
    CONSTRAINT ck_sc_project_sku_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_sc_project_sku_version_prices CHECK ((distribution_price_cents IS NULL OR distribution_price_cents >= 0) AND (sales_price_cents IS NULL OR sales_price_cents >= 0)),
    CONSTRAINT ck_sc_project_sku_version_currency CHECK (currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目SKU综合定价不可变版本';

CREATE TABLE sc_standard_pricing_quote (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '标准商品报价凭证主键',
    quote_token VARCHAR(64) NOT NULL COMMENT '随机不可猜测报价凭证',
    source_system VARCHAR(64) NOT NULL COMMENT '调用来源系统编码',
    external_tenant_id VARCHAR(128) NOT NULL COMMENT '来源系统外部租户标识',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键',
    project_id BIGINT NOT NULL COMMENT '采购项目内部主键',
    listing_id BIGINT NOT NULL COMMENT '项目商品主键',
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU内部主键',
    platform_sku_code VARCHAR(64) NOT NULL COMMENT '平台SKU永久业务编码',
    project_pricing_version_id BIGINT NOT NULL COMMENT '冻结的项目SKU综合定价版本主键',
    buyer_user_id VARCHAR(128) NOT NULL COMMENT '项目范围内用户标识；一期为规范化后的大陆手机号',
    buyer_order_no VARCHAR(128) NOT NULL COMMENT '采购商订单号',
    quantity BIGINT NOT NULL COMMENT '报价商品数量',
    distribution_unit_price_cents BIGINT NOT NULL COMMENT '冻结的商品分销单价，单位CNY分',
    sales_unit_price_cents BIGINT NOT NULL COMMENT '冻结的商品销售单价，单位CNY分',
    product_subtotal_cents BIGINT NOT NULL COMMENT '冻结的商品小计，单位CNY分',
    service_fee_cents BIGINT NOT NULL COMMENT '冻结的平台服务费，单位CNY分',
    user_payable_amount_cents BIGINT NOT NULL COMMENT '冻结的用户应付总额，单位CNY分',
    tax_metadata_json JSON NOT NULL COMMENT '冻结的税务展示属性JSON',
    service_fee_copy VARCHAR(256) NULL COMMENT '冻结的服务费展示文案；隐藏文案时为空',
    status VARCHAR(16) NOT NULL COMMENT '凭证状态：ISSUED已签发，CONSUMED已消费',
    expires_at DATETIME(3) NOT NULL COMMENT '凭证失效时间，Asia/Shanghai',
    consumed_order_id BIGINT NULL COMMENT '消费该凭证的供应链订单主键',
    consumed_at DATETIME(3) NULL COMMENT '凭证消费时间，Asia/Shanghai',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_standard_quote_token (quote_token),
    KEY idx_sc_standard_quote_order (source_system, external_tenant_id, buyer_order_no, id),
    KEY idx_sc_standard_quote_expiry (status, expires_at),
    CONSTRAINT ck_sc_standard_quote_status CHECK (status IN ('ISSUED','CONSUMED')),
    CONSTRAINT ck_sc_standard_quote_amount CHECK (quantity > 0 AND distribution_unit_price_cents >= 0 AND sales_unit_price_cents >= 0 AND product_subtotal_cents >= 0 AND service_fee_cents >= 0 AND user_payable_amount_cents >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准商品15分钟项目报价凭证';

ALTER TABLE sc_supply_chain_order
    ADD COLUMN pricing_quote_id BIGINT NULL COMMENT '标准商品报价凭证主键，非标准场景为空' AFTER project_id,
    ADD COLUMN pricing_version_id BIGINT NULL COMMENT '冻结的项目SKU综合定价版本主键，非标准场景为空' AFTER pricing_quote_id,
    ADD COLUMN sales_unit_price_cents BIGINT NULL COMMENT '冻结的商品销售单价，单位CNY分' AFTER buyer_settlement_amount,
    ADD COLUMN product_subtotal_cents BIGINT NULL COMMENT '冻结的商品小计，单位CNY分' AFTER sales_unit_price_cents,
    ADD COLUMN service_fee_cents BIGINT NULL COMMENT '冻结的平台服务费，单位CNY分' AFTER product_subtotal_cents,
    ADD COLUMN user_payable_amount_cents BIGINT NULL COMMENT '冻结的用户应付总额，单位CNY分' AFTER service_fee_cents,
    ADD COLUMN refundable_service_fee_cents BIGINT NULL COMMENT '标准商品后续售后可全额退还的服务费快照，单位CNY分' AFTER user_payable_amount_cents,
    ADD COLUMN tax_metadata_json JSON NULL COMMENT '冻结的税务展示属性JSON' AFTER refundable_service_fee_cents,
    ADD CONSTRAINT ck_sc_order_pricing_amount CHECK ((sales_unit_price_cents IS NULL OR sales_unit_price_cents >= 0) AND (product_subtotal_cents IS NULL OR product_subtotal_cents >= 0) AND (service_fee_cents IS NULL OR service_fee_cents >= 0) AND (user_payable_amount_cents IS NULL OR user_payable_amount_cents >= 0) AND (refundable_service_fee_cents IS NULL OR refundable_service_fee_cents >= 0));

ALTER TABLE sc_platform_sku
    MODIFY COLUMN tax_rate DECIMAL(6,4) NULL COMMENT '旧商品税率展示字段，如0.0600表示6%；不参与价格计算；标准商品结算以商品默认/分销商/项目通用定价版本为准',
    MODIFY COLUMN tax_included TINYINT(1) NOT NULL DEFAULT 0 COMMENT '旧商品含税展示字段：1含税，0不含税；不参与价格计算；标准商品结算以商品默认/分销商/项目通用定价版本为准';
