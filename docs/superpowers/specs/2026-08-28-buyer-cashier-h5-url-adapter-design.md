# 采购商 H5 收银台 URL Adapter 详细设计

> 日期：2026-08-28
>
> 状态：已实现，待环境联调
>
> 上游设计：
> - `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#11.5-BuyerCashierPort-契约`
> - `docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#51-saas-商城收银台编排`
> - `docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md`
>
> 参考实现：`novo-mall` `PaymentService#requestCashDeskPath` 的查询串形态，不复制海狸 `/cash/prepay`。

## 1. 目标与确认语义

旅通创建订单需要一条 POS 可打开的采购商 H5 收银台地址。本期只做「签发商城 SSO + 拼 URL」，不在中台创建海狸/吾盛预支付单。

已确认：

1. 中台 **不调用** `/cash/prepay`、不调用海狸 SSO HTTP。
2. `cashier_url` 形态对齐 `requestCashDeskPath`：
   `{h5-base-url}{h5-path}?orderNo={outTradeNo}&orderPrice={元}&token={ssoAccessToken}&source=rtmart`
3. `token` **必须是** SaaS 商城会员 SSO `accessToken`（`MemberAuthService.createTokenAfterSsoLogin` 产出），不是 `buyerUserId` 原文。
4. `source` 固定为 `rtmart`，使用常量，禁止散落字面量。
5. `orderNo` 使用中台已固化的 `outTradeNo`；`orderPrice` 由 `amountCny` 分转元，两位小数、`HALF_UP`。
6. `buyerUserId` 仍是不透明字符串；数值解释只允许发生在 member-server。

## 2. 范围与非目标

本期包括：

- member-api 新增 `MemberSsoTokenApi`（按外部三元身份签发会员 SSO）；
- member-server 实现：路由校验与 `MemberProfileResolveApiImpl` 相同，命中会员后调用 `createTokenAfterSsoLogin`；
- supply-chain-server 新增 Bean 名 `buyerCashierRpcAdapter` 的 `BuyerCashierRpcAdapter`，替换 stub 建单路径；
- 配置 `h5-base-url`（必填）与 `h5-path`（默认 `/h5/tbsg-cashier.html`）；
- 单测、契约测试、架构门禁（禁止 Adapter 把 `buyerUserId` 转 Long；日志不含 token / 完整 URL）；
- 更新 `docs/集成测试.md` 中创建订单返回收银台 URL 的说明。

本期不包括：

- 吾盛 `PlatformPointApi.createPrepay` 或商城 `BuyerCashierRpcApi` 全量四方法；
- 真实查支付 / 发起退款 / 查退款（Adapter 内保持与 stub 相同的处理中结果）；
- 修改 `BuyerCashierPort` 合同或 Facade/Lifecycle 建单编排；
- 实现 SaaS 侧 `tbsg-cashier.html` 页面本身。

## 3. 调用链

```text
旅通 POST /api/rtmart/order/create
  → InstoreChannelCommandFacade.createPayment（本地建单 + CAS 抢占，不变）
  → BuyerCashierRpcAdapter.createCashierOrder
       1. 校验 CASHIER 路由为 AIFULI_MALL / RPC / SAAS_CASHIER_RPC / v1
       2. 设置租户上下文后 Feign MemberSsoTokenApi.createToken
       3. 拼 H5 URL，返回 CREATED + cashierOrderNo=outTradeNo + launchDescriptor=URL
  → Lifecycle.recordCashierOrder 回写 cashier_url
  → 旅通 data.cashier_url
```

SSO 失败或 `h5-base-url` 未配置时抛运行时异常。Facade 已有外呼异常分支：保留原 `outTradeNo`，`cashier_url` 为空，不自动重创。禁止把失败解释成 `REJECTED`（避免误关单）。

## 4. 模块边界

### 4.1 member-api

新增 `MemberSsoTokenApi`：

```text
POST /rpc-api/member/sso/token
```

请求 DTO 字段与 `MemberProfileResolveReqDTO` 相同：`sourceSystem` / `externalTenantId` / `buyerUserId`，必填与长度约束一致，`toString()` 脱敏后两项。

响应 DTO 仅含 `accessToken`；`toString()` 不得输出 token 原文。不返回 refreshToken。

`createTokenAfterSsoLogin` 的 `platformCode` 固定为 `rtmart`，用于登录日志来源标记。

路由无效抛既有 `MEMBER_PROFILE_ROUTE_INVALID`；会员不存在或禁用返回成功且 `data=null`（Adapter 视为失败上抛，不签发空 token）。

### 4.2 member-server

实现复用 `MemberProfileResolveApiImpl` 的路由校验与 `buyerUserId → member_user.id` 解析。解析成功后调用 `MemberAuthService.createTokenAfterSsoLogin(userId, "rtmart")`。基础设施异常原样上抛。

### 4.3 supply-chain-server

`BuyerCashierRpcAdapter`：

- Bean 名 `buyerCashierRpcAdapter`，使 `BuyerCashierStubAdapter` 的 `@ConditionalOnMissingBean(name = "buyerCashierRpcAdapter")` 退位。
- `createCashierOrder`：只拼 URL 与签发 SSO；`cashierOrderNo` 等于命令中的 `outTradeNo`。
- `queryPayment` / `refund` / `queryRefund`：与当前 stub 相同，分别返回 `PENDING` / `PROCESSING` / `PROCESSING`。
- O2O 尚未迁移采购项目档案，`createLegacyCashierOrder` / `queryLegacyPayment` 继续保持现有 stub 行为，
  不签发 SaaS SSO、不改变 O2O 既有联调结果；本期仅替换 INSTORE `createCashierOrder` 的 `stub://cashier`。
- Feign 注册同时包含 `MemberProfileResolveApi` 与 `MemberSsoTokenApi`，仍不注册通用 `MemberUserApi`。
- 路由策略独立于会员资料 Adapter：能力码必须是 `CASHIER`，适配器码 `SAAS_CASHIER_RPC`；`sourceSystem/transportMode/contractVersion` 与会员 Adapter 相同。
- `externalTenantId` 仅用于框架租户头，规则与 `SaaSMemberProfileRoutePolicy.frameworkTenantId` 相同。

配置：

```yaml
aifuli:
  supply-chain:
    cashier:
      h5-base-url: ""          # 必填，例如 https://mall.example.com
      h5-path: /h5/tbsg-cashier.html
```

`h5-base-url` 去尾斜杠后与 `h5-path` 拼接。`source` 常量 `rtmart` 不进配置。
pro143 测试环境拼出 `https://fushop.qihaofu.cn/api/h5/tbsg-cashier.html`：`h5-base-url` 为
`https://fushop.qihaofu.cn`，`h5-path` 为 `/api/h5/tbsg-cashier.html`，配置放在
Nacos `supply-chain-server-test.yaml`，不在应用默认配置里写死环境域名。

## 5. 日志与安全

- 入口日志：`projectId`、档案版本、`outTradeNo`、金额、路由摘要；禁止 `buyerUserId` 全值、token、完整 `cashier_url`。
- 第三方 Feign：记录请求三元中的 `sourceSystem`、租户摘要、耗时、远程错误码；token 只记是否存在。
- URL 中的 token 必须 `URLEncoder.encode(..., UTF-8)`。

## 6. 测试

- member：路由无效、buyerUserId 非数字、用户不存在、禁用用户、成功签发；断言 `createTokenAfterSsoLogin` 的 platformCode 为 `rtmart`。
- Adapter：路由不支持 / 租户头非法 / SSO data 为空 / 基址未配置 → 抛错；成功则 URL 含 `orderNo`、两位 `orderPrice`、`source=rtmart`，且 query 中 `token` 为编码后的 SSO 值。
- `RpcConfigurationTest`：Feign clients 为上述两个专用 API。
- 架构测试：`integration.saasmall` 仍禁止 `Long.parseLong/valueOf`。
- 现有 Facade/Lifecycle 测试保持 mock Port，不改编排合同。

## 7. 联调与裁剪

本 Adapter 满足旅通 POS 跳转联调，**不满足** M2 权威查支付/查退款生产门禁。支付闭环仍依赖后续商城收银台回调或真实 `queryPayment`。
