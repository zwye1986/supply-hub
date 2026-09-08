# SaaS 商城会员资料三元解析 Adapter 详细设计

> 日期：2026-08-26
>
> 状态：已确认，待实现
>
> 上游设计：
> - `docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md`
> - `docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md`
> - `docs/adr/0003-instore-user-code-is-buyer-user-id.md`
> - `docs/adr/0006-project-identity-separates-external-tenant-route.md`

## 1. 目标与确认语义

本切片把 `BuyerMemberProfilePort` 的失败关闭默认实现替换为真实 SaaS 商城 RPC Adapter，完成旅通接口①所需的会员手机号、姓名查询。

已确认业务语义：

1. 供应链中的 `buyerUserId` 是采购项目范围内的不透明字符串，当前 SaaS 商城实现中其值等于
   `member_user.id` 的十进制字符串形式。
2. 供应链侧会员解析键始终是 `(sourceSystem, externalTenantId, buyerUserId)`；三项均由冻结的
   `MEMBER_PROFILE` 路由和一次性凭证恢复，不接受旅通请求临时覆盖。
3. `buyerUserId` 的数值解释只允许发生在拥有 `member_user` 数据的 member-server 内部；供应链 Port、
   Adapter、DTO、日志和测试不得调用 `Long.parseLong/Long.valueOf` 解释它。
4. `externalTenantId` 继续作为字符串路由坐标保留并随 RPC 请求发送；AIFULI_MALL Adapter 可以为了设置
   框架 `tenant-id` 上下文而严格转换其数字形式，但不得用转换结果替代三元身份键。
5. 会员手机号和姓名只在当前 RPC 响应与旅通接口①响应内存中短暂存在，不进入供应链数据库、Outbox、
   Timer 或普通日志。

## 2. 范围与非目标

本期包括：

- member-api 新增独立 `MemberProfileResolveApi` 及请求/响应 DTO；
- member-server 实现三元路由校验和内部会员主键兼容解析；
- supply-chain-server 实现真实 `SaaSMemberProfileAdapter`；
- 显式注册新的 member Feign API；
- 修复会员错误分类、租户上下文恢复和日志脱敏；
- 增加 API 契约、member 实现单测、供应链 Adapter 单测和禁止数值化的架构门禁；
- 更新 `docs/集成测试.md` 与 INSTORE 实现总结。

本期不包括：

- 给 `member_user` 增加冗余的外部字符串会员 ID；
- 修改 `BuyerMemberProfilePort` 的核心合同；
- 把手机号或姓名落到供应链数据库；
- 为其他 `sourceSystem/adapterCode/contractVersion` 猜测兼容逻辑；
- 实现真实 SaaS 收银台 Adapter。

## 3. 模块边界

### 3.1 member-api

新增 `MemberProfileResolveApi`，固定 RPC 路径：

```text
POST /rpc-api/member/profile/resolve
```

请求 DTO：

```java
class MemberProfileResolveReqDTO {
    String sourceSystem;
    String externalTenantId;
    String buyerUserId;
}
```

三个字段均非空，最大长度分别与供应链路由和 `buyer_user_id` DDL 对齐：`32/128/128`。DTO 的
`toString()` 不得输出 `externalTenantId` 或 `buyerUserId` 原文。

响应 DTO：

```java
class MemberProfileResolveRespDTO {
    String mobile;
    String userName;
}
```

响应不包含 `member_user.id` 或其他内部主键；`toString()` 不得输出手机号和姓名。API 返回
`CommonResult.success(null)` 表示三元身份在该 SaaS 商城未解析到有效会员，非成功 CommonResult 表示路由、认证或服务异常。

### 3.2 member-server

新增 `MemberProfileResolveApiImpl`，处理顺序固定为：

1. 校验 `sourceSystem == AIFULI_MALL`；
2. 从 `TenantContextHolder` 读取实际 RPC 租户，并确认其十进制字符串与请求的 `externalTenantId` 完全一致；
3. 在 member-server 内部把 `buyerUserId` 解析成 `member_user.id`；格式非法或超出 Long 范围时返回
   `success(null)`，不向供应链暴露内部格式错误；
4. 使用 `MemberUserService.getUser(id)` 查询当前租户会员；不存在或手机号为空时返回 `success(null)`；
5. 命中时只返回手机号和优先使用真实姓名、真实姓名为空时使用昵称的展示姓名。

来源系统不支持或租户不一致使用 member 模块稳定错误码 `MEMBER_PROFILE_ROUTE_INVALID`，不得复用“用户不存在”掩盖路由错误。内部主键转换只能存在于本实现或其 member-server 私有解析器中。

### 3.3 supply-chain-server

`SaaSMemberProfileAdapter` 实现现有 `BuyerMemberProfilePort`，仅接受以下冻结路由：

```text
capability       = MEMBER_PROFILE
sourceSystem     = AIFULI_MALL
transportMode    = RPC
adapterCode      = SAAS_MEMBER_RPC
contractVersion  = v1
```

不匹配时不调用 member-server，返回 `UNAVAILABLE`。外部租户不是框架可表达的正 Long 时同样失败关闭，但原始字符串仍保留在请求合同中，不参与供应链业务唯一键。

真实调用流程：

```text
BuyerMemberProfilePort.ResolveMemberCommand
  -> 校验冻结路由
  -> 以 externalTenantId 设置临时 TenantContext
  -> MemberProfileResolveApi.resolve(三元字符串请求)
  -> finally 恢复原 TenantContext
  -> FOUND / NOT_FOUND / UNAVAILABLE
```

结果映射：

- HTTP/RPC 成功且 data 中手机号非空：`FOUND`；
- HTTP/RPC 成功但 data 为空或手机号为空：`NOT_FOUND`；
- CommonResult 非成功、null envelope、路由不匹配、租户无法表达或 RPC 异常：`UNAVAILABLE`。

供应链不再根据 member 错误码猜测“用户不存在”，也不调用 `getCheckedData()` 把任意业务异常误分类为 `NOT_FOUND`。

## 4. 日志与异常边界

会员 RPC 是第三方/外部服务调用，入口、结果和耗时必须记录，但只允许以下字段：

- `projectId`、`profileVersionId`；
- `sourceSystem` 白名单值；
- `externalTenantId` 的不可逆短摘要；
- `adapterCode`、结果状态、CommonResult code、耗时；
- 异常类和根因类名称。

禁止记录：

- `buyerUserId` 或其内部 Long 形式；
- 手机号、姓名；
- 完整 `externalTenantId`；
- Feign 请求 URL、响应正文、原始异常 message 或完整异常栈。

`InstoreChannelMemberFacade` 原有外呼日志同步改为 externalTenantId 摘要，不再打印原文。Adapter 将 RPC 失败转为类型化 `UNAVAILABLE`，不得仅打印异常后伪装成 `NOT_FOUND`。

## 5. Spring 与依赖装配

- supply-chain-server 显式依赖 `aifuli-module-member-api` 和租户 starter；member 仍是外部服务，符合聚合商城边界。
- `RpcConfiguration` 只注册 `MemberProfileResolveApi`，不再为了本功能注册通用 `MemberUserApi`。
- `SaaSMemberProfileAdapter` 是唯一真实 `BuyerMemberProfilePort` Bean；已有
  `InstoreEntryPortConfiguration` 仅在真实 Bean 缺失时保留失败关闭默认实现。
- 真实 Adapter 不以 `enabled` 开关绕过路由校验；支持范围由已发布的 capability 路由元数据决定。

## 6. 旅通协议与部署制品同步修订

同一批次同步纠正已经由旅通 Word 示例及 pro143 成功请求确认的协议事实：

1. HOME/DETAIL 使用 `application/json; charset=UTF-8` POST JSON，而不是 form-urlencoded；
2. 签名字段排序前只过滤 `null`，空字符串按旅通 `signData/mapKeySort` 逻辑参与签名；
3. 详细设计、关键注释和契约测试统一使用“JSON 参数集”，不再称“表单”；
4. Dockerfile 创建非 root 用户 home 目录供 Nacos 快照写入；
5. 已确认的镜像 `JAVA_OPTS` 6 GiB 默认值按
   `docs/superpowers/plans/2026-08-25-supply-chain-image-jvm-defaults.md` 独立落地并验证。

未跟踪 HTML 部署报告不纳入本次代码提交；其中的实际环境地址、测试凭据和 RDS TLS 偏差必须另行脱敏、确认后才可形成仓库文档。

## 7. 自动化测试

### 7.1 member-api 契约测试

- Feign 服务名和 `/rpc-api/member/profile/resolve` 路径稳定；
- 请求三个身份字段均为 String，具备非空和长度约束；
- 响应只包含 `mobile/userName`，不包含 Long 主键；
- 请求/响应 `toString()` 不泄漏身份和会员资料。

### 7.2 member-server 单元测试

- AIFULI_MALL + 匹配租户 + 数字字符串会员 ID 命中；
- buyerUserId 非数字、越界或用户不存在返回空 data；
- sourceSystem 不支持、externalTenantId 与上下文不一致返回稳定路由错误；
- 手机号为空返回空 data；展示姓名优先真实姓名，回退昵称。

### 7.3 supply-chain Adapter 单元测试

- 使用字母数字 `buyerUserId` 断言 RPC 请求原样透传，证明 Adapter 不数值化；
- 三元字段完整透传且调用期间租户上下文正确，正常/异常后均恢复旧上下文；
- 不支持的 sourceSystem/transport/adapter/version 不发生 RPC；
- data 为空、手机号为空、CommonResult 错误、null envelope、RPC 异常分别映射正确状态；
- 捕获日志断言不出现 buyerUserId、手机号、姓名、完整 externalTenantId 和异常 message。

### 7.4 架构与回归门禁

- ArchUnit 禁止 `integration.saasmall..` 调用 `Long.parseLong(String)` 和 `Long.valueOf(String)`；
- 供应链 server 全量 `clean test`；
- 旅通 `RtmartEntryUrlHttpAdapterContractTest`；
- member-api 与 member-server 新增定向测试；
- Docker/Compose JVM 默认值和覆盖值验证；
- `docs/集成测试.md` 记录真实 member RPC 联调前置、成功/失败检查点和日志脱敏要求。

## 8. 完成标准

- 供应链会员 Adapter 不再依赖 `MemberUserApi.getUser(Long)`，生产路径不存在 buyerUserId 数值化；
- member-server 是唯一解释当前 `member_user.id` 字符串兼容关系的边界；
- 三元路由每一维都参与调用或校验，错误语义不会把服务异常伪装成会员不存在；
- 会员及路由敏感值不进入日志；
- 旅通 JSON/签名设计与当前实现、Word 原文和契约测试一致；
- JVM 镜像默认值计划完成，未跟踪 HTML 和实际环境秘密不进入提交；
- 自动化门禁全部通过且 `docs/集成测试.md` 与实现同批更新。

### 8.1 pro143 租户 162 联调口径

- 项目 `201` 的 `MEMBER_PROFILE` 路由目标为 `AIFULI_MALL/162`；调用期间
  `SaaSMemberProfileAdapter` 必须设置租户上下文 `162`，member-server 再以同一租户校验三元身份。
- 测试环境从租户 `1` 改绑到 `162` 时必须发布新档案版本，保留旧版本供已签发凭证按冻结版本收敛；
  不允许修改已发布版本的 `externalTenantId`。
- 租户 `162` 下会员不存在或手机号为空仍返回 `success(null)`，租户改绑不得绕过手机号有效性规则。
