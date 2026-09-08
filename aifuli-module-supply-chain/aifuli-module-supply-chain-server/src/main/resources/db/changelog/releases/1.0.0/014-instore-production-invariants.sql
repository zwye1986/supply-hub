-- INSTORE 生产身份与凭证不变量前向迁移。
-- 设计依据：docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#42-表结构liquibase-013-instore-channelsql--014-instore-production-invariantssql

-- 旧绑定必须能唯一回填到一个当前启用的来源租户；支付卫星也必须已经满足 1:1。
-- 任一条件不满足时通过 CHECK 失败关闭，禁止猜测租户或静默丢弃支付流水。
CREATE TEMPORARY TABLE sc_instore_014_guard (
    valid TINYINT NOT NULL,
    CONSTRAINT ck_sc_instore_014_guard CHECK (valid = 1)
);

INSERT INTO sc_instore_014_guard(valid)
SELECT CASE
    WHEN EXISTS (
        SELECT 1
        FROM sc_instore_channel_user_binding iub
        LEFT JOIN sc_buyer_tenant_binding btb
          ON btb.buyer_id = iub.buyer_id
         AND btb.current_flag = 1
         AND btb.status = 10
        GROUP BY iub.id
        HAVING COUNT(btb.id) <> 1
    ) THEN 0
    WHEN EXISTS (
        SELECT 1
        FROM sc_instore_payment_flow ipf
        GROUP BY ipf.instore_order_id
        HAVING COUNT(*) > 1
    ) THEN 0
    ELSE 1
END;

DROP TEMPORARY TABLE sc_instore_014_guard;

ALTER TABLE sc_instore_channel_user_binding
    ADD COLUMN source_system VARCHAR(32) NULL
        COMMENT '采购商来源系统编码，与external_tenant_id共同确定SaaS租户' AFTER buyer_id,
    ADD COLUMN external_tenant_id VARCHAR(128) NULL
        COMMENT '来源系统中的SaaS商城租户唯一标识' AFTER source_system;

UPDATE sc_instore_channel_user_binding iub
JOIN sc_buyer_tenant_binding btb
  ON btb.buyer_id = iub.buyer_id
 AND btb.current_flag = 1
 AND btb.status = 10
SET iub.source_system = btb.source_system,
    iub.external_tenant_id = btb.external_tenant_id;

ALTER TABLE sc_instore_channel_user_binding
    DROP INDEX uk_sc_instore_user_binding_buyer,
    MODIFY COLUMN source_system VARCHAR(32) NOT NULL
        COMMENT '采购商来源系统编码，与external_tenant_id共同确定SaaS租户',
    MODIFY COLUMN external_tenant_id VARCHAR(128) NOT NULL
        COMMENT '来源系统中的SaaS商城租户唯一标识',
    MODIFY COLUMN channel_user_id VARCHAR(128) NULL
        COMMENT '013兼容匿名渠道用户标识；生产路径不再读写',
    MODIFY COLUMN token VARCHAR(256) NULL
        COMMENT '013兼容明文token；生产路径禁止读写',
    MODIFY COLUMN token_status SMALLINT NULL
        COMMENT '013兼容token状态；生产路径禁止读写',
    MODIFY COLUMN token_expires_at DATETIME(3) NULL
        COMMENT '013兼容token失效时间；生产路径禁止读写',
    ADD UNIQUE KEY uk_sc_instore_user_binding_tenant
        (source_system, external_tenant_id, buyer_user_id, channel_code),
    ADD KEY idx_sc_instore_user_binding_tenant_status
        (source_system, external_tenant_id, channel_code, status);

ALTER TABLE sc_instore_channel_user_binding
    COMMENT='采购商来源租户内C端用户的到店购稳定绑定';

ALTER TABLE sc_instore_channel_order
    ADD COLUMN source_system VARCHAR(32) NULL
        COMMENT '受理时固化的采购商来源系统编码' AFTER buyer_id,
    ADD COLUMN external_tenant_id VARCHAR(128) NULL
        COMMENT '受理时固化的SaaS商城租户唯一标识' AFTER source_system;

UPDATE sc_instore_channel_order ico
JOIN sc_instore_channel_user_binding iub
  ON iub.id = ico.channel_user_binding_id
SET ico.source_system = iub.source_system,
    ico.external_tenant_id = iub.external_tenant_id;

ALTER TABLE sc_instore_channel_order
    MODIFY COLUMN source_system VARCHAR(32) NOT NULL
        COMMENT '受理时固化的采购商来源系统编码',
    MODIFY COLUMN external_tenant_id VARCHAR(128) NOT NULL
        COMMENT '受理时固化的SaaS商城租户唯一标识',
    MODIFY COLUMN channel_user_id VARCHAR(128) NULL
        COMMENT '013兼容匿名渠道用户标识快照；生产路径不再读写',
    ADD KEY idx_sc_instore_order_tenant_user
        (source_system, external_tenant_id, buyer_user_id, created_at);

CREATE TABLE sc_instore_entry_credential (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '到店购入口凭证内部主键',
    credential_no VARCHAR(32) NOT NULL COMMENT '到店购入口凭证稳定业务编号',
    binding_id BIGINT NOT NULL COMMENT '稳定用户绑定主键，逻辑引用sc_instore_channel_user_binding.id',
    token_digest BINARY(32) NOT NULL COMMENT '入口token原文的SHA-256摘要；禁止保存原文',
    purpose VARCHAR(16) NOT NULL COMMENT '凭证用途：HOME首页，DETAIL订单详情',
    instore_order_id BIGINT NULL COMMENT 'DETAIL凭证绑定的到店购详情主键；HOME为空',
    status SMALLINT NOT NULL COMMENT '凭证状态：10 ACTIVE，15 PROCESSING，20 USED，30 EXPIRED',
    expires_at DATETIME(3) NOT NULL COMMENT '凭证失效时间，UTC',
    processing_started_at DATETIME(3) NULL COMMENT '凭证被会员查询请求独占的时间，UTC',
    used_at DATETIME(3) NULL COMMENT '会员资料成功返回后的凭证消费时间，UTC',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '凭证状态乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '凭证创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '凭证最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_instore_credential_no (credential_no),
    UNIQUE KEY uk_sc_instore_credential_digest (token_digest),
    KEY idx_sc_instore_credential_binding (binding_id, status, expires_at),
    KEY idx_sc_instore_credential_expiry (status, expires_at),
    KEY idx_sc_instore_credential_order (instore_order_id, purpose),
    CONSTRAINT ck_sc_instore_credential_purpose CHECK (
        (purpose = 'HOME' AND instore_order_id IS NULL)
        OR (purpose = 'DETAIL' AND instore_order_id IS NOT NULL)
    ),
    CONSTRAINT ck_sc_instore_credential_status CHECK (status IN (10, 15, 20, 30))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='到店购首页与详情独立短期一次性凭证';

ALTER TABLE sc_instore_payment_flow
    ADD UNIQUE KEY uk_sc_instore_flow_order (instore_order_id),
    COMMENT='到店购支付1:1兼容卫星；每个到店购详情至多一行';
