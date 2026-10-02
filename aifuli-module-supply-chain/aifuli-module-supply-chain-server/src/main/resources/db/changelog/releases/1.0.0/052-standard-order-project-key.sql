-- 标准受理的项目永久键与身份清理（052）：
-- 1) 回执删除购买人身份列，项目永久键/历史保留键双生成列唯一键，新增指纹格式版本与不可变冻结绑定列；
-- 2) 总订单 CARD 清购买人身份并加 CHECK 约束，项目键/历史保留键双生成列唯一键，新增内部备注与真实履约目标
--    账户类型，历史 CARD 旧含用户请求 hash/业务 digest 清理为空；
-- 3) 标准履约交付资产删除专用购买人身份列及依赖索引；敏感访问审计标准旧用户摘要清 NULL；
-- 4) 每人限购 user 维度索引删除，两个独立限购规则共享实际履约手机号准确列索引；
-- 5) STANDARD_REQUEST 路由主体键统一为全局订单号，历史按 round.order_id 严格关联 CARD 总单转换；
-- 6) 既有直充限频存量覆盖门禁重新置 CLOSED，不能继承旧 READY 绕过新历史手机号覆盖。
-- 破坏性 DDL 前的 HALT 预条件检查见 db.changelog-1.0.0.yaml 052 变更集；
-- 历史含用户 hash 一律置为不可用的格式 0 历史标记，不做重算，可核验记录由离线应用流程重建格式 2。
-- 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#5-052增量历史清理与安全覆盖

-- 一、标准履约受理回执：旧 buyer+buyerOrderNo 唯一键由双生成列唯一键替代（项目回执走项目键，
-- 历史 NULL 项目回执走采购商保留键），购买人身份列删除，新冻结绑定列没有真实解析值时不补假数据。
ALTER TABLE sc_fulfillment_acceptance_receipt
    DROP KEY uk_sc_fulfillment_receipt_business;
ALTER TABLE sc_fulfillment_acceptance_receipt
    ADD COLUMN standard_project_key VARCHAR(160) GENERATED ALWAYS AS (
        CASE WHEN project_id IS NOT NULL THEN CONCAT(project_id, ':', buyer_order_no) END
    ) STORED COMMENT '项目受理永久键=项目:采购商订单号；历史NULL项目回执为空',
    ADD COLUMN legacy_buyer_key VARCHAR(160) GENERATED ALWAYS AS (
        CASE WHEN project_id IS NULL THEN CONCAT(buyer_id, ':', buyer_order_no) END
    ) STORED COMMENT '历史NULL项目回执保留键=采购商:采购商订单号；项目回执为空',
    ADD UNIQUE KEY uk_sc_fulfillment_receipt_project (standard_project_key),
    ADD UNIQUE KEY uk_sc_fulfillment_receipt_legacy (legacy_buyer_key);
ALTER TABLE sc_fulfillment_acceptance_receipt
    DROP COLUMN buyer_user_id;
ALTER TABLE sc_fulfillment_acceptance_receipt
    ADD COLUMN platform_sku_id BIGINT NULL COMMENT '受理时冻结的平台SKU内部主键；历史回执未冻结该绑定时为空' AFTER buyer_sku_code,
    ADD COLUMN target_schema_version INT NULL COMMENT '受理时冻结的履约目标Schema版本；历史回执未冻结该绑定时为空' AFTER target_schema_code,
    ADD COLUMN fingerprint_version SMALLINT NOT NULL DEFAULT 0 COMMENT '受理请求指纹格式版本：0历史未验证（含用户身份成分，不可用于重放比较），2无用户规范化格式' AFTER request_fingerprint;
ALTER TABLE sc_fulfillment_acceptance_receipt
    ADD COLUMN pricing_quote_id BIGINT NULL COMMENT '受理时消费的标准商品报价凭证主键；历史回执未绑定报价时为空' AFTER fingerprint_version,
    ADD CONSTRAINT ck_sc_fulfillment_receipt_fingerprint_version CHECK (fingerprint_version IN (0, 2));
-- 服务端解析的商品业务版本号：无可解析发布版本时为空（历史回执由 DEFAULT 0 的格式版本列统一标记为未验证）。
ALTER TABLE sc_fulfillment_acceptance_receipt
    DROP CHECK ck_sc_fulfillment_receipt_listing_version;
ALTER TABLE sc_fulfillment_acceptance_receipt
    MODIFY COLUMN listing_version_no INT NULL COMMENT '服务端解析的本次尝试商品业务版本号；无可解析发布版本时为空',
    ADD CONSTRAINT ck_sc_fulfillment_receipt_listing_version CHECK (listing_version_no IS NULL OR listing_version_no >= 1);

-- 二、供应链总订单：购买人列先改可空再清空历史 CARD，然后加不变量 CHECK（INSTORE/O2O 继续真实写入）。
ALTER TABLE sc_supply_chain_order
    MODIFY COLUMN buyer_user_id VARCHAR(128) NULL COMMENT '采购商范围内真实C端用户唯一标识；INSTORE/O2O场景受理时固化，CARD标准商品订单无购买人身份固定为空';
UPDATE sc_supply_chain_order SET buyer_user_id = NULL WHERE order_type = 'CARD';
ALTER TABLE sc_supply_chain_order
    ADD CONSTRAINT ck_sc_order_card_user_identity CHECK (order_type <> 'CARD' OR buyer_user_id IS NULL);
-- 旧 buyer+buyerOrderNo 唯一键由双生成列唯一键替代：项目 CARD 走项目键，其余订单/历史 NULL 项目 CARD 走保留键
-- （O2O 订单 buyer_order_no 为空不参与保留键），保留旧场景唯一性。
ALTER TABLE sc_supply_chain_order
    DROP KEY uk_sc_order_buyer_business;
ALTER TABLE sc_supply_chain_order
    ADD COLUMN standard_project_key VARCHAR(160) GENERATED ALWAYS AS (
        CASE WHEN order_type = 'CARD' AND project_id IS NOT NULL THEN CONCAT(project_id, ':', buyer_order_no) END
    ) STORED COMMENT '项目CARD订单永久项目键=项目:采购商订单号；其余订单为空',
    ADD COLUMN legacy_buyer_key VARCHAR(160) GENERATED ALWAYS AS (
        CASE WHEN order_type = 'CARD' AND project_id IS NOT NULL THEN NULL ELSE CONCAT(buyer_id, ':', buyer_order_no) END
    ) STORED COMMENT '历史共享订单保留键=采购商:采购商订单号；项目CARD订单为空',
    ADD UNIQUE KEY uk_sc_order_project_key (standard_project_key),
    ADD UNIQUE KEY uk_sc_order_legacy_key (legacy_buyer_key);
-- 历史 CARD 订单旧含用户请求 hash/业务 digest 清理为空（历史含用户 hash 不可标记为格式 2，可核验记录由
-- 离线应用流程按无用户格式重建）；非 CARD 行不动。
ALTER TABLE sc_supply_chain_order
    MODIFY COLUMN request_fingerprint BINARY(32) NULL COMMENT '幂等请求规范化SHA-256指纹，无用户身份成分；历史CARD订单旧含用户hash已清理为空',
    MODIFY COLUMN buyer_order_digest BINARY(32) NULL COMMENT '项目编码+采购商订单号的不可逆业务摘要SHA-256；历史CARD订单旧含用户digest已清理为空';
UPDATE sc_supply_chain_order SET request_fingerprint = NULL, buyer_order_digest = NULL WHERE order_type = 'CARD';
-- 真实履约目标账户类型与中台内部备注：账户类型来自已校验的实际履约目标而非购买人身份，历史订单未冻结时为空。
ALTER TABLE sc_supply_chain_order
    ADD COLUMN fulfillment_account_type VARCHAR(32) NULL COMMENT '履约目标真实账户类型：MOBILE手机号直充，EXTERNAL_ACCOUNT外部账号；来自已校验实际履约目标而非购买人身份，历史订单未冻结时为空' AFTER fulfillment_account_value,
    ADD COLUMN remark VARCHAR(1024) NULL COMMENT '中台订单内部业务备注，不向项目接口返回' AFTER fulfillment_account_type,
    ADD CONSTRAINT ck_sc_order_account_type CHECK (fulfillment_account_type IS NULL OR fulfillment_account_type IN ('MOBILE', 'EXTERNAL_ACCOUNT'));
-- 每人限购统计切换为实际履约目标手机号维度后，user 索引不再保留；每人与绑定手机号两个独立规则
-- 共享 (project, platform_sku, fulfillment_account_value, created_at) 准确列索引（033 已建）。
ALTER TABLE sc_supply_chain_order
    DROP KEY idx_sc_order_purchase_limit;

-- 三、标准履约交付资产：删除标准专用购买人身份列及其依赖索引；INSTORE/O2O 其他真实身份持久化不动。
ALTER TABLE sc_fulfillment_asset
    DROP KEY idx_sc_fulfillment_asset_buyer_user,
    DROP COLUMN buyer_user_id;

-- 四、敏感访问审计：本表仅审计标准履约目标与资产，标准旧购买人用户摘要清 NULL（不以零长占位），
-- 主体、项目/订单归属等其余持久化不动。
UPDATE sc_sensitive_access_audit SET buyer_user_digest = NULL WHERE buyer_user_digest IS NOT NULL;

-- 五、STANDARD_REQUEST 路由主体键统一为全局订单号：历史按 round.order_id 严格关联 CARD 总单转换
-- （HALT 预条件已保证关联成立且转换后唯一键无冲突）；无订单的历史轮次保留原键仅作审计；
-- O2O/INSTORE 的 SCENARIO_REQUEST 行不动。
UPDATE sc_supplier_routing_round r
    JOIN sc_supply_chain_order o ON o.id = r.order_id
SET r.routing_subject_key = o.order_no
WHERE r.routing_subject_type = 'STANDARD_REQUEST'
  AND r.routing_subject_key <> o.order_no;

-- 六、052 升级将既有直充限频存量覆盖门禁重新置 CLOSED：旧 READY 覆盖不含历史手机号核验，
-- 不能继承而绕过新历史手机号覆盖；密钥指纹等其余列不动，由离线核验流程重新置 READY。
UPDATE sc_recharge_frequency_migration_gate SET state = 'CLOSED' WHERE state = 'READY';
