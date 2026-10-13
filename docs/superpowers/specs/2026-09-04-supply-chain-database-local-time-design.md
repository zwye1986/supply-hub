# 供应链数据库本地时间统一设计

## 1. 状态与适用范围

本文自 2026-09-04 起替换供应链一期文档中“`DATETIME(3)` 按 UTC 存储”的约定，适用于
`aifuli-module-supply-chain` 自有数据库、持久化对象、管理端/RPC 边界和 Timer/Inbox/Outbox。

本次不修改前端工程，不把时间列改成 `VARCHAR` 或 `TIMESTAMP`，不改变 `DATE` 字段语义。
当前仅处于测试联调阶段，现有时间数据已确认均按 UTC 写入且无需保留原值，但测试环境不删除数据库。
迁移采用新增 Liquibase `022-database-local-time` 原地升级：历史 `001`—`021` 保持不可变，该变更集
将已有业务时间统一加八小时并更新列注释；禁止通过删除 schema 或清空 `DATABASECHANGELOG` 绕过升级。
测试库若已执行其它 `021-*` / `022-*`（例如明文回显、供货模式子表 `MARK_RAN`），不得要求
「最后一条必须是 `021-admin-frontend-alignment`」；发布门禁改为确认尚无
`ID=022-database-local-time`，并抽样业务 `DATETIME(3)` 仍是 UTC 墙上时间。

## 2. 决策

### 2.1 存储类型与时区

1. 供应链时间列继续使用 `DATETIME(3)`，统一表示 `Asia/Shanghai` 本地墙上时间。
2. 项目开始日、结束日等没有时分秒的业务日期继续使用 `DATE`。
3. 数据库、JDBC 会话与供应链 JVM 的运行时区必须统一为 `Asia/Shanghai`；JDBC 物理连接建立后
   必须执行 `SET time_zone = '+08:00'`，禁止依赖数据库宿主机的 `SYSTEM` 默认值。
4. 内部数据库时间不携带 offset。外部协议中的 epoch/带 offset 时间先解析为绝对时刻，再转换为
   `Asia/Shanghai` 后落库；对外返回仍遵守既有接口格式，前端无需修改。

### 2.2 当前时间来源

1. 所有持久化当前时间、有效期比较、Timer/Outbox 租约及扫描截止时间，统一由
   `SupplyChainDatabaseClock` 读取 MySQL `CURRENT_TIMESTAMP(3)`。
2. 同一次业务操作需要多个时间字段一致时，只读取一次数据库时间并复用，禁止分别调用 JVM 时钟。
3. `created_at` 与 `updated_at` 暂由应用显式写入数据库时钟值，避免在本轮同时引入数据库默认值、
   `ON UPDATE` 与 MyBatis 回填三套写入来源。
4. HMAC 请求时间窗、ULID 单调性、第三方签名等“协议绝对时刻”继续使用 `Instant/Clock`，不得改成
   数据库本地墙上时间；只有与 `DATETIME(3)` 比较或落库时才转换为本地时间。
5. 新增代码禁止直接调用 `LocalDateTime.now()`、`LocalDateTime.now(ZoneOffset.UTC)` 或在持久化 SQL 中
   使用 `UTC_TIMESTAMP()`。

2026-10-13 待评审补充：日期型数字订单号提案改为从Redis消费脚本的TIME取得编号前缀时刻，并转换为
Asia/Shanghai，避免正常发号逐次查询MySQL。该提案只涉及订单号字符串前缀与号段日期校验，
`created_at`/`updated_at`、准入、有效期、账本和Timer等数据库业务时间仍遵守本节数据库时钟规则。
Redis取号故障不回退JVM时间。详细职责、跨日与回拨边界见
[订单号Redis／MySQL协作发号详细设计](2026-10-13-supply-chain-order-number-design.md#21-格式)。
本补充尚未获用户评审通过、未实施，不表示当前代码已切换编号时间来源。

### 2.3 失败语义与启动门禁

`SupplyChainDatabaseClock` 是核心数据库依赖，读取失败必须保留异常因果并使当前业务失败，不允许回退
到 JVM 时间。应用启动时校验连接会话时间相对 UTC 的偏移为 28800 秒；不满足时启动失败，避免部分
连接写 UTC、部分连接写本地时间。

## 3. 边界转换

- epoch 毫秒输入：`Instant.ofEpochMilli(value)` 后按 `Asia/Shanghai` 转为 `LocalDateTime`。
- 带 offset 的 ISO-8601 输入：先转 `Instant`，再按 `Asia/Shanghai` 转为 `LocalDateTime`。
- 数据库 `LocalDateTime` 输出 epoch：必须明确按 `Asia/Shanghai` 解释，不得依赖调用机器临时时区。
- 纯协议校验继续比较 `Instant`，不得先转无时区 `LocalDateTime`。

## 4. 字段迁移规则

本轮字段类型不变，通过新增 Liquibase `022` 将下列 `DATETIME(3)` 字段从 UTC 原地迁移到
`Asia/Shanghai`：非空历史值执行一次 `DATE_ADD(column, INTERVAL 8 HOUR)`，DDL `COMMENT` 同步从
“UTC”改为“Asia/Shanghai”。迁移完成后由数据库时钟产生的新值直接按上海本地时间写入。

`022-database-local-time` 必须同时支持两条路径：

1. 已执行 `001`—`021`（以及测试库中其它无关 `021-*` / `022-*`）的库原地升级，保留 schema、Liquibase 历史和业务主键；
2. 空库从 `001` 顺序安装到本变更集，最终结构及时间语义与升级路径一致。

历史变更集的 id、内容与 checksum 不得修改。`022` 只执行一次，不允许使用 `runAlways`、
`validCheckSum: ANY` 或清理 checksum 的方式掩盖历史变更集漂移。若数据库中已经混入上海本地时间，
不得执行该迁移；本轮依据“现有字段均为 UTC 测试数据”的明确前提实施。

MySQL 的 `ALTER TABLE` 会隐式提交，因此 `022` 必须先执行可重复的列定义/注释变更，再把全部
`DATE_ADD` 放在文件末尾连续执行，并保持 Liquibase `runInTransaction=true`。若列定义阶段中断，已执行的
`ALTER` 可安全重跑且数据尚未偏移；若数据转换阶段失败，全部 `UPDATE` 与 Liquibase 记账一并回滚。
禁止把任何 DDL 放在首条时间数据 `UPDATE` 之后，避免升级失败后重跑导致时间二次增加。

`sc_buyer_project.start_date/end_date` 是 `DATE`，不参与迁移。

### 4.1 完整字段清单

清单覆盖 102 张 `sc_` 业务表、250 个 `DATETIME(3)` 字段；Liquibase 自身的
`DATABASECHANGELOG.DATEEXECUTED` 与 `DATABASECHANGELOGLOCK.LOCKGRANTED` 不属于业务字段，
不纳入迁移范围和精度验收。

| 表 | `DATETIME(3)` 字段 |
| --- | --- |
| `sc_account_ledger` | `created_at`, `occurred_at` |
| `sc_b_order` | `completed_at`, `created_at`, `started_at`, `updated_at` |
| `sc_brand` | `created_at`, `updated_at` |
| `sc_brand_alias` | `created_at`, `updated_at` |
| `sc_buyer` | `cooperation_started_at`, `created_at`, `updated_at` |
| `sc_buyer_access_client` | `created_at`, `updated_at` |
| `sc_buyer_account` | `created_at`, `updated_at` |
| `sc_buyer_catalog_change` | `occurred_at` |
| `sc_buyer_category_scope` | `created_at` |
| `sc_buyer_contact` | `created_at`, `updated_at` |
| `sc_buyer_cooperation_mode` | `created_at` |
| `sc_buyer_credential_set` | `created_at`, `updated_at` |
| `sc_buyer_credential_version` | `activated_at`, `created_at`, `retired_at`, `valid_from`, `valid_to` |
| `sc_buyer_file_ref` | `created_at` |
| `sc_buyer_integration_call_log` | `completed_at`, `started_at` |
| `sc_buyer_integration_capability` | `created_at` |
| `sc_buyer_integration_mode` | `created_at` |
| `sc_buyer_integration_profile` | `created_at`, `updated_at` |
| `sc_buyer_integration_profile_version` | `created_at`, `effective_at`, `published_at`, `updated_at`, `validated_at` |
| `sc_buyer_listing` | `created_at`, `updated_at` |
| `sc_buyer_listing_availability` | `evaluated_at`, `updated_at` |
| `sc_buyer_listing_version` | `created_at`, `published_at`, `valid_from`, `valid_to` |
| `sc_buyer_product` | `created_at`, `updated_at` |
| `sc_buyer_product_l2_override` | `updated_at` |
| `sc_buyer_product_l2_override_version` | `created_at` |
| `sc_buyer_product_settlement_price` | `updated_at` |
| `sc_buyer_product_settlement_price_version` | `created_at`, `published_at` |
| `sc_buyer_project` | `created_at`, `updated_at` |
| `sc_buyer_tenant_binding` | `created_at`, `updated_at`, `valid_from`, `valid_to` |
| `sc_credential_slot` | `created_at`, `updated_at` |
| `sc_fulfillment_acceptance_receipt` | `accepted_at`, `created_at`, `rejected_at`, `updated_at` |
| `sc_fulfillment_asset` | `created_at`, `updated_at`, `valid_from`, `valid_until` |
| `sc_fulfillment_call_log` | `created_at`, `started_at` |
| `sc_fulfillment_exception_case` | `created_at`, `resolved_at`, `updated_at` |
| `sc_fulfillment_operation` | `completed_at`, `created_at`, `next_recover_at`, `updated_at` |
| `sc_fulfillment_request` | `created_at`, `updated_at` |
| `sc_fund_reservation` | `created_at`, `updated_at` |
| `sc_inbox_event` | `processed_at`, `received_at`, `updated_at` |
| `sc_instore_channel_order` | `completed_at`, `created_at`, `updated_at` |
| `sc_instore_channel_user_binding` | `created_at`, `token_expires_at`, `updated_at` |
| `sc_instore_entry_credential` | `created_at`, `expires_at`, `processing_started_at`, `revoked_at`, `updated_at`, `used_at` |
| `sc_instore_operation` | `completed_at`, `created_at`, `next_recover_at`, `updated_at` |
| `sc_instore_payment_flow` | `completed_at`, `created_at`, `pay_time`, `updated_at` |
| `sc_instore_refund_case` | `completed_at`, `created_at`, `updated_at` |
| `sc_instore_supplier_order` | `completed_at`, `created_at`, `updated_at` |
| `sc_inventory_snapshot` | `created_at`, `observed_at`, `valid_until` |
| `sc_inventory_source` | `created_at`, `updated_at` |
| `sc_o2o_channel_detail` | `created_at`, `updated_at` |
| `sc_o2o_channel_user_binding` | `created_at`, `updated_at` |
| `sc_o2o_operation` | `completed_at`, `created_at`, `next_recover_at`, `updated_at` |
| `sc_o2o_refund_case` | `completed_at`, `created_at`, `updated_at` |
| `sc_o2o_supplier_order` | `completed_at`, `created_at`, `updated_at` |
| `sc_order_status_log` | `created_at` |
| `sc_outbox_event` | `available_at`, `created_at`, `lease_until`, `published_at`, `updated_at` |
| `sc_platform_settlement_price` | `updated_at` |
| `sc_platform_settlement_price_version` | `created_at`, `published_at` |
| `sc_platform_sku` | `created_at`, `updated_at` |
| `sc_platform_spu` | `created_at`, `updated_at` |
| `sc_product_category` | `created_at`, `updated_at` |
| `sc_project_app_credential_version` | `created_at`, `valid_from`, `valid_to` |
| `sc_project_application` | `created_at`, `updated_at` |
| `sc_project_auth_nonce` | `created_at`, `expire_at` |
| `sc_project_capability` | `created_at`, `updated_at` |
| `sc_project_listing` | `created_at`, `updated_at` |
| `sc_project_listing_version` | `created_at`, `published_at` |
| `sc_raw_status_observation` | `observed_at`, `received_at` |
| `sc_reconciliation_batch` | `completed_at`, `created_at`, `period_end`, `period_start`, `updated_at` |
| `sc_reconciliation_difference` | `created_at`, `observed_until`, `resolved_at`, `updated_at` |
| `sc_reconciliation_line` | `created_at`, `occurred_at` |
| `sc_scenario_routing_candidate` | `created_at`, `updated_at` |
| `sc_scenario_routing_rule` | `created_at`, `updated_at` |
| `sc_sensitive_access_audit` | `accessed_at` |
| `sc_settlement_adjustment_request` | `approved_at`, `created_at`, `executed_at`, `updated_at` |
| `sc_standard_product_routing_candidate` | `created_at`, `updated_at` |
| `sc_standard_product_routing_rule` | `created_at`, `updated_at` |
| `sc_status_mapping_definition` | `created_at`, `published_at` |
| `sc_supplier` | `created_at`, `onboarded_at`, `updated_at` |
| `sc_supplier_account_balance_snapshot` | `created_at`, `observed_at`, `valid_until` |
| `sc_supplier_channel_binding` | `created_at`, `updated_at`, `valid_from`, `valid_to` |
| `sc_supplier_config_publish_record` | `created_at` |
| `sc_supplier_connector` | `created_at`, `updated_at` |
| `sc_supplier_connector_capability` | `created_at` |
| `sc_supplier_connector_version` | `created_at`, `published_at`, `updated_at`, `validated_at`, `validation_expires_at` |
| `sc_supplier_contact` | `created_at`, `updated_at` |
| `sc_supplier_delivery_region` | `created_at` |
| `sc_supplier_error_code_map` | `created_at`, `published_at` |
| `sc_supplier_external_sku_binding` | `updated_at` |
| `sc_supplier_file_ref` | `created_at` |
| `sc_supplier_integration_mode` | `created_at` |
| `sc_supplier_offer` | `created_at`, `updated_at` |
| `sc_supplier_offer_version` | `created_at`, `published_at`, `valid_from`, `valid_to` |
| `sc_supplier_routing_policy` | `created_at`, `updated_at` |
| `sc_supplier_routing_policy_version` | `created_at`, `published_at`, `updated_at`, `valid_from`, `valid_to` |
| `sc_supplier_routing_record` | `created_at`, `fact_captured_at` |
| `sc_supplier_routing_round` | `computed_at`, `created_at` |
| `sc_supplier_settlement_account` | `created_at`, `updated_at` |
| `sc_supplier_settlement_ledger` | `created_at`, `occurred_at` |
| `sc_supplier_supply_scope` | `created_at`, `updated_at` |
| `sc_supplier_supply_scope_mode` | `created_at` |
| `sc_supply_chain_order` | `completed_at`, `created_at`, `updated_at` |
| `sc_timer_execution` | `created_at`, `finished_at`, `started_at` |
| `sc_timer_task` | `created_at`, `lease_until`, `next_fire_at`, `updated_at` |

## 5. 实现清单

- [x] 数据源为每个物理连接固定 `+08:00` 会话时区并启动校验。
- [x] 新增数据库时钟组件和真实 MySQL 契约测试。
- [x] Timer、Inbox、Outbox 改用数据库时钟。
- [x] 主数据、账户、目录、路由、履约和到店购写入改用数据库时钟。
- [x] 凭证/绑定有效期比较改用数据库时钟。
- [x] 外部 `Instant` 调度时间按 `Asia/Shanghai` 转为 `LocalDateTime`。
- [x] 恢复历史 Liquibase `001`—`021` 原始内容和 checksum。
- [x] 新增 `022`，原地将 250 个历史时间字段统一加八小时并更新字段注释。
- [x] 验证已有 `021` 库原地升级到 `022`，不删除 schema、业务表或 `DATABASECHANGELOG`。
- [x] 验证空库从 `001` 安装到 `022`，250 个业务列仍为 `DATETIME(3)`。
- [x] 运行供应链单测、ContractTest、ArchitectureTest 和真实 MySQL IntegrationTest。

## 6. 验收标准

1. 测试写入前后执行 `SELECT CURRENT_TIMESTAMP(3)`，所有系统生成时间落在该区间内。
2. `TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), CURRENT_TIMESTAMP())` 为 `28800`。
3. Timer/Outbox 多节点比较只使用数据库时钟，应用节点时钟偏移不改变到期判断。
4. 数据库直接查询到的时间与 `Asia/Shanghai` 本地时间一致。
5. 管理端现有 epoch 毫秒协议保持不变，页面显示无需前端改造。
6. 代码和 DDL 不再出现面向内部持久化的 `ZoneOffset.UTC` / `UTC_TIMESTAMP()`；协议安全、ULID、
   第三方签名使用的 `Clock.systemUTC()` 不计入该限制。
7. `021` 版本库执行 `022` 后，抽样时间恰好增加八小时，业务表和 Liquibase 历史记录均未删除；
   再次启动不重复增加八小时。
8. `022` 中全部 `ALTER TABLE` 位于全部时间数据 `UPDATE` 之前，迁移文件不包含 `DROP`、`TRUNCATE`
   或 `DELETE`。
