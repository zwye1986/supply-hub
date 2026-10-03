# HUA_SHENG Task3-A：隔离 HTTP、token 与严格协议解析设计

> 状态：独立规格复审通过；未实施，按目录桥接优先级后续执行。日期：2026-10-04。
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

新增 `HuashengHttpTransportPort.exchange(HuashengWireRequest)` 返回 `HuashengInternalAttempt<HuashengHttpExchange>`，其实现 `HuashengHttpExecutor` 是唯一使用 OkHttp 的HUA_SHENG类。wire request 由 §4 builder 生成，含 validated base/path、不可变 headers/body、独立毫秒超时和安全身份，禁止公开 `post(String arbitraryUrl,Map)`。该端口是本切片新增，不能把它写成仓库已有 HttpClientPort。HTTP成功carrier中的原响应字节仅供parser消费，parser.parseToken/parseOrder/parseTake返回 `HuashengInternalAttempt<对应类型响应>`，所有原始失败沿该内部carrier流转；安全证据为纯协议 `HuashengExchangeEvidence`，不依赖应用CallBudget/CallResult，应用可合并累计attempts而无逆向依赖。

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
4. `supplieradapter/huasheng/http/HuashengHttpTransportPort.java`、`HuashengHttpExecutor.java`、`HuashengWireRequest.java`、`HuashengHttpExchange.java`：唯一OkHttp实现、policy、流界限、超时/失败证据。
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
