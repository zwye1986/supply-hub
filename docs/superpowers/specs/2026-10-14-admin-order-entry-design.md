# 管理端录入真实标准订单详细设计

日期：2026-10-14。状态：用户已确认真实下单、每单一件；本轮仅本地实现与验证，不包含推送、部署、共享库写入或真实供应商交易。

## 1. 目标与范围

参考 `/Users/mac/codespace/frontend/cardshop/src/views/mc/order/index.vue` 的录入弹窗与 cardshop 的 `TKOrderApi.placeUnifiedOrder`：管理员选择项目、项目关联平台 SKU，填写项目订单号与履约目标，走真实下单链路，方便测试。供应链前端仅修改 `/Users/mac/codespace/supplychainhubweb`。

支持现有标准商品的卡券、直充及账号绑定权益；数量严格为整数 1。不新增多商品、批量录入、手填价格、指定供应商、模拟成功、补项目选品、退款或资金规则。现有资金允许透支，录入入口不新增余额不足拦截。到店购、O2O 与电影独立流程不在本轮范围。

采用管理端独立入口，复用标准订单 Facade 的平台 SKU 格式3分支。备选的服务器自行签名调用开放接口需要应用凭证且混淆管理员身份，不采用；仅插入模拟订单无法验证真实价格、资金及履约，不采用。

## 2. 身份、权限与共享命令

新增权限 `supply-chain:order:create`，既有 `supply-chain:admin` 是超集。所有录入与候选查询接口都检查此权限；不放宽原项目管理查询权限，不改变开放接口 HMAC、scope、nonce 或应用密钥规则。功能权限沿现有全平台运营 RBAC，不新增部门或租户数据范围。

项目编码由管理端路径输入；服务端从 `sc_*` 项目数据读取真实 projectId、buyerId，要求项目 ACTIVE、STANDARD_PRODUCT 能力 ENABLED；下单 Facade 再检查当前项目归属及分销商 ACTIVE。结算方仍由现有资金链路读取项目结算配置，不由录入请求指定。

共享编排内部改接收项目和分销商 ID；开放接口仍先校验原 StandardProductContext 操作码，再取其 ID。管理端使用独立 `createFromAdmin(ProjectSnapshot, request)` 入口，不构造假 appId/keyVersion，不把管理员伪装为项目应用，也不在 Facade 访问 DAL。控制器记录管理员 ID、项目编码、项目订单号及受理结果，不记录账号明文、密钥或资产。

管理录入的正文与开放标准订单相同，必须进入共享 `ApiAccessLogPathUtils` 标准订单保护根集合，包含 `/supply-chain/orders/entry/projects` 与 `/admin-api/supply-chain/orders/entry/projects` 的精确根及子路径。复用现有请求/响应正文省略、no-store、安全异常与安全 URL 规则；相似 `projects-other` 不命中，不调整其它业务的敏感策略。共享 common、web 与网关直接受影响回归纳入验证。

最终受理复用现有目录准备、首次报价独立短事务、受理事务、限购/限频、路由、资金预留及 Outbox。永久键仍为 `(projectId,buyerOrderNo)`，同平台 SKU 契约的开放接口与管理端同参回放、异参冲突；旧格式2订单同键冲突。ACCEPTED 仅为已受理，PROCESSING 及两类拒绝保留真实回执，不能显示履约成功。

## 3. HTTP 契约

管理端网关原 `/admin-api/supply-chain/**` 路由及登录安全链不变，新增资源：

- `GET /supply-chain/orders/entry/projects?projectName=&pageNo=1&pageSize=20`：按名称搜索 ACTIVE 项目分页。返回既有项目分页字段供名称、分销商与结算方展示，不返回凭证。前端有分页控件，不截断候选为第一页。
- `GET /supply-chain/orders/entry/projects/{projectCode}/products?pageNo=1&pageSize=20`：检查当前项目及标准商品能力，返回该项目未移除商品的既有分页；追加 SKU 的 `targetSchemaCode`、`targetSchemaVersion` 展示字段。候选出现不代表当前可售；最终订单仍执行现有准入。
- `POST /supply-chain/orders/entry/projects/{projectCode}`：原始 JSON 正文使用既有 `parseCreateV2` 严格闭包解析，拒绝 query string、未知字段、重复键、尾随 JSON、类型转换及数量非1。支持 `buyerOrderNo`、`platformSkuCode`、`quantity`、`target`、可选 `previousReceiptVersion`；不接收买方身份、价格、供应商或凭证。

```json
{"buyerOrderNo":"ADMIN_20261014_001","platformSkuCode":"SKU_CARD","quantity":1,"target":{"deliveryMode":"BUYER_ASSET_QUERY"}}
```

响应为原 `CommonResult<ProjectStandardOrderCreateRespDTO>`，包含回执状态、版本、拒绝原因、订单号和幂等回放标记。请求异常沿现有全局安全异常处理，不重复打印堆栈。

项目商品列表 Schema 字段只来源平台 SKU 主数据，不从名称、商品分类或价格推测。Schema 缺失/不支持时前端禁用提交并提示维护商品；后端保留权威 Schema 校验。

## 4. 前端行为

平台订单筛选区增加受权限控制的“录入订单”，打开现有 Element Plus 风格弹窗。项目按名称搜索并翻页；显示归属分销商、结算方；商品限当前项目并支持分页选择，名称与 SKU 码并列展示。

项目订单号打开弹窗时使用浏览器 UUID 生成 `ADMIN_` 前缀值，可修改、可重新生成；提交或网络失败后不自动换号，不因刷新产生第二单。切换项目清空商品、账号及回执；候选异步返回以当前项目/请求序号裁决，旧响应不能覆盖新项目。

数量只读1。VOUCHER_DELIVERY_TARGET 自动发送 BUYER_ASSET_QUERY，无账号输入；DIRECT_RECHARGE_TARGET 与 ACCOUNT_BOUND_BENEFIT_TARGET 展示 MOBILE/EXTERNAL_ACCOUNT 类型和账号输入。Schema 版本仅支持1。提交前明确确认“真实资金预留与供应商履约”，取消不发请求；提交期间禁止重复点击或关闭。

项目订单号最多128字符，履约账号沿严格 HTTP parser 的128字符上限（即使底层 Schema 的上限更大，也不能在录入页放宽当前 HTTP 契约）。可重试回执版本必须为正整数。CAS 尝试超时后清除旧回执并保留请求身份，普通同号重发读取当前权威回执，不携带可能过期的旧 CAS。

已受理刷新平台列表并保留订单号供打开详情。未受理显示真实状态与拒绝码，表单保留。可重试拒绝使用明确按钮携带当前 receiptVersion 做 CAS 原单重试，不自动重试或更换订单号；处理中新提交同号查询/续跑，不宣称成功。提交抛出网络/系统错误提示结果可能未知、应保留同号重试或查单。用户修改商品/目标/订单号后清空旧回执，避免携带旧 CAS。

## 5. 数据与交付

不修改订单/账本 DDL。2026-10-14 用户补充确认：录入菜单及当前超级管理员 `admin` 的显式授权改由 system-server 自己的 Liquibase 管理，代码不跨库访问 system 表。本轮只生成、验证与本地提交迁移，不执行共享库迁移或部署。

### 5.1 system 菜单迁移

追加 `016-supply-chain-order-entry-menu`，注册在 system `db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`，不修改已存在的001–015变更集。幂等建立/收敛 `application_code=supply-chain-admin`、`permission=supply-chain:order:create` 的“录入订单”按钮，父节点动态解析既有启用订单页面（query权限）；保留其它应用的同名权限、软删除历史和所有既有角色关系。前置条件缺失或有效菜单重复时 HALT，不用 MARK_RAN 静默跳过。

旧 `script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql` 仅移出本轮新增的录入按钮块并保留 Liquibase 指针；存量菜单、订单页面及其它功能块保持不变，不借本任务重新迁移整棵历史菜单树。

### 5.2 当前 admin 的显式授权迁移

追加独立 `017-supply-chain-order-entry-admin-grant`。从 `system_users` 精确用户名 `admin` 联查有效 `system_user_role` 与同租户、启用的精确角色编码 `super_admin`；用户也必须启用且未删除。tenant_id、user_id、role_id 均从真实记录解析，不硬编码1，也不只按角色名称、全部超级角色或普通 `admin` 同名用户授予。

只向这些现有超级角色补充录入按钮的 `system_role_menu` 行，显式写入角色真实 tenant_id，按有效 `(tenant_id,role_id,menu_id)` 判重；软删除旧关系不当成现有授权，重复用户角色关系不产生重复授权。保留其它授权、用户及角色状态，不创建账户、角色或用户角色绑定，不把普通用户提升为超管。找不到符合上述身份的 admin 时 HALT，防止无授权却登记完成。

启用的 `super_admin` 本来就由 PermissionServiceImpl 与 AdminMenuProjectionService 获得全部启用菜单，显式关系用于遵循用户要求、形成可核验的迁移记录，不改变超管机制或扩大其它角色权限。Liquibase SQL 不触发服务层 CacheEvict；当前用户角色绑定不变且超管不依赖新增角色菜单关系，发布后重新登录/刷新权限信息获取菜单，不能以仅写入 role_menu 宣称浏览器已验收。

### 5.3 发布顺序与边界

发布时先更新携带新 `ApiAccessLogPathUtils` 保护根的网关制品，再发布带016/017的 system-server 完成菜单/授权迁移，随后更新供应链服务与前端并刷新 admin 权限信息。共享 common 是编译依赖，单独发布供应链不会改变运行中网关的路径判定；不得在旧网关仍可能记录录入正文时开放此入口。其它进程未拥有该录入路径，不因本功能要求八进程全部重启；共享 common/web 与网关直接受影响回归已纳入本地验证。

## 6. 验证与联调

测试先行：管理端真实 HTTP 权限（未登录/只读/录入/管理员）、严格正文与项目/能力拒绝；真实 MySQL/Liquibase 下管理端到共享 Facade 的受理、同参回放、异目标冲突、格式跨入口同号、独立结算方、资金与 Outbox 不重复。供应商采用本地可控测试替身，不调用共享环境。

前端执行真实 SFC 与 API 测试：卡券/账号目标、固定数量、项目切换和异步乱序、确认取消、重复提交抑制、已受理与拒绝/处理中显示、原单CAS及网络失败保持订单身份；渲染真实组件并截图检查卡券、直充和拒绝状态。运行供应链单元/架构回归及适用集成/契约门禁，运行前端现有测试、受影响 lint 与构建。记录自动化与人工联调步骤到 `docs/集成测试.md`，不以 mock 截图宣称共享环境联调完成。

Liquibase补充验证沿既有真实MySQL完整system主清单集成夹具，覆盖菜单首次生成与存量漂移收敛、再次执行零新增、混合排序规则/大小写精确边界、admin的非1编号/真实租户授权、无关联超管/普通角色/停用删除用户角色与跨租户错误关系不误授，以及缺订单页面或缺合格admin时失败关闭。按仓库门禁只执行受影响system集成测试，非供应链纯单元测试仍跳过。
