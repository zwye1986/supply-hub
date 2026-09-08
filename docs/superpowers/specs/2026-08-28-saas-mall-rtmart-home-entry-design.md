# SaaS 商城大润发到店购 HOME 入口 BFF 详细设计

> 日期：2026-08-28  
> 状态：已确认，与中台 HOME RPC 同期实施  
> 前置设计：
> - `docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md`
> - `docs/superpowers/specs/2026-08-23-project-app-auth-design.md`
> - `docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md`

## 1. 目标与结论

登录用户在商城点击「大润发扫码购」时，由 **mall-server（trade 模块）** 作为 BFF：用当前登录会员身份签发中台 HMAC，调用 `InstoreBuyerEntryApi.getHomeEntry`，把旅通 H5 `entryUrl` 原样回给 App 打开。

核心结论：

1. App **不得**直连中台 `/rpc-api/supply-chain/**`；只调商城 `/app-api/trade/instore/rtmart/home-entry`。
2. 商城 **不持有** 旅通 `app_key`、SHA1withRSA、`company_code`；**不**在请求体传手机号、姓名、token、`companyCode`。
3. 商城只传：HMAC 头里的 `buyerUserId`（登录会员 id 的十进制字符串）+ body 经纬度。
4. HOME 企业组仍由中台配置 `aifuli.supply-chain.instore.home.company-code` 读取，商城不缓存、不覆盖。
5. HMAC 拦截器必须 **按 Feign 客户端隔离**，禁止全局 `RequestInterceptor`，以免污染 mall → member/pay/system 调用。
6. 禁止在日志、访问日志、`toString()` 中输出完整 `entryUrl`、token、`appKey`、`buyerUserId`。

## 2. 范围

### 2.1 本期范围

- App API：登录用户获取大润发 HOME 入口 URL。
- trade-server 依赖 `aifuli-module-supply-chain-api`，注册专用 Feign 客户端与 HMAC 拦截器。
- 项目应用凭证配置：`appId` / `appKey` / `keyVersion`。pro143 测试环境与中台共用 `aifuli-mall-test`，不配 `supply-chain.url`，走服务发现；仅跨命名空间时才直连。
- 单元测试：登录身份映射、请求体不含 `companyCode`、错误映射、日志脱敏、Feign 配置类不得被全局扫描成 `@Configuration`。

### 2.2 明确不在本期

- DETAIL 订单详情入口。
- App 直连中台、或在 member-server 重复暴露同一 BFF。
- 按 `appId` 绑定 `companyCode`（仍走中台过渡配置）。
- 收银台 H5 SSO（见 `docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md`）。

## 3. 模块与路径

| 项 | 选择 | 理由 |
|---|---|---|
| 进程 | `mall-server` | 用户入口是 SaaS 商城；网关已将 `/app-api/trade/**` 转到聚合服务 |
| 代码模块 | `aifuli-module-trade-server` | 到店购是购物入口，不是会员资料；登录态由 security 过滤器提供 |
| App 路径 | `POST /app-api/trade/instore/rtmart/home-entry` | Controller `@RequestMapping("/trade/instore/rtmart")` |
| 中台 RPC | `POST /rpc-api/supply-chain/instore/buyer-entry/home` | 既有 `InstoreBuyerEntryApi` |

不放 member-server：避免把购物入口挂到会员域，且 mall 与 member 是不同进程，HMAC 密钥属于采购项目应用，跟商城租户一起部署更顺。

## 4. App 契约

请求 JSON：

```json
{ "longitude": "121.4737", "latitude": "31.2304" }
```

- `longitude` / `latitude`：非空字符串，最长 32，与中台 `HomeEntryReqDTO` 及 DDL 语义一致。
- 禁止出现 `companyCode`、`buyerUserId`、手机号、姓名、token。

响应 JSON：

```json
{ "code": 0, "data": { "entryUrl": "https://..." } }
```

- `entryUrl` 原样转发；App WebView 打开。URL 内含旅通 `CXB_` token，商城不得改写、拼接或二次加前缀。
- 未登录由安全过滤器返回 401，本接口不加 `@PermitAll`。

## 5. 身份与 HMAC

1. `SecurityFrameworkUtils.getLoginUserId()` 得到商城会员主键 `Long`。
2. **仅在本 BFF 边界** 格式化为 `String.valueOf(userId)`，写入 HMAC `buyerUserId`。此后按不透明字符串处理，禁止再转 `Long` 做运算。
3. 凭证：`ProjectAppAuthCredentials(appId, appKey, keyVersion, buyerUserId, null)`；`operationCode=INSTORE_HOME_ENTRY`，scope=`BUYER_USER_ID`。
4. 使用 API 模块既有 `ProjectAppAuthFeignInterceptor`；凭证供应器在 `apply()` 时读取当前请求登录用户，依赖同步 Feign（与 Controller 同线程）。
5. 不修改 `InstoreBuyerEntryApi` 上的 `@FeignClient(configuration=...)`，避免供应链进程误建带商城拦截器的 Feign 客户端。

商城专用 Feign 接口（仅 HOME）：

- `name = ${aifuli.supply-chain.rpc.service-name:supply-chain-server}`
- `contextId = tradeInstoreBuyerEntryApi`
- `configuration = InstoreBuyerEntryFeignConfiguration`（**无** `@Configuration`，防止被 component-scan 提升为全局拦截器）
- `Logger.Level.NONE`，避免 Feign FULL 日志打出 token URL

## 6. 配置

测试、生产环境以对应 Nacos DataId 为项目应用凭证的唯一常规配置源：

```yaml
aifuli:
  mall:
    instore:
      supply-chain:
        url: ${AIFULI_MALL_INSTORE_SUPPLY_CHAIN_URL:}  # 空则服务发现
      project-app:
        app-id: rtmart-home-test
        app-key: rtmart-test-secret-2026
        key-version: 1
```

- 基础 `application.yaml` 禁止声明 `aifuli.mall.instore.project-app.*` 的空默认占位，避免 Spring Cloud
  Alibaba 默认的本地配置优先级用空值遮蔽 Nacos；本地开发值只放在 `application-local.yaml`。
- 容器环境变量可作为紧急覆盖或生产密钥注入手段，但不是 Nacos 已配置环境的启动前置条件。
- `appId`/`appKey` 均为空白：进程可启动，调用本接口返回 `INSTORE_HOME_ENTRY_NOT_CONFIGURED`。
- 只配其中一个、或 `keyVersion < 1`：启动期失败关闭。
- 密钥禁止提交到生产配置仓库明文；本地/测试可用联调值 `rtmart-home-test` / `rtmart-test-secret-2026`。
- pro143 测试环境 mall 与中台已同属 `aifuli-mall-test`，`supply-chain.url` 留空，由 Nacos 发现 `supply-chain-server`。仅跨命名空间或本机未发现中台时才配直连 URL（如 `http://127.0.0.1:58190`）。

### 6.1 pro143 项目 201 的 SaaS 租户绑定

- 测试环境项目 `201`（`INSTORE_RTMART_01`）直接绑定 SaaS 商城租户 `162`；其当前发布集成档案的
  `MEMBER_PROFILE.externalTenantId` 必须为十进制字符串 `162`。
- 改绑必须发布新的不可变集成档案版本：复制当前版本全部能力，仅把 `MEMBER_PROFILE.externalTenantId`
  从 `1` 改为 `162`，将旧版本标记为 `SUPERSEDED`，再原子切换档案的 `currentVersionId`。禁止原地修改
  已发布版本及其能力行。
- 已签发凭证继续冻结原档案版本并按十分钟有效期自然收敛；改绑完成后新签发的 HOME 凭证必须冻结新版本。
- 改绑只改变可信租户路由，不修改 `member_user`。联调会员仍须属于租户 `162` 且具有非空手机号，否则
  `MemberProfileResolveApi` 按既有契约返回 `NOT_FOUND`。
- `rtmart-home-test` 的项目应用身份和密钥版本保持不变，本次不轮换 HMAC 凭证、不改变旅通 `app_key` 或
  HOME `companyCode`。

## 7. 错误与日志

| 场景 | 行为 |
|---|---|
| 项目应用未配置 | `1_011_011_000` 到店购入口未配置 |
| 中台 `CommonResult.code != 0` | `getCheckedData()` 原样抛 `ServiceException`，把中台错误码回给 App |
| 中台返回空 `entryUrl` | `1_011_011_001` 到店购入口暂不可用 |
| Feign/网络等非业务异常 | `1_011_011_002` 到店购入口暂不可用；日志只打异常类型名 |

访问日志：`@ApiAccessLog(enable = false)`，避免请求坐标与响应 URL 入库。

业务日志：只打「申请到店购首页入口」或上游失败类型；不打 `buyerUserId`、完整 URL、`appKey`。

## 8. 测试门禁

- `InstoreLoginBuyerUserIdResolverTest`：`247L` → `"247"`；`null` → 401。
- `InstoreProjectAppAuthPropertiesTest`：空白可启动；半配/非法版本启动失败；`toString` 不含密钥。
- `MallInstoreConfigurationSourceTest`：基础 `application.yaml` 不得声明项目应用凭证；
  `application-local.yaml` 必须保留本地联调凭证，防止空占位再次遮蔽 Nacos。
- `InstoreRtmartEntryServiceImplTest`：Feign 入参只有经纬度；成功回传 URL；未配置/空 URL/上游失败；中台 `ServiceException` 透传；日志不含 URL 与用户号。
- `InstoreBuyerEntryFeignConfigurationTest`：配置类无 `@Configuration`；拦截器为 HOME 路径签出 `buyerUserId`。

不要求本期补 `*IntegrationTest` 打真实中台；人工联调写入 `docs/集成测试.md`。
