-- system-server 管理端菜单应用隔离：扩展阶段。
-- 目标库：system 数据源；不要在 supply-chain-test 执行。
-- 可先于新版 system-server 执行，旧代码会忽略新增列，新增菜单仍默认归 mall-admin。

SET @application_code_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'system_menu'
      AND COLUMN_NAME = 'application_code'
);

SET @application_code_ddl = IF(
    @application_code_column_count = 0,
    'ALTER TABLE system_menu ADD COLUMN application_code varchar(32) NOT NULL DEFAULT ''mall-admin'' COMMENT ''管理端应用标识'' AFTER id',
    'DO 0'
);

PREPARE application_code_statement FROM @application_code_ddl;
EXECUTE application_code_statement;
DEALLOCATE PREPARE application_code_statement;

UPDATE system_menu
SET application_code = 'mall-admin'
WHERE application_code IS NULL OR application_code = '';
