# HUA_SHENG Task3-A：隔离 HTTP、token 与严格协议解析设计

> 2026-10-07 19:18 CST 当前有效状态：第三轮独立只读针对性复审 `deleg_2d19d5ae` passed=true（0 阻断，全部 6 项历史阻断 100% 闭合，§17 技术附表静态可实施性完备）。当前代码与门禁仍保持 NOT_RUN，未放行编码；待用户确认实施授权。R3-04 暂停及商业启用限制保持不变。

> 2026-10-07 18:03 CST 当前有效授权：用户回复 `agree`，同意 A4 最小设计补正与针对性复审。§17 固定 5 新 main、3 新 test、FailureDiagnostics 一个文件中两个枚举末尾各追加三常量的实施候选范围；本轮仅修改设计、计划和集成测试记录，尚未修改枚举或其它 Java。TLS 装配采用 private 窄构造与 test-only MethodHandles 桥，不采用历史包私有候选；A4 全部行为门禁 NOT_RUN，必须独立针对性复审通过后再进入实施授权校准，不把本确认当作 Git 写/部署/商业放行。

> 2026-10-07 17:42 CST 当前有效裁决：A4 独立预审 `deleg_13d5496a` passed=false/BLOCKING，等待一个失败枚举文件的最小重开授权及传输技术附表确认，未放行编码。候选与微probe在仓外 `business-closure-20261007/prereview-a4/`；原单参端口与 HTTP WireRequest 清单尚未补正，不据旧草案实施。详见推进计划 §6.3.1 当前有效裁决。A4 门禁 NOT_RUN，A1–A3 有限验收及 R3-04/商业限制不变。

> 2026-10-07 17:15 CST 当前有效阶段：A1–A3 的有限验收记录分别见 §14.8、§15.6、§16.5；用户已授权继续 A4。当前进入 A4 独立只读设计预审，A4 实现与本地 HTTPS 契约均 NOT_RUN。先核现有 WireRequest、失败枚举、共享 deadline 和测试 TLS 装配的衔接契约，再冻结附表与白名单；涉及受保护前片文件或 pom 增量须另呈报确认。以下页首早期状态为历史，不代表 A1–A3 仍未实施；不解除 R3-04 暂停或任何商业启用限制。

> 状态：独立规格复审通过；未实施，按目录桥接优先级后续执行。日期：2026-10-04。
> 2026-10-07 当前首片状态：当前基线独立预审 `deleg_d2d569d1` 返回 `passed=false`，唯一阻断 BLK-API-01（金额/安全诊断精确 Java 接口未定）。本次仅补 §14 技术接口附表候选并重新送审；原业务规则不变，尚未实施或运行测试，不能据附表直接编码。R3-04 暂停及目录商业启用阻断不解除。
> 2026-10-07 11:19 CST 当前有效裁决：针对性复审 `deleg_bd83a2c0` passed=true，BLK-API-01 在首片范围闭合。父已核报告及冻结输入无漂移、HEAD/status/空暂存区，依据本轮继续推进授权，仅放行 §14 的11 main/3 test首片实施；原首审failed保留。所有行为仍 NOT_RUN，后续需真实TDD、独立规格及另一质量审查和父验收；不关闭完整Task3-A或解除任何暂停/商业限制。
> 总设计：`docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md`，尤其 §12.1、§12.3、§12.5、§12.6。
> 最终业务目标：全新 `HUA_SHENG`、`FIXED`、数量 1 的卡号卡密资产与资金闭环；第一张目标为 type201，不以 QNM 替代。当前工作次序仍为批量前端、BPD 页面、受理目录；本规格可在 BPD 设计审查期间准备，实施须另经审查。

## 1. 范围与完成定义

Task3-A 产出可独立调用和验证的HUA_SHENG通信组件：封闭端点/错误码、类型化请求、密码协议请求构造、受控 raw-body HTTP 传输、token 管理、严格明文响应解析、七种协议卡片的无损表达。组件只返回通信事实，不返回本地订单成功、可信资金失败或可由 Timer 自动重发的指令。

本切片不注册 `HUA_SHENG` 的 `AdapterMetadata` 或 `OperationExecutorPort`；不修改适配器目录、连接器 Schema、订单受理、操作表、checkpoint 表结构、Timer、Outbox、恢复任务、资产存储、账户或账本；不调用供应商真实环境；不增加回调入口；不改变 QNM。组件暂不加 Spring 自动注册注解，由测试显式组合，未来 C 的应用编排显式接线。不得出现“HTTP 单测通过即第一张卡闭环完成”的验收表述。

七种卡型全部可在协议层校验并保真。当前 `OperationAssetFact` 的 `cardNo/cardSecret` 两字段不是正式协议的能力边界，A 不修改该模型，也不把 203 的 crc、303 的 urlPass 塞进密码或丢弃。后续 B 可先完成 type201 的资产载荷/指纹/加密/两套查询 DTO/实际前端展示桥接；未来 C 定案受控步骤事务、资金核对和持久化查单恢复。type201 与 C 的这些边界完成即可启用第一张 type201，无需等待全部七种卡型的资产业务桥接；未完成桥接的其它类型在应用层失败关闭，A 仍保持七型协议无损。

完成定义：指定文件范围的代码与 TDD 测试实现，经独立 JCA、真实本地 HTTP 挡板、严格 parser、token 并发/隔离、QNM 回归及供应链架构门禁；更新 `docs/集成测试.md` 记录实际命令/计数/人工前置。本文写作阶段不执行上述实现或测试，不能预填成功记录。

## 2. 已知事实、用户补充与本地选择

### 2.1 正式协议事实

正式来源及实读范围保存于总设计 §12.6；分享访问凭据不复制到本文、夹具或日志。接口详情路径为准，不因流程图别名而试探多个路径。

1. 明文请求顶层添加整数秒 timestamp、每次唯一 nonce，不包 data。时差窗口 300 秒，nonce 最多 64 字符。AES-CBC/PKCS7、小写 hex；RSA PKCS1 v1.5/SHA512 对密文文本原字节签名。沿用已完成 `HuashengCrypto`，Java PKCS5Padding 对该块长度等价。
2. 出站头为 X-AppId/X-Sign；token 不带 Authorization，业务带 `Bearer <access_token>`。普通响应是明文 JSON，整数 `code=0` 才是业务成功。
3. token 数据为 access_token/expires_in，文档描述有效期 7200 秒；实际响应 TTL 必须解析，不能缺字段兜底 7200。
4. PLACE 的 outOrderSn 在同一经销商内幂等，同值要求同参数。couponId 正整数，num 1–10；expireTime 可选 60–1800 秒/默认 300；notifyUrl 必填且公开 HTTP/HTTPS；outMobile 可选。QUERY 的 orderSn/outOrderSn 严格二选一。
5. PLACE/CONFIRM/QUERY 详情数据字段为：id、brandId、brandName、couponId、couponName、cardType、orderSn、outOrderSn、outMobile、num、totalPrice、totalAmount、price、amount、status、refundStatus、isTake、payTime、takeTime、expireTime、refundTime、createdAt。示例 cardType 为 VIRTUAL。**这些响应字段在 OpenAPI 中均标可选**；下面必要字段是中台安全接受规则，不得描述成供应商必填保证。
6. 订单 status 为 WAIT（待支付）、SUCCESS（支付成功）、FAILED（未分配任何券且已退款）、CANCEL（取消）；refundStatus 为 NONE/WAIT/SUCCESS；isTake 为 N/Y，Y 表示主动取卡或回调已经整套交付。SUCCESS 不等于本地资产交付成功。
7. price/totalPrice 为最多 4 位小数的定点字符串，amount/totalAmount 为最多 2 位小数的面值字符串。TAKE data 仅 orderSn/num/list，list 项为 id/code/pass/crc/url/urlPass/type/validTime；TAKE 没有 outOrderSn 或 totalPrice。
8. type201/202/203/301/302/303/304 字段组合见 §6。validTime 按批次提供，格式不统一且允许空字符串。卡片 id 是外部卡券 ID，无全局唯一资产 ID 保证。
9. 61 为重新授权，66 为请求/安全校验失败。7014 不存在、7015 状态错误、7101 确认失败后先查单，7016/7019/7020/7302 等涉及执行中的内部失败，不能由错误码直接断言未扣款或未发券。

### 2.2 用户补充契约

这三项来自 2026-10-04 用户明确回答，不冒充正式页面原文或真实供应商验收：price/totalPrice 单位人民币元，不足分以 HALF_UP 四舍五入；同一 orderSn 重复 CONFIRM 只扣一次；TAKE 响应丢失或 isTake=Y 后重取仍返回同一整套卡密。模拟挡板按这些约定验收，真实环境仍需核实。

### 2.3 本地设计选择

本期对外请求数量只允许 1；协议 DTO 仍保留 num 和完整类型，不能把协议 1–10 误写成只支持 1。强类型、长度/深度界限、严格 JSON、响应身份/数量校验、token 提前刷新、有限缓存、至多一次 61 刷新重试均是本地防御策略。字段缺失或矛盾返回协议不确定事实，不代填默认订单状态、金额、卡片或有效期。

比较过三种方案：扩展 QNM 表单执行器会改变其头/表单/超时语义；移植旧客户端会引入宽松数字转换、单账号缓存和旧业务重发；本规格选择独立HUA_SHENG raw-body 执行器并复用当前端点策略与密码组件，隔离副作用，供未来编排消费事实。

## 3. 现有代码与架构边界

代码核验基线如下，不能在实施时假设不存在的类已可复用：

- `supplieradapter/huasheng/HuashengCrypto.java` 已交付，复用其 AES/RSA 实现，不能另写另一套算法。
- 仓库不存在通用 `HttpClientPort`。`supplieradapter/qnm/QnmHttpExecutor.java` 只有 postForm，采用 QNM 特定头/表单及秒级兼容超时；只复用现有 OkHttp、MockWebServer Maven 依赖，不改这个类或其行为。
- `infrastructure/security/SupplierEndpointPolicy.java` 读取 `aifuli.supply-chain.connector.allowed-hosts`，校验 HTTPS、无 userinfo/query/fragment、允许主机和私网字面量。当前调用在 ConnectorValidationService 的发布/校验路径，不能把已发布版本等同于运行时仍允许出站。
- `OperationTicket` 已有冻结 baseUrl、merchantAccount、connectorVersionId、独立 connectTimeoutMs/readTimeoutMs 和仅 appSecret/privateKeyPem 的 CredentialMaterial。现有 Ticket 没有凭据集 ID/项身份快照，不能从 Ticket 任意猜测凭据身份，也不能偷偷读取 DAL 补齐。
- `PublishedConnectorConfigurationResolver` 及第二切片已按冻结版本验证凭据集/项身份，但当前 resolver 返回的 PublishedConnectorConfiguration 也没有 itemVersions/credential_item_snapshot，不能误以为当前 Ticket/config 已携带 §4 所需快照。未来 C 必须另行设计应用投影端口，从该 connectorVersionId 对应、已经绑定校验的冻结版本 credential_item_snapshot 投影 itemId/itemKey/providerType/activeSecretVersion，并与该版本 credentialSetId 一起传入；不得查询凭据项当前版本替代冻结快照。A 只定义纯值输入与验证，测试显式构造假快照，不修改 resolver/factory/OperationTicket，也不拿 current connector 替代冻结版本。

实施前读 `SupplyChainModuleArchitectureTest`，同时遵守总设计 AD-4 的额外分层决策，不能因现有 ArchUnit 尚未禁止某条依赖便改变它。TokenManager/cache协调及通信facade位于 border.fulfillment.application.huasheng；supplieradapter只保留密码、协议转换、解析与HTTP传输，不能依赖应用token/cache组件。应用通过构造参数的端口消费基础设施缓存实现，不反向依赖 infrastructure.token；cache实现可以依赖应用定义的缓存端口，不持有具体facade/TokenManager引用。新增组件不得依赖 border DAL、任何账户/账本 DAL、Mapper、Timer、Outbox 或 Spring 事务；parser/DTO/请求 builder 不依赖 Spring/MyBatis/Redis。没有 HTTP Controller、启动类、安全链或 Feign 变更。供应链仍是独立进程与数据源，不能接入 system/member 表，也不能合并淘宝闪购或电影容器。

运行时传输必须显式调用现有 SupplierEndpointPolicy。不能新增一个接收任意 URL 的 OkHttp 工具从组件旁路出站；即便原版本已通过发布校验，也逐次校验冻结 baseUrl 和最终 URL。现策略只识别字面量私网，不保证 DNS 解析后的地址安全；沿用总设计的受控 DNS/出口前置，不将 allowlist 宣称为完整 DNS 防重绑定。A 不削弱 HTTPS，不新增全局 trust-all、不扩大允许主机；真实 endpoint 的 HTTP/HTTPS 文档差异由部署确认解决。

## 4. 输入上下文与请求契约

### 4.1 通信与凭据身份

新增不可变纯协议 `HuashengCallContext`，包含 supplierCode、connectorVersionId、baseUrl、appId、CredentialMaterial、connectTimeoutMs、readTimeoutMs、operationNo、traceId，不携带缓存对象。应用层另有 `HuashengAuthorizedCallContext(protocolContext, credentialIdentity)`，identity 由应用纯值 `HuashengCredentialIdentity(credentialSetId, sortedItemVersions)` 表达，项为 itemId/itemKey/providerType/activeSecretVersion；应用投影从经过绑定校验的冻结版本完整快照仅选择 appSecret/privateKey 两项出站身份，不能混用另一供应商集。回调公钥不得进入出站材料或 token 缓存。应用工厂拒绝缺少正 ID、缺必需项、重复项/未知项、无有效超时或缺凭据；其安全字符串只含 operationNo/connectorVersionId，不打印 identity 原始集合或秘密。A 的测试显式供给该身份，C 接线前必须落实 §3 的真实快照投影来源，不能假设现有 Ticket 提供 itemVersions。

appId 唯一来源定为冻结配置 `merchantAccount`，与 X-AppId/AES IV/app_id 一致，UTF-8 严格 16 字节；extraParameters 不允许再给第二个 appId。A 的独立构造入口显式传 appId，但未来上下文投影只能 merchantAccount→appId，不用“merchantAccount 或 extraConfig 中任选一个”。轮换创建新凭据集和新连接器版本；ENV 引用冻结不等于环境值不可变，缓存另隔离本次实际通信材料身份，见 §8。

上下文不接收调用者原始报文、不接收 token 作为持久参数、不接受秘密 extraConfig。材料/报文仅在单次通信内存在，复制数组/集合防外部修改，敏感 DTO 的 toString 固定省略。A 不扩大秘密解决端口、不将回调公钥投影到 HTTP 客户端。

### 4.2 端点与封闭请求类型

`HuashengEndpoint` 仅五项：TOKEN `/api/token`；PLACE `/api/order/placeOrder`；CONFIRM `/api/order/confirmOrder`；QUERY `/api/order/queryOrder`；TAKE `/api/order/takeCard`，均 POST。取消、余额、品牌、目录、回调不进入本切片枚举，未来扩展先更新设计。路径不从 extraConfig 或调用者 Map 读取，不尝试 `/api/queryOrder`/`/api/order/query` 别名。

类型化请求为 `HuashengTokenRequest`、`HuashengPlaceRequest`、`HuashengConfirmRequest`、`HuashengQueryRequest`、`HuashengTakeRequest`，不能以任意 Map 当公共接口。TOKEN 只 app_id/app_secret；PLACE 只 outOrderSn/couponId/num/expireTime/notifyUrl/可选 outMobile；CONFIRM/TAKE 只 orderSn；QUERY 二选一单号。builder 添加 timestamp/nonce，而非调用者覆盖这些保留字段。

PLACE 的 num 必须为 1、couponId 必须正整数 long，超范围不截断；expireTime 缺省规范化成 300，显式值严格 60–1800，不能钳制。规范化后的请求只生成一次，61 重试复用同业务字段，包括 expireTime/outMobile/notifyUrl。outOrderSn/orderSn 非空字符串、上限 128 UTF-8 字节；不 trim 或数值化业务单号，不接受首尾空白、控制字符。outMobile 缺省则省略，显式传值仅允许字符串且上限 64 UTF-8 字节，不日志输出。notifyUrl 必须绝对 HTTP/HTTPS、无 userinfo/fragment、非本机/私网字面量，上限 2048 字节；这是回调参数，不由此发起出站请求，不能拿它扩充 supplier allowlist。此长度/地址限制为本地接受边界，不冒充正式协议上限。

QUERY 提供 `byOrderSn` 与 `byOutOrderSn` 两个静态入口，序列化恰一个字段。响应期待上下文 `HuashengExpectedOrder(outOrderSn, optionalOrderSn, quantity)` 独立于请求：TAKE 没有 outOrderSn 也必须由 C 绑定原双号；已知 orderSn 不允许收到另一个。PLACE 首次可未有远端号，成功解析必须取得非空远端号；未知远端号不能用 outOrderSn 代填。

### 4.3 每次发送构造

`HuashengRequestBuilder.build(endpoint, request, context, optionalToken)` 使用注入 Clock 与 nonce source，创建独立请求 JSON；timestamp 为当前 epoch second，nonce 为无连字符 UUID（32 个 ASCII 字符），每次物理发送都调用一次。不缓存整个密文报文，不在 token 获取与业务请求之间复用 nonce；签名原文严格是该次小写 hex 密文 UTF-8。业务字段、X-AppId 和材料冻结，timestamp/nonce/密文/签名随 61 重试重新构造。

出站 Content-Type 为 `text/plain; charset=utf-8`，正文是 hex 文本，不是 JSON 字符串、不 URL 编码、不表单包裹。X-AppId/X-Sign 使用该次材料；Authorization 只业务且只在该次请求头。禁止拦截器输出 headers/body。连接 URL 为规范化 baseUrl 去尾斜杠后加封闭路径，baseUrl 的已有前缀路径保留；校验最终 origin 与 base 相同，拒绝 URL 中 query/fragment/userinfo，不通过 resolve 吞掉前缀或切换 host。

## 5. 严格响应 envelope、金额与身份

### 5.1 JSON 接受规则

`HuashengResponseParser` 使用专用 Jackson 配置，不能修改全进程 ObjectMapper。响应最多 1 MiB 解码字节、深度最多 32、单字符串最多 16384 字符；响应按严格 UTF-8 解码，损坏编码拒绝。开启 STRICT_DUPLICATE_DETECTION，解析完手工检查 nextToken 为 null，拒绝重复 key、尾随对象/字符、空正文、null/数组顶层、损坏 JSON。数值和字符串不能自动互转：code/type/num/expires_in 为整数 token，`0.0`、`"0"`、布尔值不可接受；金额必须字符串，`9.99` 数字 token 不可接受。

envelope 需要整数 int 范围 code；message/traceID 若出现须为字符串并有长度界限，不能进入原样日志。code!=0 返回 `HuashengBusinessError(rawCode, knownErrorKind, safeExchangeSummary)`，不要求成功 data、不解析错误响应中的卡密为交付；未知数字保持原始 code 与 UNKNOWN，不把多个未知码改写成 -1。code=0 需要 object data，然后按端点解析。额外顶层/data 属性不赋予业务意义；卡片敏感载荷的额外非空属性按 §6 拒绝，防止无损范围悄悄扩大。

成功响应必要字段缺失/错误类型是 `INVALID_RESPONSE`，不是“成功但空资产”、不是供应商业务拒绝。parser 不返回部分成功；列表一项非法则整个结果非法。读到 response/code 不等于取得可信订单事实。

### 5.2 金额

`HuashengMoneyParser` 对 price/totalPrice 接受 `[0-9]+(\.[0-9]{1,4})?`，amount/totalAmount 最多 2 位小数。禁止负数、正号、指数、逗号、空白、NaN/Infinity、空串、前后 trim 纠正；原文保留用于事实保真但不入日志。数字文本最长 32 字符，本地存储换算值必须在 0…Long.MAX_VALUE 分之间。用 BigDecimal 原文精确解析，元×100 后 HALF_UP、longValueExact；全部超限以固定金额非法错误拒绝，不用 double/float。

totalPrice 整笔只舍入一次；不能先 round(price)×num、不能从面值 amount 或 totalAmount推导成本。0.0049→0、0.0050→1、9.9999→1000 分必须锁定。0 分是合法协议金额事实，是否允许 0 成本发布由已有应用规则判断，不在 parser 变成 1 分。

PLACE/CONFIRM/QUERY 的最小可用事实需要 totalPrice。如 price 同时存在，验证 `price×num` 与 totalPrice 的 BigDecimal 数值相等；如 amount 与 totalAmount 同时存在，同样校验面值总和。响应未提供的单价/面值不合成字段；差额在四舍五入前判断精确一致性。与配置冻结供应商成本的对比归 C，A 从不覆盖冻结价/预占，也从不宣布金额边界已通过。

金额表示为 `HuashengPrice(rawYuan, minorUnits)`，面值为独立 `HuashengFaceAmount(rawYuan)`。TAKE 的结果类型没有 totalPrice/settlementAmount 字段；即便意外额外属性包含 totalPrice，也不能把它升级为结算来源。最终金额只能来自已验证身份的 PLACE/CONFIRM/QUERY/checkpoint 或未来可信回调。

### 5.3 订单事实

`HuashengOrderResponse` 必需：orderSn、outOrderSn、num、totalPrice、status。两单号精确匹配 expectedOrder；PLACE 未知 orderSn 时只接收非空远端号；CONFIRM/QUERY 已知 orderSn 时必须匹配。按 outOrderSn QUERY 返回新的号只在 expectedOrder 尚无远端号时可建立关系，不能覆盖已有关系。num 必须等于本地预期 1。

refundStatus/isTake 在 PLACE/CONFIRM 可缺省，保持 Optional.empty；QUERY 要形成可用于恢复判断的详情事实则两者必须存在，否则返回 INVALID_RESPONSE。其它已知字段以 Optional 强类型保真：ID 字段为非负整数 long；名称/手机号/cardType 及时间字段为有界字符串，不依靠日期格式建立资金结论；payTime/takeTime/expireTime/refundTime/createdAt 原样保留。cardType 不从示例 VIRTUAL 推导额外有限状态机，也不替代卡片 type。

status/refundStatus/isTake 只接收上述正式枚举精确大小写，不 trim/大小写纠正。所有端点检测确定矛盾：WAIT 与 isTake=Y，WAIT 与 refundStatus=SUCCESS，FAILED 与 isTake=Y，FAILED 与已出现的 refundStatus=NONE/WAIT，SUCCESS 与已出现的 refundStatus=WAIT/SUCCESS，CANCEL 与 isTake=Y，均 INVALID_RESPONSE。FAILED 缺少退款字段可表达正式 FAILED 的语义，但不据此在 A 释放预占；QUERY 缺退款字段仍不可供恢复判断。CANCEL 与 NONE/WAIT/SUCCESS 的组合不凭空规定退款完成，保留状态等待 C 按退款事实审查，禁止简单映射失败释放资金。

这些检查是防矛盾规则，未穷举的组合不成为“可信终态资金决策”。SUCCESS+isTake=Y 仍必须取回完整卡片；isTake=N 不代表可重下；UNKNOWN 状态是非法响应而非替换为 WAIT。错误/状态 DTO 从不包含 `RETRY_SAFE` 或资金动作。

## 6. 协议卡片保真与 TAKE

`HuashengTakeResponse(orderSn,num,List<HuashengProtocolCard>)` 最小必要字段全部存在，orderSn 精确等于 expectedOrder 已知号，num=本期预期 1，list.size()==num，所有项非 null 且通过验证。不能把空列表、少券、多券、错误 orderSn 解析成最终成功；也不从 TAKE 推断 outOrderSn、付款成功或价格。

卡片 DTO 含 `HuashengField<Long> id`、枚举 type、`HuashengField<String> code/pass/crc/url/urlPass/validTime`，同时提供按类型访问的 `HuashengCardPayload` 封闭类型。Field 是带 ABSENT/NULL/VALUE 的不可变值，VALUE 字符串可以为空串，避免 Optional 将缺失和显式 null 合并；其 toString 固定脱敏。required 字段要求 VALUE 非空且不能全为空白，原值不 trim。其余已知字段即便当前类型不用也完整保留，不拼接、不复制成另一语义字段。若出现未声明的新非空载荷属性，整项拒绝为不支持协议载荷，等待规格扩展；未知属性为空/null 可忽略且不参与交付。

七种类型的 required 字段：201 为 code+pass；202 为 code 或 pass 至少一个，两者都有则都保留；203 为 code+pass+crc；301 为 url；302 为 url+urlPass；303 为 code+url+urlPass；304 为 code+url。type 必须整数已知枚举。url 是交付内容，按字符串完整保留，不主动打开/抓取，也不改写短链接；不能把它当出站 baseUrl 或由于不是某一种 URL 格式而造丢字段映射。

id 可缺省，出现则严格非负 long；不以其去重或宣称全局唯一资产键。validTime 可缺省/null/空串/任意有界字符串，全部保持，不猜 ISO 格式、时区、当日截止时刻，不生成 expiresAt，不把解析失败吞掉后视为过期。B 如需要展示/查询结构化有效期，另定义识别结果与保留原文的契约。

所有卡片/载荷/list 的字符串表达只含 type/数量，不带任何 code、pass、crc、url、urlPass、validTime 或 id；不能依靠 record 默认 toString。列表不可变，协议 DTO 不导出公开 JSON Controller，不被审计全文序列化。保真不等于已经允许存储/交付其它类型；B 桥接如果只支持 201，必须明确拒绝其它型的资产桥接，同时保留 A 能正确解析正式其它型的事实。

重复 TAKE 的字节表达可能不同，A 只验证当前响应，不能创建资产指纹或跨请求去重。C+B 按规范化完整类型载荷验证原整套卡一致，发现不同卡片保持冲突并禁止第二次交付/扣款；不能仅比较 id 或仅比较 code/pass 从而漏掉 203/303 的差异。

## 7. HTTP 执行器与通信失败

### 7.1 职责与端口

新增 `HuashengHttpTransportPort.exchange(HuashengWireRequest, HuashengTransportContext)` 返回 `HuashengInternalAttempt<HuashengHttpExchange>`，其实现 `HuashengHttpExecutor` 是唯一直接执行 OkHttp 外呼的 HUA_SHENG 类（固定拦截器和正文作为该类私有嵌套实现）。wire request 由 §4 builder 生成，使用 A3 已验收的 protocol 模型；冻结 baseUrl 与共享单调 deadline 由新增纯协议 TransportContext 从原 CallContext 正向投影，详见 §17.2，不从最终 URL 逆猜，不保留单参旁路或公开 `post(String arbitraryUrl,Map)`。该端口是本切片新增，不能把它写成仓库已有 HttpClientPort。HTTP 成功 carrier 中的有限原响应字节由 HttpExchange 严格 UTF-8 桥接到 A2 的 String parser，parser 不依赖 http 包，实际返回 `HuashengInternalAttempt<HuashengParseFact<T>>`；所有原始失败沿内部 carrier 流转。安全证据为纯协议 `HuashengExchangeEvidence`，不依赖应用 CallBudget/CallResult，应用可合并累计 attempts 而无逆向依赖。§17 是本节具体签名、计量、装配和失败边界的规范性附表。

执行器每次先 `SupplierEndpointPolicy.validate(baseUrl)`，再验证拼接 URL；本地验证失败在创建 Call 前capture原因并返回PREFLIGHT内部失败carrier，由facade按是否已业务发送投影为安全异常或LocalFailure，不让policy原异常穿透。构造每次有界客户端时分别使用 connectTimeoutMs/readTimeoutMs；writeTimeout=readTimeoutMs，callTimeout=min(connectTimeoutMs+2×readTimeoutMs, 本次调用deadline剩余毫秒)（long 精确计算，超限或剩余非正拒绝），防握手/上传/读流长期挂起，TOKEN请求也分享同一deadline。禁止 followRedirects、followSslRedirects 和 retryOnConnectionFailure；不重发 408/429/5xx，不在连接池故障时隐式再发送，不在拦截器刷新 token。响应体 try-with-resources 关闭，流式读取最多 1 MiB+1 字节，超限终止，不先 body.string() 无界读。

合法 2xx 才交给 parser；3xx/4xx/5xx 返回 `HTTP_ERROR`，即便正文恰为 code=0 也不接受成功。不跟 Location，不把 token 转发到另一个 host。网络超时/IOException/中断返回 `TRANSPORT_FAILURE`，保留内部因果；线程中断恢复 interrupted 标志。不是 SocketTimeout 的 IO 不能伪装超时。

### 7.2 结果类型

应用facade的对外结果为封闭 `HuashengCallResult<T>`：`Parsed<T>`、`BusinessError`、`HttpFailure`、`TransportFailure`、`InvalidResponse`、`LocalFailure`。失败分支只带 `HuashengFailureDiagnostics` 安全值，不带 Throwable、内部失败对象或原正文。共有 exchange evidence（endpoint、HTTP status可选、raw businessCode可选、durationMs、request/response字节数、physicalSendCount、wasBusinessCallStarted），及有界 attempts 列表保留本次至多2个TOKEN和2个业务的安全证据。physicalSendCount只计业务端点，tokenSendCount另计，不能用最后一轮TOKEN覆盖业务已发送证据。使用 monotonic time 统计耗时；不返回原响应正文给未来 Timer/审计，也不把密文/header/token塞进 summary。parser/transport供facade消费的内部结果按 §9.1 与此对外结果分开，不能复用包含原cause的record。

调用前本地非法上下文/参数/加密配置以 `HuashengProtocolException.fromDiagnostics(PREFLIGHT安全诊断)` 表达，businessCallStarted=false，原因为§9.1内部失败持有；一旦调用 `Call.execute()`，保守设置 businessCallStarted=true，即使 DNS/连接失败也不宣称供应商未执行。A 不推测字节是否到达供应商。无 token 且首次 token 获取失败时业务端点未开始；已经收到业务 61 后刷新失败则业务端点已经开始，保留首轮 evidence 和刷新失败因果，不能由统一 catch 改成无副作用。发生首次业务发送后的本地构造、policy、预算/deadline错误用LocalFailure携带累计evidence，禁止再抛无已发送上下文的PREFLIGHT异常；预算耗尽也不是外部未执行证明。

parser 失败类型包括 ENVELOPE_INVALID、DATA_MISSING、IDENTITY_MISMATCH、QUANTITY_MISMATCH、MONEY_INVALID、STATE_CONFLICT、CARD_PAYLOAD_INVALID、RESPONSE_TOO_LARGE，使用枚举/固定说明，不输出 offending value。原始 Jackson/IO/Crypto Throwable 只保留于 §9.1 的受保护内部失败对象；对外结果/异常不得带该对象或原始 cause 链。固定安全的诊断转换入口负责投影，不要求内部原始 cause 的 message/toString 脱敏，不通过删除它实现对外安全。

### 7.3 错误码表与策略边界

新增 `HuashengErrorCode` 仅提供分类/本地固定说明，保留 rawCode，无无条件 retryable 布尔。当前必须锁定：0 SUCCESS；50 服务异常；51 参数校验；61 TOKEN_UNAUTHORIZED；66 SECURITY_VALIDATION；4001 token失败/4002 经销商禁用/4003 代理商不存在；7014 ORDER_NOT_FOUND；7015 ORDER_STATUS_INVALID；7016 BALANCE_FLOW_ERROR；7017 SYSTEM_BUSY；7018 BALANCE_INSUFFICIENT；7019 BALANCE_UPDATE_ERROR；7020 BALANCE_CHANGE_ERROR；7021 STOCK_DEDUCTION_ERROR；7022 STOCK_INSUFFICIENT；7023 ORDER_CREATION_ERROR；7024 BRAND_QUERY_ERROR；7025 ORDER_IDENTIFIER_CONFLICT；7101 CONFIRM_ERROR；7301 CARD_QUERY_ERROR；7302 TAKE_STATUS_UPDATE_ERROR。

剩余已知订单码也逐项枚举，不写区间吞分类：7001经销商查询失败、7002经销商不存在、7003经销商禁用、7004卡券查询失败、7005卡券不存在、7006卡券停售、7007品牌不存在、7008品牌停售、7009经销商卡券查询失败、7010卡券未分配给经销商、7011经销商卡券停售、7012订单查询失败、7013订单详情查询失败；7201已取消、7202已获卡不可取消、7203订单状态更新失败、7204订单状态已变化、7205库存退还失败、7206余额退还失败。目录5001–5005/6001–6005不接端点，若返回则保留rawCode UNKNOWN，不建立目录流程。枚举原始码按总设计§12.6正式错误事实及旧仓名称对照写测试，不移植其 requiresPlaceReconciliation 驱动逻辑。

通信层唯一内置重发为 §8 的有界 61 刷新。7017 的同幂等号退避、7101 的先查单、7014 的原订单不存在等全部交 C 判断；A 不 sleep 退避、不重下、不换供应商、不退款。66 没有刷新权限；7016/7019/7020/7302 和未知码不返回未执行证明。真实正式错误页新增码由 Unknown rawCode 保留，不因为当前枚举不全而吞掉。

## 8. TokenManager、缓存与有界 61

### 8.1 职责及缓存身份

保留总设计 AD-4 的决策：`border.fulfillment.application.huasheng.HuashengTokenManager` 只负责 obtain/refresh/invalidate 与 token 生命周期，通过注入的协议 builder/transport/parser 完成 TOKEN 端点通信，不懂订单资金事务。相同应用包的 `HuashengCommunicationFacade` 组合 manager、builder、transport、parser，公开 place/confirm/query/take，并把取到的token仅作为当次builder参数注入；supplieradapter不负责token获取/缓存或61刷新协调。HTTP executor 不知道 61 业务码、parser 不发 HTTP、Crypto 不知道缓存。A 不实现总设计的 TokenAuthenticatedExecutor/ExecuteHandler 接线示例，不向 Ticket.extraParameters 持久或临时写token；这里只细化应用注入的隔离组合，C再定案生产接线，不改变AD-4所有权。

A 使用应用包定义的 `HuashengTokenCachePort`，显式注入 `infrastructure.token.huasheng.HuashengInMemoryTokenCache` 实现，不访问 Redis。应用层manager调用cachePort.withIdentityLease(identity, action)，由action在锁内二次检查缓存并调用TOKEN通信；基础设施实现只提供有界lease/get/put/invalidateIfGeneration，不依赖具体manager/facade或supplieradapter，不自行授权。进程内以 key 单飞锁+CAS entry generation 串行授权，缓存最多 1024 个有效身份，过期优先清理，无法插入时安全失败，不能驱逐正在被授权的锁造成并发刷 token。锁引用计数/entry 退休在同一同步边界释放，不能为所有历史 key 无限留锁。缓存关闭时清理引用；TokenEntry.toString 固定 redacted。依赖方向为应用→自己的端口、infra→该端口，应用不import缓存实现；独立测试在测试装配点组合实现。

key 由 supplierCode、冻结 connectorVersionId、规范化 baseUrl、appId、credentialSetId、排序项 itemId/itemKey/providerType/activeSecretVersion，加实际出站材料指纹组成。材料指纹以进程启动随机 HMAC-SHA256 key 对长度分帧的 appSecret 与规范化私钥 DER 计算，只在内存使用、不日志/不审计/不进配置摘要；防 ENV 引用值变动复用旧 token，也不暴露可离线猜测的无盐秘密摘要。不同连接器版本、供应商、appId、凭据版本或本次材料值不得命中同缓存；不能只按 appId 或 merchantAccount 建全局缓存。

该实现只保证本进程单飞，不能宣称分布式多实例授权互不失效。A 无生产注册，因此不因本地缓存开启多实例业务执行。未来 C 启用前必须定案共享 token/分布式刷新互斥（含秘密存储保护）或限制唯一授权执行实例，并核实供应商是否使先前 token 失效；不能让多个节点各自刷新并依赖 61 循环修复。端口允许 C 更换实现，但不能跳过本节身份、CAS 与次数约束。

### 8.2 TTL 与并发

TOKEN code=0 data 必需非空字符串 access_token 与正整数 expires_in。正式文档描述有效期7200秒，未给官方最大TTL；本地选择命名常量 `HuashengTokenPolicy.MAX_ACCEPTED_TTL_SECONDS=7200` 作为安全接受上限，7201拒绝属于本地策略而不是供应商违反官方上限。A不开放该值为extraConfig，更改先改规格。parser校验正整数/表示范围，应用TokenManager按该policy校验本地上限；非正数、溢出、字符串/小数TTL或超本地上限均失败，不兜底。token 最多 8192 字符、禁止 CR/LF/NUL，不能 trim。expiresAt 以 token 请求开始 Clock instant+TTL 计算，扣去网络耗时；另以请求开始的单调Ticker+TTL限定缓存lease，缓存命中同时校验wall过期与单调lease，避免系统时间倒退延长token有效性。Ticker可注入测试；返回时已过期则 token 获取失败，不再循环补取。

refreshAhead=min(300秒, TTL/10)，整数秒向下取整；cacheUsableUntil=expiresAt-refreshAhead。TTL 1–9 秒时 ahead=0，仅在未过期时可用，不因固定300秒阈值持续刷新；每次 acquire 至多一次 TOKEN 网络请求。获取失败不缓存成功状态、不负缓存永久禁止；竞争线程获取锁后重新检查缓存，有有效结果直接复用。

Entry 带不可变 generation。业务 61 用实际使用的 entry generation 条件失效；迟到旧61不能删除已被其它调用更新的新 token。刷新线程加锁后二次检查，若已有更新有效 generation 则复用，否则只请求一次 token。token refresh 本身遇61/其它错误直接失败，不递归刷新，不授权第三次业务发送。

### 8.3 61 的一次调用上限

一次 place/confirm/query/take 的流程：acquire（缓存或一次 TOKEN）→新报文发送业务一次→仅在合法2xx严格envelope的 code=61 时 invalidate generation→refresh（复用较新 token 或一次 TOKEN）→用完全相同规范化业务 payload、新 timestamp/nonce/密文/签名再发送一次→返回第二次事实。第二次仍61直接 BusinessError；非2xx、非法JSON、超时或66都不触发刷新/重发。最多两次业务物理发送、最多两次 TOKEN（无缓存首次获取+一次强制刷新），不做第三轮。

同一订单幂等身份不能改变；请求总预算由 caller 传 `HuashengCallBudget`（可用物理业务发送次数、deadline），本地上限2，并在每次 TOKEN/业务发送前检查。预算不足/已到deadline则结束，保留已发送 evidence；未来 C 从原外呼/频控资格导出预算，禁止重置 operation attemptCount 或绕过计数。A 的独立测试明确预算2，但它不是授权原 Timer 自动重发。

61 处理是同一次隔离协议调用的授权恢复选择，不是整个业务 RETRY_SAFE。CONFIRM 网络未知必须由 C 原单 QUERY，再按 SUCCESS 跳过确认/WAIT受控确认；TAKE未知只沿原orderSn恢复；PLACE网络未知不得借61逻辑造新outOrderSn。61后刷新失败、二次发送未知，全部保持已发送身份与次数，C不得当成首次未发送重新预占/改路由。

## 9. 脱敏日志、因果与资源

重要公开入口记录 operationNo、connectorVersionId、endpoint、traceId、业务数量和尝试序号；第三方请求/响应记录相同安全身份、HTTP状态/固定错误类别/rawCode、耗时、字节数和物理发送次数。业务 outOrderSn/orderSn 如需关联仅记录进程 HMAC 摘要或固定掩码，不打印供应商回传未验证值；数量可记录，金额可记录“是否存在/转换校验结果”，不打印原报文摘要里的秘密字段。

禁止日志正文/header/token/appSecret/privateKey/signature/hex密文/outMobile/目标/code/pass/crc/url/urlPass/validTime；不对完整响应做普通 SHA-256 日志摘要（低熵卡片可能被离线猜测）。不使用 String.format/record 默认表达漏字段，不记录原 message/traceID。日志异常只消费 §9.1 转出的固定failureKind/causeKind安全值，不接收Throwable；内部原始causal chain可含秘密，只存在于受保护失败对象，不是可打印外部诊断。

正常通信入口采用 debug，异常按 endpoint+failureKind+rawCode 聚合限频（同类每分钟首条warn及抑制计数），采用有界聚合键清理；无每次token失败/定时失败都warn的风暴。汇总不包含业务单号/token材料；缓存取值不日志。HTTP流、Response、临时字节数组和测试私钥材料在成功/失败关闭或擦除，Java String不能保证物理清零，如实沿用当前材料不可变对象边界，不宣称密码已从堆中完全消失。

### 9.1 原始因果的内部保留与单向诊断投影

实施类与访问边界定为以下契约，不仅改最外层toString：

1. `supplieradapter.huasheng.failure.HuashengProtectedFailure` 为普通final类，不实现Serializable、不继承Throwable、不用record/Lombok getter。private final originalCause 保存原始Throwable对象及完整cause/suppressed链，不改其message/cause、不删因果。public静态capture(failureKind, originalCause, safeEvidence)仅允许代码枚举与已脱敏证据；原始正文不另存一份。`originalCauseForInternalInspection()` 仅同failure包可见，公开API没有Throwable/getCause/getMessage/rawResponse getter；该内部访问方法只用于受控内部诊断和同包测试，业务Controller/Timer/审计不能调用。toString固定为失败类别，不能迭代原cause链。
2. `HuashengInternalAttempt<T>` 是供协议执行链消费的内部carrier，其失败分支持有protectedFailure，parser.parseToken/parseOrder/parseTake及transport.exchange捕获原始解析/IO异常后返回该分支；builder的既有Crypto异常由应用facade（业务构造）或TokenManager（TOKEN构造）先capture。原始异常不得穿透facade。内部carrier及ProtectedFailure使用专用 `HuashengInternalOnlySerializer`，意外Jackson序列化时抛固定安全的“内部协议对象禁止序列化”异常，不能开启field visibility后导出private originalCause；其toString省略载荷/cause。它们不进入Controller DTO、审计、Outbox或持久表。禁止跨业务公开边界的返回/参数类型引用该carrier；manager仅内部消费或传递TOKEN的carrier，facade是唯一对外结果/异常转换边界，不把manager的内部失败直接返回调用方。
3. `HuashengFailureDiagnostics` 是不含Throwable引用的不可变安全值，只有failureKind、固定reasonCode、枚举causeKind（JSON_PARSE/IO/TIMEOUT/CRYPTO/VALIDATION/OTHER）、安全exchange evidence。`HuashengDiagnosticProjector.toSafeDiagnostics(protectedFailure)` 定义在failure包，按原cause的受控类型匹配产出causeKind，绝不调用原message/toString或复制stack/suppressed。转换是单向的：diagnostics不含protectedFailure/raw Throwable/原始对象ID或反查句柄，公开诊断无法恢复它。
4. 应用facade的 `finishFailure(HuashengInternalAttempt<?>, accumulatedEvidence)` 先保留内部protectedFailure引用至本次失败处理结束，再通过projector构造公开CallResult；`failPreflight(...)` 同样capture原因，必要时只抛 `HuashengProtocolException.fromDiagnostics(safeDiagnostics)`。该公开异常没有以原Throwable作为cause/suppressed，也不带protectedFailure字段；其固定message/toString和安全diagnostics完全脱离原始链。这不删除内部因果，原始对象仍在ProtectedFailure中供同包受控检查，只是不将它作为可被全局异常日志自动打印的公开cause。异常只含安全异常栈，不保存原cause的栈内容。公开结果/异常不能通过反射getter或Jackson暴露内部失败。
5. `HuashengSafeLog.recordFailure(HuashengFailureDiagnostics)` 仅接受安全值，无Throwable重载，也不能调用logger.warn/error的Throwable参数。失败处理完毕后释放内部carrier/cause引用，不新增无限缓存/永久保存原正文的诊断仓库；保留因果的范围是此次受保护执行与处理生命周期。测试持有内部对象验证转换前后 originalCause 为同一原对象，不要求其message/toString无秘密。

两个边界必须分别验收：内部保留真实因果；外部日志、公开message/toString、安全summary、对外诊断序列化完全没有秘密。Jackson异常的source片段、恶意重复字段名、供应商反射message都应作为包含秘密的内部cause夹具，不能假设禁INCLUDE_SOURCE_IN_LOCATION已足以消除它们。

## 10. 与未来 B/C 的事实接口

C 只能消费 Parsed<OrderResponse/TakeResponse> 与 exchange evidence，不能读取 raw body 后放宽验证。PLACE/CONFIRM/QUERY 的 totalPriceMinorUnits 与双号、num、状态来自同一严格解析事实；C在唯一受控事务内比较冻结成本，持久化有来源/身份的金额checkpoint。TAKE只提供远端号、数量、完整协议卡片；它与此前可信金额checkpoint及原双号合并后，才能由 B 构造完整资产事实，C再调用现有统一资产/资金终结入口。

协议事实最少保留来源endpoint、预期/返回身份匹配结论、冻结connectorVersionId、金额原文/分值（如果端点有）、状态/退款/提卡、数量、完整卡片。A不赋 trustedFailure/finalSuccess 等领域含义；C另检查步骤/认领代次/迟到冲突/原调用次数。敏感卡片或金额checkpoint后续必须经现有EnvelopeCryptoPort按新操作AAD加密，不能在A提前写明文、复制旧ciphertext或接入DAL。

金额和卡片来源可分开，但身份不能分开：不得把订单A的PLACE金额与订单B的TAKE卡密合并；不得在没有可信金额来源时用配置报价或TAKE数量“补齐供应商reportedSettlementAmount”。201桥接和C启用门禁完成可先交付201，无需等待其它六型的资产业务桥接；这也不意味其它协议类型不受支持。未完成类型桥接时应用失败关闭，禁止丢字段后成功。

回调不在A。将来回调复用金额/枚举/卡片的纯验证规则，但必须先独立完成Appid/X-Sign、解密、timestamp/nonce、冻结版本定位和防重，再生成可信来源事实；普通明文HTTP parser不能直接拿来接受公开回调。

## 11. 可实施文件与方法范围

以下相对主目录为 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/`：

1. `supplieradapter/huasheng/protocol/HuashengEndpoint.java`、`HuashengErrorCode.java`、`HuashengOrderStatus.java`、`HuashengRefundStatus.java`、`HuashengTakeStatus.java`、`HuashengCardType.java`：封闭业务常量，不散落码/状态字面量。
2. 同目录 `HuashengCallContext.java`、`HuashengExpectedOrder.java`、`HuashengRequests.java`、`HuashengProtocolResponses.java`、`HuashengProtocolCard.java`、`HuashengCardPayload.java`、`HuashengField.java`、`HuashengExchangeEvidence.java`：纯协议不可变值模型、受控构造与安全表达，不包含缓存身份/协调。
3. 同目录 `HuashengRequestBuilder.java`：build、新nonce/time、调用已有Crypto；`HuashengMoneyParser.java`：parsePrice/parseFaceAmount；`HuashengResponseParser.java`：parseEnvelope/parseToken/parseOrder/parseTake及严格字段helper。金额与身份校验不能由调用者绕过。
4. `supplieradapter/huasheng/http/HuashengHttpTransportPort.java`、`HuashengHttpExecutor.java`、`HuashengHttpExchange.java`、`HuashengHttpSafeLog.java`，以及 `supplieradapter/huasheng/protocol/HuashengTransportContext.java`：唯一 OkHttp 外呼、policy、流界限、超时/失败证据及本层安全日志。WireRequest 使用已验收的 protocol 类，不在 http 创建同名模型；精确白名单与唯一枚举重开见 §17.1。
5. `border/fulfillment/application/huasheng/HuashengAuthorizedCallContext.java`、`HuashengCredentialIdentity.java`、`HuashengTokenIdentity.java`、`HuashengTokenCachePort.java`、`HuashengTokenEntry.java`、`HuashengTokenManager.java`、`HuashengTokenPolicy.java`：应用所有的缓存身份、acquire/refreshAfterUnauthorized/条件失效、TTL策略；无DAL/事务/生产接线。
6. `infrastructure/token/huasheng/HuashengInMemoryTokenCache.java`：实现应用定义端口的有界lease/get/put/CAS/锁退休，不能import manager/facade/adapter，不自行请求token。应用代码不能import该实现。
7. `border/fulfillment/application/huasheng/HuashengCommunicationFacade.java`、`HuashengCallResult.java`、`HuashengCallBudget.java`、`HuashengProtocolException.java`、`HuashengSafeLog.java`：应用组合builder/transport/parser/manager、place/confirm/query/take、finishFailure/failPreflight及安全异常/限频。未加入AdapterRegistry或Spring扫描配置。
8. `supplieradapter/huasheng/failure/HuashengProtectedFailure.java`、`HuashengInternalAttempt.java`、`HuashengInternalOnlySerializer.java`、`HuashengFailureDiagnostics.java`、`HuashengDiagnosticProjector.java`：协议失败的内部保留、禁止序列化与单向安全投影，访问边界按§9.1。projector不依赖border应用，supplieradapter不为token/cache协调导入应用类。

允许复用HuashengCrypto、SupplierEndpointPolicy而不改变实现；本期不改QnmHttpExecutor、不改OperationTicket/OperationOutcome/OperationAssetFact、resolver、factory、ExecuteHandler或Schema。上述文件可按内聚关系使用嵌套不可变record减少小文件，但公开职责、测试及安全toString必须保持；不能把全部组件堆成一个含网络/缓存/订单的“SupplierAdapter”。新增重要类/方法中文注释链接本文对应章节。无生产DDL/部署SQL，无前端修改；B调查实际前端时必须查 `/Users/mac/Documents/codeSpace/supplychainhubweb`。

## 12. TDD 测试计划与验收命令

### 12.1 RED→GREEN 顺序

每组先写独立失败断言并保存RED，再写最小实现，再跑相关及最终门禁；不能只测build→本实现decrypt这种相互印证。第一组端点/请求/金额/响应纯逻辑，第二组真实HTTP及独立JCA，第三组token隔离/并发/61，第四组脱敏与架构/QNM回归。fixture使用本地新生成RSA与假秘密，不读取分享密码、真实token、真实卡密；本地挡板明确不是供应商沙箱。

### 12.2 纯逻辑覆盖

- `HuashengRequestBuilderTest`：五个精确path/字段/头、query二选一、appId16字节（中文字节长度）、配置缺身份、业务quantity1、num越界、couponId非法、expireTime边界/默认且不钳制、notifyUrl无效、outMobile省略、可变输入防御复制、每次nonce唯一/长度/timestamp秒级、不允许调用者覆盖保留字段。
- `HuashengMoneyParserTest`：四个小数边界、0.0049/0.0050/9.9999、面值与成本分离、整笔round而非round单价乘数量、Long分边界及超过边界/巨大数字、负数/指数/空白/数字JSON/超精度/浮点/非法格式、price×num与totalPrice不一致。
- `HuashengResponseParserTest`：重复key/尾随JSON/深度/超长/非UTF8、code缺失/字符串/小数/超int、code0缺data、非0未知code保真；stage所需字段缺失、错误类型、双号错误/缺失、num缺失/小数/少券多券、未知状态、退款/提卡矛盾、QUERY缺状态维度、CANCEL退款未完不能输出资金动作。
- `HuashengProtocolCardTest`：七型正反例，203保留crc、303保留urlPass、202两个值都保留；单项损坏整套拒绝；id缺失或相同不当唯一键；validTime缺失/null/空串/日期/非ISO/批次中文原文保真；未知type/新非空载荷拒绝；所有敏感toString不泄漏；TAKE无金额，额外totalPrice不得产生结算事实。

parser正向夹具必须包括最小合法订单，而不只用填满OpenAPI所有可选属性的示例：PLACE/CONFIRM 接受 `{"code":0,"data":{"orderSn":"HS_FIXTURE_1","outOrderSn":"OUT_FIXTURE_1","num":1,"totalPrice":"9.9999","status":"WAIT"}}`，省略全部非本地必需字段；QUERY在同一data仅加refundStatus=NONE/isTake=N即可接受，无price/amount/totalAmount/name/id/time依赖。另以最小TAKE `{"code":0,"data":{"orderSn":"HS_FIXTURE_1","num":1,"list":[{"type":201,"code":"TEST_CODE","pass":"TEST_PASS"}]}}` 接受无id/validTime，证明required来自本地阶段目标而非误将OpenAPI可选全部变必填。负向逐个删本地required得到确定failureKind，原始cause仍按§9.1受保护。

### 12.3 真实本地 HTTP/JCA 契约

`HuashengHttpContractTest` 使用真实MockWebServer socket而非只mock端口。客户端出站仍通过生产SupplierEndpointPolicy；测试配置仅允许 `hs-fixture.example`，HTTPS挡板使用测试自签名证书和仅测试注入的证书信任/DNS到loopback（如需证书工具新增test-scope okhttp-tls，版本跟随现有OkHttp管理，不加生产依赖）。生产构造不接受trust-all或禁policy参数；专用测试装配不可被Spring注册。非法allowlist/HTTP/private-literal用实际policy在发送前拒绝并断言挡板0请求，不能用测试绕过得出护栏通过。

服务器端独立JCA解密收到的hex正文并验RSA SHA512签名，对照精确明文、Content-Type/X-AppId/Bearer、无data包装；TOKEN不带Bearer，业务带Bearer。独立JCA不得调用HuashengCrypto生成预期结果。响应返回明文JSON，确认客户端没有误解密。覆盖prefix baseUrl拼接、精确QUERY详情路径、302/307不跟跳且第二服务器0请求、4xx/5xx/408/429不自动重发、真实断连/读超时/无body、1MiB+1流式拒绝、传输错误保留已开始/evidence。通过时不能声称受控DNS/真实出口已验收。

`HuashengTokenManagerTest` 以可控Clock/Ticker/实际身份值测试缓存key所有隔离维度、ENV实际材料变化、新版本/凭据轮换、短TTL/7200/非正/缺字段；正整数7201在parser层合法、在应用manager按MAX_ACCEPTED_TTL_SECONDS拒绝为本地TOKEN_TTL_UNACCEPTABLE，不能称官方TTL非法；还覆盖溢出、耗时侵蚀TTL、wall时钟倒退但单调lease到期后不命中、提前刷新、缓存容量和锁退休。并发闩锁确保同key只1次授权，不同key互不串扰；旧generation迟到61不能invalidate新generation；tokenString/identity不泄漏。`HuashengTokenCacheTest` 位于infra实现对应测试包，检查lease/CAS/容量/锁退休，不依赖协议客户端。

`HuashengCommunicationFacadeContractTest` 真实挡板序列：TOKEN→业务61→TOKEN→业务成功；最多2业务/2token，新nonce且业务字段完全不变；第二次61停；TOKEN自身61停；66/畸形JSON/非2xx/timeout无刷新重发；首次token失败businessStarted=false；第一次业务61后刷新timeout仍businessStarted=true且sendCount=1；二次业务timeout保留2；deadline/预算拒绝不能复位次数；CONFIRM同orderSn、TAKE同orderSn和整套字段。测试按应用facade→manager/adapter端口组合，不绕应用直接由supplieradapter负责token。在A不测试Timer/账户已成功，只检验通信事实。

### 12.4 脱敏、架构与回归

`HuashengSensitiveDiagnosticsTest` 分别验证两个边界。内部组在failure同包测试以恶意重复key名为秘密哨兵，触发真实Jackson异常；assertSame(originalThrowable, protectedFailure.originalCauseForInternalInspection())且cause类型/原有链正确，原始message可包含哨兵，projector转换后内部引用仍为同一对象。另用包含哨兵message的IO/Crypto原异常验证未为脱敏删除/改写cause。外部组用独特哨兵标记appSecret/privateKey/token/手机号/正文/密文/signature/code/pass/crc/url/urlPass/validTime，捕获Logback、facade所有公开结果/异常message/toString、安全summary及ObjectMapper序列化diagnostics，全部断言无哨兵；公开异常getCause/getSuppressed不持有原链，公开结果对象图不持有ProtectedFailure/Throwable。输入由供应商message/traceID/JSON source反射哨兵也不得泄漏。内部carrier/ProtectedFailure的意外Jackson序列化（含fieldVisibility=ANY）必须固定安全拒绝，不能因@JsonIgnore跳过一个getter而漏private字段；禁止把ProtectedFailure导入公开Controller/审计DTO。验证限频抑制计数和有界键。对“删除内部originalCause”“公开异常直接initCause(raw)”“调用logger.throwable或把safe summary改为cause.getMessage()”的测试均须RED，不能只测试最外层toString。

`HuashengProtocolArchitectureTest` 增加实际AD-4分层规则：supplieradapter.huasheng不依赖border.fulfillment.application.huasheng或infrastructure.token及任何cache/manager/facade；protocol不依赖Spring/OkHttp/MyBatis/DAL/Redis；仅http包执行OkHttp；应用huasheng不依赖infrastructure.token具体实现，仅依赖自己cachePort；infra缓存实现不依赖supplieradapter/manager/facade，允许依赖应用端口/缓存值。新增HS各包都不依赖Timer/Outbox/账本/DAL/OperationExecutorPort，不存在HS metadata注册；failure原始cause访问方法仅包可见，公开结果/诊断DTO字段无Throwable/ProtectedFailure，公开Controller/审计等无InternalAttempt/ProtectedFailure依赖。规则不是解释现有ArchUnit已通过，而是对总设计决策新增自动门禁。全量 `SupplyChainModuleArchitectureTest` 必须实际执行，QNM现有parser/http/adapter/sign/masking定向回归通过。无数据库更改的A不要求造无意义MySQL IT，但ContractTest仍按现有Failsafe profile运行；后续C数据库/锁/事务必须真实MySQL/Liquibase，不能由A挡板替代。

### 12.5 实施时命令

前置JDK21、现有Maven依赖与本地HTTPS挡板可用。以下为计划命令，本文没有运行记录；依赖需要更新时去掉-o准备依赖后再按实际命令记入集成测试，不能把其它模块skip扩大成供应链skip。

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -DskipTests=false '-Dtest=HuashengRequestBuilderTest,HuashengMoneyParserTest,HuashengResponseParserTest,HuashengProtocolCardTest,HuashengTokenManagerTest,HuashengTokenCacheTest,HuashengSensitiveDiagnosticsTest,HuashengProtocolArchitectureTest,HuashengCryptoTest,SupplyChainModuleArchitectureTest,*Qnm*Test'
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test-compile failsafe:integration-test failsafe:verify -Psupply-chain-integration -DskipTests=false -DskipITs=false '-Dit.test=HuashengHttpContractTest,HuashengCommunicationFacadeContractTest'
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -DskipTests=false
```

每组RED/GREEN、最终命令、实际执行数量/失败/跳过、挡板范围、供应商沙箱未跑、架构实际规则数写入 `docs/集成测试.md`；本规格审查完成后实施同次同步，不在设计准备阶段改其它文档。若无Failsafe报告/实际测试计数，不能以构建exit0宣称契约已执行。

## 13. 审查门禁与后续启用条件

本文不留关键语义TBD：单位/舍入、重复confirm/take由用户补充，正式字段及卡型已保真，61/身份/缓存/异常/测试均在A内定案。仍未具备的是A之外的生产启用条件：B至少完成type201资产无损桥接，未桥接其它类型应用失败关闭；C落实冻结snapshot→缓存身份真实投影、可信金额checkpoint与冻结成本核对、原订单QUERY恢复、认领代次/迟到围栏、唯一下一步/次数期限、UNKNOWN截止/人工处置、分布式token授权协调；回调独立审查。无需先完成全部七型资产业务桥接即可在其它条件均满足后启用201。没有这些条件，HUA_SHENG不注册metadata/executor、不上架生产履约，不能以A单独测试通过接入Timer或账本。

规格审查时逐项确认：AD-4应用所有权与新增架构规则一致；raw-body调用仍逐次policy；无QNM修改；response可选与中台required区别清楚并有最小合法夹具；TAKE不能造金额；203/303/validTime不丢；缓存冻结身份与实际ENV值分离且C补真实snapshot投影；TTL7200是正式有效期描述/本地上限选择两项不同事实；61失败保留已发送因果；内部原始cause保留与单向安全诊断分别测试；真实socket+独立JCA且无秘密；201启用不依赖全部七型资产桥接；C所需事实接口只表达事实。审查修改先更新本文再改实现，不先写代码补设计。

## 14. 金额与安全诊断首片技术接口附表（2026-10-07 待针对性复审）

**当前有效状态：** §14 已由 `deleg_bd83a2c0` 独立设计复审通过，父核后有限实施放行；本标题的「待针对性复审」及下方候选说明为补正阶段历史，不表示当前仍待预审。设计通过不是运行/质量/最终验收。复审新增30条矩阵、原8关联条款闭合，原113条及F01–F20完整保留于仓外 `pre-review-rev2/report.json`。

本节仅补 BLK-API-01 的 Java 类型、返回形状、缺省证据、构造边界与文件布局，不改变 §5.2、§9.1 的业务与安全规则。原首审报告保留于仓外 `business-closure-20261007/pre-review/`；113 行完整 Task3-A 矩阵及 F01–F20 首片矩阵仍作为逐项核验输入，运行全部 NOT_RUN。新附表须复审通过后才能实施，不是生产启用批准。

### 14.1 接受集合与首片边界

price/totalPrice 接受原定 ASCII 定点词法、最长32字符、最多4位小数；保持原文，用 BigDecimal 精确换算，HALF_UP 后 longValueExact，分值0至Long.MAX_VALUE。允许前导零，不 trim、不浮点、不把0改1。面值只保存 rawYuan，最多2位小数及32字符，不提供分值，不因 raw-only 面值新增 Long 分上限；这明确 §5.2 的「本地存储换算值」只约束实际换算值，不扩大面值接受集合。32个9的整数面值仍合法。

本片不实现 JSON token 检查、订单价格总和一致性、响应订单/卡片 parser、HTTP/token/facade、安全日志限频、资金、Timer 或资产。这里只验内部因果保存与公开诊断/异常安全，并以真实 Jackson 失败夹具验证原因可以含秘密但投影不含秘密；不算响应 parser 交付。

### 14.2 唯一类型布局与签名

以下 `protocol`、`failure` 为 `com.aifuli.module.supplychain.supplieradapter.huasheng` 下子包，`application` 为 `com.aifuli.module.supplychain.border.fulfillment.application.huasheng`。所有值不可变，引用非空要求用固定安全说明拒绝，不得打印入参；无 Lombok 自动表达，无 Spring 注解、自动注册、DAL、事务或应用反向依赖。

**protocol 金额接口：**

```java
public final class HuashengMoneyParser {
    public static HuashengInternalAttempt<HuashengPrice> parsePrice(String rawYuan);
    public static HuashengInternalAttempt<HuashengFaceAmount> parseFaceAmount(String rawYuan);
}
// 两金额类均 final，构造器包可见，只有 MoneyParser 生产调用；无 public 构造/setter。
HuashengPrice(String rawYuan, long minorUnits);
public String HuashengPrice.rawYuan();
public long HuashengPrice.minorUnits();
HuashengFaceAmount(String rawYuan);
public String HuashengFaceAmount.rawYuan();
```

本节签名表是接口记法，不是可直接复制编译的文件。MoneyParser 私有构造，两个值各自独立文件，不同时新增 Responses 中的另一套金额类型。金额类 `toString()` 固定 `HuashengPrice[redacted]` / `HuashengFaceAmount[redacted]`，raw getter仅用于内部严格协议链，不记录原文或作为对外 DTO。包可见构造不等于 Java 能限制唯一同包调用者；新增架构规则核生产构造调用只允许 MoneyParser，测试负控制证明规则有效。

null、空串、非法词法/精度/长度、price 换算超限统一返回 `HuashengInternalAttempt.Failed`，不抛原输入异常。实际 NumberFormatException/ArithmeticException 被捕获时保存原对象及因果链；词法拒绝创建固定说明 IllegalArgumentException 作为实际校验原因，不伪造不存在的底层异常。不捕获 Error/OOME。金额 helper 不发网络，失败 evidence 必须 Optional.empty()，不捏造 TOKEN/PLACE/HTTP状态。失败 kind/reason 均 MONEY_INVALID，causeKind VALIDATION。

**failure 安全值与枚举：** 三枚举均 public 嵌套于 `HuashengFailureDiagnostics`，不新增顶层枚举路径。

```java
public enum HuashengFailureKind { MONEY_INVALID }
public enum HuashengFailureReasonCode { MONEY_INVALID }
public enum HuashengCauseKind { JSON_PARSE, IO, TIMEOUT, CRYPTO, VALIDATION, OTHER }
// Diagnostics 构造器仅 failure 包可见，仅 Projector 的生产调用。
HuashengFailureDiagnostics(HuashengFailureKind kind, HuashengFailureReasonCode reason,
                          HuashengCauseKind cause, Optional<HuashengExchangeEvidence> evidence);
public HuashengFailureKind failureKind();
public HuashengFailureReasonCode reasonCode();
public HuashengCauseKind causeKind();
public Optional<HuashengExchangeEvidence> evidence();
public String toString();
```

Diagnostics 是 final 普通类，仅上述四个安全字段，无自由 message/reason/summary、原链、内部对象、对象ID或反查句柄。公开值序列化为安全字段，不能因 Optional 模块支持差异扩大字段或复制原输入。toString 仅安全枚举/安全证据，不调用原异常。其余通信 kind/reason 后片按对应行为先设计再扩枚举；首片不声称完整分类已实现。

**failure 内部因果与互斥 carrier：**

```java
public static HuashengProtectedFailure capture(HuashengFailureKind failureKind,
        Throwable originalCause, Optional<HuashengExchangeEvidence> safeEvidence);
Throwable originalCauseForInternalInspection(); // 仅同failure包可见
public String HuashengProtectedFailure.toString();
public static <T> HuashengInternalAttempt<T> parsed(T value);
public static <T> HuashengInternalAttempt<T> failed(HuashengProtectedFailure failure);
public T HuashengInternalAttempt.Parsed<T>.value();
public HuashengProtectedFailure HuashengInternalAttempt.Failed<T>.failure();
public String HuashengInternalAttempt.toString();
```

ProtectedFailure final、private 构造、非 record/Throwable/Serializable；private final originalCause 保持同一真实引用和完整 cause/suppressed，capture 不访问原 message/toString，也不另存正文。可供 Projector 使用的 kind/evidence 读取仅 failure 包可见，无公开 Throwable getter。InternalAttempt 为 sealed 类，两个 public static final 嵌套分支 Parsed<T>/Failed<T>，构造均 private，仅工厂创建互斥非空状态；表达分别固定成功/失败类别，不包含 value/failure 原文。不实现 Serializable；对外业务 Controller/Timer/审计等不得依赖该类型，即使其 Java 类型因跨协议链组合需要 public。分支 getter 仅内部组合消费，不能把 Failed 直接返回公开业务边界。

**failure 投影和序列化拒绝：**

```java
public static HuashengFailureDiagnostics HuashengDiagnosticProjector.toSafeDiagnostics(
        HuashengProtectedFailure protectedFailure);
public HuashengInternalOnlySerializer();
public void HuashengInternalOnlySerializer.serialize(Object value, JsonGenerator generator,
        SerializerProvider serializers) throws IOException;
```

Projector final 私有构造，只有上述 public 方法。只用原 Throwable 的受控类型匹配，不遍历/复制 message、stack、suppressed 或输出原链：直接原类型优先 JsonProcessingException→JSON_PARSE、SocketTimeoutException→TIMEOUT、其余 IOException→IO、GeneralSecurityException→CRYPTO、IllegalArgumentException/ArithmeticException→VALIDATION、其余→OTHER；JSON_PARSE 必须在 IO 前。固定 MONEY_INVALID kind 映射固定 MONEY_INVALID reason。若后片要新增包装异常分类或原因链遍历，先补契约，不由实现者猜。内部对象保存原因引用至本次处理结束，无永久诊断仓库。

InternalOnlySerializer 为 final JsonSerializer<Object>，直接固定安全拒绝「内部协议对象禁止序列化」；类型级 Jackson serializer 注解保护 ProtectedFailure、carrier 及两个具体分支，普通 Mapper 和 fieldVisibility=ANY 都不能导出私有字段。不改共享 Mapper。失败异常及根因文本、已有输出片段均不得含测试秘密哨兵。Serializer 不读取 value 或原 cause，因此拒绝先于任何字段写出。

**application 公开安全异常：**

```java
public static HuashengProtocolException fromDiagnostics(HuashengFailureDiagnostics diagnostics);
public HuashengFailureDiagnostics diagnostics();
public String toString();
```

HuashengProtocolException final extends RuntimeException，private 构造，固定安全 message，仅 diagnostics 字段，cause 初始化为 null 且 suppression 关闭；initCause(raw) 不可挂原链，addSuppressed(raw) 不保存原链。只保安全自身栈，无 ProtectedFailure/InternalAttempt/原 cause 引用。首片不实现 facade 的发送前后 PREFLIGHT/LocalFailure 分支；安全异常不能反向被协议包引用。

**protocol 安全通信证据：**

```java
public enum HuashengEndpoint { TOKEN, PLACE, CONFIRM, QUERY, TAKE }
public HuashengExchangeEvidence(HuashengEndpoint endpoint, OptionalInt httpStatus,
        OptionalInt rawBusinessCode, long durationMs, long requestBytes, long responseBytes,
        int physicalSendCount, boolean wasBusinessCallStarted);
public HuashengEndpoint endpoint();
public OptionalInt httpStatus();
public OptionalInt rawBusinessCode();
public long durationMs();
public long requestBytes();
public long responseBytes();
public int physicalSendCount();
public boolean wasBusinessCallStarted();
public String toString();
```

Evidence final 不可变纯协议值；引用非空，耗时/字节数/计数非负。只含 §7.2 已定义安全字段，无秘密、原单号、自由文本或反查；rawBusinessCode 保留原整数，不将未知码归一。首片只验证安全证据传递，不制造发送记录，不冻结后片的实际 HTTP 状态/次数编排；总 tokenSendCount 和 attempts 仍由后片应用结果负责。Endpoint 首片只落实封闭常量，不提供任意路径，真实五路径 builder 属后片。

### 14.3 首片写白名单与受保护范围

以下为复审候选唯一布局，共11个新增main、3个新增test；无既有代码修改。main 路径相对 §11 主目录：

- `supplieradapter/huasheng/protocol/HuashengMoneyParser.java`
- `supplieradapter/huasheng/protocol/HuashengPrice.java`
- `supplieradapter/huasheng/protocol/HuashengFaceAmount.java`
- `supplieradapter/huasheng/protocol/HuashengEndpoint.java`
- `supplieradapter/huasheng/protocol/HuashengExchangeEvidence.java`
- `supplieradapter/huasheng/failure/HuashengProtectedFailure.java`
- `supplieradapter/huasheng/failure/HuashengInternalAttempt.java`
- `supplieradapter/huasheng/failure/HuashengInternalOnlySerializer.java`
- `supplieradapter/huasheng/failure/HuashengFailureDiagnostics.java`
- `supplieradapter/huasheng/failure/HuashengDiagnosticProjector.java`
- `border/fulfillment/application/huasheng/HuashengProtocolException.java`

test 路径相对 `src/test/java/com/aifuli/module/supplychain/`：

- `supplieradapter/huasheng/protocol/HuashengMoneyParserTest.java`
- `supplieradapter/huasheng/failure/HuashengSensitiveDiagnosticsTest.java`
- `architecture/HuashengProtocolArchitectureTest.java`

负控制 fixture 可置于该架构测试嵌套测试类，不增加生产通道或额外文件。新增重要中文注释链接本文 §5.2/§9.1/§14 真实章节。禁止改 Crypto/QNM、既有架构测试、Ticket/Outcome/AssetFact、resolver/factory/ExecuteHandler、pom/资源/注册/DDL/池/manager/预算、R3-04、前端；禁止建 HTTP/token/facade/SafeLog 桩来冒充交付。需要增路径先报最小diff，不为编译扩大白名单。

### 14.4 首片门禁与非阻断实施义务

首审 F01–F20 矩阵为必需首片门禁，原113行完整矩阵继续保留，首片通过不能关闭完整 Task3-A。N01 带-am、精确 QNM 纯测与 Failsafe 契约分别运行；N02 不解除目录阻断；N03 不冒称 JSON/总和/facade/SafeLog 交付；N04 不扩面值限制/前导零/非零规则；N05 新架构规则有结构负控制且不改旧规则，均落实为实施义务。

真 RED 使用最小可编译签名/支撑，断言缺失行为失败后最小实现 GREEN；缺类/编译错误、夹具错误、BeforeAll 或0选择不计 RED。每步测试字节与原 XML/退出码/源码hash绑定，已经正确保护初次 GREEN 如实记回归，不造旧行为失败。全量供应链纯测/架构及以下 QNM 契约需实际运行，HS HTTP契约仍后片未运行。新计划 §3.3 带-am命令替代 §12.5 旧命令作为当前有效执行命令。

```bash
mvn -B -ntp -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test \
  '-Dtest=HuashengMoneyParserTest,HuashengSensitiveDiagnosticsTest,HuashengProtocolArchitectureTest,HuashengCryptoTest,SupplyChainModuleArchitectureTest,QnmGameOrderV1MetadataTest,QnmSupplierAdapterTest,QnmSignUtilsTest,QnmCallbackParserTest,QnmProtocolParserTest,QnmMaskingUtilsTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -ntp -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration verify \
  '-Dtest=com.aifuli.module.supplychain.**.*Test,!**/*IntegrationTest,!**/*ContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false '-Dit.test=QnmSupplierAdapterContractTest' \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

以上均计划未运行。独占仓外 archive/JDK21/-am/原XML逐方法/双退出码/同测试 RED→GREEN 与候选hash、独立规格→另一质量→父验收仍必需。原供应链单测不得跳过，无新 MySQL 变更不造数据库证据；QNM 契约不当作华昇 HTTP、供应商或首单验收。

### 14.5 首片实际候选状态（2026-10-07T11:53:18.743196+08:00，工具时间；不改变契约）

本轮仅 §14.3 的11 main/3 test实现并冻结可审候选；实际门禁见 `docs/集成测试.md` §7.87及仓外 `business-closure-20261007/implementation-a1/`。定向11类189项、全供应链纯测/架构195类1,894项、QNM Failsafe定向契约12项全部0失败/错误/跳过；profile verify还同轮执行完整纯测1,894项。JDK21/-o/-am/独占archive+全部5文档及本片增量；最后代码补核后全部门禁重新执行。

只报告金额/安全诊断子范围已实现和运行通过，不是规格实现审查/质量审查/父最终验收。原页首未实施及§14计划NOT_RUN为历史记录；完整响应/请求/卡片/HTTP/token/cache/facade/限频、资产资金、目录及真实供应商均未交付或未运行。内部cause/suppressed保原引用，公开投影不持原链；Optional实际安全字段用本类型嵌套固定编码，不改变共享Mapper/pom。既有保护源码逐字未改，未注册生产入口，不解除R3-04或商业暂停，不提交推送。

### 14.6 当前有效审查状态：质量失败，未最终验收（2026-10-07 12:29 CST）

独立实现规格审查 `deleg_9727e51d` 在首片范围passed；另一上下文质量/安全审查 `deleg_4f264a20` failed。父核报告及反例证据哈希、原XML单项1失败/0错误/0跳过和真实Maven/runner exit1，当前代码与冻结候选无漂移。Q-SEC-01：安全公开异常虽然cause保持null，但继承initCause拒绝时JDK21生成的新IllegalStateException文本包含传入Throwable.toString，实际假秘密哨兵泄漏；原F15仅核抛出类型未覆盖拒绝异常文本。Q-QUAL-02：重要公开方法缺少详细中文契约注释和设计章节链接，属于AGENTS.md强制缺口。

旧门禁全绿仍只证明原受测断言，不能抵消新反例；旧规格passed保留为该快照审查记录，不作为首片完整安全放行。父最终验收未进行，下一片不放行。证据在仓外 `business-closure-20261007/quality-review-a1/`，原报告/反例及此前XML全部保留。本次仅同步失败状态，未改代码/接口契约、未运行修复门禁；修复方案须另行确认后设计先行，不自动把审查建议作为实施授权，暂停/商业边界原样保持。

### 14.7 质量修正实施授权与冻结契约（2026-10-07 12:31 CST，用户确认）

**授权：** 用户对上一轮最小修正范围明确回复“是”，本小节从本轮起是本片唯一修正协议。仅允许关闭 Q-SEC-01 与 Q-QUAL-02，不改变本片既有业务/接口/分层规则，不得借修复扩大范围或重启 R3-04/R3-05+/商业研究。

**Q-SEC-01 关闭协议（公开安全异常）：**

- `HuashengProtocolException` 公开覆写 `initCause(Throwable cause)`：参数固定安全说明拒绝（保留已初始化 null cause/关闭 suppression），拒绝异常文本固定「不允许覆盖安全异常的原始原因」，不调用传入 Throwable 的 toString/message/stack，不返回或保存引用。
- 结构封闭：构造函数保持 private、`super(message, null, false, true)` 不变；签名 `public final Throwable initCause(Throwable cause)` 由 final 类覆写继承 API。不新增字段、getter、静态工厂或生产通道。
- 必须回归：拒绝异常 message/toString/printStackTrace 输出均无哨兵；恶意 Throwable.toString 在拒绝路径上真实不被调用；原始安全异常仍 cause==null、suppressed 空；已有安全序列化行为不变；普通/ANY Mapper 编码仍安全。相同语义的独立负例先 RED（沿用假秘密哨兵夹具），最小实现后 GREEN。
- 边界：该 API 是 Throwable 公开继承入口，覆写不是语言级禁止其他代码在其它类型挂原链；本设计只约束本公开异常类型。不修改共享 Mapper、pom、Crypto 或 HTTP。

**Q-QUAL-02 关闭协议（注释）**：仅在本片获准 main 文件内，为以下重要公开入口补详细中文 Javadoc，逐项写明职责、不变量、关键决策、完整设计路径及章节锚点：`HuashengMoneyParser.parsePrice/parseFaceAmount`、`HuashengProtectedFailure.capture`、`HuashengInternalAttempt.parsed/failed`、`HuashengDiagnosticProjector.toSafeDiagnostics`、`HuashengProtocolException.fromDiagnostics`、`HuashengExchangeEvidence` 构造。不得顺带改变字段/常量/行为，不得记录 raw 金额或秘密。

**修复后必须（原计划 §3.1/§14.4 全保留）：** 定向新增反例及本片全部最终门禁（原三命令）重新运行；旧通过不替代修复后证据；随后独立规格增量确认与另一质量/安全复审、父最终验收。新候选须绑定修复前后hash、原XML、逐方法双退出码。任何溢出本小节的改动先停报精确 diff；禁止提交/推送/部署、新 HTTP/token、共享配置或暂停范围变化。

### 14.8 首片有限最终验收（2026-10-07，父验收完成）

两阻断修复已完成并通过全链：质量失败报告 `deleg_4f264a20` 的两个阻断逐字关闭，独立规格增量确认 `deleg_5d293d89`（spec-confirm）与另一新上下文质量/安全复审 `deleg_5d293d89`（quality-a2）均 passed。父最终核：14 个代码文件 7 个等于修复后字节、7 个等于旧候选字节零漂移；三门禁原 XML 独立聚合 11 类190项、195类1,895项、196类1,907项全0失败/错误/跳过且 Maven/runner 双 exit0；RED（8中1断言失败）与 GREEN 同测试字节；HEAD 未变、暂存区空、19 冻结路径。**首片（金额/安全诊断有限范围）最终验收成立。** 这不是完整 Task3-A、华昇生产启用或业务闭环验收；HTTP/token/cache/facade、响应/卡片解析、资产资金、目录 B/C、真实首单仍待后片，R3-04 暂停、R3-05+、商业启用边界不变。

## 15. 严格响应解析与订单/卡片事实（A2 片）技术接口附表（2026-10-07 待针对性复审）

本节依据 A2 片独立只读设计预审 `deleg_9e06724c`（报告归档于 `business-closure-20261007/prereview-a2/`）的裁决与冻结契约制定，闭合 `BLK-A2-01`（接口缺口）与 `BLK-A2-02`（重开首片已验收保护文件）。用户已明确确认重开首片 3 个文件并授权按本附表进行针对性复审。本附表通过针对性复审前，不得进行代码编码。

### 15.1 范围、输入前移与两层载荷方案（ADJ-A2-01 ~ ADJ-A2-03）

1. **输入上下文前移**：`HuashengExpectedOrder`（原计划列于 A3）前移至 A2 实现，作为 `parseOrder` 与 `parseTake` 的必需校验输入。构造方法 public，提供 `(String outOrderSn, Optional<String> orderSn, int quantity)`，校验 `outOrderSn` 非空非空白且 ≤128 字节、`orderSn` 出现时同约束、`quantity >= 1`，违规抛出固定说明安全 IAE。A3 后续直接复用，不得重复定义。
2. **Token 解析返回类型补正**：`parseToken` 返回类型为 `HuashengTokenResponse`（包含 `String accessToken()` 与 `int expiresInSeconds()`，包可见构造，redacted toString，严格在 parser 层校验非空、<=8192 字符、无 CRLF/NUL、不 trim）。
3. **订单解析阶段区分**：`parseOrder` 接口增加 `HuashengEndpoint stage` 参数，限定 `stage ∈ {PLACE, CONFIRM, QUERY}`，非法值抛出安全 IAE。PLACE/CONFIRM 阶段允许 `refundStatus` 与 `isTake` 缺省（返回 `Optional.empty()`），而 QUERY 阶段必须强制两维度存在且非空（缺任一均归为 `DATA_MISSING`），彻底消除 §12.2 最小夹具与全量解析规则的冲突。
4. **业务拒绝两层载荷**：由于首片架构测试断言 `HuashengInternalAttempt` 密封子类恰为 `{Parsed, Failed}`，严禁给 carrier 增加第三个分支。定义 sealed interface `HuashengParseFact<T>` permits `Business<T>(HuashengBusinessError error)` 和 `Data<T>(T data)`。业务码非 0 的响应解析为 `InternalAttempt.Parsed(HuashengParseFact.Business(HuashengBusinessError))`，表示成功读取并理解了供应商 envelope 业务事实，不伪造系统 Throwable cause。

### 15.2 失败分类与错误码冻结（§7.3、ADJ-A2-07 ~ ADJ-A2-12）

1. **失败 Kind 与 Reason 追加**：在已交付的 `MONEY_INVALID` 基础上追加 7 个常量：`ENVELOPE_INVALID`、`DATA_MISSING`、`IDENTITY_MISMATCH`、`QUANTITY_MISMATCH`、`STATE_CONFLICT`、`CARD_PAYLOAD_INVALID`、`RESPONSE_TOO_LARGE`。`HuashengFailureReasonCode` 1:1 同名追加对应 7 个常量。`causeKind` 六值保持不变。
2. **Projector 映射修复**：`HuashengDiagnosticProjector` 的映射由硬编码改动为 `reason = HuashengFailureReasonCode.valueOf(kind.name())`，保持 1:1 映射，不影响既有首片旧断言。
3. **parser 失败证据恒空**：所有 A2 产生的 parser 失败以及 `HuashengBusinessError` 中的证据均固定为 `Optional.empty()`，不伪造任何网络交换或发送事实；A5 通信层后续填充真实 exchange evidence。
4. **错误码枚举（43 常量 = 42 已知 + UNKNOWN）**：`HuashengErrorCode` 严格按设计 §7.3 逐项枚举 42 个已知码（包含 `SUCCESS(0)`、`SERVER_ERROR(50)`、`PARAM_VALIDATION(51)`、`TOKEN_UNAUTHORIZED(61)`、`SECURITY_VALIDATION(66)`、`TOKEN_FAILURE(4001)`、`DEALER_DISABLED(4002)`、`AGENT_NOT_FOUND(4003)`、`ORDER_DEALER_DISABLED(7003)`、7001–7013、7014–7025、7101、7201–7206、7301/7302）。4002 与 7003 同名“经销商禁用”区分为 `DEALER_DISABLED` 与 `ORDER_DEALER_DISABLED`。目录码 5001–5005 与 6001–6005 以及所有未来未知码统一定位为 `UNKNOWN`，`rawCode` 严格保真。parser 永不产出 `Business(0, ...)`。
5. **状态矛盾矩阵（56 组）**：对齐计划与设计推导：48 组全组合（4 状态 × 4 退款 × 3 提卡，其中 24 组矛盾归入 `STATE_CONFLICT`，24 组合法通过）+ 8 组规范显式最小矛盾 = 56 组；另 11 例未知状态/退款/提卡枚举值全部归入 `STATE_CONFLICT`。

### 15.3 严格 Jackson 约束与模型规范（§5.1、§6）

1. **专用 ObjectMapper**：私有静态，配置 `StreamReadConstraints.builder().maxStringLength(16384).maxNestingDepth(32).build()`，开启 `STRICT_DUPLICATE_DETECTION`，禁用所有数据类型自动互转（coercion）。解析前预检 UTF-8 字节数，超 1MiB 拒绝为 `RESPONSE_TOO_LARGE`；`readTree` 后必须检查 `parser.nextToken() == null`，尾随字符拒绝为 `ENVELOPE_INVALID`。
2. **整数 Token 严格判别**：整数字段（code/type/num/expires_in/id 等）必须使用 `node instanceof IntNode || node instanceof LongNode` 校验，严禁使用 `node.isIntegralNumber()`（避免误接受 `DoubleNode(0.0)`）。
3. **卡片模型与字段三态**：
   - `HuashengField<T>`：sealed interface permits `Absent<T>`, `Null<T>`, `Value<T>`。提供 `absent()`, `ofNull()`, `ofValue(T nonNull)`。
   - `HuashengCardPayload`：sealed interface permits 7 种具体卡型 `Card201` ~ `Card304`。
   - `HuashengProtocolCard`：包含字段三态及类型，持有 `HuashengCardPayload payload()`。
   - 未知非空卡片载荷属性拒绝为 `CARD_PAYLOAD_INVALID`，空/null/空白字符串忽略；TAKE/订单 data 的额外未知属性忽略不报错。所有卡片与响应模型 `toString()` 严格脱敏，类级标注 `@JsonSerialize(using = HuashengInternalOnlySerializer.class)` 拒绝意外序列化。

### 15.4 首片保护文件重开与最小写白名单（BLK-A2-02）

**获准修改的 3 个首片文件（仅最小 diff）：**
1. `supplieradapter/huasheng/failure/HuashengFailureDiagnostics.java`：仅两个嵌套枚举追加 7 个对应常量，其余字节不变。
2. `supplieradapter/huasheng/failure/HuashengDiagnosticProjector.java`：仅第 35 行将硬编码 `reason = HuashengFailureReasonCode.MONEY_INVALID` 改为 `reason = HuashengFailureReasonCode.valueOf(kind.name())`，并同步方法 Javadoc，其余字节不变。
3. `architecture/HuashengProtocolArchitectureTest.java`：OWNERS 映射追加 A2 响应与卡片值类型，负控制测试合并 1 个代表性调用，构造检查追加 A2 构造，其余既有断言完全不变。

**A2 新增文件清单（15 main + 2 test）：**
- main：`protocol` 包下的 `HuashengErrorCode.java`、`HuashengOrderStatus.java`、`HuashengRefundStatus.java`、`HuashengTakeStatus.java`、`HuashengCardType.java`、`HuashengField.java`、`HuashengCardPayload.java`、`HuashengProtocolCard.java`、`HuashengOrderResponse.java`、`HuashengTakeResponse.java`、`HuashengTokenResponse.java`、`HuashengBusinessError.java`、`HuashengParseFact.java`、`HuashengExpectedOrder.java`、`HuashengResponseParser.java`。
- test：`supplieradapter/huasheng/protocol/HuashengResponseParserTest.java`、`supplieradapter/huasheng/protocol/HuashengProtocolCardTest.java`。

**严禁触碰**：`HuashengCrypto`、QNM 全类、现有架构主测试、`pom.xml`、资源文件、DDL、Spring 自动注册、HTTP/token 实际发送代码、Timer/Outbox/资金/DAL。

### 15.5 门禁与非阻断实施义务（N-A2-01 ~ N-A2-15）

1. **执行门禁（计划未运行）**：
   - 定向 13 类：包含首片 11 类 + `HuashengResponseParserTest` + `HuashengProtocolCardTest`。
   - 全供应链纯单测与架构测试。
   - QNM Failsafe 定向契约测试（`QnmSupplierAdapterContractTest`）。
2. **关键实施义务**：Q-QUAL-02 标准中文契约注释覆盖所有新增公开方法；整数判别用 `IntNode/LongNode`；parser 失败证据恒 `Optional.empty()`；7201 在 A2 必须解析通过；重开三文件后必须重新验证首片旧断言全部通过。

### 15.6 A2 片有限最终验收（2026-10-07，父验收完成）

A2 片（严格响应解析与订单/卡片事实）已完成全部规范实现、7 轮垂直 TDD（同测试字节）、三门禁全绿（13 类 198 项、197 类 1,903 项、198 类 1,915 项全部 0 失败/错误/跳过，双 exit 0），以及全链独立双审：
1. **独立规格复审（增量闭环确认）** `deleg_e4706a4c` passed=true：BLK-SPEC-A2-01 完全闭合，15 个 main 类全部 98 项公开方法/构造及包私有入口補齐了符合 AGENTS.md §4 的详细中文契约 Javadoc，10 项指标全量达成。
2. **独立质量与安全审查** `deleg_e4706a4c` passed=true：security_concerns 与 logic_errors 为空，脱敏安全、专用 Jackson 防护（1MiB预检/双深度字符串限制/STRICT_DUPLICATE_DETECTION/nextToken==null）、严格整数 Token 判别、56 组状态矛盾矩阵、43 错误码保真、密封卡片模型及三态字段、类级拒绝序列化完全达标。
3. **父最终核验**：20 个候选文件当前工作区哈希与清单 100% 吻合，HEAD 2b61ba41 未变，暂存区 index 为空，三门禁原 XML 聚合 0 错误，两份复审报告哈希一致，父最终验收成立（见仓外 `parent-a2-final-acceptance.json`）。
**范围界限**：本片仅验收 A2 响应与卡片解析纯逻辑，不关闭完整 Task3-A，不解除 R3-04 暂停或目录商业前置限制，未做 Git commit/push，无真实网络/供应商访问。

## 16. 请求构造与独立 JCA（A3 片）技术接口附表（2026-10-07 预审通过）

本节依据 A3 片独立只读设计针对性预审 `deleg_61e04967`（报告归档于 `business-closure-20261007/prereview-a3/`）的裁决与冻结契约制定。预审结论通过（passed=true，0 阻断，0 需重开文件）。本附表为 A3 片实施的唯一规范性依据。

### 16.1 架构分层解耦与 Wire 请求归属（AD-A3-01 ~ AD-A3-02）

1. **`HuashengWireRequest` 包归属裁决**：明确归属于 `com.aifuli.module.supplychain.supplieradapter.huasheng.protocol` 包，解决原草案置于 `http` 包导致的 `protocolForbidden` 架构违例。`WireRequest` 作为纯不可变线缆载荷值对象（目标 URL、不可变 Headers、小写十六进制 AES 密文字符串、超时与链路标识），零 OkHttp/网络依赖。A4 传输层从 `protocol` 消费 `WireRequest` 属于正向六边形依赖。
2. **`HuashengCallContext` 凭据表达**：直接复用 `com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket.CredentialMaterial`（`appSecret`, `privateKeyPem`）。该类位于 `domain` 层，不在 ArchUnit `protocolForbidden` 黑名单内。
3. **上下文不可变与安全约束**：
   - 构造入参：`supplierCode, connectorVersionId, baseUrl, appId, credentialMaterial, connectTimeoutMs, readTimeoutMs, operationNo, traceId`；
   - `appId` 必须严格为 16 字节 UTF-8；
   - `connectTimeoutMs` 与 `readTimeoutMs` 严格为正数；
   - `toString()` 严格脱敏，仅输出 `operationNo` 与 `connectorVersionId`；
   - 类级标注 `@JsonSerialize(using = HuashengInternalOnlySerializer.class)` 拒绝意外序列化。

### 16.2 五封闭请求模型与严格规范化校验（AD-A3-03）

1. **顶层密封请求**：`public sealed interface HuashengRequest permits HuashengTokenRequest, HuashengPlaceRequest, HuashengConfirmRequest, HuashengQueryRequest, HuashengTakeRequest`。
2. **`HuashengTokenRequest`**：仅 `app_id`、`app_secret`。校验 `appId` 16 字节 UTF-8，`appSecret` 16/24/32 字节 UTF-8。
3. **`HuashengPlaceRequest`**：
   - `num` 严格恒为 1，非 1 抛出 `IllegalArgumentException`；
   - `couponId` 严格为正整数 long (`> 0`)；
   - `expireTime` 缺省规范化为 300；显式传值严格在 `[60, 1800]`，越界立即抛出 `IllegalArgumentException`，**严禁静默钳制 (clamp)**；
   - `notifyUrl` 必须为绝对 HTTP/HTTPS 地址、无 userinfo/fragment、非私网/本地字面量、长度 <= 2048 字节；
   - `outMobile` 可选字符串，存在时 <= 64 字节，`toString()` 与日志严格脱敏，缺省时在 JSON 中省略。
4. **`HuashengConfirmRequest` / `HuashengTakeRequest`**：仅 `orderSn`（非空非空白，无首尾空格/控制字符，<= 128 字节）。
5. **`HuashengQueryRequest`**：双静态入口 `byOrderSn(orderSn)` 与 `byOutOrderSn(outOrderSn)`，序列化严格只输出对应的一个单号字段。

### 16.3 单次物理发送独立生成与签名规则（AD-A3-04）

1. **构造方法签名**：
   ```java
   public HuashengWireRequest build(HuashengEndpoint endpoint, HuashengRequest request, HuashengCallContext context, Optional<String> optionalToken);
   ```
2. **时钟与 Nonce 注入**：构造器支持注入 `Clock` 与 `Supplier<String> nonceSupplier`（默认生成 32 位无连字符 UUID nonce，秒级时间戳）。每次物理发送独立调用生成全新报文。
3. **加密与签名**：
   - 请求明文顶层拼装业务字段、`timestamp`（当前 epoch second）、`nonce`（32 字符 UUID）；
   - 密文使用 AES/CBC/PKCS5Padding，以 `appSecret` 为密钥、`appId` 为 IV，生成小写 hex 字符串；
   - 签名原文严格是该次小写 hex 密文字符串的 UTF-8 字节，使用 `HuashengCrypto.signBodyHex(cipherHex, privateKey)` 计算 SHA512withRSA 签名；
   - TOKEN 请求头严禁携带 `Authorization`；业务端点必须携带 `Authorization: Bearer <token>`；
   - 请求头包含 `Content-Type: text/plain; charset=utf-8`、`X-AppId: <appId>`、`X-Sign: <signHex>`。

### 16.4 独立 JCA 契约测试规范与最小写白名单（AD-A3-05）

1. **双盲独立 JCA 测试**：测试服务端解密与验签逻辑直接调用 Java 原生 JCA（`Cipher.getInstance("AES/CBC/PKCS5Padding")`、`Signature.getInstance("SHA512withRSA")`），绝不调用 `HuashengCrypto` 辅助方法生成预期。
2. **最小写白名单（9 新 main + 1 新 test，0 重开文件）**：
   - 新增 main（全部位于 `protocol` 包）：
     * `HuashengCallContext.java`
     * `HuashengRequest.java`
     * `HuashengTokenRequest.java`
     * `HuashengPlaceRequest.java`
     * `HuashengConfirmRequest.java`
     * `HuashengQueryRequest.java`
     * `HuashengTakeRequest.java`
     * `HuashengWireRequest.java`
     * `HuashengRequestBuilder.java`
   - 新增 test（位于 `protocol` 包）：
     * `HuashengRequestBuilderTest.java`
   - 重开修改文件：0 个。
3. **门禁与安全红线**：
   - 门禁：定向 14 类纯单测（包含首片 11 类 + A2 2 类 + `HuashengRequestBuilderTest`）、全供应链纯单测与架构测试、QNM Failsafe 契约测试；
   - 严禁触碰任何网络发送代码、Spring 自动注册、Crypto、QNM、Timer/Outbox、资金、DAL 文件；
   - 状态保持 NOT_RUN，不解除 R3-04 暂停或目录商业前置限制。

### 16.5 A3 片有限最终验收（2026-10-07，父验收完成）

A3 片（请求构造与独立 JCA）已完成全部规范实现、4 轮垂直 TDD（同测试字节 RED->GREEN）、三门禁全绿（14 类 207 项、198 类 1,912 项、199 类 1,924 项全部 0 失败/错误/跳过，双 exit 0），以及全链独立双审：
1. **独立实现规格审查** `deleg_fd1989ad` review_status=PASSED：10 个目标文件（9 新 main + 1 新 test，0 重开文件）逐条比对 §16 及关联章节全部合规，HuashengWireRequest 归属 protocol 包彻底解决 ArchUnit 分层冲突，单次发送生成全新时间戳与 32 字符 UUID nonce，五封闭请求校验守卫严密，中文契约注释完整覆盖。
2. **独立质量与安全审查** `deleg_fd1989ad` passed=true：security_concerns 与 logic_errors 为空，密码学防篡改负控制全部实证生效，测试端服务端解密验签严格使用 Java 原生 JCA（Cipher 与 Signature），绝不调用 HuashengCrypto 生成预期伪造通过；敏感数据（appSecret, outMobile, bodyHex, headers）严格脱敏，类级标注 `@JsonSerialize(using = HuashengInternalOnlySerializer.class)` 拒绝序列化。
3. **父最终核验**：10 个候选文件当前工作区哈希与清单 100% 吻合，HEAD 2b61ba41 未变，暂存区 index 为空，三门禁原 XML 聚合 0 错误，两份复审报告哈希一致，父最终验收成立（见仓外 `parent-a3-final-acceptance.json`）。
**范围界限**：本片仅验收 A3 请求构造与独立 JCA 纯逻辑，不关闭完整 Task3-A，不解除 R3-04 暂停或目录商业前置限制，未做 Git commit/push，无真实网络/供应商访问。

## 17. A4 真实本地 HTTPS 传输技术附表（最小方案已确认，待针对性复审）

### 17.1 授权、保护基线与唯一文件布局

用户于本轮明确回复 `agree`，确认父呈报的最小方案、一个前片枚举文件重开及设计补正送复审。原预审 `deleg_13d5496a` 的 BLOCKING 报告保留，报告 SHA-256 为 `d17ebfe642b72ace06c1eebb73fb8c4841613d5d4658cf5290b8c2a329b4a902`。完整原 113 条契约、54 条 A4 审查矩阵与 50 项测试场景保留在仓外 `business-closure-20261007/prereview-a4/report.json`；50 场景中 48 属于 A4，2 属于 A5 后片。此附表与原完整条款共同受审，不以缩略清单替代原规范。

当前仅放行文档补正和独立只读针对性复审；所有 A4 行为/运行门禁、实现双审、父最终验收仍 NOT_RUN，未直接放行 Java 编码。接口候选已由用户确认方向，但可实施性仍须独立复审。四路依赖分离：原 §7/§9/§12 强制契约保持；新增纯传输输入/计量及装配按本附表定案；受控 DNS/出口、真实供应商验证仍属部署前置；A4 可独立本地验收，不解除目录 B0/B1、R3-04 暂停或商业启用。

相对路径根为 server 模块的 `src/main/java/com/aifuli/module/supplychain/supplieradapter/huasheng/`：
- 新 main 共 5 个：`protocol/HuashengTransportContext.java`；`http/HuashengHttpTransportPort.java`、`http/HuashengHttpExecutor.java`、`http/HuashengHttpExchange.java`、`http/HuashengHttpSafeLog.java`。
- 新 test 共 3 个（对应 `src/test/java/.../supplieradapter/huasheng/`）：`http/HuashengHttpContractTest.java`、`http/HuashengHttpBoundaryArchitectureTest.java`、`failure/HuashengTransportCauseContractTest.java`。TLS/JCA/反射桥与负控制 helper 只可作为这些文件的嵌套类，不新增 main 公开测试通道。
- 唯一重开 `failure/HuashengFailureDiagnostics.java`：仅 `HuashengFailureKind` 与 `HuashengFailureReasonCode` 两个枚举末尾各追加 `PREFLIGHT, HTTP_ERROR, TRANSPORT_FAILURE`。旧 8 项名称/顺序/ordinal 不变，CauseKind 六值、四字段、构造、serializer 和其它方法原字节固定。
- `HuashengDiagnosticProjector` 现有 `valueOf(kind.name())` 映射不改，旧映射恒定且新增三项两枚举同名；不改变顶层原因分类或遍历原原因链。该兼容性目前仅静态证明，必须重跑旧断言。

A3 WireRequest/Builder/CallContext、A2 parser、ExchangeEvidence、ProtectedFailure/InternalAttempt、旧架构及脱敏测试、Crypto/QNM 全部保持字节不变。pom/BOM、资源、DDL/SQL、Spring/适配器注册、Timer/Outbox/资金/DAL 均不在白名单。新增所有公开与关键方法、构造器、日志路径须有详细中文契约 Javadoc（职责、不变量、关键决策、设计路径章节链接、适用的参数/返回/异常说明）。私有技术 helper 可由实施决定，不改变本附表签名或接受边界。

### 17.2 纯协议传输上下文与双参端口

以下类型所属包明确固定；protocol 不依赖 Spring/OkHttp/http/应用预算类型。新上下文不持有原 CallContext、凭据材料、私钥或 token，仅正向复制非秘密冻结字段；不新增任意 URL 发送入口。

```java
// protocol；final，类级 @JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengTransportContext {
    private HuashengTransportContext(String supplierCode, long connectorVersionId,
            String frozenBaseUrl, String appId, int connectTimeoutMs, int readTimeoutMs,
            String operationNo, String traceId, long deadlineNanos, int transportAttemptOrdinal);
    public static HuashengTransportContext from(HuashengCallContext frozenContext,
            long deadlineNanos, int transportAttemptOrdinal);
    public String supplierCode();
    public long connectorVersionId();
    public String frozenBaseUrl();
    public String appId();
    public int connectTimeoutMs();
    public int readTimeoutMs();
    public String operationNo();
    public String traceId();
    public long deadlineNanos();
    public int transportAttemptOrdinal();
    public String toString(); // 固定 HuashengTransportContext[redacted]
}
// http；无单参重载、无 Map/String URL 发送旁路
public interface HuashengHttpTransportPort {
    HuashengInternalAttempt<HuashengHttpExchange> exchange(
            HuashengWireRequest request, HuashengTransportContext transportContext);
}
```

`from` 拒绝 null 原上下文，使用固定安全说明；只采用原上下文字段，不从最终 URL 猜 base。尝试序号为 1..4（最多两 TOKEN 加两业务的统一日志序号），越界固定安全 IAE；不增加发送预算或授权重试。deadline 为同一 JVM `System.nanoTime()` 时间域的共享绝对截止点，不是 epoch 时间。支持范围限于有符号差能表达的半环内未来间隔；调用方只构造一次截止，TOKEN/业务/刷新/第二业务沿用，不把每次剩余时长当新预算。已耗尽截止由 exchange 按 PREFLIGHT 拒绝，不用正整数绝对时间判定（nanoTime 本身可为负）。

每次 exchange 先调用真实 `SupplierEndpointPolicy.validate(frozenBaseUrl)`，再 `validate(request.url())`，在创建 Call 前完成。随后核 origin 含有效端口、前缀与 endpoint 唯一路径：最终 URL 必须精确对应 A3 的 base 去尾斜杠后加封闭路径。核 X-AppId、独立超时、operationNo/traceId 与上下文绑定；不解密/重签/替换正文。头集合限 A3 的 Content-Type/X-AppId/X-Sign，业务额外 Bearer，TOKEN 无 Authorization，不接受 Host/Cookie/Expect 等任意头扩展。若 A3 合法输入因 OkHttp 规范化而改变物理路径，保全反例停报，不能暗改 A3 接受集合。allowlist 不被宣称为 DNS 防重绑定完整保护。
关于路径规范化：依据用户明确决策（2026-10-07），A4 允许 OkHttp 按照标准 HTTP 客户端 RFC 规则对其解析构建（即 `HttpUrl.get(request.url())` 的默认规范化行为，如点段 `.`、`..` 及部分编码形式的规范化），不为此在 newCall 前增加人为的 rawPath 比对拒绝逻辑，亦不修改 A3 既有模型与校验。普通业务前缀路径（如 `/prefix/api/token`）原样保真发送。最终请求 URL 仍须通过 `SupplierEndpointPolicy.validate` 校验。allowlist 不被宣称为 DNS 防重绑定完整保护。

### 17.3 Executor、私有 TLS 装配与 deadline

```java
// http；final，无 Component/Bean/生产注册
public final class HuashengHttpExecutor implements HuashengHttpTransportPort {
    public HuashengHttpExecutor(SupplierEndpointPolicy endpointPolicy);
    private HuashengHttpExecutor(SupplierEndpointPolicy endpointPolicy, okhttp3.Dns fixtureDns,
            javax.net.ssl.SSLSocketFactory fixtureSocketFactory,
            javax.net.ssl.X509TrustManager fixtureTrustManager);
    @Override public HuashengInternalAttempt<HuashengHttpExchange> exchange(
            HuashengWireRequest request, HuashengTransportContext transportContext);
}
```

采用用户确认的 **private** 窄测试构造，不采用原候选 package-private 构造，不新增跨包公开 factory。public 构造直接使用生产默认系统信任、默认 hostnameVerifier/DNS，无测试信任参数；不得调用 private 测试构造。两个路径均要求非 null、精确 `SupplierEndpointPolicy.class` 实例，拒绝覆写 validate 的子类旁路，不改原 policy，不豁免 Spring 代理（本片无 Spring 装配）。测试仅在 src/test 嵌套桥经同模块 `MethodHandles.privateLookupIn` 调用本类 private 构造；禁止反射 OkHttp 私有实现、add-opens、public 任意 OkHttpClient/Call.Factory/HostnameVerifier 注入、trust-all/policy bypass。

executor 内部创建无外部拦截器的独立 root client，各次 newBuilder 共用本 executor 的连接池/dispatcher，不改其它池或全局配置。显式 NONE authenticator、NONE proxyAuthenticator、NO_COOKIES、cache=null，不安装任意 EventListener、token 或日志拦截器。测试装配使用专用 DNS/单证书信任，保留默认 hostname 验证，显式 Proxy.NO_PROXY 仅此私有测试路径；public 路径保持默认代理选择器并禁止代理认证。CONNECT 不是供应商业务发送，不向代理 CONNECT 请求泄露 Bearer/签名等业务头。不得为补绿关闭 HTTP/2、降低 TLS 或改成每请求新池。

deadline 与毫秒超时义务：
1. duration 用单调差；`remainingNanos=deadlineNanos-System.nanoTime()`，差非正或向下换算 `remainingMs<=0` 则拒绝，不以 0 设置无限、不向上取整或钳制成 1。
2. `fullCallMs=Math.addExact((long)connectTimeoutMs, Math.multiplyExact(2L,(long)readTimeoutMs))`；`callMs=Math.min(fullCallMs,remainingMs)`；connect/read 正 int 沿用 A3，write=read。捕获真实本地校验/算术异常为 PREFLIGHT。
3. OkHttp 4.12 Builder 的 callTimeout 内部限制 int 毫秒；本方案明确使用公共 `Call.timeout().timeout(callMs, MILLISECONDS)` 及 `deadlineNanoTime(deadlineNanos)` 设置精确 long，不静默 int clamp，不访问私有字段。newCall 前与创建后 execute 前复核剩余；未执行的 Call 截止耗尽则 cancel，计数保持零。
4. 紧邻唯一一次 execute 前冻结 started/count/计划正文长度。execute 后 deadline/读流/上传/握手失败归 TRANSPORT_FAILURE，不改回 PREFLIGHT；读取及返回前复核截止，不将超时结果转成功。CallTimeout 不能强制抢占系统 DNS/JVM 任意阻塞代码，此为有限网络预算，不升级为所有代码绝对硬截止。
5. 入口已中断则不清 flag、零网络 PREFLIGHT。实际执行/读取观察真实中断或现有 flag 时 cancel 并 finally 保留/恢复 flag。不得见 InterruptedIOException 就自动 interrupt：SocketTimeoutException 按旧 projector 为 TIMEOUT，普通 InterruptedIOException 为 IO，InterruptedException 为 OTHER；不读 message、原因链重分类或擅改 Projector。

### 17.4 唯一物理发送与实际 follow-up 防护

OkHttp 4.12.0 的 503 Retry-After: 0 不受 retryOnConnectionFailure=false 完整约束，421 合并 HTTP/2 连接也有 follow-up 分支；三个开关不能替代机制验证。组合必须为：
- followRedirects/followSslRedirects/retryOnConnectionFailure 全 false，固定 NONE 认证/代理认证。
- 每次新建非 duplex、`isOneShot=true` 的固定 RequestBody；第一次 writeTo 写 exact bodyHex UTF-8，第二次调用固定 ProtocolException 拒绝，临时数组使用后擦除。
- Executor 私有嵌套固定 network interceptor 持 per-call 单次发送闩锁，第二次 proceed 前拒绝，状态仅该 call 生命周期，不无限缓存 Call 引用；无其它可注入应用/网络拦截器。
- 固定观察器只保留第一次响应的安全 numeric status，不保存 headers/body。503 超大数字 Retry-After 可能触发真实 NumberFormatException；若已观察非 2xx 则保持 HTTP_ERROR 与该 status 并 capture 同一原异常，未知 status 的运行期传输异常才归 TRANSPORT_FAILURE。不读异常文本，不删除 Retry-After 偷改响应取舍。

分别验证 oneShot、writeTo 二次守卫、发送闩锁与认证/拦截器封闭的结构和行为负控制；多层冗余保护下单独删一层未必形成外部双发，需专属可达负例验证该层，不捏造 RED。503 absent/0/1/date/超大数字、408/429、401/407、500/502/504、复用连接故障及 HTTP/2 合并连接 421 均须覆盖。421 用两域 SAN、真实 HTTP/2 预热连接与公开可观测连接/服务器请求证据确证 coalescing，不用 HTTP/1 返回 421 冒充。无法确证则记 NOT_RUN/阻断，不靠关闭 HTTP/2 补绿。只允许测试第三方对照或获授权仓外破坏变体，不改变原仓或生产池策略。

### 17.5 有限响应、严格 UTF-8 桥接与生命周期

```java
// http；final，非 record/Serializable，类级 InternalOnlySerializer
public final class HuashengHttpExchange implements AutoCloseable {
    HuashengHttpExchange(byte[] responseBytes, HuashengExchangeEvidence evidence);
    public HuashengExchangeEvidence evidence();
    public HuashengInternalAttempt<HuashengParseFact<HuashengTokenResponse>> parseToken();
    public HuashengInternalAttempt<HuashengParseFact<HuashengOrderResponse>> parseOrder(
            HuashengExpectedOrder expectedOrder, HuashengEndpoint stage);
    public HuashengInternalAttempt<HuashengParseFact<HuashengTakeResponse>> parseTake(
            HuashengExpectedOrder expectedOrder);
    @Override public void close();
    @Override public String toString(); // 固定 HuashengHttpExchange[redacted]
    byte[] responseBytesForInternalInspection(); // 同包防御副本，非公开 raw getter
}
```

包构造唯一生产 caller=Executor，有参调用及构造器引用由新增 ArchUnit 规则与负控制封闭，不把包可见称语言级唯一 caller。防御复制原字节。仅 2xx、非空且完整有限响应产 Parsed<HttpExchange>，不代表业务成功。3xx/4xx/5xx 不读正文、不交 parser；空 2xx（包括 204/205）为 ENVELOPE_INVALID；防御 null body 以实际固定 ProtocolException 归 TRANSPORT_FAILURE，mock null 不能替代真实零字节 socket 测试。

response/body try-with-resources；不调用 body.string()/bytes()/无界 readAllBytes。每次 read 的长度不超过探测剩余额度，应用最多收到 `1048577` 解码字节；成功正文最多 `1048576` 字节。发现第 1MiB+1 即 cancel-before-close、丢弃及擦除所有部分正文、RESPONSE_TOO_LARGE，不把 prefix 交 parser。失败保留真实已读字节计量。非 2xx 不保存正文/错误报文，宜 cancel-before-close 避免无关 drain。保留透明 gzip，预算和 responseBytes 均按解码后应用交付量计；不承诺底层 Okio 预取、TLS/socket 或压缩 wire 总读取的同一硬上限。测试 gzip 膨胀及巨大 chunked 流。

parse 在 http 正向桥接，使用 UTF-8 CharsetDecoder 的 REPORT（malformed/unmappable），禁止替换坏编码后成功；坏 UTF-8 capture 原 CharacterCodingException 为 ENVELOPE_INVALID，附 exchange 的真实 evidence。之后调用 A2 实际静态 String parser，不改其接口或恒空失败 evidence，parser Failed 直接透传原 ProtectedFailure，不反取/复制原 cause；A5 另持 exchange evidence 聚合。parse 方法与 evidence.endpoint 绑定：TOKEN 只 parseToken，TAKE 只 parseTake，parseOrder 的 stage 必须等于实际 PLACE/CONFIRM/QUERY；类型/阶段不匹配固定 PREFLIGHT，不发生新发送或篡改 A2 接受集合。

parse 至多一次，所有 parse 路径 finally close 擦除 owned 数组；close 幂等，重复或关闭后 parse 返回固定安全 IAE 的 PREFLIGHT，可附原 evidence，不伪装新的未发送事实。并发 parse/close 必须有确定同步边界，不允许擦除中读取或重复解析；实现以同步状态保护。局部 read buffer/正文临时数组成功失败均擦除；仅 caller 可访问的 defensive copy 由其负责销毁。Java String、第三方内部缓冲不保证物理清零，不宣称堆秘密全部消失。Exchange/Context 及内部 carrier 普通/ANY Jackson 序列化在写字段前固定拒绝。

### 17.6 安全失败、计量与逐阶段矩阵

失败统一 `HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(kind, actualCause, optionalSafeEvidence))`；保存原 Throwable 同一引用及完整因果，不打印原 message/toString/stack，不 capture Error/OOME。实际本地校验产生固定安全 IAE/NPE/ProtocolException，不伪造底层 IO 异常。无可知 wire endpoint 时 Optional.empty；可知时即便 PREFLIGHT 也有真实零发送证据。

计量定案（ExchangeEvidence 八字段/构造/serializer 不变）：
- requestBytes：execute 前 0；紧邻 execute 开始前变为该次 **计划编码正文长度**（bodyHex UTF-8 字节，非空），即使 DNS/TLS 失败保持；不是实际写出/到达供应商字节数。
- responseBytes：实际交给应用读取路径的解码字节；最多 1MiB+1，HTTP_ERROR 不读正文为 0。durationMs 为整个 exchange 单调耗时的非负整数毫秒。rawBusinessCode 在传输层恒空。
- 业务 execute 前 physicalSendCount=0、wasBusinessCallStarted=false；开始后 1/true，保守计尝试，不证明供应商未执行。TOKEN 这两字段始终 0/false，TOKEN execute 由 endpoint=TOKEN 且 requestBytes>0 区分；A5 每条 tokenAttemptCount 采用该明确规则聚合，不覆盖先前业务事实。序号不是预算，A4 不负责 61 刷新或计数重置。

| 场景 | failureKind / 返回 | 证据与因果 |
|---|---|---|
| null wire | PREFLIGHT | Optional.empty，固定实际校验原因，无 Call |
| policy/base-final 绑定/头/超时/序号拒绝 | PREFLIGHT | 可知 endpoint；0 字节、0/false；保存真实原因，零请求 |
| 入口已中断/截止非正或不足 1ms | PREFLIGHT | 无 execute，保留真实中断 flag，零请求 |
| newCall 前/后截止耗尽 | PREFLIGHT | 创建后的 call cancel，0/false，无 execute |
| TOKEN 的 DNS/TLS/IO/超时 | TRANSPORT_FAILURE | requestBytes>0；业务 0/false；真实 Throwable |
| 业务的 DNS/TLS/连接/IO/总超时 | TRANSPORT_FAILURE | requestBytes>0，1/true；若观察到 status 则保留 |
| read SocketTimeoutException | TRANSPORT_FAILURE | 顶层 projector TIMEOUT，不造中断 flag |
| 其它 InterruptedIOException/真实线程中断 | TRANSPORT_FAILURE | 旧 projector IO；仅实际中断恢复 flag |
| 3xx/4xx/5xx，包括 408/429/503 | HTTP_ERROR | exact status、responseBytes=0；固定实际 HTTP 校验异常 |
| 503 follow-up Retry-After 超大数字异常 | HTTP_ERROR | 观察到 503 时保留；捕获真实 NFE 同引用 |
| 空 2xx/204/205 | ENVELOPE_INVALID | 状态及已执行证据，responseBytes=0 |
| 防御 null body | TRANSPORT_FAILURE | 实际 ProtocolException，不用 mock 冒充 socket 场景 |
| 2xx 中途断连 | TRANSPORT_FAILURE | 已读字节/status/发送事实，原 IO；不 parse prefix |
| 第 1MiB+1 解码字节 | RESPONSE_TOO_LARGE | responseBytes=1048577，cancel+close+擦除 |
| 坏 UTF-8 | ENVELOPE_INVALID | bridge 原 CharacterCodingException+真实 evidence |
| 完整非空有限 2xx | Parsed<HttpExchange> | HTTP 事实，不解密明文响应、不代表业务成功 |
| A2 parser 拒绝 | 原 A2 kind | 原内部失败及 empty evidence 透传；exchange evidence 另保留 |
| Exchange 错阶段/重复 parse/关闭后 parse | PREFLIGHT | 原 exchange evidence 保留，无新网络，不改原发送历史 |

A5 首次业务以后本地拒绝/刷新失败的 LocalFailure 与累计 attempts 保持原规范，只列为后片义务，不借本片 PREFLIGHT 回滚业务已发送事实。Projector 的顶层顺序与原因类型原字节固定，不为更美观的 TIMEOUT 名称扩大重开。

### 17.7 A4 安全日志、TLS 与架构负控制

`http/HuashengHttpSafeLog` 为 final 包私有、非 Spring、无后台线程。精确包内接口：

```java
HuashengHttpSafeLog();
HuashengHttpSafeLog(java.util.function.LongSupplier monotonicTicker); // 包内测试时钟
void recordStart(HuashengTransportContext context, HuashengEndpoint endpoint);
void recordResult(HuashengTransportContext context, HuashengExchangeEvidence evidence);
void recordFailure(HuashengTransportContext context,
        HuashengFailureDiagnostics diagnostics);
```

只接安全投影/evidence/diagnostics，不接 Throwable、任意 logger 或原 WireRequest/headers/body；上下文可空时固定缺失标识，不因日志失败掩盖主要失败。operationNo/traceId 非可信自由文本一律固定掩码，不直接打印或新增秘密 HMAC 持久化；connectorVersionId/endpoint/尝试序号安全输出。TOKEN 不记录 quantity，其余按本期封闭规则 quantity=1，不解密正文获取数量。入口/结果 debug；失败按 endpoint+kind+rawCode 的分钟 bucket 首 warn 和抑制计数，rawCode 本层为空，最多 1024 总键（含固定 overflow 桶），满额不插无限键，惰性清理闲置 2 分钟桶，时间使用单调来源。新分钟首 warn 带上前窗抑制数，闲置退休可丢内存计数不保留永久诊断仓库；并发更新原子化。同一次 HTTP 失败 A4 已 warn，A5 不重复 warn，只补本层新失败/业务码聚合。日志禁止 URL、Wire.toString、原单号、header/token/sign/body/密钥/手机号/卡片/异常文本，禁止 Throwable logger 参数与正文普通 SHA256 摘要。

标准 keytool 在独占仓外测试目录生成短期 PKCS12+SAN，JSSE/JCA KeyStore、KeyManagerFactory、TrustManagerFactory、SSLContext 只信任该证书，MockWebServer 真 socket HTTPS；SAN 包括夹具所需两个域用于 HTTP/2 合并反例。DNS 仅将明确夹具域映射 loopback，不对其它域回退真实网络。无 sun.security.*、add-opens、全局 SSL 替换、trust-all、源码资源或 pom 增量。若证书夹具真实阻断，停报，不默认批准 okhttp-tls 下载/依赖。

新增 HttpBoundaryArchitectureTest 自动核：只有 Executor 及其固定私有嵌套实现执行 OkHttp；无任意客户端注入/单参发送旁路；protocol 无 http/OkHttp/Spring；无 DAL/Timer/Outbox/资金/应用反向依赖；新 Context 构造 private；TLS 测试构造 private、public 路径无测试装配调用、生产代码无调用/引用 private 测试构造；HttpExchange 包构造唯一精确 Executor 所有者；内部类型拒绝序列化。每项有实际结构负控制，构造归属规则核有参调用与构造器引用；测试同模块 privateLookupIn 只用于自己的获准类，不反射第三方实现。旧两套架构规则和所有权清单不改。

### 17.8 完整测试矩阵、门禁与审查完成条件

48 个 A4 测试场景来源为原预审 report 的 `section_12_3_complete_http_test_matrix`，以下 ID 是必须逐项映射到真实断言与受测字节的索引，不以一个汇总用例名代替：
- 协议/地址：HTTPS-TOKEN、HTTPS-PLACE、HTTPS-CONFIRM、HTTPS-QUERY、HTTPS-TAKE、HTTPS-PREFIX、HTTPS-JCA-TAMPER。
- 策略/TLS：HTTPS-POLICY-HOST、HTTPS-POLICY-HTTP、HTTPS-POLICY-PRIVATE、HTTPS-POLICY-TWICE、HTTPS-TLS-UNTRUSTED、HTTPS-TLS-SAN、HTTPS-TLS-HANG。
- 单次发送：HTTPS-REDIRECT302、HTTPS-REDIRECT307、HTTPS-HTTP4XX、HTTPS-HTTP5XX、HTTPS-HTTP408、HTTPS-HTTP429、HTTPS-HTTP503ZERO、HTTPS-HTTP503HUGE、HTTPS-HTTP421、HTTPS-AUTH、HTTPS-STALE。
- 流/编码：HTTPS-DISCONNECT、HTTPS-READ-TIMEOUT、HTTPS-SLOW-FLOW、HTTPS-UPLOAD、HTTPS-NO-BODY、HTTPS-LIMIT-MINUS、HTTPS-LIMIT-EQ、HTTPS-LIMIT-PLUS、HTTPS-GZIP、HTTPS-UTF8。
- 时间/事实：HTTPS-DEADLINE-ENTRY、HTTPS-DEADLINE-NEWCALL、HTTPS-DEADLINE-AFTER、HTTPS-LONG-EXACT、HTTPS-TOKEN-EVIDENCE、HTTPS-BUSINESS-EVIDENCE、HTTPS-INTERRUPT-PRE、HTTPS-INTERRUPT-REAL。
- 安全/资源：HTTPS-LIFECYCLE、HTTPS-SERIALIZE、HTTPS-SAFE-OUTPUT、HTTPS-LOG、HTTPS-ARCH。

本附表额外绑定 parse 阶段、并发 parse/close、private 测试构造、日志 1024 总键与退休规则，也必须有专属正反例，不由历史矩阵遗漏而跳过。两个 A5_DEFERRED（A5-CHAIN、A5-TTL-CACHE）保留完整后片义务，不在 A4 写桩冒充交付。

五端点与两 QUERY 工厂使用真实本地 HTTPS、生产 policy、独立原生 JCA 验签/解密，绝不调用 HuashengCrypto 生成测试端预期；明文响应不误解密。CBC 篡改不保证 padding 异常，确定认证拒绝以独立 RSA 验签负例证明；纠正错误断言且生产字节不变的轮次只记测试修正/回归，不计生产 TDD。真实 TLS/读超时/上传/中断用 socket；同步夹具错误、BeforeAll 失败、工具超时、编译错误不是生产 RED。逐行为 RED→GREEN，测试与生产双侧增量/哈希、原 XML 和双退出码归档；测试所承载规则实现 TDD 与生产 TDD 分开统计。

门禁仅在获实施授权后于仓外独占 `git archive` 固定 HEAD + 精确工作区增量副本运行，JDK21 先核 mvn-version，Maven `-o -B -ntp -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am`。选择集合：
1. 旧定向 14 类：HuashengMoneyParserTest、HuashengSensitiveDiagnosticsTest、HuashengProtocolArchitectureTest、HuashengCryptoTest、SupplyChainModuleArchitectureTest、QnmGameOrderV1MetadataTest、QnmSupplierAdapterTest、QnmSignUtilsTest、QnmCallbackParserTest、QnmProtocolParserTest、QnmMaskingUtilsTest、HuashengResponseParserTest、HuashengProtocolCardTest、HuashengRequestBuilderTest；Surefire `test -DskipTests=false -Dtest=<上述集合> -Dsurefire.failIfNoSpecifiedTests=false`。
2. 新纯测：同命令选择 HuashengHttpBoundaryArchitectureTest；完整纯测/架构另选择 `com.aifuli.module.supplychain.**.*Test,!**/*IntegrationTest,!**/*ContractTest`。
3. 契约使用 `-Psupply-chain-integration verify -DskipTests=false -DskipITs=false`，Surefire 保持上项纯测选择，Failsafe 分别 `-Dit.test=QnmSupplierAdapterContractTest` 与 `-Dit.test=HuashengHttpContractTest,HuashengTransportCauseContractTest`，带 `-Dfailsafe.failIfNoSpecifiedTests=false` 兼容无指定契约的上游模块；profile verify 必须实际运行绑定的 integration-test 与 verify。`-Dtest` 不作 Failsafe 选择器，缺目标新 XML 即失败关闭。

每轮清理仅独占副本各模块旧报告并立即归档；按模块/插件/类/逐方法核原 XML、声明计数、真实 failure/error/skipped、方法集合、候选与受测哈希、runner/Maven 双退出码。不预测测试项数、不混旧 XML、不把纯测累计数称独立契约数。分别报告旧断言回归、新 HTTP 契约、原 cause 契约和新架构；缺真实 HTTP/2 coalescing 或其它强制场景即阻断，不以总数全绿放行。完成后独立实现规格审查、另上下文质量/安全审查与父核，全部通过才有限验收 A4；不关闭 Task3-A 整体或业务闭环。

### 17.9 首轮阻断闭合映射（设计待复审，非已通过）

| 原阻断 | 本次确认后的设计补正 | 当前状态 |
|---|---|---|
| BLK-A4-01 | §17.2 双参端口/正向冻结 Context；§17.5 严格桥接 | 已落设计，待独立复审 |
| BLK-A4-02 | §17.1 唯一枚举文件末尾增量与旧断言保真 | 重开范围已获确认，Java 未改，待复审 |
| BLK-A4-03 | §17.4 oneShot/闩锁/status/认证及实际 421 负控制 | 已落设计，运行 NOT_RUN |
| BLK-A4-04 | §17.3/5/6 单调预算、明确字节计量、UTF-8/生命周期 | 已落设计，运行 NOT_RUN |
| BLK-A4-05 | §17.1/3/7 私有 TLS 装配、安全日志与精确白名单 | 已落设计，待独立复审 |

原 failed 报告与微probe 保留；候选被确认不等于这些阻断已由复审关闭。R3-04 暂停、目录商业前置、不 Git 暂存/提交/推送/部署、不访问真实供应商或真实库、不注册生产适配器的边界继续生效。

### 17.10 第二轮预审阻断记录（2026-10-07 18:25 CST）

独立只读复审 `deleg_fca58c4f` report SHA-256 `d49109b81b702c3552c3d551dbbbbca444f53305603a5668d854aa68f5ae5bd2`，裁决 passed=false。原 BLK-A4-02～05 在设计层闭合，BLK-A4-01 的上下文/共享 deadline/解析桥接部分闭合；静态可实施性因 BLK-A4-REV2-01 未通过。父已核报告/回执/微probe 源码与原结果哈希、HEAD/空暂存区/46 冻结路径字节无漂移；未重跑微probe 或 A4 门禁。

反例：现 A3 与真实 policy 允许 base `https://hs-fixture.example/tenant/..` 和 `/tenant/%2e%2e`；builder 生成的 URI rawPath 分别为 `/tenant/../api/token`、`/tenant/%2e%2e/api/token`，OkHttp 4.12 公共 HttpUrl API 的 encodedPath 都变为 `/api/token`；普通 `/prefix/api/token` 对照保持不变。仓外无网络 javac/java exit 0，仅第三方路径规范化反例，非真实 A4 请求门禁。

最小待决候选：两次真实 policy 与原绑定校验后，在 newCall 前比对最终 URI rawPath 和 HttpUrl.encodedPath，非精确一致则固定安全 PREFLIGHT，requestBytes/physicalSendCount 为 0、wasBusinessCallStarted=false，无 Call/零请求，保留实际校验原因。此规则会新增 A4 传输准入限制，**当前尚未获用户批准，不允许作为普通 helper 静默实施**；不修改 A3 模型或 builder 接受集合。确认后只补 §17.2/17.8 等设计与针对性复审，不增加 Java 重开、文件、pom 或资源范围。

实现义务 N-A4-R2-01～12 已归档于仓外 `prereview-a4-rev2/report.json`；均非运行通过，后续设计放行时须逐条纳入实施计划。包括真实同端口/同池/默认 hostnameVerifier 的 HTTP/2 coalescing、原小载荷上传的真实零窗口背压夹具、冗余保护独立负例、503/407 异常状态/原因与资源、固定有界数组擦除、日志真实有限容量等；不得因非阻断而遗漏。当前继续停止编码，A1–A3 有限验收及全部暂停/商业限制保持。

### 17.11 路径规范化决策定案与第三轮针对性复审准备（2026-10-07 18:35 CST）

用户明确裁决：“不需要增加”额外的路径比对拒绝规则，确认允许 OkHttp 按标准客户端默认行为规范化路径。
- **BLK-A4-REV2-01 闭合**：§17.2 明确采用 OkHttp `HttpUrl` 默认规范化语义，不设人为拦截，不修改 A3，不引入额外 Java 重开文件。
- **全部 6 项阻断闭合状态**：BLK-A4-01（双参 Context/共享 deadline）、BLK-A4-02（FailureDiagnostics 2 枚举追加 3 常量）、BLK-A4-03（单次发送/oneShot/防重发/HTTP2 coalescing）、BLK-A4-04（计划请求字节与解码字节计量/资源擦除）、BLK-A4-05（私有 TLS 装配与安全日志）、BLK-A4-REV2-01（路径规范化标准行为定案）已全量在设计层对齐，无未决技术取舍。
- **复审结果**：第三轮独立只读针对性复审（rev3）`deleg_2d19d5ae` 裁决 passed=true，0 阻断；§17 技术附表静态可实施性完全确立。待用户显式授权后方可进入垂直 TDD 实施。

### 17.12 A4 片有限最终验收（2026-10-07，父验收完成）

A4 片（真实本地 HTTPS 传输）已完成全部规范实现、4 轮垂直 TDD（同测试字节 RED->GREEN）、三门禁全绿（15 类 219 项、199 类 1,924 项、202 类 1,990 项全部 0 失败/错误/跳过，双 exit 0），以及全链独立双审：
1. **独立实现规格审查** `deleg_52747bc8` review_status=PASSED：9 个目标文件（1 修改 main + 5 新 main + 3 新 test）逐条比对 §17 及关联章节全部合规，HuashengFailureDiagnostics 仅两个 enum 末尾各追加 3 常量且旧 8 项完全不变，双参 exchange 与纯协议 TransportContext 成功解耦，单次发送多层冗余机制完备，流式 1MiB+1 截断与擦除严密，中文契约注释完整覆盖。
2. **独立质量与安全审查** `deleg_52747bc8` passed=true：security_concerns 与 logic_errors 为空，private 窄 TLS 构造由 ArchUnit 双向封锁且生产零调用，HTTP/2 coalescing 421 负控制真实生效，isOneShot 与二次 writeTo 守卫生效，503 极大 Retry-After 异常保留状态并捕获原 NFE 同引用，HuashengHttpSafeLog 有界 1024 键与 2 分钟闲置退休生效，脱敏与拒绝序列化过审。
3. **父最终核验**：9 个候选文件当前工作区哈希与清单 100% 吻合，HEAD 2b61ba41 未变，暂存区 index 为空，三门禁原 XML 聚合 0 错误，两份复审报告哈希一致，父最终验收成立（见仓外 `parent-a4-final-acceptance.json`）。
**范围界限**：本片仅验收 A4 真实本地 HTTPS 传输纯逻辑，不关闭完整 Task3-A，不解除 R3-04 暂停或目录商业前置限制，未做 Git commit/push，无真实网络/供应商访问。
