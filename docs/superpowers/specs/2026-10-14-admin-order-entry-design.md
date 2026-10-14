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

已受理刷新平台列表并保留订单号供打开详情。未受理显示真实状态与拒绝码，表单保留。可重试拒绝使用明确按钮携带当前 receiptVersion 做 CAS 原单重试，不自动重试或更换订单号；处理中新提交同号查询/续跑，不宣称成功。提交抛出网络/系统错误提示结果可能未知、应保留同号重试或查单。用户修改商品/目标/订单号后清空旧回执，避免携带旧 CAS。

## 5. 数据与交付

不修改订单/账本 DDL。系统库菜单权限仅通过 `script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql` 的幂等按钮种子维护；代码不跨库访问 system 表。部署 SQL 不在本轮执行；细权限角色上线时需要管理员分配录入权限并刷新登录态。

## 6. 验证与联调

测试先行：管理端真实 HTTP 权限（未登录/只读/录入/管理员）、严格正文与项目/能力拒绝；真实 MySQL/Liquibase 下管理端到共享 Facade 的受理、同参回放、异目标冲突、格式跨入口同号、独立结算方、资金与 Outbox 不重复。供应商采用本地可控测试替身，不调用共享环境。

前端执行真实 SFC 与 API 测试：卡券/账号目标、固定数量、项目切换和异步乱序、确认取消、重复提交抑制、已受理与拒绝/处理中显示、原单CAS及网络失败保持订单身份；渲染真实组件并截图检查卡券、直充和拒绝状态。运行供应链单元/架构回归及适用集成/契约门禁，运行前端现有测试、受影响 lint 与构建。记录自动化与人工联调步骤到 `docs/集成测试.md`，不以 mock 截图宣称共享环境联调完成。
