-- 吾盛积分兑换接口升级至 v2.2。
-- 1. 仅替换旧版固定路径，避免覆盖已经配置好的其他环境地址。
-- 2. v2.2 公共参数 AppId 使用既有 app_id，签名继续使用 outbound_secret_key。
-- 3. 预下单 NotifyUrl 为环境相关地址，请在 config_json.notifyUrl 中配置完整公网 HTTPS 地址。

UPDATE `mall_platform_config`
SET `api_base_url` = REPLACE(`api_base_url`, '/ragentmall/scoreexchange', '/ragentmall/v2/scoreexchange')
WHERE `platform_code` = 'WUSHENG'
  AND TRIM(TRAILING '/' FROM `api_base_url`) LIKE '%/ragentmall/scoreexchange';

-- 发布前检查：以下查询应返回 0 行；结果中不展示任何密钥内容。
SELECT `id`, `tenant_id`, `platform_code`, `api_base_url`
FROM `mall_platform_config`
WHERE `platform_code` = 'WUSHENG'
  AND `status` = 1
  AND (
    NULLIF(TRIM(`api_base_url`), '') IS NULL
    OR TRIM(TRAILING '/' FROM `api_base_url`) NOT LIKE '%/ragentmall/v2/scoreexchange'
    OR NULLIF(TRIM(`app_id`), '') IS NULL
    OR NULLIF(TRIM(`outbound_secret_key`), '') IS NULL
    OR CASE
      WHEN JSON_VALID(`config_json`) = 1
        THEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.notifyUrl')), '') IS NULL
      ELSE TRUE
    END
  );
