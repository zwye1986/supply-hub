# 商户共享额度管理闭环详细设计

## 1. 已确认范围

2026-10-19 用户确认：独立管理页面，按平台与精确商户账号管理共享额度；支持分页、创建、调额及历史；历史选择方案 A，只记录本次变更后的真实操作，存量明确显示“此前历史未记录”。不补造创建、初始化或迁移基线事件。

沿用 `2026-10-18-single-step-merchant-quota-design.md` 的准入与释放契约。额度跨供应商档案与 Connector 共享，不按 supplierCode 建页；不增加删除、无限额度、生产默认值，不改租约、容量等待、资金或供应商启用状态。

## 2. 配置与审计原子性

`MerchantQuotaService.configure` 仍是唯一写入所有者，在既有 supplyChainTransactionManager 的 REQUIRES_NEW 事务内完成根行锁、版本校验、写配置与追加审计。不能由外层事务单独补审计。任一写失败全部回滚；旧版本与重复创建返回明确冲突，不自动重试覆盖。

配置短事务显式使用 READ_COMMITTED：已有身份仍由根行锁串行化；首次创建无根行时不持 RR 间隙锁，使用身份唯一键决定唯一创建者，失败者转换为版本冲突。不改准入事务或释放事务的隔离契约。

新增 070 Liquibase 迁移，只创建 `sc_supplier_merchant_quota_audit`，无外键、无数据回填。字段：quota_id、before_rate_limit_per_second、after_rate_limit_per_second、before_concurrency_limit、after_concurrency_limit、quota_version、operator_id、reason、occurred_at。quota_id + quota_version 唯一；按此索引倒序分页。首次创建 version=0、before 两字段为空；调整保留真实前后值并增加版本。同值提交仍代表一次明确配置操作，记录版本与原因。不提供审计修改/删除入口。

原因为1–255字、不可全空；每秒成功准入上限和同步调用并发占用上限为正整数；账号1–128字符且不可全空，保持原值，不 trim、不改大小写。平台仅 RENYUE/QNM/SZ_CARD。身份创建后不可修改。操作人取登录上下文，不接收请求指定的操作人。

业务成功日志由 configure 在 afterCommit 记录 quotaId、version、operatorId、前后额度，不打印原因正文、账号或凭据。拒绝由既有 HTTP 异常边界处理，中间层不重复打异常。

## 3. 管理接口

前缀 `/supply-chain/merchant-quotas`，CommonResult 包装；每个映射显式 PreAuthorize，兼容 supply-chain:admin，独立权限为 supply-chain:merchant-quota:query/create/update。

- GET：pageNo 默认1，pageSize 默认10、最大100；可选 platformCode、merchantAccount 精确过滤；按 id 倒序，返回 list、total。
- GET `/{id}`：只读详情，缺失返回资源不存在。
- POST：platformCode、merchantAccount、rateLimitPerSecond、concurrencyLimit、reason；返回 id/version/额度。只允许创建，不存在 expectedVersion 输入。
- PUT `/{id}`：rateLimitPerSecond、concurrencyLimit、expectedVersion、reason；服务端按 id 读取不可变身份，不使用请求中的身份字段。
- GET `/{id}/history`：pageNo/pageSize 同上，返回 list、total、firstRecordedVersion。按 quotaVersion 倒序；只读事务使用一致快照，第一页之外也返回同一份最早版本元数据。firstRecordedVersion=0 表示从创建完整记录；null 或大于0表示此前历史未记录。存量第一次调整保留真实 before 值，但不能伪装成创建事件。

Long 标识、版本、操作人在响应中按十进制字符串传输，避免浏览器精度丢失。版本冲突、参数错误、资源缺失使用独立业务错误码；审计数据库失败仍是系统失败，不能误报业务冲突或成功。并发首次创建的唯一键拒绝转换为版本冲突，保留 cause。

## 4. 页面与部署

前端仓库 `/Users/mac/codespace/supplychainhubweb`，新页面 `src/views/supplier/merchant-quota/index.vue`，API 位于 `src/api/supplier/merchant-quota/index.ts`。沿用 ContentWrap、Element Plus、Pagination 与现有权限工具。列表含平台、精确账号、两项额度、版本、更新人、更新时间及调整/历史操作；平台与账号搜索明确标注精确匹配。

新建表单不提供生产建议默认额度；调整先读详情，身份只读，版本来自详情。必须填写原因，提交期间禁止重复点击。失败保持输入、不显示成功；版本冲突使表单失效并提示重新打开，刷新当前列表，不自动重放命令。读取失败不能留下可写默认值。历史抽屉分页，展示前后额度、版本、操作人、原因、时间；只查询权限无新增/调整按钮。旧请求晚返回不能覆盖新列表或新打开的记录。

现有通用 axios 拦截器对业务拒绝可能只返回字符串 `error`，本切片不改共享错误协议。因此所有写失败均保留输入但使原表单不可再次提交，统一提示核对当前额度与历史后重新打开；不把网络失败断言为“未提交”，也不通过消息文本猜测冲突码。

`script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql` 增加幂等页面和创建/调整按钮，不自动授予角色。菜单 SQL 只用于 system 库，供应链 Liquibase 只用于 supply-chain 库。本次不执行共享环境 SQL、不部署。

## 5. 验证门禁

真实 MySQL + 全量 Liquibase：创建/调整前后值、失败审计回滚创建和更新、同版本竞争只成功一次、精确身份分页、存量无历史及首次调整不补造历史。迁移空库/存量/重复启动与时间精度门禁同步更新计数，不修改旧迁移。

HTTP 契约：查询/创建/调整权限隔离（含详情与历史读取拒绝）、登录操作人、非法参数、未知平台、缺失记录、版本冲突、字符串长整型。历史响应同时验证审计主键、版本、操作人及最早真实版本的字符串保真；查询过滤条件保留账号原值。前端执行真实 SFC/API 测试覆盖无默认额度、只读、冲突、晚响应与历史提示，并渲染检查列表、新建/调整、存量历史状态。历史晚响应包含切换商户与关闭后重新打开；旧请求无论成功或失败，都不能覆盖当前商户的记录、完整度提示或加载状态。运行供应链单测和架构测试，复跑额度/单步相关集成门禁；如有现存失败明确分开记录。证据和操作步骤维护 `docs/集成测试.md`。
