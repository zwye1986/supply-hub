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
