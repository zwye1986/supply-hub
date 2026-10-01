-- 项目应用的查询与解密审计扩展；保留旧主体、旧行与无项目上下文，不产生跨库依赖或物理外键。
-- 设计：docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#5-审计事务与数据库增量
ALTER TABLE sc_sensitive_access_audit
  ADD COLUMN project_id BIGINT NULL COMMENT '相关采购项目内部主键，无项目上下文时为空',
  ADD COLUMN order_no VARCHAR(32) NULL COMMENT '相关中台订单稳定业务编号，尚未建单时为空',
  ADD COLUMN app_key_version INT NULL COMMENT '项目应用认证凭证版本，非项目应用访问时为空',
  ADD KEY idx_sc_sensitive_access_project_time (project_id, accessed_at),
  DROP CHECK ck_sc_sensitive_access_principal,
  MODIFY COLUMN principal_type VARCHAR(32) NOT NULL COMMENT '访问主体类型：BUYER_SERVICE采购商服务，ADMIN_USER运营人员，SYSTEM_WORKER系统任务，PROJECT_APPLICATION项目应用',
  MODIFY COLUMN scope_code VARCHAR(64) NOT NULL COMMENT '授权访问所使用的权限Scope或操作编码',
  MODIFY COLUMN accessed_at DATETIME(3) NOT NULL COMMENT '访问发生时间，Asia/Shanghai',
  ADD CONSTRAINT ck_sc_sensitive_access_principal CHECK (principal_type IN ('BUYER_SERVICE', 'ADMIN_USER', 'SYSTEM_WORKER', 'PROJECT_APPLICATION'));
