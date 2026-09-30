-- 项目应用统一认证防重放 nonce 表前向迁移。
-- 设计依据:
-- docs/superpowers/specs/2026-08-23-project-app-auth-design.md#64-noncestore-mysql-防重放

-- 表中文名：项目应用认证Nonce防重放记录
-- 核心职责：按 appId + nonce 主键唯一约束拦截重放请求，过期行由低频清扫任务删除
CREATE TABLE sc_project_auth_nonce (
    app_id VARCHAR(128) NOT NULL COMMENT '项目应用稳定标识，逻辑引用sc_project_application.app_id',
    nonce VARCHAR(128) NOT NULL COMMENT '调用方生成的Base64URL随机串',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    expire_at DATETIME(3) NOT NULL COMMENT '过期时间，UTC，取插入时刻加防重放窗口上限',
    PRIMARY KEY (app_id, nonce)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目应用认证Nonce防重放记录';
