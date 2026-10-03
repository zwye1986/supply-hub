-- AD-3 凭据集与凭据项（054，F1-hybrid）：
-- 供应商密钥材料从单 sc_credential_slot 升级为「凭据集 → 多凭据项」模型，QNM 与 HUA_SHENG 共用。
-- provider_type 双路：ENV 存 env:NAME 引用（密钥不入库）；ENVELOPE 存 EnvelopeCryptoPort 信封加密密文
-- （AAD 绑定 credential_set_id|item_key 防密文搬移）。secret_ref 永不存明文；masked_hint 仅脱敏提示。
-- 护栏：录入只写不回显、任何 GET/列表只返回 masked_hint、永不入日志、KMS 主密钥版本化+轮换、写入脱敏审计。
-- 新表建于 022 本地时区迁移之后，datetime 列注释统一 Asia/Shanghai。
-- 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#52-ad-3-凭据集变更集-054-envelope-provider
-- 字段语义出处 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#71-地基变更集-ddl-全文054-凭据集--055-extra_config

-- 表中文名：供应商凭据集
-- 核心职责：一个供应商可持有多套密钥材料集合，按 set_code 供应商内唯一
CREATE TABLE sc_credential_set (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商凭据集内部主键',
    supplier_id BIGINT NOT NULL COMMENT '所属供应商内部主键，逻辑引用sc_supplier.id',
    set_code VARCHAR(32) NOT NULL COMMENT '凭据集编号，供应商内唯一',
    status SMALLINT NOT NULL COMMENT '凭据集状态：10可用，20停用，90归档',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(3) NOT NULL COMMENT '最后更新时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_credential_set (supplier_id, set_code),
    CONSTRAINT ck_sc_credential_set_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商凭据集，多密钥材料集合';

-- 表中文名：供应商凭据项
-- 核心职责：凭据集内单个密钥项引用，禁止存明文；ENVELOPE 存信封密文，ENV 存 env 引用
CREATE TABLE sc_credential_item (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '凭据项内部主键',
    credential_set_id BIGINT NOT NULL COMMENT '凭据集主键，逻辑引用sc_credential_set.id',
    item_key VARCHAR(32) NOT NULL COMMENT '凭据项键，如appSecret、privateKey、callbackPublicKey、secretKey',
    provider_type VARCHAR(32) NOT NULL COMMENT '密钥托管类型：ENV环境变量引用，ENVELOPE信封加密密文，KMS托管',
    secret_ref VARCHAR(512) NOT NULL COMMENT '密钥系统中的秘密引用或信封密文，不是秘密明文；ENV存env:NAME',
    masked_hint VARCHAR(128) NULL COMMENT '供后台识别的脱敏提示，禁止含可还原信息',
    active_secret_version INT NOT NULL DEFAULT 1 COMMENT '当前生效密钥版本',
    status SMALLINT NOT NULL COMMENT '凭据项状态：10可用，20停用，90归档',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(3) NOT NULL COMMENT '最后更新时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_credential_item (credential_set_id, item_key),
    CONSTRAINT ck_sc_credential_item_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='凭据集内的单个密钥项引用';
