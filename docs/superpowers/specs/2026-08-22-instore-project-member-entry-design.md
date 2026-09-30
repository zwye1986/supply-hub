# INSTORE 项目化会员资料与入口凭证详细设计

> 日期：2026-08-22  
> 状态：已确认，待实施计划  
> 前置设计：
> - `docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md`
> - `docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md`
> - `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md`
>
> 后续切片：SaaS 商城 App HOME 入口 BFF 见 `docs/superpowers/specs/2026-08-28-saas-mall-rtmart-home-entry-design.md`。

## 1. 目标与结论

本切片在已经完成的 INSTORE 项目化收银台支付纵切之上，补齐采购商用户进入大润发到店购首页、订单详情页以及旅通接口①查询会员资料的中台闭环。

核心结论：

1. 会员业务身份是 `(projectId, buyerUserId)`；`buyerUserId` 是不透明字符串，禁止转换为 `Long`。
2. 会员资料出站解析键是 `(sourceSystem, externalTenantId, buyerUserId)`；`sourceSystem/externalTenantId` 只能来自项目已发布的 `MEMBER_PROFILE` 档案版本。
3. 稳定绑定以 `(projectId, buyerUserId, channelCode)` 唯一；`externalTenantId` 保留为路由快照，但不参与会员业务唯一性。
4. 每枚 HOME/DETAIL 短期凭证冻结 `memberProfileVersionId` 和 `companyCode`，在途凭证不读取项目当前最新版本。
5. 会员资料只转发，不在供应链数据库落手机号和姓名；接口①成功响应中的 `user_code` 始终等于原始 `buyerUserId`。
6. 供应链中台签发凭证后通过 `RtmartEntryUrlPort` 取得旅通最终 URL；SaaS 商城不持有旅通 `app_key`、签名规则或 `company_code` 配置逻辑。HOME 的 `companyCode` 过渡期由中台配置 `aifuli.supply-chain.instore.home.company-code` 读取（默认 `L0000A`），不接受请求体传入；正式态改为 `appId` 绑定，不在本期实现。
7. 本切片交付稳定 RPC 契约、本地 `ApiImpl`、Facade、两个出站 Port、018 数据迁移及自动化门禁；真实 SaaS/旅通网络 Adapter 与 appId/appKey 认证不在本切片实现。

## 2. 范围

### 2.1 本期范围

- 新增 `InstoreBuyerEntryApi`：HOME 首页入口和 INSTORE 订单详情入口。
- 新增 `InstoreChannelMemberApi`：旅通接口①会员资料解析。
- 新增 `InstoreBuyerEntryFacade`、`InstoreChannelMemberFacade`。
- 新增独立 `BuyerMemberProfilePort`，按冻结会员档案路由查询 SaaS 会员资料。
- 新增 `RtmartEntryUrlPort`，表达 HOME/DETAIL 最终 URL 获取能力。
- 项目化 `InstoreChannelUserBindingResolver` 和 `InstoreEntryCredentialService`。
- 新增 Liquibase `018-instore-member-entry-route.sql`。
- 新增凭证 `REVOKED` 终态及稳定错误语义。
- 补齐单元、契约、架构与真实 MySQL 集成测试。
- 同步维护 `docs/集成测试.md` 和 INSTORE 实现总结。

### 2.2 明确不在本期

- 不实现真实 SaaS 会员 HTTP/RPC Adapter。
- 不实现真实旅通 `getRtPosUrl/home|detail` HTTP、RSA、nonce 和错误码适配。
- 不创建 `aifuli-channel-rtmart-server`。
- 不实现采购项目 appId/appKey 验签、密钥轮换或 Nonce 存储；本期只消费可信项目上下文并在缺失时失败关闭。
- 不实现退款串行、实际退款金额核对或 `BuyerAccountReturnService`。
- 不修改 O2O 会员、支付或入口协议。

## 3. 当前代码事实与必须修复的不一致

当前代码已经具备独立 `sc_instore_entry_credential`、摘要存储、HOME/DETAIL 用途、`ACTIVE→PROCESSING→USED` CAS 以及失败释放能力，但仍存在以下不一致：

- `InstoreChannelUserBindingDO` 和 017 唯一键已经以 `projectId` 为业务作用域；`InstoreChannelUserBindingResolver` 仍调用 deprecated 的 SaaS 租户四元组查询。
- `InstoreEntryCredentialService.issueHome/issueDetail` 仍直接接收 `sourceSystem/externalTenantId`，调用方可以绕过项目已发布档案。
- 凭证没有冻结 `projectId/memberProfileVersionId/companyCode`，接口①无法在不信任请求参数的情况下稳定恢复项目、会员路由和企业组。
- `BuyerMemberProfilePort`、`RtmartEntryUrlPort`、INSTORE 入口与会员 RPC 契约尚不存在。
- 项目统一认证上下文尚未落地，业务 DTO 不能为赶进度临时增加可伪造的内部 `projectId`。

本切片必须删除新业务对 `selectByTenantUser` 的依赖；deprecated 方法只保留历史兼容，不得从新 Facade 或 Service 调用。

## 4. 模块边界

### 4.1 API 层

在 `aifuli-module-supply-chain-api/.../api/instore/` 新增：

```text
InstoreBuyerEntryApi
├── getHomeEntry(HomeEntryReqDTO) -> InstoreEntryUrlRespDTO
└── getOrderDetailEntry(OrderDetailEntryReqDTO) -> InstoreEntryUrlRespDTO

InstoreChannelMemberApi
└── resolveMember(ResolveInstoreMemberReqDTO) -> InstoreMemberRespDTO
```

API 层只保存稳定业务契约。DTO 约束：

- `HomeEntryReqDTO`：按旅通协议保持 `String` 类型的非空 `longitude`、`latitude`。禁止 `companyCode`：HOME 企业组由中台配置读取，调用方不能提交或覆盖。
- `OrderDetailEntryReqDTO`：供应链采购单号 `orderNo`。
- `InstoreEntryUrlRespDTO`：非空 `entryUrl`。
- `ResolveInstoreMemberReqDTO`：原始 `token`；只允许进入会员接口①编排，不写日志和数据库。
- `InstoreMemberRespDTO`：`userCode`、`mobile`、可空 `userName`、`companyCode`。
- 上述 DTO 禁止出现 `projectId`、`buyerId`、`sourceSystem`、`externalTenantId`、`memberProfileVersionId` 或 SaaS 内部会员主键。`HomeEntryReqDTO` 额外禁止 `companyCode`。

### 4.2 Interface Adapter 层

`interfaceapi/rpc` 新增两个本地 `ApiImpl`。`ApiImpl` 不解析路由、不访问 Mapper，只完成：

1. 从可信 `ProjectCallContextAccessor` 取得调用上下文；
2. 校验当前调用所需的项目、采购商用户或渠道项目字段存在；
3. 委派对应 Facade；
4. 将稳定异常映射为 `CommonResult`。

本切片提供失败关闭的默认上下文访问器：认证 Adapter 未注入上下文时直接返回 `BUYER_AUTH_INVALID`，不得从请求 DTO、普通 HTTP Header 或 `buyerUserId` 猜测 `projectId`。后续安全切片负责在验签成功后设置并在请求结束时清理上下文。

采购商入口需要可信 `projectId + buyerId + buyerUserId`；渠道会员接口需要可信 `projectId + channelCode`。接口①从凭证恢复 `buyerUserId`，渠道上下文不得提交或覆盖会员号。

### 4.3 Application Facade 层

新增：

- `InstoreBuyerEntryFacade`
  - 校验可信项目用户；
  - 解析当前 `MEMBER_PROFILE` 版本；
  - 校验大润发 SupplierChannelBinding；
  - 建立或复用项目会员绑定；
  - 签发 HOME/DETAIL 凭证；
  - 事务外调用 `RtmartEntryUrlPort`；
  - 外呼失败时撤销刚签发的凭证。
- `InstoreChannelMemberFacade`
  - 校验渠道项目与凭证项目一致；
  - 抢占凭证；
  - 解析凭证冻结的 `MEMBER_PROFILE` 路由；
  - 事务外调用 `BuyerMemberProfilePort`；
  - 成功消费或失败释放/撤销凭证；
  - 组装接口①最小响应，不落会员资料。

Facade 禁止直接依赖 `integration.buyer.dal`、`border.instore.dal` 或其他 Mapper；所有持久化通过 Resolver、Service、Lifecycle 或查询服务完成。

### 4.4 Integration Port 层

#### BuyerMemberProfilePort

端口合同：

```java
MemberProfileResult resolve(ResolveMemberCommand command);

record ResolveMemberCommand(BuyerIntegrationRoute route, String buyerUserId) {}
record MemberProfileResult(MemberResolutionStatus status,
                           String mobile,
                           String userName) {}
```

不变量：

- `route.capability == MEMBER_PROFILE`。
- 会员身份键严格为 `route.sourceSystem + route.externalTenantId + buyerUserId`。
- `route.projectId/profileVersionId` 只用于路由配置、项目归属和审计，不改变会员身份键。
- `buyerUserId` 必须保持 String；端口、Adapter 与测试不得调用 `Long.valueOf/parseLong`。
- `FOUND` 必须携带非空手机号；姓名可以为空。
- `NOT_FOUND` 映射会员资料无效；`UNAVAILABLE` 或保留因果链的调用异常映射暂时不可用。
- 返回模型不携带 SaaS 内部会员主键，避免被核心领域误用。

#### RtmartEntryUrlPort

端口合同：

```java
EntryUrlResult getHomeUrl(HomeUrlCommand command);
EntryUrlResult getDetailUrl(DetailUrlCommand command);
```

- HOME 命令包含可信 `projectId`、`channelCode`、已由中台 HOME 配置解析并校验的 `companyCode`、经纬度和一次性 token。
- DETAIL 命令包含可信 `projectId`、`channelCode`、订单快照 `companyCode`、唯一支付流水 `tradeId` 和一次性 token。
- 命令不包含旅通 `app_key`、私钥、签名、nonce 或 URL；这些属于真实渠道 Adapter。
- 成功结果必须携带非空最终 URL。
- token 只在内存中从凭证签发结果传给 Port；禁止记录明文。

## 5. 项目集成档案解析

`BuyerIntegrationRouteResolver` 增加两个明确用例：

1. `resolveCurrent(projectId, MEMBER_PROFILE)`：仅用于新凭证签发；从项目唯一集成档案的 `currentVersionId` 解析，要求 Profile ACTIVE、Version PUBLISHED、Capability ENABLED。
2. `resolveFrozen(projectId, memberProfileVersionId, MEMBER_PROFILE)`：仅用于已签发凭证消费；校验版本归属项目并允许 PUBLISHED/SUPERSEDED，拒绝 DRAFT、VALIDATED、RETIRED。

发布新版本不能改变在途 token；SUPERSEDED 版本仍可在凭证 TTL 内完成。RETIRED 表示显式安全撤销，使用该版本的凭证转 `REVOKED`，不调用会员端口。

`sourceSystem/externalTenantId` 均从解析结果取得，不接受 API DTO、渠道请求或用户输入覆盖。

## 6. 稳定绑定

`InstoreChannelUserBindingResolver` 新业务入口调整为：

```text
resolveOrCreateStableBinding(
    buyerId,
    projectId,
    buyerUserId,
    channelCode,
    memberRoute
)
```

行为：

1. 使用 `selectByProjectUser(projectId, buyerUserId, channelCode)`。
2. 已有绑定必须 ACTIVE，且 `buyerId/projectId/buyerUserId/channelCode` 全部一致。
3. 新绑定写入项目身份及当前 `sourceSystem/externalTenantId` 路由快照。
4. 当前会员路由与已有快照不同时，以版本 CAS 更新 `sourceSystem/externalTenantId`；它们表示最近一次签发观察到的路由，不是唯一键，也不控制旧凭证。
5. 并发首创建依赖 017 唯一键；DuplicateKey 后只能按同一项目会员键回读。

## 7. Liquibase 018

新增 `018-instore-member-entry-route.sql`，并注册到 `db.changelog-1.0.0.yaml`。

### 7.1 新增列

`sc_instore_entry_credential` 增加：

| 列 | 类型 | 语义 |
|---|---|---|
| `project_id` | BIGINT NOT NULL | 凭证所属采购项目；从稳定绑定精确回填 |
| `member_profile_version_id` | BIGINT NULL | 新凭证冻结的 MEMBER_PROFILE 版本；历史终态允许为空 |
| `company_code` | VARCHAR(64) NULL | 接口①返回和旅通 URL 使用的企业组快照；历史终态允许为空 |
| `revoked_at` | DATETIME(3) NULL | 凭证撤销时间，Asia/Shanghai |
| `revoke_reason` | VARCHAR(64) NULL | 稳定撤销原因，不保存外部异常正文 |

增加索引：

- `(project_id, status, expires_at)`：项目凭证排障与清理。
- `(member_profile_version_id, status)`：版本退役影响审计。

更新表和字段注释，使状态含义为 `10 ACTIVE、15 PROCESSING、20 USED、30 EXPIRED、40 REVOKED`。

### 7.2 前向迁移与失败关闭

永久 DDL 前创建临时 guard 并验证：

- 每条历史凭证都能通过 `binding_id` 唯一找到稳定绑定；
- 稳定绑定的 `project_id` 非空；
- 不存在同一凭证关联多条绑定的异常事实。

任一条件不满足则迁移失败，不猜测项目。

通过 guard 后：

1. 增加 nullable 新列；
2. 从稳定绑定回填 `project_id`；
3. 历史 ACTIVE/PROCESSING 因无法证明签发时的会员档案版本和 companyCode，统一转为 REVOKED，写入 `revoked_at` 和 `MIGRATION_UNFROZEN_ROUTE`；
4. 历史 USED/EXPIRED 保持终态，`member_profile_version_id/company_code` 可以为空且不再参与业务读取；
5. 将 `project_id` 改为 NOT NULL；
6. 重建状态 CHECK，并增加撤销字段一致性 CHECK；
7. 增加约束：ACTIVE/PROCESSING 必须同时具有 `member_profile_version_id` 和 `company_code`。新凭证从 ACTIVE 创建，因此不能绕过快照要求；历史终态保持兼容。

## 8. 凭证状态机

```text
ACTIVE ──抢占──> PROCESSING ──会员成功──> USED
  │                    │
  │                    ├──失败/超时/手机号为空且未过期──> ACTIVE
  │                    └──已过期──> EXPIRED
  ├──已过期──> EXPIRED
  └──URL 调用失败或冻结档案 RETIRED──> REVOKED
```

状态不变量：

- USED、EXPIRED、REVOKED 均为终态。
- `markUsed/releaseProcessing/markExpired/revoke` 都校验 id、expectedVersion、当前状态和时间条件。
- 项目不匹配、用途不匹配、订单不匹配发生在抢占之前，不改变凭证状态。
- 接口①只在 `markUsed` 成功后返回会员资料；若成功查询后消费 CAS 失败，返回并发错误，不能泄露资料并假装凭证已消费。
- URL Port 失败或超时统一视为结果未知：撤销当前 token，调用方重试时签发新 token。
- 获取最终 URL 成功但调用方未收到响应时，旧 token 保持 ACTIVE；调用方重试可获得新 token。多凭证并存是已确认语义，任一 token 消费不影响其他 token。

稳定撤销原因至少包括：

- `ENTRY_URL_CALL_FAILED`
- `MEMBER_PROFILE_RETIRED`
- `MIGRATION_UNFROZEN_ROUTE`

使用枚举或常量表达，禁止散落字符串。

## 9. 业务流程

### 9.1 HOME 首页入口

1. `InstoreBuyerEntryApiImpl` 获取可信 `projectId + buyerId + buyerUserId`；DTO 只承载经纬度。
2. Facade 从配置 `aifuli.supply-chain.instore.home.company-code` 读取 HOME 企业组（过渡默认 `L0000A`），再校验对应启用的 RT_MART SupplierChannelBinding；经纬度只作为旅通 HOME 请求参数，不持久化。调用方提交的 `companyCode`（若有）必须被忽略，不得覆盖配置。
3. `resolveCurrent(projectId, MEMBER_PROFILE)` 取得当前会员路由。
4. Resolver 按项目会员键创建/复用绑定，并更新最近路由快照。
5. `InstoreEntryCredentialService` 在本地事务内创建 HOME 凭证，写入 projectId、memberProfileVersionId、companyCode、tokenDigest 和过期时间；原始 token 只返回给 Facade。
6. 事务提交后调用 `RtmartEntryUrlPort.getHomeUrl`。
7. 成功返回最终 URL；失败或超时以新事务将凭证置 REVOKED，再返回 `INSTORE_ENTRY_URL_UNAVAILABLE`。

SupplierChannelBinding 校验失败发生在签发前，不创建凭证。

### 9.2 订单详情入口

1. SaaS 只提交供应链采购单号 `orderNo`。
2. Facade 加载 BuyerOrder 与 INSTORE 详情，校验：
   - `orderType == INSTORE`；
   - `projectId` 等于可信当前项目；
   - `buyerUserId` 等于可信当前采购商用户；
   - 存在唯一 INSTORE payment flow 和非空 `tradeId`。
3. companyCode 只取 INSTORE 订单快照；调用方不能提交或覆盖。
4. 按订单项目的当前 MEMBER_PROFILE 版本签发 DETAIL 凭证，并绑定 `instoreOrderId`。
5. 事务提交后使用订单快照 companyCode、支付流水 tradeId 与新 token 调 `getDetailUrl`。
6. 成功返回最终 URL；失败撤销凭证并返回稳定错误。

详情入口不额外限制订单状态。只要归属正确且存在唯一支付流水，就允许旅通详情页展示处理中、成功、取消或失败等权威状态。

### 9.3 旅通接口①会员解析

1. `InstoreChannelMemberApiImpl` 从可信渠道项目上下文取得 `projectId + channelCode`；请求 DTO 只提供 token。
2. Service 计算 token SHA-256 摘要并读取凭证；校验 projectId、channelCode，且用途为 HOME 或 DETAIL 后 CAS 抢占 PROCESSING。
3. 使用凭证冻结的 `memberProfileVersionId` 调 `resolveFrozen`。
4. 若版本 RETIRED，凭证转 REVOKED，返回凭证已撤销；不调会员 Port。
5. 事务外调用 `BuyerMemberProfilePort.resolve(route, buyerUserId)`。
6. FOUND 且 mobile 非空时，以新事务 CAS 标记 USED；成功后组装：
   - `userCode = buyerUserId`
   - `mobile = MemberProfileResult.mobile`
   - `userName = MemberProfileResult.userName`，允许为空
   - `companyCode = credential.companyCode`
7. NOT_FOUND 或手机号为空：未过期则释放为 ACTIVE，返回会员资料无效。
8. UNAVAILABLE、超时或网络异常：保留异常因果链，未过期则释放为 ACTIVE，返回暂时不可用。
9. 释放时已经过期则转 EXPIRED；释放 CAS 失败返回并发错误。

会员查询是只读操作，因此超时后允许同一 token 重试；不得因结果未知将凭证永久卡在 PROCESSING。

## 10. 事务、并发与外部调用

- 所有数据库事务使用 `supplyChainTransactionManager`。
- 外部 Port 调用不得位于本地事务内。
- HOME/DETAIL 使用“签发事务 → Port 外呼 → 必要时撤销事务”。
- 会员接口①使用“抢占 CAS → Port 外呼 → 成功消费或失败释放事务”。
- 同一 token 并发请求只有一个能从 ACTIVE 抢占到 PROCESSING；未抢到者不得调用会员 Port。
- 同一项目用户允许并存多枚 HOME/DETAIL 凭证；凭证摘要全局唯一。
- 绑定并发创建只依赖 017 的 `(projectId,buyerUserId,channelCode)` 唯一键，不增加进程内锁。
- 所有条件更新检查影响行数；影响行数不为 1 时返回稳定并发错误，不静默继续。
- Port 抛出的异常不得吞掉；Facade 记录脱敏摘要和耗时，保留 cause 并执行对应补偿状态转换。

## 11. 稳定错误语义

在现有错误段追加：

| 错误 | 编码 | RetryDirective | 语义 |
|---|---:|---|---|
| `INSTORE_CREDENTIAL_REVOKED` | `1_050_001_043` | `DO_NOT_RETRY` | token 已被显式撤销 |
| `INSTORE_MEMBER_PROFILE_INVALID` | `1_050_001_044` | `RETRY_SAME_KEY_AFTER_CONDITION_CHANGE` | 会员不存在或手机号缺失；token 已释放 |
| `INSTORE_MEMBER_PROFILE_UNAVAILABLE` | `1_050_001_045` | `RETRY_SAME_KEY` | 会员端暂时不可用；token 已释放或过期 |
| `INSTORE_ENTRY_URL_UNAVAILABLE` | `1_050_001_046` | `RETRY_SAME_KEY` | URL 获取失败；本次 token 已撤销，API 重试会签发新 token |

项目、用户、订单归属冲突使用既有身份/归属错误；内部 ID、外部响应正文和 Java 异常消息不直接返回调用方。后续渠道服务把上述稳定错误映射为旅通 E400x/E5000，不在核心散落旅通错误码。

## 12. 安全与日志

- token 原文只存在于签发结果、Facade 局部变量和 Port 请求对象；数据库只存 32 字节 SHA-256 摘要。
- token、手机号、姓名、appKey、签名、credentialSet 不打印到日志。
- 会员 Port 调用日志记录 projectId、profileVersionId、sourceSystem、externalTenantId 脱敏摘要、结果分类和耗时；不记录会员正文。
- URL Port 调用日志记录 projectId、channelCode、companyCode、HOME/DETAIL、tradeId 脱敏摘要和耗时；不记录 token 或最终 URL 查询参数。
- API DTO 不暴露内部 projectId/buyerId；可信上下文缺失时失败关闭。
- 接口①必须先校验渠道项目与凭证项目一致，再抢占 token，避免跨项目试探消耗他人凭证。
- 会员资料不进入 DO、Mapper、Outbox、Timer 或普通订单日志。

## 13. 自动化测试与发版门禁

### 13.1 单元测试 `*Test`

- `InstoreChannelUserBindingResolverTest`
  - 按项目会员键复用与创建；
  - 两项目共享 externalTenantId/buyerUserId 时不合并；
  - 档案切换 CAS 更新最近路由快照；
  - inactive、跨 Buyer、并发 DuplicateKey 回读失败关闭；
  - 新业务不调用 deprecated 租户四元组方法。
- `InstoreEntryCredentialServiceTest`
  - 新凭证冻结 projectId/memberProfileVersionId/companyCode；
  - HOME/DETAIL 用途与订单绑定；
  - ACTIVE/PROCESSING/USED/EXPIRED/REVOKED 状态转换；
  - URL 失败撤销；会员失败释放；过期不可释放；所有 CAS 校验版本。
- `InstoreBuyerEntryFacadeTest`
  - companyCode 取自 HOME 配置且校验先于签发，不读取请求体；
  - HOME 成功返回最终 URL；
  - URL 超时撤销旧 token，重试签发新 token；
  - detail 只接受 orderNo，并从订单读取 companyCode/tradeId；
  - 项目、用户、类型或流水归属错误时不签发、不外呼；
  - 不限制订单状态。
- `InstoreChannelMemberFacadeTest`
  - 项目不匹配不抢占、不外呼；
  - 冻结版本而非 currentVersion 路由；
  - FOUND 成功后 USED；
  - NOT_FOUND、手机号为空、超时和异常释放 ACTIVE；
  - 释放时过期转 EXPIRED；
  - RETIRED 转 REVOKED；
  - markUsed/release CAS 失败不返回会员资料。

### 13.2 真实 MySQL `*IntegrationTest`

- `InstoreMemberEntryRouteMigrationIntegrationTest`
  - 017→018 前向升级；
  - 绑定缺失或 projectId 不可恢复时永久 DDL 前失败关闭；
  - 历史 ACTIVE/PROCESSING 转 REVOKED；
  - 历史 USED/EXPIRED 保持终态；
  - 新 ACTIVE/PROCESSING 缺少版本或 companyCode 被 CHECK 拒绝；
  - 018 第二次启动执行 0 个 changeSet。
- 扩展 `InstoreEntryCredentialIntegrationTest`
  - 同 token 两个并发请求只有一个 claim 成功；
  - 两项目相同 externalTenantId/buyerUserId 完全隔离；
  - URL 失败撤销后不可消费；
  - 多枚凭证并存，消费一枚不影响其他凭证。
- `SupplyChainLiquibaseIntegrationTest`
  - 空库执行 18 个 changeSet；
  - 新列、索引、CHECK、COMMENT 与 DTO 语义一致；
  - 二次启动幂等。

每个会写固定业务编码的 IntegrationTest 使用独占空 MySQL 8.4 数据库。

### 13.3 契约测试 `*ContractTest`

- `BuyerMemberProfilePortContractTest`
  - route 必须为 MEMBER_PROFILE；
  - buyerUserId 字段为 String；
  - FOUND 手机号非空；
  - 结果不含 Long 会员主键。
- `RtmartEntryUrlPortContractTest`
  - HOME/DETAIL 必填字段；
  - 命令不含 appKey、私钥或签名字段；
  - 成功 URL 非空。
- `InstoreMemberEntryApiContractTest`
  - API DTO 不含项目、Buyer 或 SaaS 路由内部字段；
  - HOME 请求只含 `longitude`/`latitude`，不含 `companyCode`；
  - `userCode/mobile/userName/companyCode` 类型与旅通接口①一致；
  - order detail 对外键为 `orderNo`，不是 `tradeId`。

### 13.4 架构测试 `*ArchitectureTest`

- INSTORE Facade 不依赖 `integration.buyer.dal` 或 `border.instore.dal`。
- API 层不依赖具体 SaaS/旅通 Adapter。
- 字段名 `buyerUserId` 的类型不得为 Long。
- 会员资料类型不得出现在 DO/Mapper/Outbox/Timer 包。
- 新业务类不得依赖 deprecated 的 `selectByTenantUser`。

### 13.5 人工联调记录

实现时同步更新 `docs/集成测试.md`，至少记录：

1. HOME 返回最终 URL；数据库只出现 token 摘要。
2. DETAIL 以 orderNo 申请，实际旅通请求使用订单 tradeId。
3. 接口①成功返回原 buyerUserId、非空手机号、可空姓名和凭证 companyCode。
4. 会员端超时后同 token 可重试。
5. URL 端超时后旧 token 已撤销，业务重试生成新 token。
6. 两个项目共享 SaaS 租户和会员号时仍不能跨项目消费凭证或查看详情。

## 14. 完成标准与后续顺序

本切片完成需同时满足：

- 018、项目化绑定、凭证冻结与 REVOKED 状态全部落地；
- 两套 RPC 契约和本地 ApiImpl 可通过测试上下文运行；
- 两个出站 Port 通过测试 Adapter 跑通 HOME、DETAIL 和会员接口①完整编排；
- 全部新增单元、契约、架构与指定真实 MySQL 门禁通过；
- 文档与实现同一变更内保持一致；
- 工作区不存在裸 TODO/TBD 或未解释的生产占位逻辑。

后续落地顺序保持：

1. INSTORE 退款串行、实际退款金额校验与 `BuyerAccountReturnService`；
2. 真实 SaaS 会员/收银台 Adapter；
3. 项目 appId/appKey 统一认证与可信上下文注入；
4. `aifuli-channel-rtmart-server`、旅通协议六接口及 HOME/DETAIL 真实 Adapter。
