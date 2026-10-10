# 旅通扫码购入口适配器与 pro143 首次部署详细设计

> 日期：2026-08-24
>
> 状态：代码与本地部署制品已实现，待 pro143 部署及真实 HOME 联调
>
> 前置设计：
> - `docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md`
> - `docs/superpowers/specs/2026-08-23-project-app-auth-design.md`
> - `docs/integration/rtmart/大润发到店购渠道分销接口.docx`

## 1. 背景与决策

供应链中台就是时序图中的分销商。SaaS 商城是供应链中台的一个采购项目/租户，也是中台所识别的分销商会员来源；
旅通与大润发之间的实际调用边界不进入供应链核心模型。SaaS 商城调用供应链中台入口 RPC 后，中台签发短期凭证，
再主动调用旅通 `getRtPosUrl/home|detail` 获取最终跳转地址。

当前 `InstoreBuyerEntryFacade` 已完成项目、会员、企业组、订单与短期凭证编排，但生产环境只有
`RtmartEntryUrlPort` 的失败关闭默认实现。该实现不会发出 HTTP 请求，而是抛出
`Rtmart entry URL adapter is not configured`；门面撤销刚签发的凭证后，向 SaaS 返回稳定错误
`INSTORE_ENTRY_URL_UNAVAILABLE（1_050_001_046）`。因此首次联调前必须先实现真实旅通 HTTP Adapter，
不能仅靠部署现有 JAR 消除该错误。

本设计作出以下决策：

1. 在供应链中台进程内实现 `RtmartEntryUrlPort` 的真实 OkHttp Adapter，不创建新的渠道微服务。
2. HOME/DETAIL 使用旅通文档测试地址、JSON POST、`SHA1withRSA` 签名；协议翻译全部封装在 Integration Adapter。
3. 正式成功响应按 `code/result/jumpUrl` 解析，不把第三方 DTO 扩散到 Facade 或 API。
4. 不对获取入口 URL 自动重试；结果未知时由现有 Facade 撤销旧 token，业务方显式重试并签发新 token。
5. 首次在 pro143 以单实例 Docker Compose 运行，通过 Nacos 注册给同机 SaaS 服务发现；不改 Gateway/Nginx。
6. 开发和测试共用数据库期间关闭 Timer 与 Outbox 扫描，避免本地与 pro143 抢占同一任务。

## 2. 范围

### 2.1 本期范围

- 实现旅通 HOME、DETAIL 入口地址 HTTP 调用。
- 实现 RSA 签名、nonce、时间戳、JSON 编码、响应解析和旅通错误映射。
- 增加可启动校验的旅通配置、单元测试和 MockWebServer 契约测试。
- 更新 INSTORE 人工联调步骤与排障记录。
- 增加供应链服务运行时 Dockerfile、精确构建上下文和 pro143 Compose 部署文件。
- 在 Nacos `aifuli-mall-test` 命名空间、`test` Group 创建
  `supply-chain-server-test.yaml` 非敏感配置。
- 首次启动允许 Liquibase 在空的 `supply-chain-test` 库执行现有全部 19 个 changeSet。
- 使用已确认的旅通测试 `app_key`、`company_code` 和文档测试 RSA 私钥完成首次 HOME 联调。

### 2.2 明确不在本期

- 不实现独立 `aifuli-channel-rtmart-server`。
- 不实现旅通其余会员、支付、退款接口。
- 不实现真实 SaaS 会员资料 Adapter；它是旅通拿到 `jumpUrl` 后消费 token 时的后续联调依赖。
- 不把旅通 `app_key`、RSA 私钥或 SaaS/数据库/Nacos 密码写入 Git 或 Nacos。
- 不新增 Gateway 路由、Nginx 入口或公网安全组端口。
- 不自动导入业务主数据；采购项目、集成档案、应用凭证、采购商绑定和企业组绑定单独受控准备。
- 不回滚 Liquibase DDL；应用回滚只切换镜像，数据库保持前向结构。

## 3. 组件边界

### 3.1 保持不变的核心端口

`RtmartEntryUrlPort` 继续作为核心与旅通协议之间的唯一边界：

```java
EntryUrlResult getHomeUrl(HomeUrlCommand command);
EntryUrlResult getDetailUrl(DetailUrlCommand command);
```

- `HomeUrlCommand` 只携带可信 `projectId/channelCode/companyCode`、经纬度和短期 token。
- `DetailUrlCommand` 只携带可信 `projectId/channelCode/companyCode`、订单冻结的 `tradeId` 和短期 token。
- `app_key`、私钥、nonce、timestamp、sign、HTTP 地址和第三方响应均不得进入端口合同。
- `EntryUrlResult` 只返回旅通最终 `jumpUrl`，其 `toString()` 继续脱敏。

Facade、RPC DTO、项目路由和数据库表无需因旅通响应格式变化而修改。

### 3.2 新增 Integration Adapter

建议在 `integration/rtmart` 下新增以下职责明确的对象：

```text
RtmartEntryProperties
├── enabled/baseUrl/homePath/detailPath
├── appKey/privateKey
├── connectTimeout/readTimeout/writeTimeout
└── maxResponseBytes

RtmartRequestSigner
├── 生成 nonce_str 与 timestamp
├── 排序并构造签名原文
└── SHA1withRSA + Base64

RtmartEntryUrlHttpAdapter implements RtmartEntryUrlPort
├── 构造 HOME/DETAIL JSON 请求
├── 执行 OkHttp 调用
├── 解析并验证响应
└── 映射为 EntryUrlResult 或 RtmartEntryException

RtmartEntryResponse
└── msg/result/code/jumpUrl
```

`OkHttpClient` 为单例 Bean，不得每次请求重新创建连接池。Adapter 以注入的单例为连接池与超时配置来源，
但必须在构造期通过 `newBuilder()` 派生专用 client，并强制设置 `retryOnConnectionFailure(false)`、
`followRedirects(false)` 与 `followSslRedirects(false)`；不能只依赖装配层配置。这样即使调用方错误注入默认
client，也不会在结果未知时隐式重放请求，或把 token/sign JSON 请求发送到重定向的非预期地址。

旅通专用 client 必须声明为 `defaultCandidate=false`，且 Adapter 以名称/Qualifier 注入它。这样它不会参与聚合
Spring 容器中其他未限定 `OkHttpClient` 消费者的候选解析，现有共享 client 仍是这些消费者的唯一/默认候选。

### 3.3 Bean 启用与失败关闭

- `aifuli.supply-chain.instore.rtmart.enabled=true` 时注册真实 Adapter。
- 启用时缺少 base URL、app key 或私钥，或者私钥无法解析，服务启动失败，禁止带着无效配置注册 Nacos。
- 即使全局设置 `spring.main.lazy-initialization=true`，启用态的旅通 Properties、PKCS#8 signer 与真实 Adapter
  仍必须在应用启动完成前 eager 初始化；非法秘密不得推迟至首个入口调用后才暴露，更不得在错误配置下完成注册。
- 未启用时保留 `InstoreEntryPortConfiguration` 的失败关闭默认端口，调用仍稳定返回
  `1_050_001_046`，不得生成假 URL。
- 真实 Adapter 注册后，现有 `@ConditionalOnMissingBean(RtmartEntryUrlPort.class)` 默认 Bean 自动退位。

## 4. 旅通请求协议

### 4.1 HTTP 约定

首次联调按当前文档与已确认假设使用：

- 方法：`POST`
- Content-Type：`application/json; charset=UTF-8`
- Accept：`application/json`
- HOME：`/pay_center/rt-pos-api/getRtPosUrl/home`
- DETAIL：`/pay_center/rt-pos-api/getRtPosUrl/detail`
- 测试 base URL：`https://test.ltsw.cn`

路径必须以配置方式保存，禁止在业务类散落完整地址；生产配置的 base URL 只允许 HTTPS。base URL
若显式携带端口，端口必须在 `1..65535` 内；端口 `0` 或超出上限的配置必须在启动校验阶段失败关闭。
MockWebServer 契约测试允许
显式注入 HTTP，但 URI host 只能是精确 IP 字面量 `127.0.0.1` 或 `::1`；不接受 `localhost`、域名或任何 DNS
解析结果，防止 DNS rebinding。其他 HTTP 地址在启动校验阶段失败。收到旅通正式方法或 Content-Type 修订时，
仅调整本 Adapter 与契约测试。

### 4.2 请求字段

公共字段：

- `app_key`：Adapter 私密配置。
- `company_code`：HOME 过渡期来自中台配置 `aifuli.supply-chain.instore.home.company-code`（默认 `L0000A`）并须通过启用的渠道商户绑定校验；DETAIL 来自订单冻结快照。不接受 SaaS 请求体覆盖。
- `token`：中台刚签发的短期明文 token，只存在于当前调用内存和旅通请求。
- `nonce_str`：每次请求使用安全随机数生成的 32 位小写十六进制字符串。
- `timestamp`：以注入的 `Clock` 生成当前 Unix 秒；服务时钟必须保持 NTP 同步。
- `sign`：对除 `sign` 外的参与字段生成的 Base64 RSA 签名。

HOME 额外携带原始字符串精度的 `longitude/latitude`；DETAIL 额外携带订单冻结的 `trade_id`。字段名以旅通 Word
文档为准，不在 Facade 内改名或构造 JSON 请求。

### 4.3 签名规则

1. 移除 `sign`，只过滤值为 null 的参数；空字符串按旅通 `signData/mapKeySort` 参考实现参与签名。
2. 按参数名自然升序排序。
3. 依次拼接 `参数名 + 参数值`，不插入 `&` 或 `=`。
4. 使用 UTF-8 将签名原文编码为字节。
5. 使用配置的 RSA 私钥执行 `SHA1withRSA`。
6. 对签名字节执行标准 Base64 编码，作为 `sign` JSON 字段值。

私钥解析器接受 PKCS#8 的 PEM 包装或裸 Base64；pro143 `.env` 统一保存移除 PEM 包装和空白后的单行 PKCS#8 Base64，
避免多行环境变量解析差异。文档测试私钥若不是 PKCS#8，部署准备阶段先离线转换并用对应公钥验签，不在运行时引入
宽松格式猜测。启动时只完成规范化、Base64 解码和 KeyFactory 解析，不输出原文或指纹以外的密钥信息。自动化测试
使用运行时生成的独立测试密钥，不复制旅通文档测试私钥。

## 5. 响应与错误语义

### 5.1 正式成功响应

旅通已提供的成功 envelope 为：

```json
{
  "msg": "",
  "result": "success",
  "code": "E0000",
  "jumpUrl": "https://hourh5-em-shop.feiniugo.com/pages/transferpage?<redacted>"
}
```

成功必须同时满足：

1. HTTP 状态为 2xx；
2. `code` 精确等于 `E0000`；
3. `result` 精确等于 `success`；
4. `jumpUrl` 非空、可解析为绝对 URI、scheme 为 HTTPS 且 host 非空。

`jumpUrl` 查询参数包含 token/sign 等凭证。Adapter 只验证地址结构，然后原样放入 `EntryUrlResult`；不得拆解、
重排、URL decode/re-encode 或打印完整值。聊天工具可能把 URL 渲染成 Markdown 链接或把查询字符显示为实体，
实现始终以真实 HTTP JSON 中的原始 `jumpUrl` 字符串为准。

响应体先在固定上限内读取为原始 UTF-8 `byte[]`，直接交 Jackson 解析，禁止先转为 `String` 再解析。解析必须
拒绝 trailing token/garbage、重复 JSON 字段和将数字、布尔或 JSON `null` 强制转换为 String；非法 UTF-8 同样是
协议错误。Jackson 仍忽略旅通未来增加的未知字段，但不兼容此前假设的 `data.url` 或顶层 `url`，避免错误响应被
误判为成功。

### 5.2 失败分类

新增内部异常 `RtmartEntryException`，至少区分：

- `CONFIGURATION`：配置或私钥不可用；启用阶段应在启动时失败。
- `SIGNATURE`：运行时签名失败。
- `TRANSPORT`：DNS、连接、TLS、读写或取消异常。
- `HTTP_STATUS`：非 2xx。
- `REMOTE_ERROR`：旅通 `E4001/E4002/E4003/E4004/E5000` 或其他非成功业务码。
- `PROTOCOL`：空响应、超限、非法 UTF-8、非 JSON、trailing token、重复字段、标量强制转换、字段缺失、
  `E0000` 与 result/jumpUrl 矛盾、非法 URL。

异常允许保存非敏感的旅通 code、HTTP status 和失败分类。因果链必须统一经过安全化边界：
安全副本递归保留原异常的边界类别、Java 类型名、栈帧、嵌套 cause 结构和 suppressed 类别，并带有固定的递归深度、
整棵树节点总数和栈帧总数上限；同一原始 `Throwable` 在非环共享子图中再次出现时只生成 `reference` 标记，禁止重复展开。
单节点 suppressed 宽度同样必须有固定上限（默认 16），复制时不得按原 suppressed 总数分配数组。宽度、深度、节点或
栈帧预算超限时用不含原异常引用的安全标记节点显式表示已截断，不得静默丢弃，也不得在预算耗尽后继续为每条分支分配节点；
但不得保留可能包含 token、jumpUrl、响应或配置原文的原始 message，也不得持有原始 `Throwable` 引用。原敏感 `Throwable`
不可直接成为最终 `RtmartEntryException` 的 cause。无论配置 URI 解析、私钥解析、nonce 生成、签名、传输、JSON 解析还是
URL 校验失败，都必须复用该边界，不能为单一调用点保留原异常。同时不得保存/拼接请求 token、sign、私钥、完整 JSON 请求、
完整响应正文或 jumpUrl。`InstoreBuyerEntryFacade` 继续统一撤销本次凭证，并向 SaaS 映射
`INSTORE_ENTRY_URL_UNAVAILABLE（1_050_001_046）`。`046` 表示中台未能可靠取得入口 URL，
不是旅通原始业务错误码。

响应体设置固定上限，建议默认 64 KiB；Content-Length 或实际读取超过上限即按 `PROTOCOL` 失败，防止异常响应
占用无界内存。

## 6. 超时、重试与事务

- 建议默认连接超时 3 秒、写超时 5 秒、读超时 10 秒，均可由 Nacos 调整。三个 timeout 必须是整数毫秒，即
  `Duration.equals(Duration.ofMillis(Duration.toMillis()))`，且在 OkHttp 可表达区间 `[1ms, Integer.MAX_VALUE ms]`
  内；小于 1ms 或任意亚毫秒尾数都会在换算时被截断，可能改变调用方意图或变成 OkHttp 的 0（禁用超时），必须在
  启动校验阶段失败关闭。
- Adapter 不自动重试；Adapter 自身从注入 client 派生专用 client，强制 OkHttp
  `retryOnConnectionFailure(false)`、`followRedirects(false)` 与 `followSslRedirects(false)`，不能将这些安全语义
  仅交由 Bean 装配层保证。
- HTTP 调用保持在中台数据库事务之外。
- Facade 先在短事务内签发 ACTIVE 凭证并提交，再请求旅通。
- 调用失败或结果不确定时，按现有逻辑将该凭证撤销为 REVOKED，原因保持 `ENTRY_URL_CALL_FAILED`。
- SaaS 业务重试会签发新 token、nonce、timestamp，不复用已撤销 token。
- 调用成功只返回 jumpUrl，不把完整 URL 或旅通响应持久化到供应链数据库。

## 7. 配置与秘密管理

### 7.1 Nacos 非敏感配置

命名空间：`aifuli-mall-test`（与 mall-server、member-server 等商城测试服务共用，便于 Feign 服务发现）

Group：`test`

Data ID：`supply-chain-server-test.yaml`

建议内容：

```yaml
aifuli:
  supply-chain:
    timer:
      enabled: false
    outbox:
      enabled: false
    instore:
      home:
        company-code: L0000A
      rtmart:
        enabled: true
        base-url: https://test.ltsw.cn
        home-path: /pay_center/rt-pos-api/getRtPosUrl/home
        detail-path: /pay_center/rt-pos-api/getRtPosUrl/detail
        connect-timeout: 3s
        write-timeout: 5s
        read-timeout: 10s
        max-response-bytes: 65536
```

Nacos 不保存 app key、RSA 私钥、数据库密码、Nacos 密码或项目认证主密钥。

无论是否启用全局 lazy initialization，`enabled=true` 的旅通 Properties、私钥解析与真实 Adapter 都不允许延迟；
它们必须在应用就绪及服务发现注册之前完成校验。配置错误仅返回受控分类；原始异常必须按
第 5.2 节转换为不含原 message 或原 `Throwable` 引用的安全因果链，不在异常消息或 cause 中回显 app key、
私钥原文、base URL 原文或其他秘密。

### 7.2 pro143 `.env` 敏感配置

部署目录固定为 `/data/aifuli-test/supply-chain`，`.env` 所有者为 root、权限 `0600`。RSA 私钥使用单行 Base64，
不得把 PEM 的多行原文直接写成不确定的 env_file 语法。`.env` 至少包含：

- 供应链数据库 URL、用户名、密码；JDBC 默认使用 RDS 私网主机名并带
  `sslMode=VERIFY_IDENTITY`，由 MySQL 驱动校验服务器证书和主机名，禁止模板回退为
  `useSSL=false` 或 `allowPublicKeyRetrieval=true`；
- 同 VPC 可访问的 Nacos 私网地址、用户名、密码、Config/Discovery namespace 与 Group；不得将公网 Nacos
  地址固化进 Git 模板；
- 32 字节 Base64 项目认证主密钥；
- 旅通测试 app key 和文档测试 RSA 私钥；
- `SPRING_PROFILES_ACTIVE=test`；
- 显式关闭 Timer/Outbox 的环境变量，作为共享数据库期间高于 Nacos 的第二道保护。

使用 Spring Boot 标准环境变量绑定，不在 Compose 中写实际值。首次生成的项目认证主密钥在后续重新部署时复用，
不得每次随机更换，否则数据库中加密的项目应用密钥将不可解密。

`.env.example` 的 Nacos 和 RDS host 只能是占位符。pro143 实际 `.env` 填入可由该主机访问的 VPC 私网 DNS
名称或私网 IP；RDS TLS 所需的受信任 CA 必须已在运行时镜像/JVM 信任库中可用，连接失败时不得为排障关闭
`VERIFY_IDENTITY`。

HOME 的 `company_code` 过渡期由中台非敏感配置 `aifuli.supply-chain.instore.home.company-code` 读取（默认 `L0000A`），
不作为旅通秘密、也不接受 SaaS 请求体传入；DETAIL 仍从订单快照读取。正式态改为 `appId` 绑定。

## 8. 自动化测试

### 8.1 纯逻辑 `*Test`

- `RtmartRequestSignerTest`
  - 参数排序稳定；
  - 排除 sign/null，保留空字符串参与签名；
  - UTF-8、SHA1withRSA 和 Base64 与独立验签结果一致；
  - 固定 Clock 和 nonce 后签名可重复；
  - 私钥格式错误保留因果链且不泄漏密钥。
- 配置测试
  - enabled=false 时失败关闭默认端口存在；
  - enabled=true 且配置完整时真实 Adapter 替换默认端口；
  - enabled=true 且秘密缺失/非法时上下文启动失败。

### 8.2 MockWebServer `*ContractTest`

- HOME JSON 包含文档要求字段，token/经纬度保持原值，sign 可用测试公钥验证。
- DETAIL 使用 `trade_id`，不把 `orderNo` 错发给旅通。
- `E0000 + success + jumpUrl` 返回原始 URL。
- E0000 但 result 非 success、jumpUrl 缺失/非 HTTPS/非法时失败关闭。
- E4001、E4002、E4003、E4004、E5000 和未知 code 映射 `REMOTE_ERROR`。
- 非 2xx、空响应、非 JSON、超限响应和超时分别映射稳定内部分类。
- 不跟随 3xx，不自动重试，同一次 Port 调用最多向 MockWebServer 发出一次请求。
- 测试捕获日志，断言 token、sign、私钥和完整 jumpUrl 不出现。

现有 `InstoreBuyerEntryFacadeTest` 继续验证 Adapter 抛错后凭证撤销及对外 `046`，无需让 Facade 了解旅通 code。

### 8.3 发布门禁

1. 供应链 server 全量 `clean test`。
2. 新增旅通 `*ContractTest`，通过 `supply-chain-integration` profile 执行。
3. 既有架构测试继续保证 API/Application 不依赖具体旅通 Adapter。
4. Docker 镜像启动冒烟检查不替代上述自动化测试。
5. 实现完成时同步更新 `docs/集成测试.md`，记录真实 HOME 请求、预期响应、数据库摘要与排障方法。

## 9. Docker 镜像设计

### 9.1 构建

- 本地使用 JDK 21/Maven 构建精确产物
  `aifuli-module-supply-chain-server/target/aifuli-module-supply-chain-server.jar`。
- Mac 为 arm64、pro143 为 amd64，Docker Buildx 必须显式指定 `--platform linux/amd64`。
- 运行时基础镜像使用仓库同类服务采用的 `eclipse-temurin:21-jre`。
- Dockerfile 只复制上述精确 JAR，不使用 `target/*.jar`，避免把当前工作区的同步冲突副本装入镜像。
- Dockerfile 通过 `ENV JAVA_OPTS` 提供
  `-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8` 镜像默认值，其中 JVM 的 `6g`
  表示 6 GiB。默认值属于可移植的运行基线，新服务器不需要在 `.env` 中重复声明即可启动。
- ENTRYPOINT 继续展开 `${JAVA_OPTS}`；部署环境可以通过同名环境变量整体覆盖镜像默认值，以适配不同内存规格。
  覆盖是整串替换而不是参数合并，覆盖值必须同时包含所需的堆、时区和编码参数。`.env.example` 仅提供注释形式的
  可选覆盖示例，不得以活动配置遮蔽镜像默认值。
- `.dockerignore` 排除 Git、源码无关 target 产物、`*.env`、私钥、文档和同步冲突文件。
- 镜像标签使用 Git commit SHA，不使用不可追溯的 `latest` 作为部署依据。
- 部署模板仅使用可由 pro143 访问的 VPC 私网 Nacos/RDS 地址占位符；RDS JDBC 保持
  `sslMode=VERIFY_IDENTITY`，不以关闭 TLS 或跳过身份校验换取连通性。

首次单机部署不引入镜像仓库：本地构建 `linux/amd64` 镜像，经 `docker save | gzip` 传到 pro143，
再执行 `docker load`。未来需要多机发布、镜像扫描或保留策略时再迁移到阿里云私有镜像仓库。

### 9.2 Compose 运行

- Compose 只运行一个 `supply-chain-server` 实例，`restart: unless-stopped`。
- 使用 Linux host network，监听既定端口 58190，不再做容器端口二次映射。
- Nacos Discovery 显式注册地址为 pro143 内网地址 `172.19.64.105`、端口 58190，避免注册 Docker bridge IP。
- Config 与 Discovery 的 Nacos server address 均取 pro143 可达的同 VPC 私网端点；仅 Discovery 的服务注册地址
  固定为 `172.19.64.105:58190`。
- Compose 不重复声明 JVM 参数；无显式 `JAVA_OPTS` 时使用镜像 6 GiB 默认值，有显式值时由 `env_file` 覆盖。
- 不新增 Gateway/Nginx 路由；云安全组不得向公网开放 58190。
- 日志输出 stdout/stderr，由 `docker logs` 诊断并设置 Docker 日志轮转上限，防止磁盘无界增长。
- 未引入 Actuator 前使用容器内 TCP 58190 检查作为存活检查；业务就绪以 Liquibase 完成、Nacos 注册和签名冒烟为准。
- `.env` 只在服务器创建，Compose 文件只引用变量名。

## 10. 首次部署顺序

1. 确认 Git 目标提交、供应链自动化门禁和 Docker 构建上下文干净；不同步或删除用户现有冲突文件。
2. 启动本机 Docker Desktop，确认 Buildx 可构建 `linux/amd64`。
3. 构建 JAR、镜像并检查镜像架构、JAR 路径和提交标签。
4. 在 pro143 创建 `/data/aifuli-test/supply-chain`，写入权限 0600 的 `.env`；随机生成并持久化认证主密钥。
5. 在 Nacos 创建非敏感 Data ID，核对 namespace/group 均为已确认值。
6. 再次只读确认共享数据库为空或处于预期 Liquibase 版本，确保没有另一供应链实例同时迁移。当前 RDS 实测为
   MySQL 8.0.36，而自动化发布基线为 MySQL 8.4；19 个 changeSet 不得引入 8.4 专属语法，首次迁移结果本身是部署门禁。
7. 传输并 `docker load` 镜像，上传 Compose；不得在命令输出中展开 `.env`。
8. `docker compose up -d`；首次启动由 Liquibase 执行 19 个 changeSet。
9. 核对容器状态、启动日志、数据库 `DATABASECHANGELOG` 数量、Nacos 注册 IP/端口以及 58190 TCP。
10. 按受控脚本准备联调项目、应用认证、采购商/会员路由和企业组数据，不在应用启动时自动 seed。
11. 由 SaaS 或签名测试客户端调用 HOME RPC，确认中台只发出一次旅通请求并返回正式 jumpUrl。
12. 确认 `sc_instore_entry_credential` 只保存 token SHA-256 摘要，日志中无 token/sign/私钥/jumpUrl。

## 11. 回滚与故障处置

- 保留当前和上一个 Git SHA 镜像。应用回滚只修改 Compose 的镜像标签并重新 `up -d`。
- 首次部署前没有旧供应链运行版本；若启动失败，停止容器并保留 Liquibase 结构和日志，不执行手工删表或 DDL 回滚。
- 启动阶段配置失败：修正 `.env`/Nacos 后重启，不绕过 fail-fast 校验。
- 旅通返回 E4001/E4003：检查 app key 与必填字段；E4002 检查 NTP、timestamp 和三分钟窗口；E4004 检查排序、私钥和
  JSON 字段集；E5000 或未知响应保留内部 code/耗时后与旅通核对。
- 对外持续出现 `1_050_001_046` 时，依次检查真实 Adapter 是否启用、启动配置、请求是否实际到达旅通、HTTP/旅通 code、
  响应 `result/jumpUrl` 和凭证撤销记录；不能把 `046` 直接解释为旅通原始错误。
- 共享数据库阶段任何 Timer/Outbox 抢占迹象都视为配置事故：立即停止 pro143 容器，核对环境变量与 Nacos 双重开关。

## 12. 完成标准

- 真实 `RtmartEntryUrlPort` 在完整配置下替代失败关闭 Bean，HOME/DETAIL 均可生成签名 JSON 请求并解析正式 jumpUrl。
- 旅通协议失败仍由现有业务边界稳定映射 `1_050_001_046`，失败 token 被撤销且不复用。
- 自动化测试覆盖签名、正式响应、全部已知错误、网络/协议异常、单次调用和日志脱敏。
- 详细设计、关键中文注释、`docs/集成测试.md` 与实现同一批次对齐。
- `linux/amd64` 镜像可在 pro143 通过 Compose 启动，空库完成 19 个 changeSet，服务以
  `172.19.64.105:58190` 注册到 `aifuli-mall-test`。
- 镜像元数据包含 6 GiB JVM 默认值；无覆盖时 Java 进程使用该值，显式设置 `JAVA_OPTS` 时 Java 进程使用完整覆盖值。
- Timer/Outbox 在共享数据库阶段保持关闭，所有部署级明文秘密只存在 pro143 权限 0600 的 `.env`；业务密钥如需入库，
  只能按既有项目认证设计加密持久化。
- 首次 HOME 联调返回旅通 `code=E0000/result=success` 的原始 jumpUrl，数据库和日志均不泄漏短期凭证。
