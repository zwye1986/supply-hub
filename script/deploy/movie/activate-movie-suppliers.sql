-- 仅在中台库执行：启用此前已插入的三家电影v1配置，不建表、不插入重复供应商。
-- 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#数据库账号与渠道选择落地2026-09-29
-- 执行前确认中台进程已配置对应env:引用的真实密钥，停止电影同步任务。
-- 本脚本是本次初始化的显式发布，不伪造自动验证通过或管理端审计记录。
-- 发布后不要原地修改JSON和凭证引用，应创建新版本并切换current_version_id。
-- 发生SQL错误必须ROLLBACK，不能继续COMMIT；执行后查询应返回三行，且ready均为1。

SET time_zone = '+08:00';
START TRANSACTION;

-- 核对目标版本、槽与应用标识已填写；不显示应用标识或秘密。
SELECT c.connector_no, c.status AS connector_status, c.current_version_id,
       v.id AS config_id, v.version_no, v.status AS config_status,
       s.status AS supplier_status, r.status AS credential_status,
       r.provider_type, JSON_UNQUOTE(JSON_EXTRACT(v.adapter_config_json, '$.channel')) AS channel
FROM sc_supplier_connector c
JOIN sc_supplier_connector_version v ON v.connector_id = c.id AND v.version_no = 1
JOIN sc_supplier s ON s.id = c.supplier_id
JOIN sc_credential_slot r ON r.id = v.credential_slot_id
WHERE c.connector_no IN ('CON_MOVIE_JC_DEFAULT', 'CON_MOVIE_JY_DEFAULT', 'CON_MOVIE_LX_DEFAULT')
FOR UPDATE;

-- 只处理已启用主体/凭证、初始草稿或已发布v1，不覆盖当前已指向其它版本的账号。
UPDATE sc_supplier_connector c
JOIN sc_supplier_connector_version v ON v.connector_id = c.id AND v.version_no = 1
JOIN sc_supplier s ON s.id = c.supplier_id
JOIN sc_credential_slot r ON r.id = v.credential_slot_id
SET c.status = 10,
    c.current_version_id = v.id,
    c.version = c.version + 1,
    c.updated_at = NOW(3),
    v.status = 10,
    v.published_at = COALESCE(v.published_at, NOW(3)),
    v.version = v.version + 1,
    v.updated_at = NOW(3)
WHERE c.connector_no IN ('CON_MOVIE_JC_DEFAULT', 'CON_MOVIE_JY_DEFAULT', 'CON_MOVIE_LX_DEFAULT')
  AND c.adapter_type IN ('MOVIE_JC_V1', 'MOVIE_JY_V1', 'MOVIE_LX_V1')
  AND c.status IN (10, 20) AND v.status IN (0, 5, 10)
  AND (c.current_version_id IS NULL OR c.current_version_id = v.id)
  AND s.status = 10 AND s.supplier_type = 'STANDARD_SUPPLIER'
  AND r.status = 10 AND r.provider_type = 'ENV'
  AND r.secret_ref LIKE 'env:%';

COMMIT;

SELECT c.connector_no, c.status, c.current_version_id, v.status AS config_status,
       (c.status = 10 AND c.current_version_id = v.id AND v.status = 10
           AND s.status = 10 AND r.status = 10) AS ready
FROM sc_supplier_connector c
JOIN sc_supplier_connector_version v ON v.connector_id = c.id AND v.version_no = 1
JOIN sc_supplier s ON s.id = c.supplier_id
JOIN sc_credential_slot r ON r.id = v.credential_slot_id
WHERE c.connector_no IN ('CON_MOVIE_JC_DEFAULT', 'CON_MOVIE_JY_DEFAULT', 'CON_MOVIE_LX_DEFAULT');
