# 供应链中台项目应用统一认证详细设计

## 1. 文档定位

本文档是采购商侧「项目 appId/appKey 统一认证」的落地设计，实现
`docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#5-项目应用身份与统一认证`
定义的身份链与签名协议，并为后续两个切片提供前置：

- 集成测试环境 SaaS 收银台 mock（回调侧需要本认证签名）；
- `aifuli-channel-rtmart-server` 渠道服务（调中台需要本认证签名）。

上游依赖已就绪：`sc_project_application` / `sc_project_app_credential_version` 已由
Liquibase `016-project-integration-route.sql` 落地（一项目一应用、密钥版本 CURRENT/GRACE/REVOKED/EXPIRED）；
`ProjectCallContextAccessor` 三型上下文（`BuyerContext` / `ChannelContext` / `ProjectContext`）
及失败关闭默认实现已存在。本切片补齐：入站验签过滤器、密钥解密、防重放、可信上下文真实注入、签名客户端。

## 2. 背景与目标

当前供应链服务端采购商侧 RPC 端点无应用层认证：`ProjectCallContextConfiguration` 的
`@ConditionalOnMissingBean` 默认实现全部抛 `BUYER_AUTH_INVALID`，端到端 HTTP 调用实际不可用；
联调依赖测试 Bean 注入上下文。目标：

1. INSTORE 四组 API（命令、会员、入口、回调，共 10 个方法）支持真实 HTTP 验签调用；
2. 渠道服务、SaaS 收银台（真实或 mock）、集成测试三方统一以「项目 appKey」身份签名调用；
3. 验签失败、重放、密钥失效全部失败关闭，外部统一返回认证失败，避免项目枚举。

## 3. 范围

### 3.1 本期范围

- INSTORE 四组 API 的入站验签过滤器与 operationCode 注册表；
- 凭证加载与解密（appId → 项目 → CURRENT/GRACE 凭证版本 → appKey）；
- MySQL nonce 防重放表与清扫任务；
- `ProjectCallContextAccessor` 真实实现（替代测试 Bean 注入）；
- 签名客户端（静态签名工具 + Feign 拦截器），发布于 `aifuli-module-supply-chain-api`；
- Schema 019（nonce 表）；集成测试种子数据与门禁。

### 3.2 明确不做（后续切片）

- O2O 端点接入（`aifuli-channel-taobao-flash-server` 改造时一并做）；
- appKey 轮换管理后台与「只显示一次」发码流程（凭证行可由运维 SQL 先行插入）；
- 真实 KMS（`AppKeySecretResolver` SPI 默认实现为本地信封加密，后续可替换）；
- mTLS、网关层验签（纵深防御，不决定业务归属）；
- 同一 operationCode 多传输形态的请求规范化器（当前全部端点均为单一 POST JSON）。

## 4. 签名协议

### 4.1 请求头字段

| 头字段 | 必填 | 格式与语义 |
|---|---|---|
| `appId` | 是 | 项目全局唯一稳定应用标识（`sc_project_application.app_id`），最长 128 |
| `keyVersion` | 是 | 十进制整数，调用方使用的密钥版本号 |
| `contractVersion` | 是 | 契约版本，须等于注册表中该 operationCode 登记的版本（当前 `1.0.0`） |
| `operationCode` | 是 | 操作码（见 §5 注册表），须与实际路径匹配 |
| `timestamp` | 是 | Unix 毫秒，十进制字符串；服务端允许 ±5 分钟偏差 |
| `nonce` | 是 | 调用方随机串，Base64URL，非空、最长 128，按 `appId + nonce` 全局唯一 |
| `requestSha256` | 是 | 请求 body 原始字节的 SHA-256 小写十六进制；空 body 取空字节串摘要 |
| `signature` | 是 | 签名串的 HMAC-SHA256 结果，Base64URL |
| `buyerUserId` | 入口组必填 | 不透明字符串（`BuyerContext.buyerUserId`），其余操作传空 |
| `channelCode` | 命令/会员组必填 | 渠道编码（`ChannelContext.channelCode`，如 `RT_MART`），其余操作传空 |

传输层对头的基数要求独立于业务必填语义：前 8 个核心认证头必须**恰好出现一次**；
`buyerUserId`、`channelCode` 两个 scope 头各自至多出现一次，缺失或仅空白时统一规范化为
`null`。上述任意 10 类头只要出现两个及以上值（即使值完全相同）都直接拒绝，禁止通过
`getHeader` 静默取首值。所有头值在进入注册表、数据库与加密计算前完成 CR/LF、格式与长度校验；
`requestSha256` 固定为 64 位小写十六进制，`signature` 固定为 HMAC-SHA256 的 43 位
Base64URL 无填充编码，`keyVersion` 必须为正数。

### 4.2 requestSha256（对上游设计 §5.2 的落地明确）

上游设计规定 `requestSha256` 为「封闭请求规范化器生成的规范 JSON」摘要。本期四组端点
全部为单一传输（POST + JSON body），无 query 参数、无 path 参数，因此**取原始 body 字节
作为规范形**：`requestSha256 = lowerHex(SHA-256(bodyBytes))`，空 body 取 `SHA-256(空字节串)`。
待未来同一 operationCode 出现多传输形态时，再引入规范化器并升级 contractVersion。

### 4.3 签名串（对上游设计 §5.2 的补强 delta）

签名串为固定 9 行（8 个换行符连接），**所有操作统一字段顺序，不适用的 scope 字段取空字符串**：

```text
appId
keyVersion
contractVersion
operationCode
timestamp
nonce
requestSha256
buyerUserId
channelCode
```

即 `signature = base64Url(HMAC-SHA256(appKey, join('\n', 上表9字段)))`。

与上游设计的差异及理由：上游签名串仅覆盖 7 个认证字段（§5.2 的 8 个传输字段中
`signature` 自身不参与签名）；本设计把两个 scope 声明字段
（`buyerUserId`/`channelCode`）一并纳入签名，共 9 字段。它们是从认证身份推导上下文的声明属性，
纳入签名后已认证应用无法在传输中被篡改 scope，属于对 §5.2 的安全补强（不破坏兼容——
该协议尚未有任何已上线调用方）。

### 4.4 失败语义

统一外部行为：以下任一失败抛 `BUYER_AUTH_INVALID`（复用现有错误码 1_050_001_001，
对外提示「采购商调用身份无效」，不区分失败原因，避免项目枚举与探测）：

- 头字段缺失/格式非法、operationCode 与路径不匹配、contractVersion 不符；
- appId 未知、应用状态非启用（status != 10）、项目不存在或非启用（status != 10）、
  项目 INSTORE 能力未启用（status != 10）；
- keyVersion 无对应凭证、凭证状态非 CURRENT/GRACE、不在生效窗口；
- 时间戳超窗、签名不匹配；
- nonce 重复插入冲突（重放）；
- nonce 存储不可用（数据库异常，失败关闭，禁止绕过）。

认证入口和拒绝都属于可被攻击者高频触发的路径，只允许记录不附 Throwable 的 DEBUG 或按错误类别限频后的
安全摘要，禁止每次请求打印 INFO/WARN 形成日志风暴；确需 ERROR 的内部存储故障沿用 §6.4 的
每分钟限频安全诊断。日志不得记录 appKey、完整签名、nonce、body 或 scope 声明值。
框架的非生产环境 `ApiAccessLogInterceptor` 同样属于该约束：`ProjectAuthOperations` 的四个受保护
路径前缀必须登记为平台敏感路径，只输出“参数已省略”，禁止完整打印会员 token、userCode、业务 DTO
或其他签名请求 body。该遮蔽必须有路径工具参数化单测，且不能依赖 `aifuli.access-log.enable=false`
（当前该开关只控制持久化 Filter，不会移除 MVC 日志拦截器）。路径工具必须同时接收
`requestURI` 与 `contextPath`，先仅剥离 URI 开头的真实 context path 再匹配精确前缀；MVC
Interceptor 与持久化 Filter 都传入 `request.getContextPath()`，保证非空部署上下文下仍然遮蔽，
同时不得把相似前缀（如 `channel-commandary`）或 URI 中段出现的伪 context path 误判为敏感路径。

## 5. operationCode 注册表

`ProjectAuthOperations` 以静态注册表声明受保护端点。过滤器只挂载四个 INSTORE
路径前缀；落在前缀内但与注册表任何 `(HTTP 方法, 路径)` 不精确匹配的请求直接拒绝
（防止新增端点忘记登记造成保护缺口）。

| operationCode | 方法与路径 | scope 要求 | 上下文产出 |
|---|---|---|---|
| `INSTORE_CREATE_PAYMENT_ORDER` | POST `/rpc-api/supply-chain/instore/channel-command/createPaymentOrder` | channelCode | `ChannelContext` |
| `INSTORE_QUERY_PAYMENT_STATUS` | POST `/rpc-api/supply-chain/instore/channel-command/queryPaymentStatus` | channelCode | `ChannelContext` |
| `INSTORE_CANCEL_PAYMENT_FLOW` | POST `/rpc-api/supply-chain/instore/channel-command/cancelPaymentFlow` | channelCode | `ChannelContext` |
| `INSTORE_ACCEPT_REFUND` | POST `/rpc-api/supply-chain/instore/channel-command/acceptRefund` | channelCode | `ChannelContext` |
| `INSTORE_QUERY_REFUND_STATUS` | POST `/rpc-api/supply-chain/instore/channel-command/queryRefundStatus` | channelCode | `ChannelContext` |
| `INSTORE_RESOLVE_MEMBER` | POST `/rpc-api/supply-chain/instore/channel-member/resolve` | channelCode | `ChannelContext` |
| `INSTORE_HOME_ENTRY` | POST `/rpc-api/supply-chain/instore/buyer-entry/home` | buyerUserId | `BuyerContext` |
| `INSTORE_ORDER_DETAIL_ENTRY` | POST `/rpc-api/supply-chain/instore/buyer-entry/detail` | buyerUserId | `BuyerContext` |
| `INSTORE_CONFIRM_PAYMENT_CALLBACK` | POST `/rpc-api/supply-chain/buyer/instore/payment/confirmPayment` | 无 | `ProjectContext` |
| `INSTORE_REPORT_REFUND_RESULT_CALLBACK` | POST `/rpc-api/supply-chain/buyer/instore/payment/reportRefundResult` | 无 | `ProjectContext` |

注册表同时登记每个 operationCode 的 `contractVersion`（初始均为 `1.0.0`）与 scope 类型。
`ProjectAuthOperations` 在类初始化时从 `OPERATIONS` 构造不可变的 operationCode 索引 Map；任何
重复 operationCode 必须使类初始化失败，禁止后写覆盖。客户端/服务端统一通过
`findByOperationCode` 查询，服务端不得再次 stream `OPERATIONS` 自建规则或重复解析。
`BuyerContext.buyerId` 不由调用方声明，由服务端按 `appId → sc_project_application.project_id →
sc_buyer_project.buyer_id` 解析。

## 6. 服务端组件

新包 `com.aifuli.module.supplychain.infrastructure.auth`，只依赖自身 DAL 与
`infrastructure.security`，不依赖 `border`/`application`/`tradecore`（架构规则强制）。

### 6.1 `ProjectAppAuthFilter`

Servlet Filter，经 `FilterRegistrationBean` 注册（`Ordered.HIGHEST_PRECEDENCE + 10`，
仅匹配 §5 四个前缀，显式 `asyncSupported=false`）。本期 10 个受保护接口均为同步 Spring MVC
JSON 请求，不支持 Servlet async dispatch、`startAsync()` 或 non-blocking `ReadListener`。缓存包装器
必须覆盖 `isAsyncSupported()` 返回 false；两个 `startAsync` 重载按 Servlet 契约抛
`IllegalStateException`；缓存输入流对 null listener 抛 `NullPointerException`，对任何非 null listener
均抛 `IllegalStateException`，禁止同步伪造 `onDataAvailable/onAllDataRead` 回调。每次复读取得的独立流
同样执行该限制，不能通过重新获取流绕过。职责单一、顺序固定：

1. 在读取 body 前清除当前线程可能遗留的旧 Holder；按 `maxRequestBodyBytes` 有界读取原始字节，
   超限立即认证失败，`HttpServletRequestWrapper` 缓存包装保证 controller 可重复读；
2. 解析 10 类头字段并做基础格式校验；8 个核心头必须恰好一个值，两个 scope 头至多一个值且
   缺失/空白规范化为 `null`；所有 10 类头的重复值（即使相同）直接拒绝；
3. 注册表匹配 `(method, path)` → operationCode、scope、contractVersion；匹配成功后记录一条高频
   DEBUG 入口安全摘要，仅允许 method、注册表解析出的 operationCode、已完成长度/CRLF 校验的 appId、
   正数 keyVersion；禁止记录 nonce、signature、body、buyerUserId、channelCode、appKey/masterKey/
   secretRef。成功摘要如需记录只允许追加 projectId；
4. 校验时间戳窗口（默认 ±5 分钟，纯内存比较，先于任何数据库访问）；差值计算必须使用
   `Math.subtractExact` 或等价无溢出算法，`Long.MIN_VALUE/MAX_VALUE` 等恶意时间戳只能拒绝，
   禁止使用 `Math.abs(now - timestamp)`；
5. `ProjectAppCredentialService.authenticate(appId, keyVersion)` → `AuthenticatedApp`
   （含 projectId、buyerId、appKey 明文，仅存活于本次请求内存）；
6. 验签（重算 requestSha256 + 签名串 + HMAC 比对，常量时间比较）；
7. `NonceStore.ensureUnique(appId, nonce)`（重放即拒绝）；
8. 精确校验 scope 字段：`CHANNEL_CODE` 要求 channel 非空且 buyer 为空，`BUYER_USER_ID` 反之，
   `NONE` 要求两者均空；不适用的空串规范化为 `null`；
9. 构造 `ProjectAuthContext` 写入 `ProjectAuthContextHolder`（ThreadLocal）；构造器的防御性
   `IllegalArgumentException` 必须映射为带原始 cause 的 `BUYER_AUTH_INVALID`，不得泄漏内部校验消息；
10. 整个过滤器边界使用最外层 `finally` 清理 ThreadLocal，覆盖 body 超限、重复头、解析、验签、
    scope、上下文构造、拒绝响应写出和下游异常等所有退出路径，禁止只在认证成功后清理。

验签在前、nonce 插入在后：无效签名的重放无害且不值得消耗数据库写；有效签名的重放必须拦截。

### 6.2 `ProjectAppCredentialService`（应用服务）

```java
AuthenticatedApp authenticate(String appId, int keyVersion)
```

查询链：`sc_project_application`（`app_id` 唯一、`status = 10` 启用）→ `sc_buyer_project`
（取 `buyer_id`，校验 `status = 10` 启用）→ `sc_project_app_credential_version`
（`application_id + key_version` 唯一，状态 ∈ {CURRENT, GRACE}，`valid_from ≤ now < valid_to 或
valid_to IS NULL`）→ `sc_project_capability`（`project_id + 'INSTORE'`，`status = 10` 启用）
→ `AppKeySecretResolver` 解密 `secret_ref` 得 appKey。

任一步为空或状态不符即认证失败。appKey 明文不落日志、不进缓存。

认证是高频入口，入口及分阶段拒绝日志统一使用 `debug`，只记录 `appId`、`keyVersion`
与内部阶段摘要，禁止记录 `appKey`、`masterKey`、`secretRef`；不得对每次认证攻击打印
`warn` 形成日志风暴。对外所有失败仍统一为 `BUYER_AUTH_INVALID`，阶段摘要只用于受控的
内部 debug 诊断。`AppKeySecretResolver` 抛出的 `IllegalArgumentException` 必须映射为该
`ServiceException`，并通过 `initCause(ex)` 保留原始因果链，禁止吞掉解密失败原因。

`AuthenticatedApp.appKey` 虽仍提供 accessor 给后续验签过滤器读取，但必须显式标注
`@JsonIgnore`，确保默认 Jackson 序列化既不输出明文值，也不暴露 `appKey` 属性；其
`toString()` 同样不得包含 appKey。凭证只读投影的 `secretRef` 必须通过
`@ToString.Exclude` 排除在 Lombok `toString()` 之外，避免诊断日志意外泄密。

### 6.3 `AppKeySecretResolver`（SPI）与本地信封实现

```java
public interface AppKeySecretResolver {
    /** 解密 secret_ref 得 appKey 明文；格式或解密失败抛认证失败。 */
    String resolveAppKey(String appId, int keyVersion, String secretRef);
}
```

默认实现 `LocalEnvelopeAppKeySecretResolver`：AES-256-GCM 信封解密。
`secret_ref` 格式 `v1:{base64(iv)}:{base64(ciphertext)}`，GCM AAD 绑定
`appId + "\n" + keyVersion`（防密文跨应用/跨版本挪用）。主密钥为 32 字节 Base64，
从配置 `aifuli.supply-chain.auth.master-key` 注入；`auth.enabled=true` 时缺失即启动失败
（失败关闭）。发码工具类（同一格式加密）随 SPI 一并提供，运维/测试用于生成 `secret_ref`。

`ProjectAppAuthConfiguration` 必须是 Spring Boot `@AutoConfiguration`，其 FQCN 注册到
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，不得依赖普通
组件扫描的配置类处理顺序。默认本地 resolver bean 使用
`@ConditionalOnMissingBean(AppKeySecretResolver.class)`：Boot 先注册用户/KMS resolver，再处理
自动配置，因此自定义 resolver 存在时默认实现可靠退位，且不读取、不要求本地主密钥；没有自定义
resolver 时才创建本地实现，并按上述规则校验主密钥、失败关闭。

### 6.4 `NonceStore`（MySQL 防重放）

表 `sc_project_auth_nonce`（Schema 019）：

```sql
CREATE TABLE sc_project_auth_nonce (
    app_id VARCHAR(128) NOT NULL COMMENT '项目应用稳定标识，逻辑引用sc_project_application.app_id',
    nonce VARCHAR(128) NOT NULL COMMENT '调用方生成的Base64URL随机串',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    expire_at DATETIME(3) NOT NULL COMMENT '过期时间，Asia/Shanghai，取插入时刻加防重放窗口上限',
    PRIMARY KEY (app_id, nonce)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目应用认证Nonce防重放记录';
```

- `ensureUnique(appId, nonce)`：直接 INSERT；`DuplicateKeyException` → 重放拒绝；
  其他 `DataAccessException` → 失败关闭拒绝。两类数据库异常都统一映射为
  `BUYER_AUTH_INVALID`，并以 `initCause` 保留原始异常；该入口属于高频安全路径，入口和重放
  仅记 DEBUG，存储不可用的 ERROR 按进程内时钟限频为每分钟最多一条。禁止把原始
  `DataAccessException` 直接交给日志框架：异常 message / cause 可能含完整 nonce 或 SQL 参数。
  每分钟首条 ERROR 只记录 `appId`、异常类、根因类，以及 cause 链中可取得的
  `SQLException.sqlState/errorCode`，并附一个安全诊断 Throwable；安全 Throwable 的 message 只由
  上述安全字段组成，stack trace 复制原始数据库异常调用栈，不挂接原始 cause / message。其余请求
  降为 DEBUG，只记录 `appId`、错误类别与异常类型摘要且不附 Throwable。cause 链遍历必须按对象身份
  防循环，且不得访问或输出 `SQLException.message`；对外 `ServiceException` 仍以 `initCause` 保留
  原始异常供进程内因果链使用，但本组件日志不得原样输出。所有日志均不得记录完整 nonce、签名或凭据；
- `@Scheduled` 低频清扫（默认每小时）删除 `expire_at < now` 的过期行，
  每轮使用同一个固定数据库本地时间截止点，单批 `LIMIT 1000`、最多 100 轮；不足 1000 行即停止。
  清扫关闭时跳过，数据库失败由本次定时任务记录安全诊断 WARN 并返回、等待下周期，不在当前周期
  重试；WARN 同样不得直接挂接原始数据库异常，只保留安全类型/SQL 元数据与复制的调用栈；
- 保留窗口必须覆盖最坏重放有效期：时间戳允许 `±skew`，首次请求可携带未来 `skew`
  时间戳，因此最晚可在首次服务器时刻后 `2 × skew` 仍通过时间校验。`enabled=true` 时
  `nonceRetentionHours` 必须严格大于 `2 × timestampSkewMinutes` 对应时长，否则构造
  `NonceStore` / 启动失败关闭；`enabled=false` 时跳过该校验。时长换算必须使用防溢出的
  `Duration` / 精确算术，禁止单位换算溢出后误放行。即使 `Duration` 本身可构造，也必须在
  `NonceStore` 构造期取当前数据库 `Asia/Shanghai` `LocalDateTime` 实际执行一次 `plus(retention)`，确保
  `expire_at` 在 MyBatis 使用的时间类型中可表示；`DateTimeException` / `ArithmeticException`
  统一包装为保留 cause 的 `IllegalStateException`，不得把失败推迟到首个认证请求。默认 1 小时与
  5 分钟满足该不变量，过期行删除后到达的同 nonce 请求必已被时间戳校验拒绝；
- 选择 MySQL 而非 Redis：供应链服务端当前无 Redis 依赖，主键唯一约束天然防重放，
  与既有 Testcontainers MySQL 门禁复用同一基础设施。
- 持久化契约在 Task 7 即使用真实 MySQL 8.4 + Liquibase 门禁：同 `(appId, nonce)` 两线程并发
  必须恰一成功、恰一由 Spring 翻译为 `DuplicateKeyException`，且库中只有一行；命名为
  `supplyChainTransactionManager` 的事务中插入后回滚，事务外必须为零行；
  `deleteExpiredBefore(cutoff, 1)` 必须真实生成 MySQL `DELETE ... LIMIT 1`，两次调用逐行删除
  两条 `expire_at < cutoff` 记录，并严格保留等于/晚于 cutoff 的记录。Task 10 仍负责真实 HTTP
  重放 E2E，不重复承担本 Mapper 持久化契约。

### 6.5 `ProjectAuthContextHolder` 与 `ProjectCallContextAccessorImpl`

- `ProjectAuthContext` 是验签完成后的包级不可变 `final` 快照，类、构造器与全部 accessor 均不得
  `public`，只允许 `infrastructure.auth` 包内过滤器构造及访问，阻止 auth 包外伪造可信身份。
  全部状态字段必须为 `final`。`projectId`、`buyerId` 必须为正数，
  `appId`、`operationCode` 必须非空，`keyVersion` 必须为正数；`operationCode` 必须能在
  `ProjectAuthOperations` 注册表中唯一解析，未知操作直接拒绝，不能把未登记调用伪装成可信上下文；
- 构造器只调用一次 `ProjectAuthOperations.findByOperationCode` 并把解析出的 `ScopeType` 缓存在
  `final` 字段；后续 accessor 与 `toString` 读取缓存值，禁止重复扫描注册表；
- scope 声明与注册表必须精确一致并在快照构造期完成纵深校验：`BUYER_USER_ID` 只允许
  `buyerUserId` 非空且 `channelCode` 为空，`CHANNEL_CODE` 只允许 `channelCode` 非空且
  `buyerUserId` 为空，`NONE` 要求两者均空。空白的不适用字段规范化为 `null`，适用字段保留
  验签时的原始不透明字符串，尤其禁止把 `buyerUserId` 转为 `Long`；
- `ProjectAuthContext.toString()` 只输出安全诊断字段，不得输出 `appId`、`buyerUserId` 或
  `channelCode`，构造失败与访问器 scope 不匹配异常同样不得回显这些值；
- `ProjectAuthContextHolder` 类以及 `set/get/clear` 全部为包级，禁止 auth 包外直接写入或读取；它使用
  普通 `ThreadLocal`（禁止 `InheritableThreadLocal`）持有快照，
  `set(null)` 直接拒绝，同线程再次 `set` 覆盖旧快照，`clear()` 必须调用 `remove()`；父线程上下文
  不得被子线程继承，`filter` 必须在 `finally` 清理，杜绝线程池复用串号；
- `ProjectCallContextAccessorImpl` 是 `@Component` 真实实现，只按注册表 scope 类型精确产出
  一种上下文：`CHANNEL_CODE` 才能取得 `ChannelContext(projectId, channelCode)`，
  `BUYER_USER_ID` 才能取得 `BuyerContext(projectId, buyerId, buyerUserId)`，`NONE` 才能取得
  `ProjectContext(projectId)`。ThreadLocal 为空或调用了不匹配的访问器均统一抛不含 scope 值的
  `BUYER_AUTH_INVALID`，不得降级为另一种上下文；
- `ProjectCallContextConfiguration` 的失败关闭 Bean 必须改为 Spring Boot `@AutoConfiguration`
  并登记到 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。
  自动配置在用户组件注册后判断 `@ConditionalOnMissingBean`，从而保证 `@Component` 真实实现
  存在时兜底可靠退位且容器内只有一个 `ProjectCallContextAccessor`；不能依赖普通组件扫描配置
  与真实实现之间未定义的解析顺序。

业务包（`interfaceapi.rpc` 等）继续只消费 `ProjectCallContextAccessor`，不感知认证细节。

### 6.6 配置项

| 配置 | 默认 | 语义 |
|---|---|---|
| `aifuli.supply-chain.auth.enabled` | `true` | 过滤器总开关；`false` 仅供本地调试（不注册过滤器，上下文回到失败关闭默认实现；默认本地 resolver 可装配但不得被调用） |
| `aifuli.supply-chain.auth.master-key` | 无默认 | 默认本地 resolver 生效且 `enabled=true` 时必填的 32 字节 Base64 主密钥，缺失启动失败；自定义/KMS resolver 存在时不读取、不要求 |
| `aifuli.supply-chain.auth.timestamp-skew-minutes` | `5` | 时间戳允许偏差（分钟）；`enabled=true` 时必须为正数且能精确、安全地转换为毫秒，配置错误在 Filter Bean 创建期启动失败关闭 |
| `aifuli.supply-chain.auth.nonce-retention-hours` | `1` | nonce 过期保留时长（清扫与 expire_at 计算共用）；`enabled=true` 时必须严格大于 `2 × timestamp-skew-minutes` 对应时长，且当前数据库 `Asia/Shanghai` `LocalDateTime + retention` 必须可表示；0、相等、更短或超出日期表示范围均启动失败 |
| `aifuli.supply-chain.auth.max-request-body-bytes` | `1048576`（1 MiB） | Task 9 验签包装器可缓存的 body 上限；1 MiB 同时是不可提高的安全硬上限。`enabled=true` 时配置必须在 `1..1048576`，并在 Filter Bean 创建期启动失败关闭；超限统一认证失败且不得继续读取到无界 byte[]。当前 10 个受保护端点均为固定字段 JSON DTO，正常请求远小于该值 |

nonce/时间窗口不变量与日期可表示性由 `NonceStore` 构造时校验；Task 9 在过滤器 Bean 创建期同步
校验 body 上限与 timestamp skew，使错误配置不能拖延到首个请求。关闭认证仅用于本地调试：
`enabled=false` 时不注册 Filter，并跳过 body/skew 运行期配置校验；此时不处理认证请求且清扫跳过。

## 7. 签名客户端（`aifuli-module-supply-chain-api`）

发布于 API 模块，渠道服务、mock、集成测试三方复用（三方均已依赖该模块）：

- `ProjectAppAuthSigner`：纯静态工具。输入 appKey、10 字段值，输出完整请求头 Map
  （含自动生成 timestamp/nonce 与计算 requestSha256）；与 §4 签名串逐字段对齐；
- `ProjectAppAuthFeignInterceptor`：Feign `RequestInterceptor`，按模板方法/路径在客户端侧
  注册表中定位 operationCode（与服务端注册表同源常量），读取 `ProjectAppAuthCredentials`
  （调用方注入的 appId/appKey/keyVersion/contractVersion/scope 提供者），为请求补齐
  10 个签名头。requestSha256 取 Feign 已序列化 body 字节。

调用方持有密钥：渠道服务在自有库维护「项目 → appKey」映射；mock 与集成测试从种子
配置读取。客户端不缓存、不落日志 appKey。

## 8. 端到端调用流程

```text
调用方（渠道服务 / mock / 测试）
  │ 1. 组装 body JSON
  │ 2. ProjectAppAuthSigner 生成 10 头（timestamp/nonce/requestSha256/signature）
  ▼
供应链服务端 ProjectAppAuthFilter
  │ 3. 注册表匹配 (method, path) → operationCode
  │ 4. 凭证链：appId → application启用 → project启用 → CURRENT/GRACE凭证且在生效窗口
  │    → INSTORE能力启用 → resolver解密 appKey
  │ 5. 请求时间窗校验 → HMAC 验签（常量时间比较）→ nonce 唯一插入
  │ 6. scope 校验 → ProjectAuthContext 写入 ThreadLocal
  ▼
ApiImpl（interfaceapi.rpc）
  │ 7. ProjectCallContextAccessorImpl.require*Context() 产出可信上下文
  ▼
Facade / border 应用层（现有逻辑不变）
```

异常路径：任一步失败抛 `BUYER_AUTH_INVALID`，由全局异常处理转 `CommonResult`；
ThreadLocal 在 finally 清理，杜绝线程复用串号。

## 9. 测试门禁

- **单测**（`infrastructure.auth`）：
  - 签名串构造与 HMAC 验签对拍（客户端 Signer ↔ 服务端验签互为逆运算）；
  - 时间窗内/外、nonce 重放、`DuplicateKeyException` 与其他 DB 异常的失败关闭；
  - CURRENT 通过、GRACE 通过、REVOKED/EXPIRED/窗口外拒绝；
  - scope 缺失、非必填组携带多余声明、operationCode 与路径不匹配、contractVersion 不符；
  - 三型上下文注入（Channel/Buyer/Project）与 ThreadLocal 清理；
  - 信封加解密往返、AAD 篡改拒绝、`secret_ref` 格式非法拒绝。
- **契约测试**（API 模块）：注册表常量覆盖四组 API 全部 10 个方法与精确路径；
  客户端/服务端注册表同源断言。
- **集成测试**（真实 MySQL + Liquibase + 完整 `SupplyChainServerApplication` + 真 HTTP 随机端口）：
  - 发布基线为 Testcontainers `mysql:8.4`；本地无 Docker 时允许通过
    `supply-chain.test.jdbc-*` 接入该测试独占的外部空 MySQL 库，并记录实际数据库版本；
  - 服务以专用 `project-app-auth-it` profile 启动，`server.port=0`、数据源、Nacos、admin、
    OpenAPI、timer、outbox 等开关必须作为 `SpringApplication.run("--key=value")` 的高优先级
    命令行参数传入，不能只用 `SpringApplicationBuilder.properties` 的低优先级默认属性；实际端口
    必须从 `WebServerApplicationContext.getWebServer().getPort()` 读取，禁止依赖本地固定 58190；
  - 本门禁只验证项目应用 HMAC 认证链路，nonce 按 §6.4 使用 MySQL。完整应用自订单编号改造后
    必须装配真实 Redis 客户端；测试使用独占 `redis:7.4` 容器和显式客户端、发号 `env=test` 与
    `scope=project-app-auth`，不把它改成 nonce 存储。测试上下文仍以 `aifuli.tenant.enable=false`
    关闭无关的租户子系统、排除本机 Redis 自动配置，并隔离其他测试配置的组件扫描；启动失败与
    正常退出均回收资源，禁止伪造 Redis Bean 或关闭生产缺依赖门禁。修订依据见
    `docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md` §10.4；
  - 种子为 buyer + 项目(10) + INSTORE 能力(10) + application(10) +
    CURRENT/GRACE/REVOKED 三版凭证，`secret_ref` 必须用生产 AES-256-GCM codec 真实加密；
  - 四组 API 分别用合法 DTO 和合法签名穿透 Spring Security、Filter、Accessor、ApiImpl 与 Facade：
    channel-command 返回 `INSTORE_CHANNEL_USER_BINDING_MISSING`，channel-member 返回
    `INSTORE_CREDENTIAL_INVALID`，buyer-entry 使用数字外观但不透明的 `buyerUserId` 并返回
    `INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND`，payment callback 返回
    `INSTORE_PAYMENT_FLOW_NOT_FOUND`。以上业务错误均须区别于 `BUYER_AUTH_INVALID`，分别证明
    `ChannelContext`、`BuyerContext`、`ProjectContext` 已由真实链路注入；
  - 参数化遍历注册表全部 10 个 operation，无认证头均须返回 HTTP 200 +
    `BUYER_AUTH_INVALID`，证明 Security 对四前缀放行且 Filter 保护面完整；
  - 同 nonce 首次到达业务错误、第二次认证拒绝且数据库只留一行；篡改 body、时间戳超窗、
    REVOKED、暂停项目均拒绝，GRACE 可到达业务层；前缀内未知路径使用已登记路径先生成格式和签名
    均合法的 headers，再只改变 URL，以精确证明注册表失败关闭；
  - 篡改签名、超窗和 REVOKED 等在 nonce 写入前失败的场景必须断言数据库没有对应 nonce，验证
    `timestamp → credential → signature → nonce` 顺序；每个用例使用唯一 nonce 与业务号，不依赖执行顺序；
  - HTTP helper 每次同时断言 HTTP 200、JSON `CommonResult` 非空；暂停项目必须在 `finally` 恢复；
    server、容器及测试持有的主密钥字节必须在异常启动与正常结束路径上完成 `finally` 清理；外部库
    测试种子的每条逆序 DELETE 必须独立尝试并累计 suppressed 异常，禁止首条失败时跳过后续清理。
- **Spring Security 生产装配前置**：仓库安全 starter 的
  `AifuliWebSecurityConfigurerAdapter` 以 `@Resource List<AuthorizeRequestsCustomizer>` 注入，独立
  应用没有任何该类型 Bean 时会在启动阶段失败。供应链 server 必须提供一个命名的、显式 no-op
  `AuthorizeRequestsCustomizer` Bean，只满足框架集合装配，不额外放行任何 URL；本期四前缀仍只由
  `application.yaml` 的 `aifuli.security.permit-all-urls` 移除登录 token 前置要求，再由 HMAC Filter
  失败关闭。禁止照搬其他模块的 `ApiConstants.PREFIX + "/**"` 全量 permit，避免扩大认证旁路面。
  OpenAPI 打开时，网关 Knife4j 会拉 `/v3/api-docs`（及可能未 StripPrefix 的
  `/admin-api/supply-chain/v3/api-docs`）。这些路径也必须写进同一 `permit-all-urls`，否则
  `/doc.html` 能开、选 `supply-chain-server` 会 401。只放行文档路径，禁止放行整个 `/admin-api/**`。
- **架构测试**（新增 3 条规则）：
  - `infrastructure.auth` 不得依赖 `..border..`/`..application..`/`..tradecore..`/`..interfaceapi..`；
  - auth 包外不得直接引用 `ProjectAuthContextHolder` 或包级 `ProjectAuthContext`，必须经
    `ProjectCallContextAccessor`；规则必须由显式 JUnit 5 `@Test` 执行，禁止 ArchUnit 发现 0 条假绿。
- 全量门禁脚本共 16 个独占空库：既有 14 项 + Task 7
  `ProjectAuthNonceStoreIntegrationTest` + 本节 Flow E2E。脚本启用可靠错误传播，每项保留并打印
  测试摘要，任一建库、Maven 或测试失败均累计到 `FAILED` 并最终非零退出；只有全部成功才能输出
  `GATE PASSED`，禁止 `mvn | grep | tail` 吞掉 Maven 退出码造成假绿；指定测试类不存在或未匹配时
  必须由 Failsafe 非零退出，禁止以 0 个测试冒充门禁成功。
- 全量 API/server 单测与 Task 8/9 回归不得回归；测试总数以本次 Maven 新鲜输出为准，不沿用旧计划
  中随任务增长已经过期的静态计数。

## 10. 已确认的关键决策

1. **渠道服务身份 = 项目 appKey**（复用 016「一项目一应用」模型）：渠道服务持有每个开通
   INSTORE 项目下发时同步的 appKey，以项目应用身份签名调中台；`channelCode` 是签名内
   声明属性，服务端按项目渠道绑定校验，渠道身份不冒充项目应用身份（上游设计 §5.1）。
2. **requestSha256 取原始 body 字节**：当前全部端点单一 POST JSON 传输，规范形即原始字节；
   多传输出现时再引入规范化器（§4.2）。
3. **scope 字段纳入签名**：对上游 §5.2 的补强 delta，防传输中篡改声明（§4.3）。
4. **MySQL nonce 表**：不引入 Redis 依赖，主键唯一约束防重放，失败关闭由 DB 异常实现（§6.4）。
5. **本地信封加密**：AES-256-GCM + AAD 绑定 appId/keyVersion，主密钥配置注入；SPI 预留
   真 KMS 替换位（§6.3）。
6. **错误码复用 `BUYER_AUTH_INVALID`**：与现有可信上下文失败语义一致，对外统一防枚举。
7. **过滤器只挂 INSTORE 四前缀**：O2O 等其余端点本期不接入，避免影响淘宝闪购存量链路；
   前缀内未登记路径一律拒绝，杜绝保护缺口（§5）。

## 11. 关联文档与影响清单

- 上游设计：`docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#5-项目应用身份与统一认证`
- 端点契约：`docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md`
- Schema：新增 `019-project-app-auth.sql`（nonce 表）；复用 `016`（application/credential 表）
- 影响模块：`aifuli-module-supply-chain-server`（新包 `infrastructure.auth` + schema +
  集成测试）、`aifuli-module-supply-chain-api`（签名客户端）
- 后续切片依赖：SaaS 收银台 mock（回调签名）、`aifuli-channel-rtmart-server`（出站签名）
