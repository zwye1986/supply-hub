-- 表中文名：采购商
-- 核心职责：维护供应链中台采购主体及其合作状态
CREATE TABLE sc_buyer (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商内部主键',
    buyer_code VARCHAR(32) NOT NULL COMMENT '采购商永久业务编码',
    buyer_name VARCHAR(128) NOT NULL COMMENT '采购商标准名称',
    short_name VARCHAR(64) NOT NULL COMMENT '采购商列表简称',
    buyer_type VARCHAR(32) NOT NULL COMMENT '采购商类型，一期为SAAS_MALL',
    status SMALLINT NOT NULL COMMENT '管理状态：10启用，20暂停，90已归档',
    contact_name VARCHAR(64) NULL COMMENT '运营联系人姓名',
    contact_mobile VARCHAR(32) NULL COMMENT '运营联系人手机号',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_code (buyer_code),
    KEY idx_sc_buyer_status (status),
    CONSTRAINT ck_sc_buyer_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应链采购商';

-- 表中文名：采购商租户绑定
-- 核心职责：把调用来源系统中的租户身份版本化映射为中台采购商
CREATE TABLE sc_buyer_tenant_binding (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商租户绑定内部主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    source_system VARCHAR(32) NOT NULL COMMENT '调用来源系统编码',
    external_tenant_id VARCHAR(128) NOT NULL COMMENT '来源系统中的租户唯一标识',
    binding_version INT NOT NULL COMMENT '同一来源租户的绑定版本号',
    valid_from DATETIME(3) NOT NULL COMMENT '绑定生效时间，UTC',
    valid_to DATETIME(3) NULL COMMENT '绑定失效时间，UTC，空表示未到期',
    current_flag TINYINT NULL COMMENT '当前绑定标记：1当前，NULL历史',
    status SMALLINT NOT NULL COMMENT '绑定状态：10启用，20停用',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_binding_version (source_system, external_tenant_id, binding_version),
    UNIQUE KEY uk_sc_buyer_binding_current (source_system, external_tenant_id, current_flag),
    KEY idx_sc_buyer_binding_buyer (buyer_id, status),
    CONSTRAINT ck_sc_buyer_binding_current CHECK (current_flag IS NULL OR current_flag = 1),
    CONSTRAINT ck_sc_buyer_binding_time CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_sc_buyer_binding_status CHECK (status IN (10, 20))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调用租户与采购商版本化绑定';

-- 表中文名：平台品牌
-- 核心职责：维护标准商品池统一使用的品牌主数据
CREATE TABLE sc_brand (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '品牌内部主键',
    brand_code VARCHAR(32) NOT NULL COMMENT '平台品牌永久编码',
    brand_name VARCHAR(128) NOT NULL COMMENT '品牌中文或标准名称',
    english_name VARCHAR(128) NULL COMMENT '品牌英文名称',
    logo_url VARCHAR(512) NULL COMMENT '品牌Logo地址',
    status SMALLINT NOT NULL COMMENT '管理状态：0草稿，10启用，20停用，90已归档',
    sort INT NOT NULL DEFAULT 0 COMMENT '后台展示排序值，越小越靠前',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_brand_code (brand_code),
    KEY idx_sc_brand_status_sort (status, sort),
    CONSTRAINT ck_sc_brand_status CHECK (status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台统一品牌';

-- 表中文名：品牌别名
-- 核心职责：维护外部系统和历史数据中的品牌名称映射
CREATE TABLE sc_brand_alias (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '品牌别名内部主键',
    brand_id BIGINT NOT NULL COMMENT '品牌内部主键，逻辑引用sc_brand.id',
    source_system VARCHAR(32) NOT NULL COMMENT '别名来源系统编码',
    alias_name VARCHAR(128) NOT NULL COMMENT '外部或历史品牌名称',
    status SMALLINT NOT NULL COMMENT '别名状态：10启用，20停用',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_brand_alias_source_name (source_system, alias_name),
    KEY idx_sc_brand_alias_brand (brand_id, status),
    CONSTRAINT ck_sc_brand_alias_status CHECK (status IN (10, 20))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='品牌外部名称与历史别名';

-- 表中文名：平台商品类目
-- 核心职责：维护标准商品池的树形类目主数据
CREATE TABLE sc_product_category (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '商品类目内部主键',
    category_code VARCHAR(32) NOT NULL COMMENT '平台类目永久编码',
    category_name VARCHAR(128) NOT NULL COMMENT '类目名称',
    parent_id BIGINT NULL COMMENT '父类目内部主键，逻辑引用本表id',
    category_level SMALLINT NOT NULL COMMENT '类目层级，从1开始',
    sort INT NOT NULL DEFAULT 0 COMMENT '同级类目排序值，越小越靠前',
    status SMALLINT NOT NULL COMMENT '管理状态：0草稿，10启用，20停用，90已归档',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_category_code (category_code),
    KEY idx_sc_category_parent_sort (parent_id, sort),
    KEY idx_sc_category_status (status),
    CONSTRAINT ck_sc_category_level CHECK (category_level >= 1),
    CONSTRAINT ck_sc_category_status CHECK (status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台商品类目树';

-- 表中文名：供应商
-- 核心职责：维护标准履约供应商和O2O外部商户主体
CREATE TABLE sc_supplier (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商内部主键',
    supplier_code VARCHAR(32) NOT NULL COMMENT '供应商永久业务编码',
    supplier_name VARCHAR(128) NOT NULL COMMENT '供应商标准名称',
    short_name VARCHAR(64) NOT NULL COMMENT '供应商列表简称',
    supplier_type VARCHAR(32) NOT NULL COMMENT '供应商类型：STANDARD_SUPPLIER标准供应商，O2O_MERCHANT外部商户',
    legal_entity_name VARCHAR(256) NULL COMMENT '供应商签约法律主体名称',
    status SMALLINT NOT NULL COMMENT '管理状态：10启用，20暂停，90已归档',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_code (supplier_code),
    KEY idx_sc_supplier_type_status (supplier_type, status),
    CONSTRAINT ck_sc_supplier_type CHECK (supplier_type IN ('STANDARD_SUPPLIER', 'O2O_MERCHANT')),
    CONSTRAINT ck_sc_supplier_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应链履约供应商或O2O外部商户';

-- 表中文名：供应商渠道商户绑定
-- 核心职责：把O2O渠道外部商户标识版本化映射为中台供应商
CREATE TABLE sc_supplier_channel_binding (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商渠道绑定内部主键',
    channel_code VARCHAR(32) NOT NULL COMMENT 'O2O渠道编码，例如ELEME',
    external_merchant_id VARCHAR(128) NOT NULL COMMENT '渠道中的稳定外部商户标识',
    supplier_id BIGINT NOT NULL COMMENT 'O2O商户供应商主键，逻辑引用sc_supplier.id',
    mapping_version INT NOT NULL COMMENT '同一渠道商户映射版本号',
    valid_from DATETIME(3) NOT NULL COMMENT '映射生效时间，UTC',
    valid_to DATETIME(3) NULL COMMENT '映射失效时间，UTC，空表示未到期',
    current_flag TINYINT NULL COMMENT '当前映射标记：1当前，NULL历史',
    status SMALLINT NOT NULL COMMENT '映射状态：10启用，20停用',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_channel_version (channel_code, external_merchant_id, mapping_version),
    UNIQUE KEY uk_sc_supplier_channel_current (channel_code, external_merchant_id, current_flag),
    KEY idx_sc_supplier_channel_supplier (supplier_id, status),
    CONSTRAINT ck_sc_supplier_channel_current CHECK (current_flag IS NULL OR current_flag = 1),
    CONSTRAINT ck_sc_supplier_channel_time CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_sc_supplier_channel_status CHECK (status IN (10, 20))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='O2O渠道外部商户与供应商版本化绑定';

-- 表中文名：供应商连接器
-- 核心职责：维护供应商技术接入账号和协议实现的稳定身份
CREATE TABLE sc_supplier_connector (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商连接器内部主键',
    connector_no VARCHAR(32) NOT NULL COMMENT '供应商连接器稳定业务编号',
    supplier_id BIGINT NOT NULL COMMENT '供应商内部主键，逻辑引用sc_supplier.id',
    connector_code VARCHAR(64) NOT NULL COMMENT '供应商范围内的连接器编码',
    adapter_type VARCHAR(64) NOT NULL COMMENT '代码中受控的协议适配器类型',
    current_version_id BIGINT NULL COMMENT '当前已发布连接器版本主键',
    status SMALLINT NOT NULL COMMENT '连接器状态：10启用，20暂停，90已归档',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_connector_no (connector_no),
    UNIQUE KEY uk_sc_connector_supplier_code (supplier_id, connector_code),
    KEY idx_sc_connector_supplier_status (supplier_id, status),
    KEY idx_sc_connector_current_version (current_version_id),
    CONSTRAINT ck_sc_connector_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商技术接入连接器稳定身份';

-- 表中文名：供应商凭证槽
-- 核心职责：维护密钥系统中的供应商秘密材料引用，不保存明文凭证
CREATE TABLE sc_credential_slot (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '凭证槽内部主键',
    slot_no VARCHAR(32) NOT NULL COMMENT '凭证槽稳定业务编号',
    provider_type VARCHAR(32) NOT NULL COMMENT '密钥托管类型，例如KMS或ENVELOPE',
    secret_ref VARCHAR(512) NOT NULL COMMENT '密钥系统中的秘密引用，不是秘密明文',
    masked_hint VARCHAR(128) NULL COMMENT '供后台识别的脱敏提示',
    active_secret_version VARCHAR(64) NOT NULL COMMENT '当前秘密材料版本标识',
    status SMALLINT NOT NULL COMMENT '凭证槽状态：10启用，20暂停，90已归档',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_credential_slot_no (slot_no),
    KEY idx_sc_credential_slot_status (status),
    CONSTRAINT ck_sc_credential_slot_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商秘密材料引用槽';

-- 表中文名：供应商连接器版本
-- 核心职责：维护可验证、可发布且发布后不可变的非秘密接入配置
CREATE TABLE sc_supplier_connector_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商连接器版本内部主键',
    connector_id BIGINT NOT NULL COMMENT '连接器内部主键，逻辑引用sc_supplier_connector.id',
    version_no INT NOT NULL COMMENT '连接器业务版本号，从1递增',
    base_url VARCHAR(512) NOT NULL COMMENT '供应商API基础地址',
    merchant_account VARCHAR(128) NULL COMMENT '非秘密供应商商户账号',
    credential_slot_id BIGINT NOT NULL COMMENT '凭证槽主键，逻辑引用sc_credential_slot.id',
    protocol_version VARCHAR(32) NOT NULL COMMENT '供应商协议版本',
    callback_path VARCHAR(256) NULL COMMENT '适配器版本化回调路径',
    connect_timeout_ms INT NOT NULL COMMENT '连接超时时间，毫秒',
    read_timeout_ms INT NOT NULL COMMENT '读取超时时间，毫秒',
    rate_limit INT NULL COMMENT '每秒调用限额，空表示使用平台上限',
    concurrency_limit INT NULL COMMENT '最大并发调用数，空表示使用平台上限',
    adapter_config_schema_version INT NOT NULL COMMENT '适配器配置JSON Schema版本',
    adapter_config_json JSON NOT NULL COMMENT '经封闭Schema校验的低频非秘密配置',
    validation_status SMALLINT NOT NULL COMMENT '验证结果：0未验证，10通过，20失败，30已过期',
    validated_at DATETIME(3) NULL COMMENT '最近验证通过时间，UTC',
    validation_expires_at DATETIME(3) NULL COMMENT '验证结论失效时间，UTC',
    published_at DATETIME(3) NULL COMMENT '版本发布时间，UTC',
    config_digest BINARY(32) NOT NULL COMMENT '规范化配置SHA-256摘要',
    status SMALLINT NOT NULL COMMENT '生命周期：0草稿，5已验证，10已发布，90已退役',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '草稿和状态转换乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_connector_version (connector_id, version_no),
    KEY idx_sc_connector_version_credential (credential_slot_id),
    KEY idx_sc_connector_version_status (connector_id, status),
    CONSTRAINT ck_sc_connector_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_sc_connector_timeout CHECK (connect_timeout_ms > 0 AND read_timeout_ms > 0),
    CONSTRAINT ck_sc_connector_limit CHECK ((rate_limit IS NULL OR rate_limit > 0) AND (concurrency_limit IS NULL OR concurrency_limit > 0)),
    CONSTRAINT ck_sc_connector_validation CHECK (validation_status IN (0, 10, 20, 30)),
    CONSTRAINT ck_sc_connector_version_status CHECK (status IN (0, 5, 10, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商连接器版本化非秘密配置';

-- 表中文名：供应商配置发布记录
-- 核心职责：追加记录连接器配置验证、发布、回滚和退役审计
CREATE TABLE sc_supplier_config_publish_record (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商配置发布审计内部主键',
    record_no VARCHAR(32) NOT NULL COMMENT '配置发布审计业务编号',
    connector_id BIGINT NOT NULL COMMENT '连接器内部主键，逻辑引用sc_supplier_connector.id',
    source_version_id BIGINT NULL COMMENT '回滚时复制的来源连接器版本主键',
    target_version_id BIGINT NOT NULL COMMENT '本次操作目标连接器版本主键',
    action_type VARCHAR(32) NOT NULL COMMENT '操作类型：VALIDATE验证，PUBLISH发布，ROLLBACK回滚发布，RETIRE退役',
    validation_result VARCHAR(32) NULL COMMENT '脱敏验证结果分类',
    operator_id BIGINT NOT NULL COMMENT '执行操作的管理用户ID',
    reason VARCHAR(512) NOT NULL COMMENT '发布、回滚或退役原因',
    config_digest BINARY(32) NOT NULL COMMENT '目标配置SHA-256摘要',
    trace_id VARCHAR(64) NOT NULL COMMENT '链路追踪标识',
    created_at DATETIME(3) NOT NULL COMMENT '审计记录创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_config_publish_record_no (record_no),
    KEY idx_sc_config_publish_connector_time (connector_id, created_at),
    KEY idx_sc_config_publish_target (target_version_id),
    CONSTRAINT ck_sc_config_publish_action CHECK (action_type IN ('VALIDATE', 'PUBLISH', 'ROLLBACK', 'RETIRE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商配置验证与发布不可变审计';
