# 供应链中台一期数据库 DDL

> 采购商项目化目标表与约束见[《供应链中台采购商主体与采购项目详细设计》](./2026-08-21-supply-chain-buyer-project-design.md#12-模块边界与目标数据模型)。本文记录一期既有 DDL 基线，不再代表 Buyer/Listing/集成/订单身份的最终目标模型。
>
> PRD 对齐新增表（L1/L2 价格版本、加价模板、CARD 售后、路由 `scope_type`/`ranking_mode`）以前向 Liquibase 变更集落地，语义以[《供应链中台 PRD 对齐详细设计》](./2026-08-28-supply-chain-prd-alignment-design.md)为准，不回改本文已发布 SQL。

## 1. 文档定位

本文是[《供应链中台一期详细设计》](./2026-08-07-supply-chain-phase1-detailed-design.md)的数据库实现伴随文档，集中维护一期 Liquibase 叶子变更集对应的完整 MySQL 8 DDL。详细设计只描述数据库发布原则并链接本文，避免同一份 DDL 在多个文档中重复维护。

## 2. 通用约定

本文是一期字段、类型、约束和索引的历史实现基线，DDL 面向 MySQL 8，并按 Liquibase formatted SQL 叶子文件分组。所有内部主键均为 `BIGINT AUTO_INCREMENT`，金额单位为分，时间以 `Asia/Shanghai` 写入 `DATETIME(3)`。表之间只建立逻辑引用和查询索引，不创建物理外键。

> 2026-09-04 时间语义修订：为保持已执行 Liquibase checksum 稳定，本文后续历史 DDL 摘录及
> `001`—`021` 仍保留当时的“UTC”字段说明；新增 `022-database-local-time` 负责将最终数据与列说明
> 原地切换为 `Asia/Shanghai`。完整字段清单和时钟来源以
> [供应链数据库本地时间统一设计](2026-09-04-supply-chain-database-local-time-design.md) 为准。

以下预留模型不进入本文和一期 Liquibase：`sc_buyer_account_funding_order`、`sc_inventory_balance`、`sc_inventory_transaction`、`sc_inventory_batch`、`sc_inventory_asset`、`sc_inventory_policy_version`、`sc_supply_reservation`、`sc_settlement_contract`、`sc_settlement_contract_version`、`sc_settlement_quote`、`sc_legacy_id_mapping` 和 `sc_fulfillment_ext`。

## 3. `001-identity-and-master.sql`

```sql
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
```

## 4. `002-product-and-offer.sql`

```sql
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
```

## 5. `003-account-and-ledger.sql`

`003` 按首次发布原文保留，含账户 `balance >= 0 AND frozen >= 0 AND balance >= frozen`，以及账本 `balance_* >= 0`、`balance >= frozen`、`DEPOSIT` 要求 `balance_delta > 0`。一期弱化余额门槛后的有效约束由 `011-buyer-account-overdraft.sql` 前向替换，见第 13 节。

```sql
-- 表中文名：采购商资金账户
-- 核心职责：维护采购商预付账户余额和冻结金额的唯一汇总
CREATE TABLE sc_buyer_account (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商资金账户内部主键',
    account_no VARCHAR(32) NOT NULL COMMENT '采购商资金账户稳定业务编号',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    currency CHAR(3) NOT NULL COMMENT '账户币种，一期固定CNY',
    balance BIGINT NOT NULL DEFAULT 0 COMMENT '账户总余额，单位为分',
    frozen BIGINT NOT NULL DEFAULT 0 COMMENT '账户已冻结金额，单位为分',
    status SMALLINT NOT NULL COMMENT '账户状态：10正常，20冻结新交易，90已关闭',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '余额并发更新乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_account_no (account_no),
    UNIQUE KEY uk_sc_buyer_account_currency (buyer_id, currency),
    KEY idx_sc_buyer_account_status (status),
    CONSTRAINT ck_sc_buyer_account_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_buyer_account_amount CHECK (balance >= 0 AND frozen >= 0 AND balance >= frozen),
    CONSTRAINT ck_sc_buyer_account_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商预付资金账户唯一余额汇总';

-- 表中文名：采购商账户流水
-- 核心职责：追加记录采购商账户每次余额和冻结金额变化
CREATE TABLE sc_account_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商账户流水内部主键',
    ledger_no VARCHAR(32) NOT NULL COMMENT '账户流水稳定业务编号',
    account_id BIGINT NOT NULL COMMENT '采购商资金账户主键，逻辑引用sc_buyer_account.id',
    entry_key VARCHAR(128) NOT NULL COMMENT '账户范围内业务幂等键',
    entry_type VARCHAR(32) NOT NULL COMMENT '流水类型：DEPOSIT入金或期初余额，FREEZE冻结，COMMIT确认扣款，RELEASE解冻，REFUND返还，ADJUSTMENT调账',
    balance_delta BIGINT NOT NULL COMMENT '本次总余额变化金额，单位为分，可正可负',
    frozen_delta BIGINT NOT NULL COMMENT '本次冻结金额变化，单位为分，可正可负',
    balance_before BIGINT NOT NULL COMMENT '变更前账户总余额，单位为分',
    balance_after BIGINT NOT NULL COMMENT '变更后账户总余额，单位为分',
    frozen_before BIGINT NOT NULL COMMENT '变更前账户冻结金额，单位为分',
    frozen_after BIGINT NOT NULL COMMENT '变更后账户冻结金额，单位为分',
    reservation_no VARCHAR(32) NULL COMMENT '关联资金预留业务编号',
    order_no VARCHAR(32) NULL COMMENT '关联供应链订单业务编号',
    reference_type VARCHAR(32) NOT NULL COMMENT '触发流水的业务引用类型',
    reference_no VARCHAR(64) NOT NULL COMMENT '触发流水的业务引用编号',
    operator_id BIGINT NULL COMMENT '人工触发资金命令的操作人用户ID，自动流水为空',
    evidence_ref VARCHAR(512) NULL COMMENT '期初余额或人工资金操作的受控证据引用',
    evidence_digest BINARY(32) NULL COMMENT '期初余额或人工资金操作证据SHA-256摘要',
    audit_note VARCHAR(1024) NULL COMMENT '期初余额或人工资金操作的脱敏审计说明',
    trace_id VARCHAR(64) NOT NULL COMMENT '链路追踪标识',
    causation_id VARCHAR(64) NULL COMMENT '触发本流水的命令或事件标识',
    occurred_at DATETIME(3) NOT NULL COMMENT '资金事实发生时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '流水入库时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_account_ledger_no (ledger_no),
    UNIQUE KEY uk_sc_account_ledger_entry (account_id, entry_key),
    KEY idx_sc_account_ledger_order (order_no, id),
    KEY idx_sc_account_ledger_reservation (reservation_no, id),
    KEY idx_sc_account_ledger_reference (reference_type, reference_no),
    CONSTRAINT ck_sc_account_ledger_balance CHECK (balance_before >= 0 AND balance_after >= 0),
    CONSTRAINT ck_sc_account_ledger_frozen CHECK (frozen_before >= 0 AND frozen_after >= 0 AND balance_before >= frozen_before AND balance_after >= frozen_after),
    CONSTRAINT ck_sc_account_ledger_delta CHECK (balance_after = balance_before + balance_delta AND frozen_after = frozen_before + frozen_delta),
    CONSTRAINT ck_sc_account_ledger_type CHECK (entry_type IN ('DEPOSIT', 'FREEZE', 'COMMIT', 'RELEASE', 'REFUND', 'ADJUSTMENT')),
    CONSTRAINT ck_sc_account_ledger_entry_semantics CHECK ((entry_type = 'DEPOSIT' AND balance_delta > 0 AND frozen_delta = 0) OR (entry_type = 'FREEZE' AND balance_delta = 0 AND frozen_delta > 0) OR (entry_type = 'COMMIT' AND balance_delta < 0 AND frozen_delta = balance_delta) OR (entry_type = 'RELEASE' AND balance_delta = 0 AND frozen_delta < 0) OR (entry_type = 'REFUND' AND balance_delta > 0 AND frozen_delta = 0) OR (entry_type = 'ADJUSTMENT' AND balance_delta <> 0 AND frozen_delta = 0)),
    CONSTRAINT ck_sc_account_ledger_deposit_audit CHECK (entry_type <> 'DEPOSIT' OR (operator_id IS NOT NULL AND evidence_ref IS NOT NULL AND evidence_digest IS NOT NULL AND audit_note IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商账户不可变资金流水';

-- 表中文名：资金预留
-- 核心职责：维护订单资金冻结、确认扣款、释放和返还的守恒事实
CREATE TABLE sc_fund_reservation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '资金预留内部主键',
    reservation_no VARCHAR(32) NOT NULL COMMENT '资金预留稳定业务编号',
    account_id BIGINT NOT NULL COMMENT '采购商资金账户主键，逻辑引用sc_buyer_account.id',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    reference_type VARCHAR(32) NOT NULL COMMENT '资金预留业务引用类型：CARD_ORDER或O2O_ORDER',
    reference_no VARCHAR(64) NOT NULL COMMENT '资金预留业务引用编号',
    total_amount BIGINT NOT NULL COMMENT '初始预留总金额，单位为分',
    committed_amount BIGINT NOT NULL DEFAULT 0 COMMENT '已确认扣款金额，单位为分',
    released_amount BIGINT NOT NULL DEFAULT 0 COMMENT '已释放或返还金额，单位为分',
    remaining_amount BIGINT NOT NULL COMMENT '仍冻结等待处理的金额，单位为分',
    currency CHAR(3) NOT NULL COMMENT '预留币种，一期固定CNY',
    status SMALLINT NOT NULL COMMENT '预留状态：10已预留，20部分确认，30已确认，40已释放，50部分确认后释放，90人工处理',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '状态和金额并发更新乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '资金预留创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '资金预留最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_fund_reservation_no (reservation_no),
    UNIQUE KEY uk_sc_fund_reservation_reference (buyer_id, reference_type, reference_no),
    KEY idx_sc_fund_reservation_account_status (account_id, status),
    CONSTRAINT ck_sc_fund_reservation_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_fund_reservation_amount CHECK (total_amount >= 0 AND committed_amount >= 0 AND released_amount >= 0 AND remaining_amount >= 0 AND total_amount = committed_amount + released_amount + remaining_amount),
    CONSTRAINT ck_sc_fund_reservation_status CHECK (status IN (10, 20, 30, 40, 50, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商订单资金冻结与确认事实';
```

## 6. `004-routing-order-and-fulfillment.sql`

```sql
-- 表中文名：供应商路由策略
-- 核心职责：维护标准商品或场景供应商路由策略的稳定身份与启停状态
CREATE TABLE sc_supplier_routing_policy (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商路由策略内部主键',
    policy_code VARCHAR(64) NOT NULL COMMENT '供应商路由策略稳定业务编码',
    policy_name VARCHAR(128) NOT NULL COMMENT '供应商路由策略中文名称',
    routing_type VARCHAR(32) NOT NULL COMMENT '路由类型：STANDARD_PRODUCT标准商品供给路由，SCENARIO场景供应商路由',
    status SMALLINT NOT NULL COMMENT '管理状态：10启用，20停用，90已归档',
    active_flag TINYINT NULL COMMENT '同一路由类型当前启用标记：1启用，NULL停用或归档',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '策略身份乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '策略创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '策略最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_routing_policy_code (policy_code),
    UNIQUE KEY uk_sc_routing_policy_active (routing_type, active_flag),
    KEY idx_sc_routing_policy_type_status (routing_type, status),
    CONSTRAINT ck_sc_routing_policy_type CHECK (routing_type IN ('STANDARD_PRODUCT', 'SCENARIO')),
    CONSTRAINT ck_sc_routing_policy_status CHECK (status IN (10, 20, 90)),
    CONSTRAINT ck_sc_routing_policy_active CHECK ((status = 10 AND active_flag IS NOT NULL AND active_flag = 1) OR (status IN (20, 90) AND active_flag IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商路由策略稳定身份与启停状态';

-- 表中文名：供应商路由策略版本
-- 核心职责：维护发布后不可变、按业务时间生效的路由规则版本
CREATE TABLE sc_supplier_routing_policy_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商路由策略版本内部主键',
    policy_id BIGINT NOT NULL COMMENT '供应商路由策略主键，逻辑引用sc_supplier_routing_policy.id',
    version_no INT NOT NULL COMMENT '策略业务版本号，从1递增',
    version_name VARCHAR(128) NOT NULL COMMENT '便于运营识别的版本名称',
    valid_from DATETIME(3) NOT NULL COMMENT '策略版本业务生效时间，UTC',
    valid_to DATETIME(3) NULL COMMENT '策略版本业务失效时间，UTC，空表示长期有效',
    config_digest BINARY(32) NOT NULL COMMENT '版本内规则与候选规范化SHA-256摘要',
    status SMALLINT NOT NULL COMMENT '版本生命周期：0草稿，10已发布，90已退役',
    publish_reason VARCHAR(512) NULL COMMENT '版本发布或退役原因，草稿可为空',
    published_by BIGINT NULL COMMENT '发布人用户ID，草稿为空',
    published_at DATETIME(3) NULL COMMENT '版本发布时间，UTC，草稿为空',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '草稿编辑和发布状态乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '版本创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '版本最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_routing_policy_version (policy_id, version_no),
    KEY idx_sc_routing_policy_effective (policy_id, status, valid_from, valid_to),
    CONSTRAINT ck_sc_routing_policy_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_sc_routing_policy_validity CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_sc_routing_policy_version_status CHECK (status IN (0, 10, 90)),
    CONSTRAINT ck_sc_routing_policy_publish CHECK ((status = 0 AND published_by IS NULL AND published_at IS NULL) OR (status IN (10, 90) AND published_by IS NOT NULL AND published_at IS NOT NULL AND publish_reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发布后不可变的供应商路由策略版本';

-- 表中文名：标准商品路由规则
-- 核心职责：维护采购商、平台商品、履约类型和数量区间的强类型匹配条件
CREATE TABLE sc_standard_product_routing_rule (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '标准商品路由规则内部主键',
    policy_version_id BIGINT NOT NULL COMMENT '路由策略版本主键，逻辑引用sc_supplier_routing_policy_version.id',
    rule_code VARCHAR(64) NOT NULL COMMENT '策略版本内稳定规则编码',
    rule_name VARCHAR(128) NOT NULL COMMENT '标准商品路由规则中文名称',
    buyer_id BIGINT NULL COMMENT '采购商内部主键，空表示全局规则',
    platform_sku_id BIGINT NULL COMMENT '平台SKU内部主键，空表示不限定具体平台SKU',
    fulfillment_type VARCHAR(32) NULL COMMENT '履约类型条件：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益',
    min_quantity BIGINT NULL COMMENT '命中规则的最小数量，空表示不限定数量区间',
    max_quantity BIGINT NULL COMMENT '命中规则的最大数量，空表示不限定数量区间',
    rule_priority INT NOT NULL COMMENT '同特异性规则优先级，数值越小越优先',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '草稿规则乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '规则创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '规则最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_standard_rule_code (policy_version_id, rule_code),
    KEY idx_sc_standard_rule_match (policy_version_id, buyer_id, platform_sku_id, fulfillment_type, rule_priority),
    CONSTRAINT ck_sc_standard_rule_fulfillment CHECK (fulfillment_type IS NULL OR fulfillment_type IN ('DIRECT_RECHARGE', 'VOUCHER_CODE', 'CARD_CODE_SECRET', 'VOUCHER_URL', 'ACCOUNT_BOUND_BENEFIT')),
    CONSTRAINT ck_sc_standard_rule_quantity CHECK ((min_quantity IS NULL AND max_quantity IS NULL) OR (min_quantity IS NOT NULL AND max_quantity IS NOT NULL AND min_quantity > 0 AND max_quantity >= min_quantity)),
    CONSTRAINT ck_sc_standard_rule_priority CHECK (rule_priority >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准商品供给路由的强类型匹配规则';

-- 表中文名：标准商品路由候选
-- 核心职责：维护标准商品规则下的供应商顺序与动态事实检查策略
CREATE TABLE sc_standard_product_routing_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '标准商品路由候选内部主键',
    rule_id BIGINT NOT NULL COMMENT '标准商品路由规则主键，逻辑引用sc_standard_product_routing_rule.id',
    supplier_id BIGINT NOT NULL COMMENT '候选供应商内部主键，运行时解析有效SupplierOfferVersion',
    candidate_priority INT NOT NULL COMMENT '规则内候选优先级，数值越小越优先',
    inventory_check_mode VARCHAR(16) NOT NULL COMMENT '库存检查模式：REQUIRED必需，OPTIONAL可选，UNSUPPORTED不支持',
    inventory_max_age_seconds INT NULL COMMENT '库存事实最大允许年龄秒数，不支持时为空',
    balance_check_mode VARCHAR(16) NOT NULL COMMENT '供应商余额检查模式：REQUIRED必需，OPTIONAL可选，UNSUPPORTED不支持',
    balance_max_age_seconds INT NULL COMMENT '供应商余额事实最大允许年龄秒数，不支持时为空',
    health_check_mode VARCHAR(16) NOT NULL COMMENT '供应商健康检查模式：REQUIRED必需，OPTIONAL可选，UNSUPPORTED不支持',
    health_max_age_seconds INT NULL COMMENT '供应商健康事实最大允许年龄秒数，不支持时为空',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '草稿候选配置乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '候选配置创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '候选配置最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_standard_candidate_supplier (rule_id, supplier_id),
    KEY idx_sc_standard_candidate_order (rule_id, candidate_priority, supplier_id),
    KEY idx_sc_standard_candidate_supplier (supplier_id),
    CONSTRAINT ck_sc_standard_candidate_priority CHECK (candidate_priority >= 0),
    CONSTRAINT ck_sc_standard_inventory_mode CHECK ((inventory_check_mode = 'UNSUPPORTED' AND inventory_max_age_seconds IS NULL) OR (inventory_check_mode IN ('REQUIRED', 'OPTIONAL') AND inventory_max_age_seconds > 0)),
    CONSTRAINT ck_sc_standard_balance_mode CHECK ((balance_check_mode = 'UNSUPPORTED' AND balance_max_age_seconds IS NULL) OR (balance_check_mode IN ('REQUIRED', 'OPTIONAL') AND balance_max_age_seconds > 0)),
    CONSTRAINT ck_sc_standard_health_mode CHECK ((health_check_mode = 'UNSUPPORTED' AND health_max_age_seconds IS NULL) OR (health_check_mode IN ('REQUIRED', 'OPTIONAL') AND health_max_age_seconds > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准商品路由的供应商候选与检查策略';

-- 表中文名：场景供应商路由规则
-- 核心职责：维护采购商、业务场景和外部商户的强类型匹配条件
CREATE TABLE sc_scenario_routing_rule (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '场景供应商路由规则内部主键',
    policy_version_id BIGINT NOT NULL COMMENT '路由策略版本主键，逻辑引用sc_supplier_routing_policy_version.id',
    rule_code VARCHAR(64) NOT NULL COMMENT '策略版本内稳定规则编码',
    rule_name VARCHAR(128) NOT NULL COMMENT '场景供应商路由规则中文名称',
    buyer_id BIGINT NULL COMMENT '采购商内部主键，空表示全局规则',
    scenario_code VARCHAR(32) NOT NULL COMMENT '受控业务场景编码，例如DINING点餐、MOVIE电影',
    external_merchant_id VARCHAR(128) NULL COMMENT '场景子系统稳定外部商户标识，空表示场景通用规则',
    rule_priority INT NOT NULL COMMENT '同特异性规则优先级，数值越小越优先',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '草稿规则乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '规则创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '规则最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_scenario_rule_code (policy_version_id, rule_code),
    KEY idx_sc_scenario_rule_match (policy_version_id, buyer_id, scenario_code, external_merchant_id, rule_priority),
    CONSTRAINT ck_sc_scenario_rule_code CHECK (scenario_code <> ''),
    CONSTRAINT ck_sc_scenario_rule_priority CHECK (rule_priority >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='点餐、电影等场景供应商路由强类型规则';

-- 表中文名：场景供应商路由候选
-- 核心职责：维护场景路由规则下的供应商顺序与健康检查策略
CREATE TABLE sc_scenario_routing_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '场景供应商路由候选内部主键',
    rule_id BIGINT NOT NULL COMMENT '场景供应商路由规则主键，逻辑引用sc_scenario_routing_rule.id',
    supplier_id BIGINT NOT NULL COMMENT '候选场景供应商内部主键',
    candidate_priority INT NOT NULL COMMENT '规则内候选优先级，数值越小越优先',
    health_check_mode VARCHAR(16) NOT NULL COMMENT '供应商健康检查模式：REQUIRED必需，OPTIONAL可选，UNSUPPORTED不支持',
    health_max_age_seconds INT NULL COMMENT '供应商健康事实最大允许年龄秒数，不支持时为空',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '草稿候选配置乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '候选配置创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '候选配置最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_scenario_candidate_supplier (rule_id, supplier_id),
    KEY idx_sc_scenario_candidate_order (rule_id, candidate_priority, supplier_id),
    KEY idx_sc_scenario_candidate_supplier (supplier_id),
    CONSTRAINT ck_sc_scenario_candidate_priority CHECK (candidate_priority >= 0),
    CONSTRAINT ck_sc_scenario_health_mode CHECK ((health_check_mode = 'UNSUPPORTED' AND health_max_age_seconds IS NULL) OR (health_check_mode IN ('REQUIRED', 'OPTIONAL') AND health_max_age_seconds > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='场景路由的供应商候选与健康检查策略';

-- 表中文名：标准履约受理回执
-- 核心职责：证明采购商标准履约请求是否已被中台受理，并约束原业务号重试语义
CREATE TABLE sc_fulfillment_acceptance_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '标准履约受理回执内部主键',
    receipt_no VARCHAR(32) NOT NULL COMMENT '受理回执稳定业务编号',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    buyer_order_no VARCHAR(128) NOT NULL COMMENT '采购商侧C端订单号，与buyer_id组成永久受理键',
    buyer_user_id VARCHAR(128) NOT NULL COMMENT '首次请求固化的采购商范围内C端用户唯一标识',
    buyer_sku_code VARCHAR(64) NOT NULL COMMENT '首次请求固化的采购商商品编码',
    quantity BIGINT NOT NULL COMMENT '采购商请求数量原值；通过校验的M1订单固定为1，失败订单保留非法原值',
    target_schema_code VARCHAR(64) NOT NULL COMMENT '履约目标封闭Schema编码',
    target_digest BINARY(32) NOT NULL COMMENT '履约目标规范化HMAC摘要，不保存目标明文',
    request_snapshot_schema_version INT NULL COMMENT 'PROCESSING恢复快照Schema版本；回执完成后清空',
    request_snapshot_ciphertext LONGBLOB NULL COMMENT '信封加密的最小受理命令恢复快照；仅PROCESSING期间保存',
    request_snapshot_key_version VARCHAR(64) NULL COMMENT '恢复快照信封加密密钥版本；回执完成后清空',
    invariant_fingerprint BINARY(32) NOT NULL COMMENT '不含Listing版本的不可变请求字段SHA-256指纹',
    request_fingerprint BINARY(32) NOT NULL COMMENT '当前尝试完整请求SHA-256指纹',
    listing_version_no INT NULL COMMENT '采购商提交的商品业务版本号；报文未携带或无法解析时为空',
    listing_version_id BIGINT NULL COMMENT '校验成功后解析的采购商商品版本主键，解析失败时为空',
    status SMALLINT NOT NULL COMMENT '回执状态：0 PROCESSING处理中，10 ORDER_CREATED已创建采购方订单，30 REJECTED_FINAL边界最终拒绝',
    result_buyer_order_id BIGINT NULL COMMENT '已创建的采购方订单主键，ORDER_CREATED时必填',
    result_order_no VARCHAR(32) NULL COMMENT '已创建的采购方订单号快照，ORDER_CREATED时必填',
    result_fulfillment_request_no VARCHAR(32) NULL COMMENT '履约请求号快照；前置业务校验失败时为空',
    rejection_code VARCHAR(64) NULL COMMENT '边界最终拒绝稳定错误码；ORDER_CREATED时业务失败原因以采购方订单为准',
    recovery_attempt_count INT NOT NULL DEFAULT 0 COMMENT 'PROCESSING回执自动恢复已认领次数',
    recover_after DATETIME(3) NULL COMMENT 'PROCESSING回执下一次允许恢复时间，完成后为空',
    completed_at DATETIME(3) NULL COMMENT '回执完成时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '受理回执创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '受理回执最后更新时间，UTC',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '回执状态与宕机恢复CAS版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_fulfillment_receipt_no (receipt_no),
    UNIQUE KEY uk_sc_fulfillment_receipt_business (buyer_id, buyer_order_no),
    UNIQUE KEY uk_sc_fulfillment_receipt_result_order (result_buyer_order_id),
    KEY idx_sc_fulfillment_receipt_status (status, recover_after, id),
    KEY idx_sc_fulfillment_receipt_order (result_buyer_order_id),
    CONSTRAINT ck_sc_fulfillment_receipt_recovery_count CHECK (recovery_attempt_count >= 0),
    CONSTRAINT ck_sc_fulfillment_receipt_status CHECK (status IN (0, 10, 30)),
    CONSTRAINT ck_sc_fulfillment_receipt_snapshot CHECK ((status = 0 AND request_snapshot_schema_version IS NOT NULL AND request_snapshot_schema_version >= 1 AND request_snapshot_ciphertext IS NOT NULL AND request_snapshot_key_version IS NOT NULL AND recover_after IS NOT NULL) OR (status IN (10, 30) AND request_snapshot_schema_version IS NULL AND request_snapshot_ciphertext IS NULL AND request_snapshot_key_version IS NULL AND recover_after IS NULL)),
    CONSTRAINT ck_sc_fulfillment_receipt_result CHECK ((status = 10 AND result_buyer_order_id IS NOT NULL AND result_order_no IS NOT NULL AND rejection_code IS NULL AND completed_at IS NOT NULL) OR (status = 30 AND result_buyer_order_id IS NULL AND result_order_no IS NULL AND result_fulfillment_request_no IS NULL AND rejection_code IS NOT NULL AND completed_at IS NOT NULL) OR (status = 0 AND result_buyer_order_id IS NULL AND result_order_no IS NULL AND rejection_code IS NULL AND completed_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准履约永久业务键的订单创建回执与恢复状态';

-- 表中文名：采购方订单
-- 核心职责：聚合采购方订单共有属性、公共状态和列表投影
CREATE TABLE sc_buyer_order (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购方订单内部主键',
    order_no VARCHAR(32) NOT NULL COMMENT '中台采购方订单稳定业务编号',
    order_type VARCHAR(16) NOT NULL COMMENT '订单类型：CARD标准商品履约，O2O渠道场景',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    buyer_code_snapshot VARCHAR(32) NOT NULL COMMENT '受理时采购商业务编码快照',
    buyer_name_snapshot VARCHAR(128) NOT NULL COMMENT '受理时采购商名称快照',
    buyer_user_id VARCHAR(128) NOT NULL COMMENT '采购商范围内的C端用户唯一标识',
    buyer_order_no VARCHAR(128) NULL COMMENT '采购商侧C端订单号，CARD场景必填',
    acceptance_receipt_id BIGINT NULL COMMENT 'CARD场景受理回执主键，O2O场景为空',
    buyer_sku_code VARCHAR(64) NULL COMMENT '采购商请求中的商品编码原值，CARD场景必填',
    display_supplier_id BIGINT NULL COMMENT '列表展示供应商内部主键',
    display_supplier_code VARCHAR(32) NULL COMMENT '列表展示供应商编码快照',
    display_supplier_name VARCHAR(128) NULL COMMENT '列表展示供应商名称快照',
    supplier_attempt_count INT NOT NULL DEFAULT 0 COMMENT '已经创建的供应商订单数量，前置失败为0',
    buyer_settlement_amount BIGINT NULL COMMENT '采购商应向中台结算的订单金额，单位为分；价格解析失败时为空',
    currency CHAR(3) NOT NULL COMMENT '订单币种，一期固定CNY',
    platform_sku_id BIGINT NULL COMMENT 'CARD场景平台SKU内部主键',
    sku_code_snapshot VARCHAR(32) NULL COMMENT 'CARD场景平台SKU编码快照',
    sku_name_snapshot VARCHAR(256) NULL COMMENT 'CARD场景平台SKU名称快照',
    quantity BIGINT NULL COMMENT '采购商请求数量原值，CARD场景必填；有效M1订单为1',
    buyer_order_digest BINARY(32) NULL COMMENT '采购商原订单最小摘要SHA-256',
    order_status SMALLINT NOT NULL COMMENT '采购方订单状态：10 FULFILLING履约中，30 SUCCEEDED成功，40 FAILED失败，90 MANUAL人工处理',
    aftersale_status SMALLINT NOT NULL COMMENT '售后状态：0无售后，10处理中，20部分退款，30全部退款，90人工处理',
    business_stage VARCHAR(32) NOT NULL COMMENT '当前业务阶段稳定编码',
    failure_stage VARCHAR(32) NULL COMMENT '失败阶段：BUYER_VALIDATION、LISTING_VALIDATION、REQUEST_VALIDATION、FUND_RESERVATION、ROUTING或SUPPLIER_FULFILLMENT',
    failure_code VARCHAR(64) NULL COMMENT '采购方可识别的稳定失败码，非失败订单为空',
    risk_status SMALLINT NOT NULL DEFAULT 0 COMMENT '内部风险状态：0 NORMAL正常，10 OPEN存在未关闭履约异常',
    primary_risk_type VARCHAR(64) NULL COMMENT '优先级最高的未关闭履约异常类型，无风险时为空',
    open_exception_count INT NOT NULL DEFAULT 0 COMMENT '未关闭履约异常工单数量',
    detail_type VARCHAR(32) NOT NULL COMMENT '场景详情类型：NONE无详情，FULFILLMENT标准履约，O2O_CHANNEL渠道场景',
    detail_id BIGINT NULL COMMENT '场景详情内部主键；前置业务失败且detail_type为NONE时为空',
    detail_version BIGINT NOT NULL DEFAULT 0 COMMENT '已投影到采购方订单的场景详情版本号，无详情时为0',
    request_fingerprint BINARY(32) NOT NULL COMMENT '幂等请求规范化SHA-256指纹',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '总订单乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '订单受理时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '订单最后更新时间，UTC',
    completed_at DATETIME(3) NULL COMMENT '订单进入业务终态时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_order_no (order_no),
    UNIQUE KEY uk_sc_order_detail (detail_type, detail_id),
    UNIQUE KEY uk_sc_order_buyer_business (buyer_id, buyer_order_no),
    UNIQUE KEY uk_sc_order_acceptance_receipt (acceptance_receipt_id),
    KEY idx_sc_order_buyer_time (buyer_id, created_at),
    KEY idx_sc_order_supplier_time (display_supplier_id, created_at),
    KEY idx_sc_order_status_time (order_status, created_at),
    CONSTRAINT ck_sc_order_type CHECK (order_type IN ('CARD', 'O2O')),
    CONSTRAINT ck_sc_order_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_order_amount CHECK (buyer_settlement_amount IS NULL OR buyer_settlement_amount >= 0),
    CONSTRAINT ck_sc_order_attempt_count CHECK (supplier_attempt_count >= 0),
    CONSTRAINT ck_sc_order_type_fields CHECK ((order_type = 'CARD' AND buyer_order_no IS NOT NULL AND acceptance_receipt_id IS NOT NULL AND buyer_sku_code IS NOT NULL AND quantity IS NOT NULL AND detail_type IN ('NONE', 'FULFILLMENT')) OR (order_type = 'O2O' AND buyer_order_no IS NULL AND acceptance_receipt_id IS NULL AND buyer_sku_code IS NULL AND platform_sku_id IS NULL AND sku_code_snapshot IS NULL AND sku_name_snapshot IS NULL AND quantity IS NULL AND buyer_settlement_amount IS NOT NULL AND buyer_settlement_amount > 0 AND detail_type = 'O2O_CHANNEL' AND display_supplier_id IS NOT NULL)),
    CONSTRAINT ck_sc_order_status CHECK (order_status IN (10, 30, 40, 90)),
    CONSTRAINT ck_sc_order_failure CHECK ((order_status = 40 AND failure_stage IS NOT NULL AND failure_code IS NOT NULL) OR (order_status <> 40 AND failure_stage IS NULL AND failure_code IS NULL)),
    CONSTRAINT ck_sc_order_aftersale CHECK (aftersale_status IN (0, 10, 20, 30, 90)),
    CONSTRAINT ck_sc_order_risk CHECK ((risk_status = 0 AND primary_risk_type IS NULL AND open_exception_count = 0) OR (risk_status = 10 AND primary_risk_type IS NOT NULL AND open_exception_count > 0)),
    CONSTRAINT ck_sc_order_detail CHECK ((detail_type = 'NONE' AND detail_id IS NULL AND detail_version = 0) OR (detail_type IN ('FULFILLMENT', 'O2O_CHANNEL') AND detail_id IS NOT NULL)),
    CONSTRAINT ck_sc_order_supplier_snapshot CHECK ((display_supplier_id IS NULL AND display_supplier_code IS NULL AND display_supplier_name IS NULL) OR (display_supplier_id IS NOT NULL AND display_supplier_code IS NOT NULL AND display_supplier_name IS NOT NULL)),
    CONSTRAINT ck_sc_order_completion CHECK ((order_status IN (30, 40) AND completed_at IS NOT NULL) OR (order_status IN (10, 90) AND completed_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应链中台采购方订单聚合总表';

-- 表中文名：采购方订单状态日志
-- 核心职责：追加记录采购方订单状态与列表投影的每次变化
CREATE TABLE sc_buyer_order_status_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购方订单状态日志内部主键',
    buyer_order_id BIGINT NOT NULL COMMENT '采购方订单主键，逻辑引用sc_buyer_order.id',
    order_version BIGINT NOT NULL COMMENT '本次变化后的采购方订单乐观锁版本号',
    from_order_status SMALLINT NULL COMMENT '变更前订单状态，首条日志为空',
    to_order_status SMALLINT NOT NULL COMMENT '变更后订单状态：10履约中，30成功，40失败，90人工处理',
    from_aftersale_status SMALLINT NULL COMMENT '变更前售后状态：0无售后，10处理中，20部分退款，30全部退款，90人工处理；首条为空',
    to_aftersale_status SMALLINT NOT NULL COMMENT '变更后售后状态：0无售后，10处理中，20部分退款，30全部退款，90人工处理',
    from_risk_status SMALLINT NULL COMMENT '变更前内部风险状态，首条日志为空',
    to_risk_status SMALLINT NOT NULL COMMENT '变更后内部风险状态：0正常，10存在未关闭异常',
    business_stage VARCHAR(32) NOT NULL COMMENT '变更后的业务阶段稳定编码',
    supplier_order_id BIGINT NULL COMMENT '触发本次投影变化的供应商订单主键，无供应商尝试时为空',
    source_event_type VARCHAR(64) NOT NULL COMMENT '触发状态投影的事实或事件类型',
    source_event_no VARCHAR(64) NOT NULL COMMENT '触发状态投影的事实或事件编号',
    reason_code VARCHAR(64) NULL COMMENT '状态变化原因码',
    trace_id VARCHAR(64) NOT NULL COMMENT '链路追踪标识',
    created_at DATETIME(3) NOT NULL COMMENT '状态审计创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_order_status_version (buyer_order_id, order_version),
    KEY idx_sc_buyer_order_status_time (buyer_order_id, created_at),
    KEY idx_sc_buyer_order_status_supplier_order (supplier_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购方订单状态与列表投影不可变审计日志';

-- 表中文名：标准商品履约请求
-- 核心职责：保存CARD场景目标、商品版本、数量和履约聚合状态
CREATE TABLE sc_fulfillment_request (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '标准商品履约请求内部主键',
    request_no VARCHAR(32) NOT NULL COMMENT '履约请求稳定业务编号',
    buyer_order_id BIGINT NOT NULL COMMENT '采购方订单主键，逻辑引用sc_buyer_order.id',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键',
    listing_id BIGINT NOT NULL COMMENT '采购商商品内部主键',
    listing_version_id BIGINT NOT NULL COMMENT '受理时冻结的采购商商品版本主键',
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU内部主键',
    target_schema_code VARCHAR(64) NOT NULL COMMENT '履约目标封闭Schema编码',
    target_schema_version INT NOT NULL COMMENT '履约目标Schema版本',
    target_ciphertext LONGBLOB NOT NULL COMMENT '履约目标信封加密密文',
    target_digest BINARY(32) NOT NULL COMMENT '履约目标规范化HMAC或SHA-256摘要',
    target_key_version VARCHAR(64) NOT NULL COMMENT '履约目标加密密钥版本',
    requested_quantity BIGINT NOT NULL COMMENT '请求履约数量',
    fulfilled_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '已经成功交付的数量',
    supplier_attempt_count INT NOT NULL DEFAULT 1 COMMENT '已经创建的供应商订单数量；履约请求创建时首单同步创建',
    current_candidate_sequence INT NOT NULL DEFAULT 1 COMMENT '当前或最后一次消费的冻结路由候选序号',
    status SMALLINT NOT NULL COMMENT '履约请求状态：10 ACCEPTED已受理，20 FULFILLING履约中，30 SUCCEEDED成功，40 FAILED失败，90 MANUAL人工处理',
    aggregate_version BIGINT NOT NULL DEFAULT 0 COMMENT '履约聚合版本号',
    created_at DATETIME(3) NOT NULL COMMENT '履约请求创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '履约请求最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_fulfillment_request_no (request_no),
    UNIQUE KEY uk_sc_fulfillment_request_order (buyer_order_id),
    KEY idx_sc_fulfillment_request_listing (listing_id, created_at),
    CONSTRAINT ck_sc_fulfillment_request_quantity CHECK (requested_quantity = 1 AND fulfilled_quantity IN (0, 1)),
    CONSTRAINT ck_sc_fulfillment_request_attempt CHECK (supplier_attempt_count >= 1 AND current_candidate_sequence >= 1),
    CONSTRAINT ck_sc_fulfillment_request_schema CHECK (target_schema_version >= 1),
    CONSTRAINT ck_sc_fulfillment_request_status CHECK (status IN (10, 20, 30, 40, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CARD场景一对一履约详情';

-- 表中文名：供应商路由轮次
-- 核心职责：追加记录标准商品或场景供应商的一次完整路由决策
CREATE TABLE sc_supplier_routing_round (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商路由轮次内部主键',
    routing_round_no VARCHAR(32) NOT NULL COMMENT '供应商路由轮次稳定业务编号',
    buyer_id BIGINT NOT NULL COMMENT '发起路由的采购商内部主键',
    routing_subject_type VARCHAR(32) NOT NULL COMMENT '路由主体类型：STANDARD_REQUEST标准履约请求，SCENARIO_REQUEST场景订单请求',
    routing_subject_key VARCHAR(128) NOT NULL COMMENT '采购商范围内路由主体稳定业务键，无候选时仍可审计',
    routing_type VARCHAR(32) NOT NULL COMMENT '路由类型：STANDARD_PRODUCT标准商品供给路由，SCENARIO场景供应商路由',
    buyer_order_id BIGINT NOT NULL COMMENT '采购方订单主键；无候选时关联前置创建的FAILED采购方订单',
    fulfillment_request_id BIGINT NULL COMMENT '标准商品受理成功后的履约请求主键，场景路由或无候选时为空',
    round_sequence INT NOT NULL COMMENT '同一路由主体内的轮次序号，从1递增；场景路由固定为1',
    policy_id BIGINT NULL COMMENT '命中的供应商路由策略主键，未找到已发布策略时为空',
    policy_version_id BIGINT NULL COMMENT '命中的供应商路由策略版本主键，未找到已发布策略时为空',
    matched_rule_id BIGINT NULL COMMENT '命中的强类型规则主键，按routing_type解析所属规则表；未命中或冲突时为空',
    routing_input_digest BINARY(32) NOT NULL COMMENT '路由输入事实SHA-256摘要',
    snapshot_schema_version INT NOT NULL COMMENT '脱敏路由事实快照Schema版本',
    fact_snapshot_json JSON NOT NULL COMMENT '用于历史解释和重放的脱敏不可变事实快照',
    candidate_count INT NOT NULL COMMENT '本轮候选供应商数量',
    decision_status SMALLINT NOT NULL COMMENT '路由决策状态：10已选择，20无可用候选，30规则冲突',
    decision_reason_code VARCHAR(64) NOT NULL COMMENT '本轮最终决策稳定原因码',
    decision_reason_message VARCHAR(512) NOT NULL COMMENT '本轮最终决策中文说明快照',
    computed_at DATETIME(3) NOT NULL COMMENT '纯路由计算完成时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '路由轮次入库时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_routing_round_no (routing_round_no),
    UNIQUE KEY uk_sc_routing_round_subject (buyer_id, routing_subject_type, routing_subject_key, round_sequence),
    KEY idx_sc_routing_round_buyer_time (buyer_id, created_at),
    KEY idx_sc_routing_round_order (buyer_order_id, round_sequence),
    KEY idx_sc_routing_round_request (fulfillment_request_id, round_sequence),
    KEY idx_sc_routing_round_policy (policy_version_id, created_at),
    KEY idx_sc_routing_round_status (decision_status, created_at),
    CONSTRAINT ck_sc_routing_round_sequence CHECK (round_sequence >= 1),
    CONSTRAINT ck_sc_routing_round_subject CHECK (routing_subject_type IN ('STANDARD_REQUEST', 'SCENARIO_REQUEST')),
    CONSTRAINT ck_sc_routing_round_type CHECK (routing_type IN ('STANDARD_PRODUCT', 'SCENARIO')),
    CONSTRAINT ck_sc_routing_round_subject_type CHECK ((routing_type = 'STANDARD_PRODUCT' AND routing_subject_type = 'STANDARD_REQUEST') OR (routing_type = 'SCENARIO' AND routing_subject_type = 'SCENARIO_REQUEST')),
    CONSTRAINT ck_sc_routing_round_policy CHECK ((policy_id IS NULL AND policy_version_id IS NULL AND matched_rule_id IS NULL) OR (policy_id IS NOT NULL AND policy_version_id IS NOT NULL)),
    CONSTRAINT ck_sc_routing_round_snapshot CHECK (snapshot_schema_version >= 1),
    CONSTRAINT ck_sc_routing_round_candidate CHECK (candidate_count >= 0),
    CONSTRAINT ck_sc_routing_round_selected CHECK (decision_status <> 10 OR (candidate_count > 0 AND policy_version_id IS NOT NULL AND matched_rule_id IS NOT NULL)),
    CONSTRAINT ck_sc_routing_round_status CHECK (decision_status IN (10, 20, 30))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准商品或场景供应商的一次不可变路由决策';

-- 表中文名：供应商路由候选记录
-- 核心职责：追加记录每轮全部供应商候选的排序、检查、淘汰和选择事实
CREATE TABLE sc_supplier_routing_record (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商路由候选记录内部主键',
    routing_round_id BIGINT NOT NULL COMMENT '供应商路由轮次主键',
    candidate_sequence INT NOT NULL COMMENT '本轮候选排序序号，从1递增',
    supplier_id BIGINT NOT NULL COMMENT '候选供应商内部主键',
    supplier_code_snapshot VARCHAR(32) NOT NULL COMMENT '候选供应商编码快照',
    supplier_name_snapshot VARCHAR(128) NOT NULL COMMENT '候选供应商名称快照',
    offer_id BIGINT NULL COMMENT '标准商品候选报价身份主键，场景候选或报价未解析时为空',
    offer_version_id BIGINT NULL COMMENT '标准商品候选报价版本主键，场景候选或报价未解析时为空',
    connector_version_id BIGINT NULL COMMENT '标准商品候选连接器版本主键，场景候选可为空',
    inventory_source_id BIGINT NULL COMMENT '标准商品候选库存来源主键，场景候选可为空',
    configured_priority INT NOT NULL COMMENT '已发布规则中配置的候选优先级，数值越小越优先',
    availability_class VARCHAR(16) NOT NULL COMMENT '动态可用性分类：CONFIRMED已确认，UNKNOWN未知，UNAVAILABLE不可用',
    supplier_settlement_unit_price BIGINT NULL COMMENT '标准商品候选供应商履约结算单价快照，单位为分',
    health_score DECIMAL(7,4) NULL COMMENT '路由时供应商健康度快照，取值0至100且数值越大越健康',
    fact_captured_at DATETIME(3) NULL COMMENT '本候选动态事实最近采集时间，UTC；未执行动态检查时为空',
    check_snapshot_schema_version INT NOT NULL COMMENT '受控检查结果JSON Schema版本',
    check_result_json JSON NOT NULL COMMENT '库存、余额、健康度等检查模式、结果、耗时和脱敏原因快照',
    eligible_flag TINYINT NOT NULL COMMENT '候选资格：1符合，0被淘汰',
    decision_reason_code VARCHAR(64) NOT NULL COMMENT '进入、淘汰或选择的稳定原因码',
    decision_reason_message VARCHAR(512) NOT NULL COMMENT '进入、淘汰或选择的中文说明快照',
    first_choice_flag TINYINT NULL COMMENT '本轮第一选择标记：1第一选择，NULL其他候选',
    supplier_order_id BIGINT NULL COMMENT '消费本候选创建的供应商订单主键；候选未尝试时为空',
    created_at DATETIME(3) NOT NULL COMMENT '候选记录创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_routing_record_sequence (routing_round_id, candidate_sequence),
    UNIQUE KEY uk_sc_routing_record_first_choice (routing_round_id, first_choice_flag),
    UNIQUE KEY uk_sc_routing_record_supplier_order (supplier_order_id),
    KEY idx_sc_routing_record_supplier (supplier_id, created_at),
    KEY idx_sc_routing_record_offer_version (offer_version_id),
    CONSTRAINT ck_sc_routing_candidate_sequence CHECK (candidate_sequence >= 1),
    CONSTRAINT ck_sc_routing_candidate_priority CHECK (configured_priority >= 0),
    CONSTRAINT ck_sc_routing_availability CHECK (availability_class IN ('CONFIRMED', 'UNKNOWN', 'UNAVAILABLE')),
    CONSTRAINT ck_sc_routing_offer_pair CHECK ((offer_id IS NULL AND offer_version_id IS NULL) OR (offer_id IS NOT NULL AND offer_version_id IS NOT NULL)),
    CONSTRAINT ck_sc_routing_candidate_price CHECK (supplier_settlement_unit_price IS NULL OR supplier_settlement_unit_price >= 0),
    CONSTRAINT ck_sc_routing_health_score CHECK (health_score IS NULL OR (health_score >= 0 AND health_score <= 100)),
    CONSTRAINT ck_sc_routing_check_schema CHECK (check_snapshot_schema_version >= 1),
    CONSTRAINT ck_sc_routing_eligible CHECK (eligible_flag IN (0, 1)),
    CONSTRAINT ck_sc_routing_first_choice CHECK (first_choice_flag IS NULL OR first_choice_flag = 1),
    CONSTRAINT ck_sc_routing_first_eligible CHECK (first_choice_flag IS NULL OR eligible_flag = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商路由候选排序、检查和选择的追加式审计';

-- 表中文名：供应商订单
-- 核心职责：维护一次供应商履约尝试、不可变路由快照及供应成本事实
CREATE TABLE sc_supplier_order (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商订单内部主键',
    supplier_order_no VARCHAR(32) NOT NULL COMMENT '中台供应商订单稳定业务编号',
    buyer_order_id BIGINT NOT NULL COMMENT '采购方订单主键，逻辑引用sc_buyer_order.id',
    fulfillment_request_id BIGINT NULL COMMENT 'CARD履约请求主键，O2O供应商订单为空',
    o2o_channel_detail_id BIGINT NULL COMMENT 'O2O场景详情主键，CARD供应商订单为空',
    routing_record_id BIGINT NULL COMMENT 'CARD冻结路由候选记录主键，O2O固定商户订单为空',
    attempt_sequence INT NOT NULL COMMENT '采购方订单内供应商尝试序号，从1递增',
    attempt_reason VARCHAR(32) NOT NULL COMMENT '尝试原因：INITIAL_ROUTE首次路由，FAILOVER权威失败后切换，O2O_FIXED_MERCHANT固定商户',
    previous_supplier_order_id BIGINT NULL COMMENT '前一张供应商订单主键，首次尝试为空',
    supplier_id BIGINT NOT NULL COMMENT '实际尝试供应商内部主键',
    supplier_code_snapshot VARCHAR(32) NOT NULL COMMENT '供应商业务编码快照',
    supplier_name_snapshot VARCHAR(128) NOT NULL COMMENT '供应商名称快照',
    offer_id BIGINT NULL COMMENT '冻结的供应商报价身份主键，O2O无报价时为空',
    offer_version_id BIGINT NULL COMMENT '冻结的供应商报价版本主键，O2O无报价时为空',
    connector_version_id BIGINT NULL COMMENT '冻结的供应商连接器版本主键，O2O渠道服务场景为空',
    inventory_source_id BIGINT NULL COMMENT '冻结的库存来源主键，无库存模型时为空',
    supplier_request_no VARCHAR(64) NOT NULL COMMENT '发给供应商的稳定请求业务号；同一供应商订单重试和查单必须复用',
    external_supplier_order_no VARCHAR(128) NULL COMMENT '供应商返回的外部订单号',
    requested_quantity BIGINT NOT NULL COMMENT '本次整单请求履约数量',
    fulfilled_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '本次已经成功交付数量',
    supplier_settlement_unit_price BIGINT NULL COMMENT '冻结的供应商履约结算单价，单位为分；无报价O2O场景为空',
    expected_supplier_settlement_amount BIGINT NULL COMMENT '预计供应商结算金额，单位为分；无合同报价时为空',
    reported_supplier_settlement_amount BIGINT NULL COMMENT '供应商响应或查单报告的结算金额，单位为分',
    confirmed_supplier_settlement_amount BIGINT NULL COMMENT '经过数量与对账规则确认的应结金额，单位为分',
    currency CHAR(3) NOT NULL COMMENT '供应商结算币种，一期固定CNY',
    status SMALLINT NOT NULL COMMENT '供应商订单状态：0 PENDING待执行，10 PROCESSING处理中，20 RESULT_UNKNOWN结果未知，30 SUCCEEDED成功，40 FAILED失败，90 MANUAL人工处理',
    settlement_status SMALLINT NOT NULL COMMENT '结算核对状态：0待报告，10已匹配，20有差异，90人工处理',
    failure_code VARCHAR(64) NULL COMMENT '供应商订单标准失败码，非失败状态为空',
    latest_raw_status_digest BINARY(32) NULL COMMENT '最近一次供应商原始状态事实摘要',
    active_flag TINYINT NULL COMMENT '活动供应商订单标记：1活动，NULL非活动；用于限制采购方订单最多一张活动单',
    effective_success_flag TINYINT NULL COMMENT '有效成功标记：1作为采购方订单唯一有效成功，NULL非有效成功',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '履约尝试乐观锁版本号',
    started_at DATETIME(3) NULL COMMENT '首次开始外部履约时间，UTC',
    completed_at DATETIME(3) NULL COMMENT '履约尝试进入终态时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '供应商订单创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '供应商订单最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_order_no (supplier_order_no),
    UNIQUE KEY uk_sc_supplier_order_request_no (supplier_request_no),
    UNIQUE KEY uk_sc_supplier_order_attempt (buyer_order_id, attempt_sequence),
    UNIQUE KEY uk_sc_supplier_order_previous (previous_supplier_order_id),
    UNIQUE KEY uk_sc_supplier_order_routing_record (routing_record_id),
    UNIQUE KEY uk_sc_supplier_order_o2o_detail (o2o_channel_detail_id),
    UNIQUE KEY uk_sc_supplier_order_external (supplier_id, connector_version_id, external_supplier_order_no),
    UNIQUE KEY uk_sc_supplier_order_active (buyer_order_id, active_flag),
    UNIQUE KEY uk_sc_supplier_order_effective_success (buyer_order_id, effective_success_flag),
    KEY idx_sc_supplier_order_request (fulfillment_request_id, attempt_sequence),
    KEY idx_sc_supplier_order_o2o (o2o_channel_detail_id),
    KEY idx_sc_supplier_order_status (status, updated_at),
    KEY idx_sc_supplier_order_connector (connector_version_id, created_at),
    CONSTRAINT ck_sc_supplier_order_attempt CHECK (attempt_sequence >= 1),
    CONSTRAINT ck_sc_supplier_order_reason CHECK (attempt_reason IN ('INITIAL_ROUTE', 'FAILOVER', 'O2O_FIXED_MERCHANT')),
    CONSTRAINT ck_sc_supplier_order_reason_sequence CHECK ((attempt_sequence = 1 AND attempt_reason IN ('INITIAL_ROUTE', 'O2O_FIXED_MERCHANT')) OR (attempt_sequence > 1 AND attempt_reason = 'FAILOVER')),
    CONSTRAINT ck_sc_supplier_order_chain CHECK ((attempt_sequence = 1 AND previous_supplier_order_id IS NULL) OR (attempt_sequence > 1 AND previous_supplier_order_id IS NOT NULL)),
    CONSTRAINT ck_sc_supplier_order_source CHECK ((fulfillment_request_id IS NOT NULL AND o2o_channel_detail_id IS NULL AND routing_record_id IS NOT NULL AND offer_id IS NOT NULL AND offer_version_id IS NOT NULL AND connector_version_id IS NOT NULL AND supplier_settlement_unit_price IS NOT NULL AND expected_supplier_settlement_amount IS NOT NULL AND attempt_reason IN ('INITIAL_ROUTE', 'FAILOVER')) OR (fulfillment_request_id IS NULL AND o2o_channel_detail_id IS NOT NULL AND routing_record_id IS NULL AND offer_id IS NULL AND offer_version_id IS NULL AND connector_version_id IS NULL AND inventory_source_id IS NULL AND supplier_settlement_unit_price IS NULL AND expected_supplier_settlement_amount IS NULL AND reported_supplier_settlement_amount IS NULL AND confirmed_supplier_settlement_amount IS NULL AND settlement_status = 0 AND attempt_sequence = 1 AND attempt_reason = 'O2O_FIXED_MERCHANT')),
    CONSTRAINT ck_sc_supplier_order_quantity CHECK (requested_quantity = 1 AND fulfilled_quantity IN (0, 1)),
    CONSTRAINT ck_sc_supplier_order_price CHECK ((supplier_settlement_unit_price IS NULL OR supplier_settlement_unit_price >= 0) AND (expected_supplier_settlement_amount IS NULL OR expected_supplier_settlement_amount >= 0) AND (reported_supplier_settlement_amount IS NULL OR reported_supplier_settlement_amount >= 0) AND (confirmed_supplier_settlement_amount IS NULL OR confirmed_supplier_settlement_amount >= 0)),
    CONSTRAINT ck_sc_supplier_order_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_supplier_order_active CHECK ((active_flag = 1 AND status IN (0, 10, 20)) OR (active_flag IS NULL AND status IN (30, 40, 90))),
    CONSTRAINT ck_sc_supplier_order_effective CHECK ((effective_success_flag = 1 AND status = 30) OR effective_success_flag IS NULL),
    CONSTRAINT ck_sc_supplier_order_external CHECK (external_supplier_order_no IS NULL OR external_supplier_order_no <> ''),
    CONSTRAINT ck_sc_supplier_order_status CHECK (status IN (0, 10, 20, 30, 40, 90)),
    CONSTRAINT ck_sc_supplier_order_failure CHECK ((status = 40 AND failure_code IS NOT NULL) OR (status <> 40 AND failure_code IS NULL)),
    CONSTRAINT ck_sc_supplier_order_completion CHECK ((status IN (30, 40) AND completed_at IS NOT NULL) OR (status IN (0, 10, 20, 90) AND completed_at IS NULL)),
    CONSTRAINT ck_sc_supplier_order_settlement CHECK (settlement_status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次具体供应商履约尝试及供应成本事实';

-- 表中文名：供应商订单状态日志
-- 核心职责：追加记录供应商订单每次状态转换及其来源事实
CREATE TABLE sc_supplier_order_status_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商订单状态日志内部主键',
    supplier_order_id BIGINT NOT NULL COMMENT '供应商订单主键，逻辑引用sc_supplier_order.id',
    supplier_order_version BIGINT NOT NULL COMMENT '本次转换后的供应商订单乐观锁版本号',
    from_status SMALLINT NULL COMMENT '变更前供应商订单状态，首条日志为空',
    to_status SMALLINT NOT NULL COMMENT '变更后供应商订单状态：0待执行，10处理中，20结果未知，30成功，40失败，90人工处理',
    reason_code VARCHAR(64) NOT NULL COMMENT '状态变化稳定原因码',
    source_type VARCHAR(32) NOT NULL COMMENT '来源类型：COMMAND命令，CALL_RESULT调用结果，CALLBACK回调，QUERY_RESULT查单，TIMER恢复，SYSTEM系统',
    source_event_no VARCHAR(128) NOT NULL COMMENT '来源命令、事件或回调稳定编号',
    operation_id BIGINT NULL COMMENT '关联供应商订单操作主键，无操作时为空',
    raw_observation_id BIGINT NULL COMMENT '关联供应商原始状态观察主键，无观察时为空',
    trace_id VARCHAR(64) NOT NULL COMMENT '链路追踪标识',
    created_at DATETIME(3) NOT NULL COMMENT '状态日志创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_order_status_version (supplier_order_id, supplier_order_version),
    KEY idx_sc_supplier_order_status_time (supplier_order_id, created_at),
    KEY idx_sc_supplier_order_status_operation (operation_id),
    CONSTRAINT ck_sc_supplier_order_log_status CHECK (to_status IN (0, 10, 20, 30, 40, 90)),
    CONSTRAINT ck_sc_supplier_order_log_source CHECK (source_type IN ('COMMAND', 'CALL_RESULT', 'CALLBACK', 'QUERY_RESULT', 'TIMER', 'SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商订单状态转换不可变审计日志';

-- 表中文名：供应商订单操作
-- 核心职责：维护供应商订单中可独立识别、幂等执行和恢复的外部副作用
CREATE TABLE sc_supplier_order_operation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商订单操作内部主键',
    operation_no VARCHAR(32) NOT NULL COMMENT '供应商订单操作稳定业务编号',
    supplier_order_id BIGINT NOT NULL COMMENT '供应商订单内部主键',
    target_asset_id BIGINT NULL COMMENT '作废、解绑或查资产时的目标交付资产主键',
    operation_kind VARCHAR(32) NOT NULL COMMENT '操作类型：FULFILL履约，QUERY_ORDER查单，QUERY_ASSET查资产，VOID_ASSET作废资产，UNBIND_ASSET解绑资产',
    adapter_step_code VARCHAR(64) NOT NULL COMMENT '适配器声明的协议步骤编码',
    sequence_no INT NOT NULL COMMENT 'SupplierOrder范围内的操作顺序号，从1递增',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '外部业务副作用幂等键',
    request_fingerprint BINARY(32) NOT NULL COMMENT '不可变操作票据请求SHA-256指纹',
    external_operation_no VARCHAR(128) NULL COMMENT '供应商返回的外部操作号',
    status SMALLINT NOT NULL COMMENT '操作状态：0待执行，10已认领，20执行中，30结果未知，40成功，50失败，90人工处理',
    result_class VARCHAR(32) NULL COMMENT '标准结果：ACCEPTED、SUCCEEDED、PARTIALLY_SUCCEEDED、REJECTED、NOT_FOUND、RESULT_UNKNOWN或MANUAL_REQUIRED',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已发起网络尝试次数',
    next_recover_at DATETIME(3) NULL COMMENT '下一次查单或恢复时间，UTC',
    checkpoint_schema_version INT NULL COMMENT '多步骤恢复检查点Schema版本',
    checkpoint_ciphertext LONGBLOB NULL COMMENT '恢复下一步骤所需的最小加密检查点',
    created_at DATETIME(3) NOT NULL COMMENT '操作创建时间，UTC',
    completed_at DATETIME(3) NULL COMMENT '操作进入终态时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '操作最后更新时间，UTC',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '操作状态乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_order_operation_no (operation_no),
    UNIQUE KEY uk_sc_supplier_order_operation_key (idempotency_key),
    UNIQUE KEY uk_sc_supplier_order_operation_sequence (supplier_order_id, sequence_no),
    KEY idx_sc_supplier_order_operation_recover (status, next_recover_at, id),
    KEY idx_sc_supplier_order_operation_asset (target_asset_id),
    CONSTRAINT ck_sc_supplier_order_operation_sequence CHECK (sequence_no >= 1),
    CONSTRAINT ck_sc_supplier_order_operation_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_sc_supplier_order_operation_kind CHECK (operation_kind IN ('FULFILL', 'QUERY_ORDER', 'QUERY_ASSET', 'VOID_ASSET', 'UNBIND_ASSET')),
    CONSTRAINT ck_sc_supplier_order_operation_status CHECK (status IN (0, 10, 20, 30, 40, 50, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SupplierOrder下可独立恢复的外部履约操作';

-- 表中文名：履约交付资产
-- 核心职责：保存实际交付的逐券、卡密、链接或账户绑定权益
CREATE TABLE sc_fulfillment_asset (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '履约交付资产内部主键',
    asset_no VARCHAR(32) NOT NULL COMMENT '履约交付资产稳定业务编号',
    fulfillment_request_id BIGINT NOT NULL COMMENT '履约请求内部主键',
    supplier_order_id BIGINT NOT NULL COMMENT '产生资产的供应商订单主键',
    operation_id BIGINT NOT NULL COMMENT '产生资产的供应商订单操作主键',
    supplier_id BIGINT NOT NULL COMMENT '交付资产供应商内部主键',
    buyer_id BIGINT NOT NULL COMMENT '资产所属采购商内部主键',
    buyer_user_id VARCHAR(128) NOT NULL COMMENT '采购商范围内的C端用户唯一标识',
    sequence_no INT NOT NULL COMMENT 'SupplierOrder范围内资产顺序号，从1递增',
    unit_quantity BIGINT NOT NULL COMMENT '本资产代表的已交付数量',
    asset_type VARCHAR(32) NOT NULL COMMENT '资产类型：VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益',
    external_asset_id VARCHAR(128) NULL COMMENT '供应商侧独立资产标识',
    external_product_id VARCHAR(128) NULL COMMENT '供应商侧资产商品标识',
    encrypted_payload LONGBLOB NOT NULL COMMENT '卡号卡密、券码、链接或权益信息信封加密密文',
    masked_value VARCHAR(256) NOT NULL COMMENT '后台和授权查询使用的脱敏资产值',
    fingerprint BINARY(32) NOT NULL COMMENT '资产明文HMAC指纹，用于去重且不可反推明文',
    key_version VARCHAR(64) NOT NULL COMMENT '资产密文加密密钥版本',
    valid_from DATETIME(3) NULL COMMENT '资产有效期开始时间，UTC',
    valid_until DATETIME(3) NULL COMMENT '资产有效期结束时间，UTC',
    lifecycle_status SMALLINT NOT NULL COMMENT '资产生命周期：10 AVAILABLE可交付，20 DELIVERED已交付，30 VOIDED已作废，40 UNBOUND已解绑，50 EXPIRED已过期，90 QUARANTINED已隔离',
    delivered_at DATETIME(3) NULL COMMENT '资产首次向采购方授权交付时间，UTC；未交付或隔离时为空',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '资产生命周期乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '资产创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '资产最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_fulfillment_asset_no (asset_no),
    UNIQUE KEY uk_sc_fulfillment_asset_sequence (supplier_order_id, sequence_no),
    UNIQUE KEY uk_sc_fulfillment_asset_external (supplier_id, external_asset_id),
    UNIQUE KEY uk_sc_fulfillment_asset_fingerprint (supplier_id, fingerprint),
    KEY idx_sc_fulfillment_asset_request (fulfillment_request_id, sequence_no),
    KEY idx_sc_fulfillment_asset_operation (operation_id),
    KEY idx_sc_fulfillment_asset_buyer_user (buyer_id, buyer_user_id, created_at),
    CONSTRAINT ck_sc_fulfillment_asset_sequence CHECK (sequence_no >= 1 AND unit_quantity > 0),
    CONSTRAINT ck_sc_fulfillment_asset_validity CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_sc_fulfillment_asset_type CHECK (asset_type IN ('VOUCHER_CODE', 'CARD_CODE_SECRET', 'VOUCHER_URL', 'ACCOUNT_BOUND_BENEFIT')),
    CONSTRAINT ck_sc_fulfillment_asset_status CHECK (lifecycle_status IN (10, 20, 30, 40, 50, 90)),
    CONSTRAINT ck_sc_fulfillment_asset_delivery CHECK ((lifecycle_status = 20 AND delivered_at IS NOT NULL) OR (lifecycle_status <> 20))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商返回的加密履约资产及交付或隔离状态';

-- 表中文名：履约异常工单
-- 核心职责：幂等记录缺少资产、迟到成功和重复成功等履约风险事实
CREATE TABLE sc_fulfillment_exception_case (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '履约异常工单内部主键',
    case_no VARCHAR(32) NOT NULL COMMENT '履约异常工单稳定业务编号',
    exception_key VARCHAR(256) NOT NULL COMMENT '异常事实稳定幂等键，同一事实重复回调不得重复建单',
    buyer_order_id BIGINT NOT NULL COMMENT '采购方订单主键，逻辑引用sc_buyer_order.id',
    fulfillment_request_id BIGINT NOT NULL COMMENT '标准商品履约请求主键',
    supplier_order_id BIGINT NOT NULL COMMENT '发生异常的供应商订单主键',
    operation_id BIGINT NULL COMMENT '触发异常的供应商订单操作主键，非操作事实时为空',
    exception_type VARCHAR(64) NOT NULL COMMENT '异常类型：RECOVER_ASSET缺失资产，LATE_SUCCESS_AFTER_FAILOVER切换后迟到成功，DUPLICATE_SUPPLIER_SUCCESS重复成功，LATE_SUCCESS_AFTER_RELEASE资金释放后迟到成功',
    status SMALLINT NOT NULL COMMENT '工单状态：0 OPEN待处理，10 RESOLVED已处理',
    resolution_code VARCHAR(64) NULL COMMENT '处置结果稳定编码；OPEN时为空，RESOLVED时必填',
    evidence_ref VARCHAR(512) NOT NULL COMMENT '脱敏处置证据的受控存储引用',
    evidence_digest BINARY(32) NOT NULL COMMENT '处置证据内容SHA-256摘要',
    recovered_asset_id BIGINT NULL COMMENT '补录成功后的履约资产主键，仅RECOVER_ASSET类型使用',
    resolved_by BIGINT NULL COMMENT '完成处置的运营人员用户ID；自动恢复时可为空',
    resolution_note VARCHAR(1024) NULL COMMENT '处置原因和结果的脱敏说明',
    resolved_at DATETIME(3) NULL COMMENT '工单解决时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '异常处置单创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '异常处置单最后更新时间，UTC',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '异常工单乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_fulfillment_exception_no (case_no),
    UNIQUE KEY uk_sc_fulfillment_exception_key (exception_key),
    UNIQUE KEY uk_sc_fulfillment_exception_asset (recovered_asset_id),
    KEY idx_sc_fulfillment_exception_order (buyer_order_id, status, created_at),
    KEY idx_sc_fulfillment_exception_request (fulfillment_request_id, status),
    KEY idx_sc_fulfillment_exception_status (status, updated_at),
    CONSTRAINT ck_sc_fulfillment_exception_type CHECK (exception_type IN ('RECOVER_ASSET', 'LATE_SUCCESS_AFTER_FAILOVER', 'DUPLICATE_SUPPLIER_SUCCESS', 'LATE_SUCCESS_AFTER_RELEASE')),
    CONSTRAINT ck_sc_fulfillment_exception_status CHECK (status IN (0, 10)),
    CONSTRAINT ck_sc_fulfillment_exception_resolution CHECK ((status = 0 AND resolution_code IS NULL AND resolved_at IS NULL) OR (status = 10 AND resolution_code IS NOT NULL AND resolved_at IS NOT NULL)),
    CONSTRAINT ck_sc_fulfillment_exception_asset_rule CHECK ((exception_type = 'RECOVER_ASSET' AND status = 10 AND recovered_asset_id IS NOT NULL) OR (exception_type = 'RECOVER_ASSET' AND status = 0 AND recovered_asset_id IS NULL) OR (exception_type <> 'RECOVER_ASSET' AND recovered_asset_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='缺失资产、迟到成功和重复成功等履约风险工单';

-- 表中文名：供应商原始状态观察
-- 核心职责：追加保存创建响应、回调和查单返回的原始状态事实
CREATE TABLE sc_raw_status_observation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商原始状态观察内部主键',
    observation_no VARCHAR(32) NOT NULL COMMENT '原始状态观察稳定业务编号',
    observation_key VARCHAR(128) NOT NULL COMMENT '响应、回调或查单事实的稳定去重键',
    source_type VARCHAR(32) NOT NULL COMMENT '观察来源：CREATE_RESPONSE创建响应，CALLBACK回调，QUERY_RESPONSE查单响应',
    operation_id BIGINT NULL COMMENT '关联履约操作主键',
    supplier_order_id BIGINT NOT NULL COMMENT '关联供应商订单主键',
    connector_version_id BIGINT NOT NULL COMMENT '解析原始状态所使用的连接器版本主键',
    external_supplier_order_no VARCHAR(128) NULL COMMENT '供应商返回的外部订单号',
    external_status VARCHAR(64) NOT NULL COMMENT '供应商原始主状态码',
    external_sub_status VARCHAR(64) NULL COMMENT '供应商原始子状态码',
    mapping_definition_id BIGINT NOT NULL COMMENT '冻结使用的状态映射定义主键',
    mapped_result_class VARCHAR(32) NOT NULL COMMENT '映射得到的封闭标准事实类型',
    payload_digest BINARY(32) NOT NULL COMMENT '脱敏原始状态载荷SHA-256摘要',
    observed_at DATETIME(3) NOT NULL COMMENT '供应商事实发生或报告时间，UTC',
    received_at DATETIME(3) NOT NULL COMMENT '中台收到并落库时间，UTC',
    trace_id VARCHAR(64) NOT NULL COMMENT '链路追踪标识',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_raw_status_no (observation_no),
    UNIQUE KEY uk_sc_raw_status_key (observation_key),
    KEY idx_sc_raw_status_supplier_order (supplier_order_id, received_at),
    KEY idx_sc_raw_status_operation (operation_id, received_at),
    KEY idx_sc_raw_status_external (connector_version_id, external_supplier_order_no, received_at),
    CONSTRAINT ck_sc_raw_status_source CHECK (source_type IN ('CREATE_RESPONSE', 'CALLBACK', 'QUERY_RESPONSE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商创建响应、回调和查单原始状态审计';
```

## 7. `005-o2o-detail.sql`

```sql
-- 表中文名：O2O渠道用户绑定
-- 核心职责：以渠道匿名用户标识安全映射采购商及其C端用户，禁止渠道直接声明内部归属
CREATE TABLE sc_o2o_channel_user_binding (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'O2O渠道用户绑定内部主键',
    binding_no VARCHAR(32) NOT NULL COMMENT 'O2O渠道用户绑定稳定业务编号',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    buyer_user_id VARCHAR(128) NOT NULL COMMENT '采购商范围内C端用户唯一标识',
    channel_code VARCHAR(32) NOT NULL COMMENT 'O2O渠道编码，一期为ELEME',
    channel_user_id VARCHAR(128) NOT NULL COMMENT '中台生成的高熵随机渠道匿名用户标识',
    status SMALLINT NOT NULL COMMENT '绑定状态：10 ACTIVE已启用，20 DISABLED已停用',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '绑定状态乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '绑定创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '绑定最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_o2o_user_binding_no (binding_no),
    UNIQUE KEY uk_sc_o2o_user_binding_buyer (buyer_id, buyer_user_id, channel_code),
    UNIQUE KEY uk_sc_o2o_user_binding_channel (channel_code, channel_user_id),
    KEY idx_sc_o2o_user_binding_status (buyer_id, channel_code, status),
    CONSTRAINT ck_sc_o2o_user_binding_status CHECK (status IN (10, 20))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商C端用户与O2O渠道匿名用户标识的唯一映射';

-- 表中文名：O2O渠道订单详情
-- 核心职责：保存O2O渠道交易、商户、收银台、资金和履约协同状态
CREATE TABLE sc_o2o_channel_detail (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'O2O渠道订单详情内部主键',
    channel_detail_no VARCHAR(32) NOT NULL COMMENT 'O2O渠道详情稳定业务编号',
    buyer_order_id BIGINT NOT NULL COMMENT '采购方订单主键，逻辑引用sc_buyer_order.id',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键',
    buyer_user_id VARCHAR(128) NOT NULL COMMENT '从启用绑定解析并固化的采购商范围内C端用户唯一标识',
    channel_code VARCHAR(32) NOT NULL COMMENT 'O2O渠道编码，一期为ELEME',
    channel_user_binding_id BIGINT NOT NULL COMMENT '受理时使用的O2O渠道用户绑定主键',
    channel_user_id VARCHAR(128) NOT NULL COMMENT '受理时使用的渠道匿名用户标识快照',
    transaction_id VARCHAR(128) NOT NULL COMMENT '渠道交易唯一标识',
    supplier_channel_binding_id BIGINT NOT NULL COMMENT '受理时冻结的渠道商户绑定版本记录主键',
    supplier_id BIGINT NOT NULL COMMENT '渠道外部商户供应商主键，例如盒马',
    supplier_code_snapshot VARCHAR(32) NOT NULL COMMENT '渠道外部商户供应商编码快照',
    supplier_name_snapshot VARCHAR(128) NOT NULL COMMENT '渠道外部商户供应商名称快照',
    external_store_id VARCHAR(128) NULL COMMENT '渠道外部门店标识',
    external_store_name VARCHAR(256) NULL COMMENT '渠道外部门店名称快照',
    store_snapshot_json JSON NULL COMMENT '门店地址等非高频检索展示快照',
    out_trade_no VARCHAR(128) NOT NULL COMMENT '拉起采购商收银台的稳定外部交易号',
    channel_pay_amount BIGINT NOT NULL COMMENT 'ele.me payAmount，渠道对账权威金额，单位为分',
    buyer_paid_amount BIGINT NULL COMMENT '采购商收银台实际支付金额，单位为分',
    buyer_settlement_amount BIGINT NOT NULL COMMENT '采购商账户冻结和结算金额，单位为分',
    currency CHAR(3) NOT NULL COMMENT '交易币种，一期固定CNY',
    cashier_reference VARCHAR(128) NULL COMMENT '采购商收银台支付单引用',
    payment_status SMALLINT NOT NULL COMMENT '用户支付状态：0待支付，10支付处理中，20已支付，30支付失败，40已关闭，90人工处理',
    fund_status SMALLINT NOT NULL COMMENT '中台资金状态：0未冻结，10已冻结，20已提交，30部分返还，40已全部返还，90人工处理',
    channel_fulfillment_status SMALLINT NOT NULL COMMENT '渠道履约状态：0待支付，10处理中，20待取餐或待使用，30已完成，40已取消，50失败，90人工处理',
    total_channel_refund_amount BIGINT NOT NULL DEFAULT 0 COMMENT '渠道累计发起退款金额，单位为分',
    total_buyer_refund_amount BIGINT NOT NULL DEFAULT 0 COMMENT '采购商累计实际退款金额，单位为分',
    total_account_return_amount BIGINT NOT NULL DEFAULT 0 COMMENT '中台累计返还采购商账户金额，单位为分',
    active_refund_case_id BIGINT NULL COMMENT '当前正在采购商退款的退款Case主键',
    status SMALLINT NOT NULL COMMENT 'O2O详情聚合状态：0已创建，10处理中，20已完成，30已取消，40失败，90人工处理',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'O2O详情聚合乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT 'O2O详情创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT 'O2O详情最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_o2o_detail_no (channel_detail_no),
    UNIQUE KEY uk_sc_o2o_detail_order (buyer_order_id),
    UNIQUE KEY uk_sc_o2o_detail_transaction (channel_code, transaction_id),
    UNIQUE KEY uk_sc_o2o_detail_out_trade (out_trade_no),
    KEY idx_sc_o2o_detail_user_binding (channel_user_binding_id, created_at),
    KEY idx_sc_o2o_detail_buyer_user (buyer_id, buyer_user_id, created_at),
    KEY idx_sc_o2o_detail_supplier (supplier_id, created_at),
    KEY idx_sc_o2o_detail_payment (payment_status, updated_at),
    KEY idx_sc_o2o_detail_active_refund (active_refund_case_id),
    CONSTRAINT ck_sc_o2o_detail_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_o2o_detail_amount CHECK (channel_pay_amount >= 0 AND buyer_settlement_amount = channel_pay_amount AND (buyer_paid_amount IS NULL OR buyer_paid_amount >= 0) AND total_channel_refund_amount BETWEEN 0 AND channel_pay_amount AND total_buyer_refund_amount BETWEEN 0 AND channel_pay_amount AND total_account_return_amount BETWEEN 0 AND buyer_settlement_amount),
    CONSTRAINT ck_sc_o2o_detail_paid_match CHECK (payment_status <> 20 OR buyer_paid_amount = channel_pay_amount),
    CONSTRAINT ck_sc_o2o_detail_payment CHECK (payment_status IN (0, 10, 20, 30, 40, 90)),
    CONSTRAINT ck_sc_o2o_detail_fund CHECK (fund_status IN (0, 10, 20, 30, 40, 90)),
    CONSTRAINT ck_sc_o2o_detail_status CHECK (status IN (0, 10, 20, 30, 40, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='O2O场景一对一订单详情与资金协同状态';

-- 表中文名：O2O退款Case
-- 核心职责：串行协调渠道发起、采购商执行和中台账户返还的退款流程
CREATE TABLE sc_o2o_refund_case (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'O2O退款Case内部主键',
    refund_case_no VARCHAR(32) NOT NULL COMMENT '中台退款稳定业务编号',
    o2o_detail_id BIGINT NOT NULL COMMENT 'O2O渠道详情内部主键',
    buyer_order_id BIGINT NOT NULL COMMENT '采购方订单内部主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键',
    channel_code VARCHAR(32) NOT NULL COMMENT 'O2O渠道编码，一期为ELEME',
    channel_refund_no VARCHAR(128) NOT NULL COMMENT 'ele.me等渠道退款唯一编号',
    refund_sequence INT NOT NULL COMMENT '同一O2O详情内退款顺序号，从1递增',
    refund_type VARCHAR(32) NOT NULL COMMENT '退款类型：CHANNEL_REFUND渠道退款，REJECTION_COMPENSATION拒付补偿，LATE_PAYMENT_COMPENSATION迟到支付补偿',
    channel_refund_amount BIGINT NOT NULL COMMENT '渠道要求退款金额，单位为分',
    buyer_actual_refund_amount BIGINT NULL COMMENT '采购商实际退给C端用户的金额，单位为分',
    account_return_amount BIGINT NULL COMMENT '中台返还采购商账户金额，单位为分',
    currency CHAR(3) NOT NULL COMMENT '退款币种，一期固定CNY',
    cashier_refund_reference VARCHAR(128) NULL COMMENT '采购商收银台退款单引用',
    request_fingerprint BINARY(32) NOT NULL COMMENT '退款请求规范化SHA-256指纹',
    status SMALLINT NOT NULL COMMENT '退款状态：0待处理，10排队中，20采购商退款中，30退款成功，40退款失败，50通知渠道中，60已完成，90人工处理',
    buyer_refunding_flag TINYINT NULL COMMENT '采购商退款活动标记：1正在退款，NULL其他，用于限制同一详情只有一个活动退款',
    result_reason_code VARCHAR(64) NULL COMMENT '退款结果或人工处理原因码',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '退款Case乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '退款Case创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '退款Case最后更新时间，UTC',
    completed_at DATETIME(3) NULL COMMENT '退款Case完成时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_o2o_refund_case_no (refund_case_no),
    UNIQUE KEY uk_sc_o2o_refund_channel_no (channel_code, channel_refund_no),
    UNIQUE KEY uk_sc_o2o_refund_sequence (o2o_detail_id, refund_sequence),
    UNIQUE KEY uk_sc_o2o_refund_active (o2o_detail_id, buyer_refunding_flag),
    KEY idx_sc_o2o_refund_order (buyer_order_id, created_at),
    KEY idx_sc_o2o_refund_status (status, updated_at),
    CONSTRAINT ck_sc_o2o_refund_sequence CHECK (refund_sequence >= 1),
    CONSTRAINT ck_sc_o2o_refund_amount CHECK (channel_refund_amount >= 0 AND (buyer_actual_refund_amount IS NULL OR buyer_actual_refund_amount >= 0) AND (account_return_amount IS NULL OR account_return_amount >= 0)),
    CONSTRAINT ck_sc_o2o_refund_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_o2o_refund_active CHECK (buyer_refunding_flag IS NULL OR buyer_refunding_flag = 1),
    CONSTRAINT ck_sc_o2o_refund_type CHECK (refund_type IN ('CHANNEL_REFUND', 'REJECTION_COMPENSATION', 'LATE_PAYMENT_COMPENSATION')),
    CONSTRAINT ck_sc_o2o_refund_rule CHECK ((refund_type IN ('CHANNEL_REFUND', 'REJECTION_COMPENSATION') AND (buyer_actual_refund_amount IS NULL OR buyer_actual_refund_amount = channel_refund_amount) AND (account_return_amount IS NULL OR account_return_amount = channel_refund_amount)) OR (refund_type = 'LATE_PAYMENT_COMPENSATION' AND (buyer_actual_refund_amount IS NULL OR buyer_actual_refund_amount = channel_refund_amount) AND (account_return_amount IS NULL OR account_return_amount = 0))),
    CONSTRAINT ck_sc_o2o_refund_status CHECK (status IN (0, 10, 20, 30, 40, 50, 60, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='O2O渠道退款串行协同Case';

-- 表中文名：O2O外部操作
-- 核心职责：维护预下单、查单、退款和渠道通知等可恢复业务副作用
CREATE TABLE sc_o2o_operation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'O2O外部操作内部主键',
    operation_no VARCHAR(32) NOT NULL COMMENT 'O2O外部操作稳定业务编号；PREORDER操作同时将其作为唯一invocationId',
    o2o_detail_id BIGINT NOT NULL COMMENT 'O2O渠道详情内部主键',
    supplier_order_id BIGINT NULL COMMENT '支付成功后固定商户供应商订单主键；CHANNEL_NOTIFY操作必填，支付准备和查询为空',
    refund_case_id BIGINT NULL COMMENT '退款相关操作对应的退款Case主键',
    operation_type VARCHAR(32) NOT NULL COMMENT '操作类型：PREORDER预下单，PAYMENT_QUERY支付查单，BUYER_REFUND采购商退款，REFUND_QUERY退款查单，CHANNEL_NOTIFY渠道通知',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '外部业务副作用稳定幂等键',
    request_fingerprint BINARY(32) NOT NULL COMMENT '不可变操作请求SHA-256指纹',
    external_operation_no VARCHAR(128) NULL COMMENT '渠道或采购商返回的外部操作号',
    status SMALLINT NOT NULL COMMENT '操作状态：0待执行，10执行中，20结果未知，30成功，40确定失败，90人工处理',
    result_class VARCHAR(32) NULL COMMENT '封闭标准操作结果分类',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已发起外部调用次数',
    next_recover_at DATETIME(3) NULL COMMENT '下一次查单或恢复时间，UTC',
    response_digest BINARY(32) NULL COMMENT '脱敏外部响应SHA-256摘要',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '操作状态乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT 'O2O操作创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT 'O2O操作最后更新时间，UTC',
    completed_at DATETIME(3) NULL COMMENT 'O2O操作进入终态时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_o2o_operation_no (operation_no),
    UNIQUE KEY uk_sc_o2o_operation_key (idempotency_key),
    KEY idx_sc_o2o_operation_detail (o2o_detail_id, created_at),
    KEY idx_sc_o2o_operation_supplier_order (supplier_order_id, created_at),
    KEY idx_sc_o2o_operation_refund (refund_case_id, created_at),
    KEY idx_sc_o2o_operation_recover (status, next_recover_at, id),
    CONSTRAINT ck_sc_o2o_operation_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_sc_o2o_operation_type CHECK (operation_type IN ('PREORDER', 'PAYMENT_QUERY', 'BUYER_REFUND', 'REFUND_QUERY', 'CHANNEL_NOTIFY')),
    CONSTRAINT ck_sc_o2o_operation_supplier_order CHECK ((operation_type = 'CHANNEL_NOTIFY' AND supplier_order_id IS NOT NULL) OR (operation_type <> 'CHANNEL_NOTIFY' AND supplier_order_id IS NULL)),
    CONSTRAINT ck_sc_o2o_operation_status CHECK (status IN (0, 10, 20, 30, 40, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='O2O场景可恢复语义外部操作';
```

## 8. `006-adapter-and-observation.sql`

```sql
-- 表中文名：供应商连接器能力
-- 核心职责：声明连接器版本通过契约验证后可启用的封闭能力
CREATE TABLE sc_supplier_connector_capability (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '连接器能力内部主键',
    connector_version_id BIGINT NOT NULL COMMENT '连接器版本主键，逻辑引用sc_supplier_connector_version.id',
    capability_code VARCHAR(32) NOT NULL COMMENT '封闭能力编码，例如CREATE_RECHARGE、ISSUE_ASSET或QUERY_ORDER',
    enabled TINYINT NOT NULL COMMENT '能力启用标记：1启用，0禁用',
    metadata_version INT NOT NULL COMMENT '能力元数据契约版本',
    created_at DATETIME(3) NOT NULL COMMENT '能力记录创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_connector_capability (connector_version_id, capability_code),
    KEY idx_sc_connector_capability_enabled (capability_code, enabled),
    CONSTRAINT ck_sc_connector_capability_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_sc_connector_capability_version CHECK (metadata_version >= 1),
    CONSTRAINT ck_sc_connector_capability_code CHECK (capability_code IN ('CREATE_RECHARGE', 'ISSUE_ASSET', 'BIND_BENEFIT', 'QUERY_ORDER', 'QUERY_ASSET', 'VOID_ASSET', 'UNBIND_ASSET', 'QUERY_BALANCE', 'QUERY_INVENTORY', 'RECEIVE_CALLBACK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商连接器已验证的封闭能力';

-- 表中文名：供应商错误码映射
-- 核心职责：把供应商原始错误码版本化映射为受限标准动作
CREATE TABLE sc_supplier_error_code_map (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商错误码映射内部主键',
    connector_version_id BIGINT NOT NULL COMMENT '连接器版本主键，逻辑引用sc_supplier_connector_version.id',
    mapping_version INT NOT NULL COMMENT '错误码映射发布版本',
    external_error_code VARCHAR(64) NOT NULL COMMENT '供应商原始错误码',
    external_sub_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '供应商原始子错误码，无子码使用空字符串',
    error_class VARCHAR(32) NOT NULL COMMENT '标准错误分类：BUSINESS_REJECTED、RETRYABLE、RESULT_UNKNOWN或MANUAL_REQUIRED',
    action_type VARCHAR(32) NOT NULL COMMENT '受限动作：REJECT拒绝，RETRY_SAFE安全重试，QUERY_FIRST先查单，MANUAL人工',
    retryable_flag TINYINT NOT NULL COMMENT '是否允许在策略约束下重试：1允许，0不允许',
    status SMALLINT NOT NULL COMMENT '映射状态：0草稿，10已发布，90已退役',
    published_at DATETIME(3) NULL COMMENT '映射发布时间，UTC',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '映射创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_error_map (connector_version_id, mapping_version, external_error_code, external_sub_code),
    KEY idx_sc_supplier_error_lookup (connector_version_id, status, external_error_code),
    CONSTRAINT ck_sc_supplier_error_version CHECK (mapping_version >= 1),
    CONSTRAINT ck_sc_supplier_error_retry CHECK (retryable_flag IN (0, 1)),
    CONSTRAINT ck_sc_supplier_error_class CHECK (error_class IN ('BUSINESS_REJECTED', 'RETRYABLE', 'RESULT_UNKNOWN', 'MANUAL_REQUIRED')),
    CONSTRAINT ck_sc_supplier_error_action CHECK (action_type IN ('REJECT', 'RETRY_SAFE', 'QUERY_FIRST', 'MANUAL')),
    CONSTRAINT ck_sc_supplier_error_status CHECK (status IN (0, 10, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商原始错误码到受限标准动作的版本化映射';

-- 表中文名：供应商状态映射定义
-- 核心职责：把供应商原始状态版本化映射为业务阶段和封闭事实
CREATE TABLE sc_status_mapping_definition (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商状态映射定义内部主键',
    connector_version_id BIGINT NOT NULL COMMENT '连接器版本主键，逻辑引用sc_supplier_connector_version.id',
    mapping_version INT NOT NULL COMMENT '状态映射发布版本',
    external_status VARCHAR(64) NOT NULL COMMENT '供应商原始主状态码',
    external_sub_status VARCHAR(64) NOT NULL DEFAULT '' COMMENT '供应商原始子状态码，无子状态使用空字符串',
    business_phase VARCHAR(32) NOT NULL COMMENT '供应商协议业务阶段，例如CREATE、PROCESSING或DELIVERY',
    fact_type VARCHAR(32) NOT NULL COMMENT '封闭标准事实：ACCEPTED、SUCCEEDED、PARTIALLY_SUCCEEDED、REJECTED、NOT_FOUND、RESULT_UNKNOWN或MANUAL_REQUIRED',
    terminal_flag TINYINT NOT NULL COMMENT '该供应商原始状态是否为其协议终态：1是，0否',
    priority INT NOT NULL DEFAULT 0 COMMENT '同一状态匹配优先级，数值越大越优先',
    status SMALLINT NOT NULL COMMENT '映射生命周期：0草稿，10已发布，90已退役',
    published_at DATETIME(3) NULL COMMENT '映射发布时间，UTC',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '映射创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_status_mapping (connector_version_id, mapping_version, external_status, external_sub_status),
    KEY idx_sc_status_mapping_lookup (connector_version_id, status, external_status),
    CONSTRAINT ck_sc_status_mapping_version CHECK (mapping_version >= 1),
    CONSTRAINT ck_sc_status_mapping_terminal CHECK (terminal_flag IN (0, 1)),
    CONSTRAINT ck_sc_status_mapping_fact CHECK (fact_type IN ('ACCEPTED', 'SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'REJECTED', 'NOT_FOUND', 'RESULT_UNKNOWN', 'MANUAL_REQUIRED')),
    CONSTRAINT ck_sc_status_mapping_status CHECK (status IN (0, 10, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商原始状态到业务阶段和封闭事实的版本化映射';

-- 表中文名：供应商履约调用日志
-- 核心职责：追加记录每次供应商网络调用的脱敏传输审计
CREATE TABLE sc_fulfillment_call_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商调用审计内部主键',
    call_no VARCHAR(32) NOT NULL COMMENT '供应商调用稳定审计编号',
    operation_id BIGINT NOT NULL COMMENT '履约外部操作主键，逻辑引用sc_supplier_order_operation.id',
    supplier_order_id BIGINT NOT NULL COMMENT '供应商订单主键',
    attempt_no INT NOT NULL COMMENT '同一操作内网络调用尝试序号，从1递增',
    direction VARCHAR(16) NOT NULL COMMENT '调用方向：OUTBOUND中台请求供应商，INBOUND供应商回调中台',
    connector_version_id BIGINT NOT NULL COMMENT '本次调用冻结使用的连接器版本主键',
    endpoint_code VARCHAR(64) NOT NULL COMMENT '适配器声明的供应商端点编码',
    started_at DATETIME(3) NOT NULL COMMENT '调用开始时间，UTC',
    duration_ms BIGINT NOT NULL COMMENT '调用耗时，毫秒',
    result_class VARCHAR(32) NOT NULL COMMENT '脱敏调用结果分类',
    http_status INT NULL COMMENT 'HTTP响应状态码，未获得响应时为空',
    request_digest BINARY(32) NOT NULL COMMENT '脱敏请求SHA-256摘要',
    response_digest BINARY(32) NULL COMMENT '脱敏响应SHA-256摘要，未响应时为空',
    redaction_version INT NOT NULL COMMENT '日志脱敏规则版本',
    trace_id VARCHAR(64) NOT NULL COMMENT '链路追踪标识',
    created_at DATETIME(3) NOT NULL COMMENT '调用审计入库时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_fulfillment_call_no (call_no),
    UNIQUE KEY uk_sc_fulfillment_call_attempt (operation_id, attempt_no),
    KEY idx_sc_fulfillment_call_supplier_order (supplier_order_id, started_at),
    KEY idx_sc_fulfillment_call_connector (connector_version_id, started_at),
    KEY idx_sc_fulfillment_call_result (result_class, started_at),
    CONSTRAINT ck_sc_fulfillment_call_attempt CHECK (attempt_no >= 1),
    CONSTRAINT ck_sc_fulfillment_call_duration CHECK (duration_ms >= 0),
    CONSTRAINT ck_sc_fulfillment_call_direction CHECK (direction IN ('OUTBOUND', 'INBOUND')),
    CONSTRAINT ck_sc_fulfillment_call_redaction CHECK (redaction_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商网络调用脱敏传输审计';

-- 表中文名：供应商账号余额快照
-- 核心职责：追加记录供应商账号级资金、授信或通用额度观察
CREATE TABLE sc_supplier_account_balance_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商账号余额观察内部主键',
    connector_version_id BIGINT NOT NULL COMMENT '连接器版本主键，逻辑引用sc_supplier_connector_version.id',
    balance_type VARCHAR(32) NOT NULL COMMENT '余额类型：CASH资金，CREDIT授信，GENERAL_QUOTA通用额度',
    amount BIGINT NOT NULL COMMENT '观察到的账号余额或额度，单位为最小计量单位',
    currency CHAR(3) NULL COMMENT '资金或授信币种，一期固定CNY；无货币单位的通用额度为空',
    observed_status VARCHAR(32) NOT NULL COMMENT '观察状态：AVAILABLE可用，UNAVAILABLE不可用，UNKNOWN未知，ERROR查询异常',
    observed_at DATETIME(3) NOT NULL COMMENT '供应商账号余额观察时间，UTC',
    valid_until DATETIME(3) NOT NULL COMMENT '余额观察有效截止时间，UTC',
    supplier_request_no VARCHAR(128) NULL COMMENT '供应商余额查询请求号',
    raw_digest BINARY(32) NOT NULL COMMENT '脱敏原始余额响应SHA-256摘要',
    created_at DATETIME(3) NOT NULL COMMENT '余额快照入库时间，UTC',
    PRIMARY KEY (id),
    KEY idx_sc_supplier_balance_observed (connector_version_id, balance_type, observed_at),
    KEY idx_sc_supplier_balance_validity (connector_version_id, balance_type, valid_until),
    CONSTRAINT ck_sc_supplier_balance_amount CHECK (amount >= 0),
    CONSTRAINT ck_sc_supplier_balance_currency CHECK ((balance_type IN ('CASH', 'CREDIT') AND currency = 'CNY') OR (balance_type = 'GENERAL_QUOTA' AND currency IS NULL)),
    CONSTRAINT ck_sc_supplier_balance_time CHECK (valid_until > observed_at),
    CONSTRAINT ck_sc_supplier_balance_type CHECK (balance_type IN ('CASH', 'CREDIT', 'GENERAL_QUOTA')),
    CONSTRAINT ck_sc_supplier_balance_status CHECK (observed_status IN ('AVAILABLE', 'UNAVAILABLE', 'UNKNOWN', 'ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商Connector账号级资金授信或通用额度观察';
```

## 9. `007-reconciliation.sql`

```sql
-- 表中文名：对账批次
-- 核心职责：维护O2O和供应商履约账单的导入、账期和核对状态
CREATE TABLE sc_reconciliation_batch (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '对账批次内部主键',
    batch_no VARCHAR(32) NOT NULL COMMENT '对账批次稳定业务编号',
    source_type VARCHAR(32) NOT NULL COMMENT '对账来源：O2O_PAYMENT支付，O2O_REFUND退款，SUPPLIER_FULFILLMENT供应商履约',
    channel_code VARCHAR(32) NULL COMMENT 'O2O渠道编码，供应商履约对账为空',
    supplier_id BIGINT NULL COMMENT '供应商内部主键，O2O渠道级批次可为空',
    period_start DATETIME(3) NOT NULL COMMENT '对账账期开始时间，UTC',
    period_end DATETIME(3) NOT NULL COMMENT '对账账期结束时间，UTC',
    source_file_ref VARCHAR(512) NULL COMMENT '原始账单文件受控存储引用',
    source_digest BINARY(32) NOT NULL COMMENT '原始账单内容SHA-256摘要',
    import_version INT NOT NULL COMMENT '同一账期账单导入版本',
    total_line_count BIGINT NOT NULL DEFAULT 0 COMMENT '批次标准账单行总数',
    total_amount BIGINT NOT NULL DEFAULT 0 COMMENT '批次账单金额合计，单位为分',
    currency CHAR(3) NOT NULL COMMENT '批次账单币种，一期固定CNY',
    status SMALLINT NOT NULL COMMENT '批次状态：0待导入，10导入中，20待核对，30已完成，40失败，90人工处理',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '批次状态乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建或导入操作人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '对账批次创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '对账批次最后更新时间，UTC',
    completed_at DATETIME(3) NULL COMMENT '对账批次完成时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_reconciliation_batch_no (batch_no),
    UNIQUE KEY uk_sc_reconciliation_source_digest (source_type, source_digest, import_version),
    KEY idx_sc_reconciliation_period (source_type, period_start, period_end),
    KEY idx_sc_reconciliation_supplier (supplier_id, period_start),
    CONSTRAINT ck_sc_reconciliation_period CHECK (period_end > period_start),
    CONSTRAINT ck_sc_reconciliation_total CHECK (total_line_count >= 0 AND total_amount >= 0),
    CONSTRAINT ck_sc_reconciliation_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_reconciliation_source CHECK (source_type IN ('O2O_PAYMENT', 'O2O_REFUND', 'SUPPLIER_FULFILLMENT')),
    CONSTRAINT ck_sc_reconciliation_batch_status CHECK (status IN (0, 10, 20, 30, 40, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='O2O和供应商履约标准对账批次';

-- 表中文名：标准对账账单行
-- 核心职责：追加保存标准化支付、退款或供应商履约账单事实
CREATE TABLE sc_reconciliation_line (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '标准对账账单行内部主键',
    line_no VARCHAR(32) NOT NULL COMMENT '标准账单行稳定业务编号',
    batch_id BIGINT NOT NULL COMMENT '对账批次内部主键',
    source_line_no VARCHAR(128) NOT NULL COMMENT '原始账单中的行唯一标识',
    bill_version INT NOT NULL COMMENT '同一原始账单行的修订版本，从1递增',
    line_type VARCHAR(32) NOT NULL COMMENT '账单行类型：PAYMENT支付，REFUND退款，SUPPLIER_FULFILLMENT供应商履约',
    channel_code VARCHAR(32) NULL COMMENT 'O2O渠道编码',
    supplier_id BIGINT NULL COMMENT '供应商内部主键',
    business_key VARCHAR(128) NOT NULL COMMENT '跨账单定位交易的规范化业务键',
    transaction_id VARCHAR(128) NULL COMMENT 'O2O渠道交易标识',
    refund_no VARCHAR(128) NULL COMMENT 'O2O渠道退款编号',
    external_supplier_order_no VARCHAR(128) NULL COMMENT '供应商外部订单号',
    buyer_order_id BIGINT NULL COMMENT '匹配到的采购方订单主键',
    supplier_order_id BIGINT NULL COMMENT '匹配到的供应商订单主键',
    amount BIGINT NOT NULL COMMENT '账单行权威金额，单位为分',
    currency CHAR(3) NOT NULL COMMENT '账单行币种，一期固定CNY',
    external_status VARCHAR(64) NULL COMMENT '账单中的外部交易或履约状态',
    occurred_at DATETIME(3) NOT NULL COMMENT '账单事实发生时间，UTC',
    payload_digest BINARY(32) NOT NULL COMMENT '规范化账单行SHA-256摘要',
    created_at DATETIME(3) NOT NULL COMMENT '标准账单行入库时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_reconciliation_line_no (line_no),
    UNIQUE KEY uk_sc_reconciliation_line_version (batch_id, source_line_no, bill_version),
    KEY idx_sc_reconciliation_line_business (line_type, business_key),
    KEY idx_sc_reconciliation_line_order (buyer_order_id),
    KEY idx_sc_reconciliation_line_supplier_order (supplier_order_id),
    CONSTRAINT ck_sc_reconciliation_line_version CHECK (bill_version >= 1),
    CONSTRAINT ck_sc_reconciliation_line_amount CHECK (amount >= 0),
    CONSTRAINT ck_sc_reconciliation_line_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_reconciliation_line_type CHECK (line_type IN ('PAYMENT', 'REFUND', 'SUPPLIER_FULFILLMENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变标准支付退款或供应商履约账单行';

-- 表中文名：对账差异
-- 核心职责：维护O2O三方和供应商结算金额或状态的差异处理过程
CREATE TABLE sc_reconciliation_difference (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '对账差异内部主键',
    difference_no VARCHAR(32) NOT NULL COMMENT '对账差异稳定业务编号',
    batch_id BIGINT NOT NULL COMMENT '对账批次内部主键',
    line_id BIGINT NULL COMMENT '关联标准账单行主键，单边缺失时可为空',
    difference_type VARCHAR(64) NOT NULL COMMENT '差异类型，例如O2O_AMOUNT_MISMATCH、SUPPLIER_AMOUNT_MISMATCH或MISSING_SIDE',
    buyer_order_id BIGINT NULL COMMENT '关联采购方订单主键',
    supplier_order_id BIGINT NULL COMMENT '关联供应商订单主键',
    o2o_detail_id BIGINT NULL COMMENT '关联O2O渠道详情主键',
    source_amount BIGINT NULL COMMENT '外部渠道或供应商账单金额，单位为分',
    platform_amount BIGINT NULL COMMENT '中台订单、预留或账本金额，单位为分',
    counterparty_amount BIGINT NULL COMMENT '采购商收银台或其他对手方金额，单位为分',
    expected_supplier_amount BIGINT NULL COMMENT 'SupplierOrder预计供应商结算金额，单位为分',
    reported_supplier_amount BIGINT NULL COMMENT '供应商报告结算金额，单位为分',
    confirmed_supplier_amount BIGINT NULL COMMENT '中台确认供应商应结金额，单位为分',
    currency CHAR(3) NOT NULL COMMENT '差异金额币种，一期固定CNY',
    status SMALLINT NOT NULL COMMENT '差异状态：0等待观察，10待处理，20审核中，30无需调账已解决，40已申请调账，50已关闭，90人工处理',
    conclusion_code VARCHAR(64) NULL COMMENT '人工审核结论稳定编码',
    conclusion_note VARCHAR(1024) NULL COMMENT '人工审核脱敏说明',
    observed_until DATETIME(3) NULL COMMENT '单边缺失观察宽限截止时间，UTC',
    resolved_by BIGINT NULL COMMENT '解决差异的管理用户ID',
    resolved_at DATETIME(3) NULL COMMENT '差异解决时间，UTC',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '差异处理乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '差异创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '差异最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_reconciliation_difference_no (difference_no),
    KEY idx_sc_reconciliation_difference_batch (batch_id, status),
    KEY idx_sc_reconciliation_difference_order (buyer_order_id, status),
    KEY idx_sc_reconciliation_difference_supplier_order (supplier_order_id, status),
    KEY idx_sc_reconciliation_difference_observe (status, observed_until),
    CONSTRAINT ck_sc_reconciliation_difference_amount CHECK ((source_amount IS NULL OR source_amount >= 0) AND (platform_amount IS NULL OR platform_amount >= 0) AND (counterparty_amount IS NULL OR counterparty_amount >= 0) AND (expected_supplier_amount IS NULL OR expected_supplier_amount >= 0) AND (reported_supplier_amount IS NULL OR reported_supplier_amount >= 0) AND (confirmed_supplier_amount IS NULL OR confirmed_supplier_amount >= 0)),
    CONSTRAINT ck_sc_reconciliation_difference_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_reconciliation_difference_status CHECK (status IN (0, 10, 20, 30, 40, 50, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='O2O三方和供应商结算金额状态差异';

-- 表中文名：人工资金调账申请
-- 核心职责：维护需要双人审批并由唯一账本执行的采购商账户调账
CREATE TABLE sc_settlement_adjustment_request (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '人工资金调账申请内部主键',
    adjustment_no VARCHAR(32) NOT NULL COMMENT '人工调账申请稳定业务编号',
    difference_id BIGINT NOT NULL COMMENT '触发申请的对账差异主键',
    account_id BIGINT NOT NULL COMMENT '采购商资金账户主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键',
    direction VARCHAR(16) NOT NULL COMMENT '调账方向：CREDIT增加余额，DEBIT扣减余额',
    amount BIGINT NOT NULL COMMENT '调账金额绝对值，单位为分',
    currency CHAR(3) NOT NULL COMMENT '调账币种，一期固定CNY',
    evidence_ref VARCHAR(512) NOT NULL COMMENT '审批证据受控存储引用',
    evidence_digest BINARY(32) NOT NULL COMMENT '审批证据SHA-256摘要',
    applicant_id BIGINT NOT NULL COMMENT '调账申请人用户ID',
    approver_id BIGINT NULL COMMENT '调账审批人用户ID，必须与申请人不同',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '调账命令稳定幂等键',
    status SMALLINT NOT NULL COMMENT '申请状态：0待审批，10已批准，20已拒绝，30执行中，40执行成功，50执行失败，90人工处理',
    ledger_entry_id BIGINT NULL COMMENT '执行成功后追加的账户流水主键',
    failure_reason_code VARCHAR(64) NULL COMMENT '审批或执行失败原因码',
    approved_at DATETIME(3) NULL COMMENT '审批完成时间，UTC',
    executed_at DATETIME(3) NULL COMMENT '调账命令执行时间，UTC',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '调账申请乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '调账申请创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '调账申请最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_adjustment_no (adjustment_no),
    UNIQUE KEY uk_sc_adjustment_idempotency (idempotency_key),
    KEY idx_sc_adjustment_difference (difference_id, status),
    KEY idx_sc_adjustment_account (account_id, created_at),
    CONSTRAINT ck_sc_adjustment_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_sc_adjustment_amount CHECK (amount > 0),
    CONSTRAINT ck_sc_adjustment_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_adjustment_separation CHECK (approver_id IS NULL OR approver_id <> applicant_id),
    CONSTRAINT ck_sc_adjustment_status CHECK (status IN (0, 10, 20, 30, 40, 50, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='需要双人审批的采购商账户人工调账申请';
```

## 10. `008-timer-inbox-outbox.sql`

```sql
-- 表中文名：持久化定时任务
-- 核心职责：维护多节点可抢占、可续租、可恢复的可靠业务定时任务
CREATE TABLE sc_timer_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '持久化定时任务内部主键',
    task_no VARCHAR(32) NOT NULL COMMENT '持久化定时任务稳定业务编号',
    task_type VARCHAR(64) NOT NULL COMMENT '受控的定时任务处理器类型',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '同一任务类型内的业务幂等键',
    payload_version INT NOT NULL COMMENT '任务载荷Schema版本',
    payload_json JSON NOT NULL COMMENT '任务处理器版本化业务载荷',
    schedule_type VARCHAR(16) NOT NULL COMMENT '调度类型：ONCE一次，FIXED_DELAY固定延迟',
    next_fire_at DATETIME(3) NOT NULL COMMENT '下一次计划触发时间，UTC',
    priority INT NOT NULL DEFAULT 0 COMMENT '任务优先级，数值越大越优先',
    shard INT NOT NULL DEFAULT 0 COMMENT '稳定任务分片编号',
    status SMALLINT NOT NULL COMMENT '任务状态：0待调度，10已认领，20执行中，30等待重试，40已完成，50已取消，60死信，90人工处理',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已经开始的执行次数',
    max_attempts INT NOT NULL COMMENT '转死信前允许的最大执行次数',
    lease_owner VARCHAR(128) NULL COMMENT '当前任务租约持有节点标识',
    lease_until DATETIME(3) NULL COMMENT '当前任务租约到期时间，UTC',
    fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '每次成功认领递增的防旧执行者令牌',
    last_error_code VARCHAR(64) NULL COMMENT '最近一次执行失败原因码',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '任务状态乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '任务创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '任务最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_timer_task_no (task_no),
    UNIQUE KEY uk_sc_timer_task_idempotency (task_type, idempotency_key),
    KEY idx_sc_timer_task_scan (status, next_fire_at, lease_until, id),
    KEY idx_sc_timer_task_owner (lease_owner, lease_until),
    CONSTRAINT ck_sc_timer_task_payload CHECK (payload_version >= 1),
    CONSTRAINT ck_sc_timer_task_schedule CHECK (schedule_type IN ('ONCE', 'FIXED_DELAY')),
    CONSTRAINT ck_sc_timer_task_attempt CHECK (attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts),
    CONSTRAINT ck_sc_timer_task_shard CHECK (shard >= 0),
    CONSTRAINT ck_sc_timer_task_status CHECK (status IN (0, 10, 20, 30, 40, 50, 60, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='多节点可恢复持久化定时任务';

-- 表中文名：定时任务执行记录
-- 核心职责：追加记录持久化定时任务每次带防旧令牌的执行结果
CREATE TABLE sc_timer_execution (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '定时任务执行审计内部主键',
    execution_no VARCHAR(32) NOT NULL COMMENT '定时任务执行稳定业务编号',
    task_id BIGINT NOT NULL COMMENT '持久化定时任务内部主键',
    attempt_no INT NOT NULL COMMENT '任务执行尝试序号，从1递增',
    fencing_token BIGINT NOT NULL COMMENT '本次执行持有的防旧执行者令牌',
    worker_id VARCHAR(128) NOT NULL COMMENT '实际执行任务的节点和线程池工作者标识',
    status SMALLINT NOT NULL COMMENT '执行结果：10执行中，20成功，30失败可重试，40死信，90人工处理',
    started_at DATETIME(3) NOT NULL COMMENT '任务执行开始时间，UTC',
    finished_at DATETIME(3) NULL COMMENT '任务执行结束时间，UTC',
    duration_ms BIGINT NULL COMMENT '任务执行耗时，毫秒',
    error_code VARCHAR(64) NULL COMMENT '任务执行失败原因码',
    error_digest BINARY(32) NULL COMMENT '脱敏异常信息SHA-256摘要',
    result_digest BINARY(32) NULL COMMENT '脱敏执行结果SHA-256摘要',
    created_at DATETIME(3) NOT NULL COMMENT '执行审计入库时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_timer_execution_no (execution_no),
    UNIQUE KEY uk_sc_timer_execution_attempt (task_id, attempt_no),
    KEY idx_sc_timer_execution_started (status, started_at),
    CONSTRAINT ck_sc_timer_execution_attempt CHECK (attempt_no >= 1 AND fencing_token >= 1),
    CONSTRAINT ck_sc_timer_execution_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT ck_sc_timer_execution_status CHECK (status IN (10, 20, 30, 40, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='持久化定时任务每次执行的追加式审计';

-- 表中文名：Outbox事件
-- 核心职责：维护至少一次投递、按聚合有序和可恢复的领域事件
CREATE TABLE sc_outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Outbox事件内部主键',
    event_id VARCHAR(32) NOT NULL COMMENT 'Outbox事件稳定业务编号',
    aggregate_type VARCHAR(64) NOT NULL COMMENT '产生事件的聚合类型',
    aggregate_id VARCHAR(64) NOT NULL COMMENT '产生事件的聚合业务编号',
    aggregate_version BIGINT NOT NULL COMMENT '产生事件时的聚合版本号',
    event_type VARCHAR(128) NOT NULL COMMENT '版本稳定的领域事件类型',
    target_type VARCHAR(32) NOT NULL COMMENT '投递目标类型：LOCAL内部消费者，HTTP外部HTTP端点',
    target_name VARCHAR(128) NOT NULL COMMENT '内部消费者名或外部目标编码',
    payload_version INT NOT NULL COMMENT '事件载荷Schema版本',
    payload_json JSON NOT NULL COMMENT '版本化事件载荷',
    available_at DATETIME(3) NOT NULL COMMENT '事件最早可投递时间，UTC',
    status SMALLINT NOT NULL COMMENT '事件状态：0待投递，10已认领，20投递中，30等待重试，40已投递，50死信，90人工处理',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已经发起投递次数',
    max_attempts INT NOT NULL COMMENT '转死信前最大投递次数',
    lease_owner VARCHAR(128) NULL COMMENT '当前事件租约持有节点标识',
    lease_until DATETIME(3) NULL COMMENT '当前事件租约到期时间，UTC',
    fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '每次成功认领递增的防旧执行者令牌',
    trace_id VARCHAR(64) NOT NULL COMMENT '链路追踪标识',
    correlation_id VARCHAR(64) NULL COMMENT '跨事件业务关联标识',
    causation_id VARCHAR(64) NULL COMMENT '触发当前事件的命令或事件标识',
    last_error_code VARCHAR(64) NULL COMMENT '最近一次投递失败原因码',
    created_at DATETIME(3) NOT NULL COMMENT '事件创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '事件状态最后更新时间，UTC',
    published_at DATETIME(3) NULL COMMENT '事件成功投递时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_outbox_event_id (event_id),
    UNIQUE KEY uk_sc_outbox_aggregate_event (aggregate_type, aggregate_id, aggregate_version, event_type),
    KEY idx_sc_outbox_scan (status, available_at, lease_until, id),
    KEY idx_sc_outbox_aggregate_order (aggregate_type, aggregate_id, aggregate_version),
    CONSTRAINT ck_sc_outbox_version CHECK (aggregate_version >= 0 AND payload_version >= 1),
    CONSTRAINT ck_sc_outbox_attempt CHECK (attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts),
    CONSTRAINT ck_sc_outbox_target CHECK (target_type IN ('LOCAL', 'HTTP')),
    CONSTRAINT ck_sc_outbox_status CHECK (status IN (0, 10, 20, 30, 40, 50, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='至少一次可靠投递的Outbox事件';

-- 表中文名：Inbox消费记录
-- 核心职责：维护内部消费者的事件去重和幂等处理结果
CREATE TABLE sc_inbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Inbox消费记录内部主键',
    consumer_name VARCHAR(128) NOT NULL COMMENT '内部幂等消费者名称',
    event_id VARCHAR(32) NOT NULL COMMENT '被消费的Outbox事件稳定业务编号',
    payload_digest BINARY(32) NOT NULL COMMENT '收到的事件载荷SHA-256摘要',
    status SMALLINT NOT NULL COMMENT '消费状态：10处理中，20成功，30失败可重试，90人工处理',
    trace_id VARCHAR(64) NOT NULL COMMENT '链路追踪标识',
    received_at DATETIME(3) NOT NULL COMMENT '消费者首次收到事件时间，UTC',
    processed_at DATETIME(3) NULL COMMENT '消费者成功完成业务事务时间，UTC',
    last_error_code VARCHAR(64) NULL COMMENT '最近一次消费失败原因码',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '消费状态乐观锁版本号',
    updated_at DATETIME(3) NOT NULL COMMENT '消费记录最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_inbox_consumer_event (consumer_name, event_id),
    KEY idx_sc_inbox_status_time (status, updated_at),
    CONSTRAINT ck_sc_inbox_status CHECK (status IN (10, 20, 30, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内部事件消费者幂等Inbox记录';
```

## 11. `009-buyer-integration.sql`

```sql
-- 表中文名：采购商集成档案
-- 核心职责：维护每个采购商唯一的集成配置入口和当前发布版本
CREATE TABLE sc_buyer_integration_profile (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商集成档案内部主键',
    profile_no VARCHAR(32) NOT NULL COMMENT '采购商集成档案稳定业务编号',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    profile_name VARCHAR(128) NOT NULL COMMENT '采购商集成档案名称',
    current_version_id BIGINT NULL COMMENT '当前已发布配置版本内部主键，逻辑引用sc_buyer_integration_profile_version.id',
    status SMALLINT NOT NULL COMMENT '档案状态：10启用，20暂停新业务，90已归档',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '档案乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_integration_profile_no (profile_no),
    UNIQUE KEY uk_sc_buyer_integration_profile_buyer (buyer_id),
    KEY idx_sc_buyer_integration_profile_status (status),
    KEY idx_sc_buyer_integration_profile_current (current_version_id),
    CONSTRAINT ck_sc_buyer_integration_profile_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商唯一集成配置档案';

-- 表中文名：采购商集成档案版本
-- 核心职责：维护草稿、验证、发布、替代和退役的不可变配置版本
CREATE TABLE sc_buyer_integration_profile_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商集成档案版本内部主键',
    profile_id BIGINT NOT NULL COMMENT '采购商集成档案内部主键，逻辑引用sc_buyer_integration_profile.id',
    version_no INT NOT NULL COMMENT '同一档案下递增的配置版本号',
    status SMALLINT NOT NULL COMMENT '版本状态：0草稿，5已验证，10已发布，20已被替代，90已退役',
    effective_at DATETIME(3) NULL COMMENT '版本业务生效时间，UTC',
    validated_by BIGINT NULL COMMENT '执行配置验证的用户ID',
    validated_at DATETIME(3) NULL COMMENT '配置验证通过时间，UTC',
    published_by BIGINT NULL COMMENT '发布配置版本的用户ID',
    published_at DATETIME(3) NULL COMMENT '配置版本发布时间，UTC',
    change_reason VARCHAR(512) NOT NULL COMMENT '本版本变更或回滚原因',
    content_digest BINARY(32) NOT NULL COMMENT '版本全部能力配置的SHA-256摘要',
    current_flag TINYINT NULL COMMENT '当前已发布版本标记：1当前，NULL历史或草稿',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '草稿修改和发布状态乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_integration_version_no (profile_id, version_no),
    UNIQUE KEY uk_sc_buyer_integration_current (profile_id, current_flag),
    KEY idx_sc_buyer_integration_version_status (profile_id, status, effective_at),
    CONSTRAINT ck_sc_buyer_integration_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_sc_buyer_integration_current CHECK (current_flag IS NULL OR (current_flag = 1 AND status = 10)),
    CONSTRAINT ck_sc_buyer_integration_version_status CHECK (status IN (0, 5, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商集成配置不可变发布版本';

-- 表中文名：采购商集成能力配置
-- 核心职责：维护一个集成版本内各能力的传输、Adapter、契约和执行策略
CREATE TABLE sc_buyer_integration_capability (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商集成能力配置内部主键',
    profile_version_id BIGINT NOT NULL COMMENT '采购商集成档案版本内部主键，逻辑引用sc_buyer_integration_profile_version.id',
    capability_code VARCHAR(32) NOT NULL COMMENT '能力编码：CASHIER、ORDER_EVENT、FULFILLMENT_EVENT、CATALOG_EVENT',
    transport_mode VARCHAR(16) NOT NULL COMMENT '传输模式：RPC内部服务调用，HTTP签名网络调用',
    adapter_code VARCHAR(64) NOT NULL COMMENT '受控的采购商Adapter编码',
    contract_version VARCHAR(32) NOT NULL COMMENT '采购商集成契约版本',
    http_base_url VARCHAR(512) NULL COMMENT 'HTTP模式HTTPS基础地址，RPC模式必须为NULL',
    credential_set_id BIGINT NULL COMMENT 'HTTP出站凭证集内部主键，逻辑引用sc_buyer_credential_set.id',
    connect_timeout_ms INT NOT NULL COMMENT '连接超时时间，毫秒',
    read_timeout_ms INT NOT NULL COMMENT '读取超时时间，毫秒',
    total_timeout_ms INT NOT NULL COMMENT '单次调用端到端超时时间，毫秒',
    max_response_bytes INT NOT NULL COMMENT '允许的最大响应体字节数',
    bulkhead_concurrency INT NOT NULL COMMENT '同一Buyer同一能力的最大并发舱大小',
    rate_limit_per_second INT NOT NULL COMMENT '同一Buyer同一能力的每秒请求上限',
    circuit_breaker_policy VARCHAR(64) NOT NULL COMMENT '受控的熔断策略编码',
    retry_policy_code VARCHAR(32) NOT NULL COMMENT '重试策略：NONE、QUERY_SAME_KEY、OUTBOX_RETRY',
    status SMALLINT NOT NULL COMMENT '能力状态：10启用，20停用',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_integration_capability (profile_version_id, capability_code),
    KEY idx_sc_buyer_integration_adapter (capability_code, transport_mode, adapter_code, contract_version),
    KEY idx_sc_buyer_integration_credential (credential_set_id),
    CONSTRAINT ck_sc_buyer_integration_capability CHECK (capability_code IN ('CASHIER', 'ORDER_EVENT', 'FULFILLMENT_EVENT', 'CATALOG_EVENT')),
    CONSTRAINT ck_sc_buyer_integration_transport CHECK (transport_mode IN ('RPC', 'HTTP')),
    CONSTRAINT ck_sc_buyer_integration_endpoint CHECK ((transport_mode = 'RPC' AND http_base_url IS NULL AND credential_set_id IS NULL) OR (transport_mode = 'HTTP' AND http_base_url IS NOT NULL AND credential_set_id IS NOT NULL)),
    CONSTRAINT ck_sc_buyer_integration_timeout CHECK (connect_timeout_ms > 0 AND read_timeout_ms > 0 AND total_timeout_ms > 0),
    CONSTRAINT ck_sc_buyer_integration_limits CHECK (max_response_bytes > 0 AND bulkhead_concurrency > 0 AND rate_limit_per_second > 0),
    CONSTRAINT ck_sc_buyer_integration_retry CHECK (retry_policy_code IN ('NONE', 'QUERY_SAME_KEY', 'OUTBOX_RETRY')),
    CONSTRAINT ck_sc_buyer_integration_capability_status CHECK (status IN (10, 20))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商版本化集成能力与执行策略';

-- 表中文名：采购商凭证集
-- 核心职责：维护采购商入站或出站鉴权凭证的稳定逻辑身份
CREATE TABLE sc_buyer_credential_set (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商凭证集内部主键',
    credential_set_no VARCHAR(32) NOT NULL COMMENT '采购商凭证集稳定业务编号',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    direction VARCHAR(16) NOT NULL COMMENT '凭证方向：INBOUND采购商调用中台，OUTBOUND中台调用采购商',
    credential_code VARCHAR(64) NOT NULL COMMENT '同一采购商和方向下的凭证用途编码',
    authentication_scheme VARCHAR(32) NOT NULL COMMENT '鉴权方案，HTTP一期保留HMAC_SHA256',
    status SMALLINT NOT NULL COMMENT '凭证集状态：10启用，20暂停，90已退役',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '凭证集乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_credential_set_no (credential_set_no),
    UNIQUE KEY uk_sc_buyer_credential_set_code (buyer_id, direction, credential_code),
    KEY idx_sc_buyer_credential_set_status (buyer_id, status),
    CONSTRAINT ck_sc_buyer_credential_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_sc_buyer_credential_scheme CHECK (authentication_scheme IN ('HMAC_SHA256')),
    CONSTRAINT ck_sc_buyer_credential_set_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商入站与出站鉴权凭证集';

-- 表中文名：采购商凭证版本
-- 核心职责：维护密钥系统引用、生效窗口和ACTIVE/PREVIOUS无停机轮换位
CREATE TABLE sc_buyer_credential_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商凭证版本内部主键',
    credential_set_id BIGINT NOT NULL COMMENT '采购商凭证集内部主键，逻辑引用sc_buyer_credential_set.id',
    version_no INT NOT NULL COMMENT '同一凭证集下递增的凭证版本号',
    key_id VARCHAR(128) NOT NULL COMMENT '签名协议中可传输的密钥标识，不是密钥本身',
    secret_ref VARCHAR(512) NOT NULL COMMENT '密钥管理系统引用，禁止保存或回显明文密钥',
    masked_hint VARCHAR(128) NULL COMMENT '用于管理页确认的脱敏凭证提示',
    slot_code VARCHAR(16) NULL COMMENT '轮换位：ACTIVE当前，PREVIOUS过渡，NULL待激活或已退役',
    valid_from DATETIME(3) NOT NULL COMMENT '凭证最早可使用时间，UTC',
    valid_to DATETIME(3) NULL COMMENT '凭证最晚可验证时间，UTC，空表示未预设到期',
    status SMALLINT NOT NULL COMMENT '凭证版本状态：0待激活，10当前使用，20轮换过渡，90已退役',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    activated_by BIGINT NULL COMMENT '激活或轮换本版本的用户ID',
    activated_at DATETIME(3) NULL COMMENT '凭证版本激活时间，UTC',
    retired_at DATETIME(3) NULL COMMENT '凭证版本退役时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_credential_version_no (credential_set_id, version_no),
    UNIQUE KEY uk_sc_buyer_credential_slot (credential_set_id, slot_code),
    UNIQUE KEY uk_sc_buyer_credential_key (credential_set_id, key_id),
    KEY idx_sc_buyer_credential_validity (credential_set_id, status, valid_from, valid_to),
    CONSTRAINT ck_sc_buyer_credential_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_sc_buyer_credential_slot CHECK ((status = 0 AND slot_code IS NULL) OR (status = 10 AND slot_code = 'ACTIVE') OR (status = 20 AND slot_code = 'PREVIOUS') OR (status = 90 AND slot_code IS NULL)),
    CONSTRAINT ck_sc_buyer_credential_time CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_sc_buyer_credential_version_status CHECK (status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商密钥引用版本与无停机轮换位';

-- 表中文名：采购商HTTP访问客户端
-- 核心职责：为保留的HTTP入站方式把appKey和来源系统映射为可验证Buyer与scope
CREATE TABLE sc_buyer_access_client (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商HTTP访问客户端内部主键',
    client_no VARCHAR(32) NOT NULL COMMENT '采购商HTTP访问客户端稳定业务编号',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    client_code VARCHAR(64) NOT NULL COMMENT '同一采购商下的HTTP访问客户端编码',
    app_key VARCHAR(128) NOT NULL COMMENT 'HTTP签名中用于定位客户端的公开appKey',
    source_system VARCHAR(32) NOT NULL COMMENT '客户端允许声明的来源系统编码',
    credential_set_id BIGINT NOT NULL COMMENT 'HTTP入站凭证集内部主键，逻辑引用sc_buyer_credential_set.id',
    scope_json JSON NOT NULL COMMENT '允许该客户端使用的受控scope编码数组',
    allowed_ip_cidrs_json JSON NULL COMMENT '可选的入站IP网段白名单数组',
    status SMALLINT NOT NULL COMMENT '访问客户端状态：10启用，20暂停，90已退役',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '访问客户端乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_access_client_no (client_no),
    UNIQUE KEY uk_sc_buyer_access_app_key (app_key),
    UNIQUE KEY uk_sc_buyer_access_client_code (buyer_id, client_code),
    KEY idx_sc_buyer_access_source (source_system, status),
    KEY idx_sc_buyer_access_credential (credential_set_id),
    CONSTRAINT ck_sc_buyer_access_client_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商HTTP入站签名客户端与权限映射';

-- 表中文名：采购商集成调用日志
-- 核心职责：追加记录每次采购商集成尝试使用的配置、凭证、摘要和标准结果
CREATE TABLE sc_buyer_integration_call_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商集成调用日志内部主键',
    invocation_id VARCHAR(64) NOT NULL COMMENT '业务操作或事件投递的稳定调用标识',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    capability_code VARCHAR(32) NOT NULL COMMENT '本次调用的能力编码',
    operation_code VARCHAR(64) NOT NULL COMMENT '本次调用的标准操作编码',
    profile_version_id BIGINT NOT NULL COMMENT '实际使用的采购商集成档案版本内部主键',
    credential_version_id BIGINT NULL COMMENT '实际使用或验证的凭证版本内部主键，RPC可为NULL',
    transport_mode VARCHAR(16) NOT NULL COMMENT '本次调用的传输模式：RPC或HTTP',
    business_key_hash BINARY(32) NOT NULL COMMENT '业务键SHA-256摘要，禁止保存敏感业务键明文',
    request_digest BINARY(32) NOT NULL COMMENT '脱敏标准请求SHA-256摘要',
    response_digest BINARY(32) NULL COMMENT '脱敏标准响应SHA-256摘要',
    result_status SMALLINT NOT NULL COMMENT '调用结果：0调用中，10成功，20业务拒绝，30结果未知，40技术失败',
    error_code VARCHAR(64) NULL COMMENT '标准化调用错误码',
    http_status INT NULL COMMENT 'HTTP响应状态码，RPC或无响应时为NULL',
    duration_ms BIGINT NULL COMMENT '本次调用耗时，毫秒，调用未终结时为NULL',
    trace_id VARCHAR(64) NOT NULL COMMENT '本次调用的链路追踪标识',
    started_at DATETIME(3) NOT NULL COMMENT '本次调用开始时间，UTC',
    completed_at DATETIME(3) NULL COMMENT '本次调用结束时间，UTC',
    PRIMARY KEY (id),
    KEY idx_sc_buyer_integration_call_invocation (invocation_id, started_at, id),
    KEY idx_sc_buyer_integration_call_buyer (buyer_id, capability_code, started_at),
    KEY idx_sc_buyer_integration_call_profile (profile_version_id, started_at),
    KEY idx_sc_buyer_integration_call_result (result_status, started_at),
    KEY idx_sc_buyer_integration_call_trace (trace_id),
    CONSTRAINT ck_sc_buyer_integration_call_capability CHECK (capability_code IN ('CASHIER', 'ORDER_EVENT', 'FULFILLMENT_EVENT', 'CATALOG_EVENT')),
    CONSTRAINT ck_sc_buyer_integration_call_transport CHECK (transport_mode IN ('RPC', 'HTTP')),
    CONSTRAINT ck_sc_buyer_integration_call_result CHECK (result_status IN (0, 10, 20, 30, 40)),
    CONSTRAINT ck_sc_buyer_integration_call_time CHECK ((result_status = 0 AND completed_at IS NULL AND duration_ms IS NULL) OR (result_status <> 0 AND completed_at IS NOT NULL AND completed_at >= started_at AND duration_ms IS NOT NULL AND duration_ms >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商集成调用脱敏追加审计';

-- 表变更：Outbox事件增加采购商目标扩展
-- 核心职责：为采购商事件固化RPC/HTTP目标能力与发布配置版本
ALTER TABLE sc_outbox_event
    MODIFY COLUMN target_type VARCHAR(32) NOT NULL COMMENT '投递目标类型：LOCAL内部消费者，RPC内部服务，HTTP外部签名端点',
    ADD COLUMN target_capability_code VARCHAR(32) NULL COMMENT '采购商投递能力编码，非采购商目标可为NULL' AFTER target_name,
    ADD COLUMN target_config_version_id BIGINT NULL COMMENT '创建事件时固化的目标配置版本内部主键' AFTER target_capability_code,
    ADD KEY idx_sc_outbox_target_config (target_type, target_capability_code, target_config_version_id);

ALTER TABLE sc_outbox_event
    DROP CHECK ck_sc_outbox_target;

ALTER TABLE sc_outbox_event
    ADD CONSTRAINT ck_sc_outbox_target CHECK (target_type IN ('LOCAL', 'RPC', 'HTTP'));
```

## 12. `010-sensitive-access-audit.sql`

本变更集属于尚未执行的首版 Liquibase 基线，用于落实“所有查询、解密和导出均写入不可变审计”的要求。该表只允许追加写入，不提供更新或删除能力；当前订单模型修改直接进入对应基线叶子文件，不新增迁移补丁。

```sql
-- 表中文名：敏感数据访问审计
-- 核心职责：追加记录履约目标与交付资产的查询、解密和导出行为
CREATE TABLE sc_sensitive_access_audit (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '敏感数据访问审计内部主键',
    audit_no VARCHAR(32) NOT NULL COMMENT '敏感数据访问审计稳定业务编号',
    access_type VARCHAR(32) NOT NULL COMMENT '访问类型：TARGET_DECRYPT目标解密，ASSET_QUERY资产查询，ASSET_DECRYPT资产解密，ASSET_EXPORT资产导出',
    principal_type VARCHAR(32) NOT NULL COMMENT '访问主体类型：BUYER_SERVICE采购商服务，ADMIN_USER运营人员，SYSTEM_WORKER系统任务',
    principal_id VARCHAR(128) NOT NULL COMMENT '经过认证的访问主体稳定标识',
    buyer_id BIGINT NULL COMMENT '相关采购商内部主键，无采购商上下文时为空',
    buyer_user_digest BINARY(32) NULL COMMENT '采购商用户标识HMAC摘要，不保存用户标识明文',
    resource_type VARCHAR(32) NOT NULL COMMENT '资源类型：FULFILLMENT_REQUEST履约请求，FULFILLMENT_ASSET履约资产',
    resource_id BIGINT NOT NULL COMMENT '被访问资源内部主键',
    scope_code VARCHAR(64) NOT NULL COMMENT '授权访问所使用的权限Scope编码',
    result_status SMALLINT NOT NULL COMMENT '访问结果：10允许，20拒绝，30失败',
    reason_code VARCHAR(64) NOT NULL COMMENT '允许、拒绝或失败的稳定原因码',
    trace_id VARCHAR(64) NOT NULL COMMENT '访问链路追踪标识',
    accessed_at DATETIME(3) NOT NULL COMMENT '访问发生时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_sensitive_access_audit_no (audit_no),
    KEY idx_sc_sensitive_access_buyer_time (buyer_id, accessed_at),
    KEY idx_sc_sensitive_access_resource (resource_type, resource_id, accessed_at),
    KEY idx_sc_sensitive_access_principal (principal_type, principal_id, accessed_at),
    CONSTRAINT ck_sc_sensitive_access_type CHECK (access_type IN ('TARGET_DECRYPT', 'ASSET_QUERY', 'ASSET_DECRYPT', 'ASSET_EXPORT')),
    CONSTRAINT ck_sc_sensitive_access_principal CHECK (principal_type IN ('BUYER_SERVICE', 'ADMIN_USER', 'SYSTEM_WORKER')),
    CONSTRAINT ck_sc_sensitive_access_resource CHECK (resource_type IN ('FULFILLMENT_REQUEST', 'FULFILLMENT_ASSET')),
    CONSTRAINT ck_sc_sensitive_access_result CHECK (result_status IN (10, 20, 30))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='履约目标与交付资产查询解密导出的不可变审计';
```

## 13. `011-buyer-account-overdraft.sql`

本变更集追加在 `010` 之后，落实[《一期弱化采购商账户余额门槛》](./2026-08-17-supply-chain-phase1-buyer-account-overdraft.md)：不修改已发布的 `003`，替换账户与账本金额 `CHECK`，并更新账户列注释。

```sql
ALTER TABLE sc_buyer_account
    DROP CHECK ck_sc_buyer_account_amount;

ALTER TABLE sc_buyer_account
    ADD CONSTRAINT ck_sc_buyer_account_amount CHECK (frozen >= 0);

ALTER TABLE sc_buyer_account
    MODIFY COLUMN balance BIGINT NOT NULL DEFAULT 0
        COMMENT '账户总余额，单位为分；一期允许为负（透支扣减后）';

ALTER TABLE sc_buyer_account
    MODIFY COLUMN frozen BIGINT NOT NULL DEFAULT 0
        COMMENT '账户已冻结金额，单位为分；一期允许大于 balance';

ALTER TABLE sc_account_ledger
    DROP CHECK ck_sc_account_ledger_balance;

ALTER TABLE sc_account_ledger
    DROP CHECK ck_sc_account_ledger_frozen;

ALTER TABLE sc_account_ledger
    DROP CHECK ck_sc_account_ledger_entry_semantics;

ALTER TABLE sc_account_ledger
    ADD CONSTRAINT ck_sc_account_ledger_frozen CHECK (frozen_before >= 0 AND frozen_after >= 0);

ALTER TABLE sc_account_ledger
    ADD CONSTRAINT ck_sc_account_ledger_entry_semantics CHECK (
        (entry_type = 'DEPOSIT' AND balance_delta >= 0 AND frozen_delta = 0)
        OR (entry_type = 'FREEZE' AND balance_delta = 0 AND frozen_delta > 0)
        OR (entry_type = 'COMMIT' AND balance_delta < 0 AND frozen_delta = balance_delta)
        OR (entry_type = 'RELEASE' AND balance_delta = 0 AND frozen_delta < 0)
        OR (entry_type = 'REFUND' AND balance_delta > 0 AND frozen_delta = 0)
        OR (entry_type = 'ADJUSTMENT' AND balance_delta <> 0 AND frozen_delta = 0)
    );
```
