-- AD-5 连接器版本扩展可调参数（055）：
-- 非秘密可调参数（如 HUA_SHENG expireTime秒、num、outMobile），运行时经 extraParameters 传递链注入 ticket。
-- 可空 JSON：密钥类禁止入此列（由服务层 ClosedJsonSchemaValidator + 凭据护栏守卫，模式层只提供列形态）。
-- 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#53-ad-5-extra_config变更集-055
ALTER TABLE sc_supplier_connector_version
    ADD COLUMN extra_config JSON NULL
    COMMENT '连接器版本非秘密可调参数，如expireTime秒、outMobile；密钥类禁止入此列' AFTER adapter_config_json;
