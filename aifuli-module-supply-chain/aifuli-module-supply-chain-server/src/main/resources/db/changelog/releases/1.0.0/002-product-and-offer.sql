-- 表中文名：平台标准商品SPU
-- 核心职责：维护采购商可理解的标准产品概念和展示主数据
CREATE TABLE sc_platform_spu (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '平台SPU内部主键',
    spu_code VARCHAR(32) NOT NULL COMMENT '平台SPU永久业务编码',
    brand_id BIGINT NOT NULL COMMENT '品牌内部主键，逻辑引用sc_brand.id',
    primary_category_id BIGINT NOT NULL COMMENT '平台主类目主键，逻辑引用sc_product_category.id',
    spu_name VARCHAR(256) NOT NULL COMMENT '平台SPU标准名称',
    subtitle VARCHAR(512) NULL COMMENT '平台SPU副标题',
    cover_url VARCHAR(512) NULL COMMENT '商品封面地址',
    use_instruction TEXT NULL COMMENT '商品使用说明',
    status SMALLINT NOT NULL COMMENT '管理状态：0草稿，10启用，20停用，90已归档',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_spu_code (spu_code),
    KEY idx_sc_spu_brand_status (brand_id, status),
    KEY idx_sc_spu_category_status (primary_category_id, status),
    CONSTRAINT ck_sc_spu_status CHECK (status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台标准商品SPU';

-- 表中文名：平台标准商品SKU
-- 核心职责：维护可精确下单和履约的最小标准商品单元
CREATE TABLE sc_platform_sku (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '平台SKU内部主键',
    sku_code VARCHAR(32) NOT NULL COMMENT '平台SKU永久业务编码',
    spu_id BIGINT NOT NULL COMMENT '所属SPU内部主键，逻辑引用sc_platform_spu.id',
    sku_name VARCHAR(256) NOT NULL COMMENT '平台SKU标准名称',
    fulfillment_type VARCHAR(32) NOT NULL COMMENT '履约类型：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益',
    face_value BIGINT NOT NULL COMMENT '标准面值，单位为分',
    currency CHAR(3) NOT NULL COMMENT '面值币种，一期固定CNY',
    target_schema_code VARCHAR(64) NULL COMMENT '履约目标封闭Schema编码',
    target_schema_version INT NULL COMMENT '履约目标Schema版本',
    result_schema_code VARCHAR(64) NOT NULL COMMENT '履约结果封闭Schema编码',
    result_schema_version INT NOT NULL COMMENT '履约结果Schema版本',
    status SMALLINT NOT NULL COMMENT '管理状态：0草稿，10启用，20停用，90已归档',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_sku_code (sku_code),
    KEY idx_sc_sku_spu_status (spu_id, status),
    KEY idx_sc_sku_fulfillment_status (fulfillment_type, status),
    CONSTRAINT ck_sc_sku_face_value CHECK (face_value >= 0),
    CONSTRAINT ck_sc_sku_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_sku_schema_version CHECK ((target_schema_version IS NULL OR target_schema_version >= 1) AND result_schema_version >= 1),
    CONSTRAINT ck_sc_sku_status CHECK (status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台可下单最小标准商品SKU';

-- 表中文名：采购商商品
-- 核心职责：维护采购商获准采购和销售的平台SKU关系
CREATE TABLE sc_buyer_listing (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商商品内部主键',
    listing_no VARCHAR(32) NOT NULL COMMENT '采购商商品稳定业务编号',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU内部主键，逻辑引用sc_platform_sku.id',
    buyer_sku_code VARCHAR(64) NOT NULL COMMENT '采购商范围内的商品编码',
    current_version_id BIGINT NULL COMMENT '当前已发布采购商商品版本主键',
    status SMALLINT NOT NULL COMMENT '生命周期：0草稿，10已发布，20暂停，90已退役',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_listing_no (listing_no),
    UNIQUE KEY uk_sc_listing_buyer_sku (buyer_id, platform_sku_id),
    UNIQUE KEY uk_sc_listing_buyer_code (buyer_id, buyer_sku_code),
    KEY idx_sc_listing_current_version (current_version_id),
    KEY idx_sc_listing_status (buyer_id, status),
    CONSTRAINT ck_sc_listing_status CHECK (status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商获准采购和销售的平台商品';

-- 表中文名：采购商商品版本
-- 核心职责：保存采购商结算价、购买限制和展示信息的不可变商业版本
CREATE TABLE sc_buyer_listing_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商商品版本内部主键',
    listing_id BIGINT NOT NULL COMMENT '采购商商品内部主键，逻辑引用sc_buyer_listing.id',
    version_no INT NOT NULL COMMENT '采购商商品业务版本号，从1递增',
    buyer_settlement_unit_price BIGINT NOT NULL COMMENT '采购商向中台结算的单位价格，单位为分',
    currency CHAR(3) NOT NULL COMMENT '结算币种，一期固定CNY',
    display_name VARCHAR(256) NOT NULL COMMENT '向采购商商城展示的商品名称',
    min_quantity BIGINT NOT NULL COMMENT '单次最小购买数量',
    max_quantity BIGINT NOT NULL COMMENT '单次最大购买数量',
    valid_from DATETIME(3) NOT NULL COMMENT '版本业务生效时间，UTC',
    valid_to DATETIME(3) NULL COMMENT '版本业务失效时间，UTC，空表示长期有效',
    publish_reason VARCHAR(512) NOT NULL COMMENT '版本发布原因',
    published_by BIGINT NOT NULL COMMENT '发布人用户ID',
    published_at DATETIME(3) NOT NULL COMMENT '版本发布时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '版本记录创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_listing_version (listing_id, version_no),
    KEY idx_sc_listing_version_validity (listing_id, valid_from, valid_to),
    CONSTRAINT ck_sc_listing_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_sc_listing_price CHECK (buyer_settlement_unit_price >= 0),
    CONSTRAINT ck_sc_listing_quantity CHECK (min_quantity > 0 AND max_quantity >= min_quantity),
    CONSTRAINT ck_sc_listing_validity CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_sc_listing_currency CHECK (currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商商品不可变商业版本';

-- 表中文名：采购商商品变更日志
-- 核心职责：为商城提供采购商范围内可增量拉取的商品变化游标
CREATE TABLE sc_buyer_catalog_change (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商商品变更游标，同一采购商范围内单调递增但允许有间隙',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    change_type VARCHAR(32) NOT NULL COMMENT '变更类型：UPSERT新增或更新，TOMBSTONE下架墓碑，AVAILABILITY_CHANGED可用性变化',
    listing_id BIGINT NOT NULL COMMENT '采购商商品内部主键，逻辑引用sc_buyer_listing.id',
    listing_version_id BIGINT NULL COMMENT '相关采购商商品版本主键',
    availability_version BIGINT NULL COMMENT '相关可用性投影版本号',
    occurred_at DATETIME(3) NOT NULL COMMENT '变更发生时间，UTC',
    payload_digest BINARY(32) NOT NULL COMMENT '推送载荷SHA-256摘要',
    PRIMARY KEY (id),
    KEY idx_sc_catalog_change_buyer_cursor (buyer_id, id),
    KEY idx_sc_catalog_change_listing (listing_id, id),
    CONSTRAINT ck_sc_catalog_change_type CHECK (change_type IN ('UPSERT', 'TOMBSTONE', 'AVAILABILITY_CHANGED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商商品增量同步变更日志';

-- 表中文名：供应商供给
-- 核心职责：维护供应商向一个平台SKU提供履约的稳定商业身份
CREATE TABLE sc_supplier_offer (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商报价身份内部主键',
    offer_no VARCHAR(32) NOT NULL COMMENT '供应商报价稳定业务编号',
    offer_code VARCHAR(64) NOT NULL COMMENT '供应商范围内的报价编码',
    supplier_id BIGINT NOT NULL COMMENT '供应商内部主键，逻辑引用sc_supplier.id',
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU内部主键，逻辑引用sc_platform_sku.id',
    current_version_id BIGINT NULL COMMENT '当前已发布报价版本主键',
    status SMALLINT NOT NULL COMMENT '生命周期：0草稿，10已发布，20暂停，90已退役',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_offer_no (offer_no),
    UNIQUE KEY uk_sc_offer_supplier_code (supplier_id, offer_code),
    KEY idx_sc_offer_sku_status (platform_sku_id, status),
    KEY idx_sc_offer_current_version (current_version_id),
    CONSTRAINT ck_sc_offer_status CHECK (status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商面向平台SKU的稳定供给身份';

-- 表中文名：供应商供给版本
-- 核心职责：保存报价、外部商品、执行画像和有效期的不可变供给条件
CREATE TABLE sc_supplier_offer_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商报价版本内部主键',
    offer_id BIGINT NOT NULL COMMENT '供应商报价内部主键，逻辑引用sc_supplier_offer.id',
    version_no INT NOT NULL COMMENT '供应商报价业务版本号，从1递增',
    external_sku_code VARCHAR(128) NOT NULL COMMENT '连接器范围内的供应商外部商品编码',
    supplier_settlement_unit_price BIGINT NOT NULL COMMENT '成功履约一个计价单位的供应商应结单价，单位为分',
    currency CHAR(3) NOT NULL COMMENT '供应商结算币种，一期固定CNY',
    connector_version_id BIGINT NOT NULL COMMENT '执行履约的连接器版本主键',
    inventory_source_id BIGINT NOT NULL COMMENT '供给库存来源主键',
    min_quantity BIGINT NOT NULL COMMENT '供应商允许的最小履约数量',
    max_quantity BIGINT NOT NULL COMMENT '供应商允许的最大履约数量',
    quantity_step BIGINT NOT NULL COMMENT '供应商允许的数量步长',
    primary_capability_code VARCHAR(32) NOT NULL COMMENT '主要履约能力编码',
    completion_mode VARCHAR(32) NOT NULL COMMENT '完成模式：SYNCHRONOUS同步，QUERY查询，CALLBACK回调，QUERY_OR_CALLBACK查询或回调',
    create_idempotency_mode VARCHAR(32) NOT NULL COMMENT '创建幂等模式：NONE无保证，DEDUP_ONLY仅去重，REPLAYABLE_RESULT可重放原结果',
    unknown_result_policy VARCHAR(32) NOT NULL COMMENT '结果未知策略：QUERY_ORDER查单，WAIT_CALLBACK等回调，REPLAY_SAME_KEY原键重放，MANUAL人工',
    partial_delivery_mode VARCHAR(16) NOT NULL COMMENT '部分交付模式：FORBIDDEN禁止，ALLOWED允许',
    result_multiplicity VARCHAR(16) NOT NULL COMMENT '结果资产数量模式：NONE无资产，SINGLE单资产，MULTIPLE多资产',
    fulfillment_config_schema_version INT NOT NULL COMMENT '履约私有配置JSON Schema版本',
    fulfillment_config_json JSON NOT NULL COMMENT '经封闭Schema校验的低频SKU履约参数',
    valid_from DATETIME(3) NOT NULL COMMENT '报价版本生效时间，UTC',
    valid_to DATETIME(3) NULL COMMENT '报价版本失效时间，UTC，空表示长期有效',
    publish_reason VARCHAR(512) NOT NULL COMMENT '报价版本发布原因',
    published_by BIGINT NOT NULL COMMENT '发布人用户ID',
    published_at DATETIME(3) NOT NULL COMMENT '报价版本发布时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '版本记录创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_offer_version (offer_id, version_no),
    KEY idx_sc_offer_version_connector (connector_version_id),
    KEY idx_sc_offer_version_inventory (inventory_source_id),
    KEY idx_sc_offer_version_validity (offer_id, valid_from, valid_to),
    CONSTRAINT ck_sc_offer_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_sc_offer_price CHECK (supplier_settlement_unit_price >= 0),
    CONSTRAINT ck_sc_offer_quantity CHECK (min_quantity > 0 AND max_quantity >= min_quantity AND quantity_step > 0),
    CONSTRAINT ck_sc_offer_validity CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_sc_offer_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_offer_completion CHECK (completion_mode IN ('SYNCHRONOUS', 'QUERY', 'CALLBACK', 'QUERY_OR_CALLBACK')),
    CONSTRAINT ck_sc_offer_idempotency CHECK (create_idempotency_mode IN ('NONE', 'DEDUP_ONLY', 'REPLAYABLE_RESULT')),
    CONSTRAINT ck_sc_offer_unknown CHECK (unknown_result_policy IN ('QUERY_ORDER', 'WAIT_CALLBACK', 'REPLAY_SAME_KEY', 'MANUAL')),
    CONSTRAINT ck_sc_offer_replay CHECK (unknown_result_policy <> 'REPLAY_SAME_KEY' OR create_idempotency_mode = 'REPLAYABLE_RESULT'),
    CONSTRAINT ck_sc_offer_partial CHECK (partial_delivery_mode IN ('FORBIDDEN', 'ALLOWED')),
    CONSTRAINT ck_sc_offer_multiplicity CHECK (result_multiplicity IN ('NONE', 'SINGLE', 'MULTIPLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商供给条件不可变版本';

-- 表中文名：供应商外部商品绑定
-- 核心职责：维护连接器外部商品编码到当前供给版本的唯一投影
CREATE TABLE sc_supplier_external_sku_binding (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商外部商品绑定内部主键',
    connector_id BIGINT NOT NULL COMMENT '连接器内部主键，逻辑引用sc_supplier_connector.id',
    external_sku_code VARCHAR(128) NOT NULL COMMENT '连接器范围内的供应商外部商品编码',
    offer_id BIGINT NOT NULL COMMENT '供应商报价内部主键，逻辑引用sc_supplier_offer.id',
    offer_version_id BIGINT NOT NULL COMMENT '当前报价版本内部主键',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '投影乐观锁版本号',
    updated_at DATETIME(3) NOT NULL COMMENT '投影最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_external_sku_connector (connector_id, external_sku_code),
    UNIQUE KEY uk_sc_external_sku_offer (offer_id),
    KEY idx_sc_external_sku_offer_version (offer_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='当前供应商外部商品编码绑定投影';

-- 表中文名：库存来源
-- 核心职责：维护供应商对平台SKU的实际远程或本地供给来源
CREATE TABLE sc_inventory_source (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '库存来源内部主键',
    source_no VARCHAR(32) NOT NULL COMMENT '库存来源稳定业务编号',
    supplier_id BIGINT NOT NULL COMMENT '供应商内部主键，逻辑引用sc_supplier.id',
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU内部主键，逻辑引用sc_platform_sku.id',
    source_type VARCHAR(32) NOT NULL COMMENT '来源类型：REMOTE_ON_DEMAND远程按需，REMOTE_OBSERVED远程观察，LOCAL_QUANTITY本地数量，LOCAL_ASSET_POOL本地资产池',
    external_source_code VARCHAR(128) NULL COMMENT '供应商外部库存来源编码',
    owner_type VARCHAR(32) NOT NULL COMMENT '库存所有权类型：SUPPLIER供应商，PLATFORM平台，BUYER采购商',
    current_policy_version_id BIGINT NULL COMMENT '当前本地库存策略版本主键，远程来源为空',
    status SMALLINT NOT NULL COMMENT '生命周期：0草稿，10启用，20暂停，90已退役',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_inventory_source_no (source_no),
    KEY idx_sc_inventory_source_supplier_sku (supplier_id, platform_sku_id, status),
    KEY idx_sc_inventory_source_policy (current_policy_version_id),
    CONSTRAINT ck_sc_inventory_source_type CHECK (source_type IN ('REMOTE_ON_DEMAND', 'REMOTE_OBSERVED', 'LOCAL_QUANTITY', 'LOCAL_ASSET_POOL')),
    CONSTRAINT ck_sc_inventory_owner CHECK (owner_type IN ('SUPPLIER', 'PLATFORM', 'BUYER')),
    CONSTRAINT ck_sc_inventory_source_status CHECK (status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台SKU的实际供给库存来源';

-- 表中文名：远程库存观察快照
-- 核心职责：追加记录带有效期的供应商远程库存观察事实
CREATE TABLE sc_inventory_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '远程库存观察快照内部主键',
    inventory_source_id BIGINT NOT NULL COMMENT '库存来源内部主键，逻辑引用sc_inventory_source.id',
    observed_quantity BIGINT NULL COMMENT '供应商观察数量，空表示供应商未返回数量',
    observed_status VARCHAR(32) NOT NULL COMMENT '观察状态：AVAILABLE可用，UNAVAILABLE不可用，UNKNOWN未知，ERROR查询异常',
    observed_at DATETIME(3) NOT NULL COMMENT '供应商库存观察时间，UTC',
    valid_until DATETIME(3) NOT NULL COMMENT '库存观察有效截止时间，UTC',
    supplier_request_no VARCHAR(128) NULL COMMENT '供应商库存查询请求号',
    raw_digest BINARY(32) NOT NULL COMMENT '脱敏原始响应SHA-256摘要',
    created_at DATETIME(3) NOT NULL COMMENT '快照入库时间，UTC',
    PRIMARY KEY (id),
    KEY idx_sc_inventory_snapshot_source_time (inventory_source_id, observed_at),
    KEY idx_sc_inventory_snapshot_validity (inventory_source_id, valid_until),
    CONSTRAINT ck_sc_inventory_snapshot_quantity CHECK (observed_quantity IS NULL OR observed_quantity >= 0),
    CONSTRAINT ck_sc_inventory_snapshot_time CHECK (valid_until > observed_at),
    CONSTRAINT ck_sc_inventory_snapshot_status CHECK (observed_status IN ('AVAILABLE', 'UNAVAILABLE', 'UNKNOWN', 'ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商远程库存追加式观察快照';

-- 表中文名：采购商商品可用性
-- 核心职责：维护采购商商品当前是否具备供给能力的可重建投影
CREATE TABLE sc_buyer_listing_availability (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商商品可用性投影内部主键',
    listing_id BIGINT NOT NULL COMMENT '采购商商品内部主键，逻辑引用sc_buyer_listing.id',
    availability SMALLINT NOT NULL COMMENT '供给可用性：10可用，20不可用，30未知',
    reason_code VARCHAR(64) NOT NULL COMMENT '稳定不可用或未知原因码',
    evaluated_at DATETIME(3) NOT NULL COMMENT '可用性评估时间，UTC',
    basis_version BIGINT NOT NULL COMMENT '本次评估所依据的供给事实版本',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '投影乐观锁版本号',
    updated_at DATETIME(3) NOT NULL COMMENT '投影最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_listing_availability_listing (listing_id),
    KEY idx_sc_listing_availability_state (availability, evaluated_at),
    CONSTRAINT ck_sc_listing_availability CHECK (availability IN (10, 20, 30))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商商品当前供给可用性投影';
