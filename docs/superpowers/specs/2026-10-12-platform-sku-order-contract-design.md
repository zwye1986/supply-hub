# 对外平台 SKU 下单与历史兼容详细设计

日期：2026-10-12。状态：**修改方案与详细设计草案，待用户评审；未实现、未迁移、未开放 v2。** 用户同意“对外以平台 SKU 定位商品，内部保留商业身份”的调查建议；具体版本、幂等格式和发布切换须以本文件评审结论为准。

本设计为第二独立切片。先行状态修复见 [目录真实来源校验与管理状态纠正](2026-10-12-catalog-source-freshness-correction-design.md)，不以本片接口改造阻断误判修复。

## 1. 业务目标、方案选择与范围

### 1.1 业务目标

分销商／项目提交**中台平台 SKU 编码**，中台从认证项目取得分销商与项目范围，找到已配置商业关系与该 SKU 的一个或多个供应商 Offer，再按本项目路由规则选出一个供应商履约。请求不让项目指定供应商，不接受供应商外部 SKU，也不拿 BPD／PLT／LST 代替平台 SKU。

“只允许 SKU”限定商品定位字段，不是整个请求只传一个 SKU。项目认证、项目订单号、数量和履约目标仍必需。供应商切换是同一等价权益的供给选择；权益、面值、有效期或交付语义不等价的商品不得为凑多供应商而映射到同一平台 SKU。

### 1.2 推荐方案及备选

**采用独立 v2 SKU 契约，v1 与内部 RPC 保留。** 新项目接入默认 v2；旧调用方按版本继续使用原字段，历史记录无需重写。未完成接入方盘点，不能因测试库订单少就认定可以破坏原契约。代价是增加三个明确 HTTP 操作及一个回执增量。

不采用把 SKU 放入 v1 `buyerSkuCode` 并按前缀猜语义：既有别名、跨表碰撞、签名合同和幂等会混淆。不采用直接替换 v1：只有完成真实调用方／SDK／历史记录盘点并协调全体切换才可能成立，本轮证据不足。不合并 BPD／PLT／LST，也不重建路由引擎。

保持 quantity=1、封闭 target、永久项目键、条件 CAS、永久首次报价、当前项目准入、限购／限频、资金与原单供应商执行／恢复。没有多商品订单、拆单或并行向多家供应商履约的新增范围。

## 2. 领域身份与权限

- **SKU**：平台规格／权益身份，对外 v2 商品定位字段为 `platformSkuCode`。
- **BPD**：分销商选品和 L2 定价身份；同 `(buyer_id, platform_sku_id)` 唯一。v2 不要求调用方持有 BPD，但内部保留。
- **PLT**：项目选品及 L3 商业配置身份；同 `(project_id, buyer_product_id)` 唯一。没有本项目选品不能通过别的项目授权。
- **LST**：该分销商／SKU 的受理目录及商业／供给投影；保持唯一和 AUTO／MANUAL 生命周期。
- **Offer**：供应商对平台 SKU 的供给、外部商品映射、连接器与成本快照；不是项目可自行指定的商品身份。

以上唯一约束使“可信 buyerId/projectId + 平台 SKU”可以唯一定位现有关系。SKU 编码公开不等于授权，安全由认证、真实归属及最终准入保证，不能靠编号难猜。

v2 不在下单时自动新增 BPD 或 PLT。已有 BPD 缺 LST 时，保留现有真实受理准备／管理补身份协议，不在纯身份 Resolver 中创建目录，不以项目请求擅自补项目选品。MANUAL LST 可以在真实 BPD、PLT 关系存在时复用，不把它接管成 AUTO，也不改变手工价／暂停／退役行为。

## 3. HTTP 与签名合同

### 3.1 三个明确 v2 操作

- `POST /open-api/supply-chain/project/v2/orders`，operationCode=`STANDARD_ORDER_CREATE_V2`：项目 ACTIVE、STANDARD_PRODUCT 已启用。
- `POST /open-api/supply-chain/project/v2/orders/query`，operationCode=`STANDARD_ORDER_QUERY_V2`：项目 ACTIVE 或 SUSPENDED；标准商品能力曾开通且现为启用／停用均可。
- `POST /open-api/supply-chain/project/v2/orders/assets/query`，operationCode=`STANDARD_ORDER_ASSET_QUERY_V2`：项目 ACTIVE、STANDARD_PRODUCT 已启用。

三个新注册操作的 contractVersion 为 `2.0.0`，scopeType=NONE、requiredCapability=STANDARD_PRODUCT。应用／密钥禁用、吊销、过期和项目 CLOSED／ARCHIVED 仍全部拒绝，从未开通能力仍全部拒绝。创建请求的幂等回放也不能绕过入口授权。

`ProjectAuthOperations` 的 operationCode 唯一，不能复用同名 v1 登记。HMAC 继续绑定**操作码、契约版本及原始 body 字节**；路径本身不在现有签名串中，所以必须用新操作码隔离。旧十个 INSTORE 操作及 v1 操作字节合同不变。nonce、时间戳、限频、凭证轮换沿原协议，新 nonce 不改变永久订单身份。

### 3.2 创建请求闭包

必填只有 `buyerOrderNo`、`platformSkuCode`、`quantity`、`target`；可选 `previousReceiptVersion`：

```json
{
  "buyerOrderNo": "P202610120001",
  "platformSkuCode": "SKU_011_001",
  "quantity": 1,
  "target": {"deliveryMode": "BUYER_ASSET_QUERY"}
}
```

- `buyerOrderNo`：非空字符串、最长128字符，与可信 projectId 组成永久键；数据库既有等价比较规则不变。
- `platformSkuCode`：非空字符串、最长64字符，以供应链 `sc_platform_sku.sku_code` 登记值解析；不做 BPD／PLT／LST 或供应商外部编码回退。不能只检查 `SKU_` 前缀便认为商品存在，也不新增阻断既有平台合法编码的格式规则。
- `quantity`：严格 JSON 整数1；字符串、浮点、空、0、负数及大于1均拒绝。
- `target`：复用平台 SKU 的封闭目标 Schema，卡密、手机号直充、外部账号规则不变；Schema／版本不由调用方声明。
- `previousReceiptVersion`：可选正整数，仅可重试回执的条件 CAS，非请求不变量。

v2 不接受 `buyerSkuCode`、BPD／PLT／LST 字段、supplierCode、source/tenant、buyer/project 身份、价格、用户、paidAt、报价 token 或版本覆盖。未知字段、重复键、尾随 JSON、query string 以及错误类型使用既有安全参数错误，不回显非法正文。不能双字段二选一。

保留请求商品编码原始字符串参与指纹，不转换成 BPD 或根据现行目录重新生成。仅 target 沿既有 Schema 规则规范化；商品字符串／订单号被修改，即使库排序规则命中同一行，仍是不同请求。

### 3.3 响应与查询

创建响应复用原封闭项目 HTTP DTO、回执状态、稳定 requestId／orderNo 和 rejectionCode；ACCEPTED 仅表示承担履约，不表示供应商已成功。两个查询请求仍只有 `buyerOrderNo`，资产 no-store／解码审计不变，不返回 supplier、内部数字 ID 或履约请求号。

**查询不按创建接口版本隔离订单。** 经同一可信项目授权，v1/v2 摘要与资产查询都可查询该项目的 v1/v2 历史订单；共享永久键和权威归属。不能因迁移要求调用方改订单号或搬迁订单。查询兼容不意味着创建请求可跨版本互认同参。

## 4. 解析、准备与最终路由

### 4.1 冻结优先

认证及当前项目／分销商门禁后，先读 `(projectId, buyerOrderNo)` 的回执身份，明确区分无回执、可信格式2、可信格式3和已存在但不可信历史。Facade 通过 border 应用读服务读取，不访问 DAL。

- 已有可信格式3：用回执冻结 SKU／Schema及内部BPD快照，结合**本次请求**的原平台SKU码、数量和规范化target计算格式3比较，不查当前 SKU／BPD／LST。回执原平台请求码可先核对，不能替换本次请求码而掩盖异参。完全同参 ACCEPTED／FINAL／普通可重试拒绝只回放。
- 已有格式2或0：v2 创建返回 IDEMPOTENCY_CONFLICT，不解析当前映射，不重新下单。格式3经 v1／旧 RPC 创建同键请求也冲突。
- 无回执：catalog 应用 Resolver 按平台 SKU 登记码找 SKU，再按可信 buyer+SKU 找现有 BPD 和唯一 LST；返回不可变身份及服务端 Schema。不以当前可售状态过滤掉真实身份，状态在当前准入时裁决。

v2首次 Schema 直接来自已定位的真实平台 SKU，不要求 LST 已发布才取得 Schema。SKU 的 Schema 引用缺失／非法属于数据完整性故障，claim 前失败，不用假 Schema 占位；目标值不合法则保留既有 targetValid=false 的真实拒绝路径，不签发报价。

首次 SKU 不存在、BPD 不存在或不属于当前分销商，使用既有 `SKU_NOT_SALEABLE` 安全业务错误，**此分支在 claim 前失败，未创建回执／报价／订单／资金**；同号未来重发会作为首次尝试。不得为了满足 receipt 的非空采购商编码列填假 BPD。LST 暂无或正常状态未就绪则沿原受理／目录准备的真实拒绝语义，不伪造已发布事实。

身份解析不得先把 SKU 转 BPD 再用格式2计算新指纹。内部 BPD 只用于既有商业／锁定／路由职责，格式3必须保存并比较原 `platformSkuCode`。

### 4.2 共享命令及执行分支

`PreparedAcceptance` 追加受控指纹格式和可空的原平台 SKU 请求编码，原 `buyerSkuCode` 保留既有角色：格式2为原 BPD／别名，格式3为**服务端解析的 BPD 快照**。不把平台 SKU 冒充这个内部字段。旧构造入口明确默认格式2；只有可信 v2 适配器能创建格式3。

`CatalogIdentity.requestedCode` 在格式3内部与已解析 BPD 一致，保持最终内部关联校验；原平台请求码单独传递至指纹／回执。不得在限频协调器重建命令时丢失格式或平台请求码，或把缺字段静默降级为格式2。

claim 插入、已有回执比较、项目键竞争后的新事务重探测、PROCESSING 续跑、CAS 重开、reject、最终 accept 和等待队列恢复均传播相同请求身份。格式3路径不调用旧别名 Resolver 回退；所需身份通过可信快照或冻结回执恢复，不依赖前缀猜测。

格式3首次 claim 的 `platform_sku_id` 来自 Resolver 已验证的真实 SKU，即使尚无 LST 也不能取空；现有“只从 gateListing 取SKU”的写法须按格式分支调整。该绑定和原平台请求码首次固化，后续拒绝／重开不覆盖；格式2的既有绑定规则不变。

无回执的首次准备在 claim 前完成真实 Schema 下的目标规范化／校验／加密。已有冻结回执则 claim 前仅用冻结 Schema 规范化并算指纹，不查现行配置或加密；claim 确认要执行续跑后，才解析当前目录、校验／加密及执行 AUTO 准备。只有合法且需执行的尝试进入当前项目资格与永久报价首冻。当前解析 SKU 必须等于冻结 SKU；不能因映射变化转到另一个权益。状态正常变化沿原稳定拒绝；冻结／当前身份损坏继续系统失败。target AAD、目标摘要用途及密钥不变。

### 4.3 事务、路由与履约

保持首冻 REQUIRES_NEW 短事务和最终受理事务分离；沿用 receipt→LST→SKU→quote→PLT→BPD→BuyerPricing 当前锁顺序及其例外细则。Resolver 普通身份定位在最终事务外，禁止在 LST/SKU 锁前建立 RR 旧视图破坏限购或配额。

最终 HTTP 仍要求真实商业发布／有效期、SKU／SPU 有效、Schema 兼容、当前项目选品及价格准入、AUTO 商业真实来源与当前 READY 定价，以及本项目 `CurrentSupplyEligibilityReader` 的 CONFIRMED。分销商聚合状态及别的项目可用不能授权当前项目。身份存在、SKU 编码正确、有永久报价都不是全部准入通过。

从该 SKU 的已发布 Offer 构造候选，复用 FIXED、AUTO+SPECIFIED、AUTO+ALL 与 STABLE／PRICE／COMPOSITE 的真实策略。FIXED 不自动扩大供给；首选 OPTIONAL UNKNOWN 不为凑可用跳选后备；没有合法候选沿原拒绝，不改变事实检查。

一次受理选中一个供应商，冻结 Offer／connector／成本与路由结果，既有 Outbox／执行器履约。供应商外部 SKU 只由选中 Offer 解析。未知外呼结果沿原保留资金、查单恢复协议，不因平台 SKU 多供给就盲目改投另一个供应商。成功同参回放不重路由、不重首冻、不重复预占或发码。

## 5. 永久幂等与格式3

### 5.1 不改变永久键

v1、v2 与内部标准 RPC 共用 `(projectId, buyerOrderNo)`，不得把版本／操作码加进唯一键。不同项目同号隔离；同项目同号跨契约冲突，而不是第二笔订单。

格式2规范化字节完全不变。格式3沿既有长度分隔 UTF-8／int32大端／null=-1 编码，字段顺序固定：`formatVersion=3`、`buyerId`、`projectCode`、`buyerOrderNo`、`productCodeType=PLATFORM_SKU`、`platformSkuCode`、`frozenPlatformSkuId`、`quantity`、`targetSchemaCode`、`targetSchemaVersion`、`targetDigestHex`。SHA-256 生成 invariant/request 指纹，两者同源。

格式3不包含 BPD／PLT／LST、当前版本、nonce、CAS、凭证版本、供应商、价格或操作时刻。首次请求冻结真实 SKU，后续基于冻结身份；原 SKU 请求码与目标摘要不可变。

在现有 `StandardAcceptanceFingerprintPolicy` 增加独立格式3规范化入口，**不得全局把 `FORMAT_VERSION=2` 改成3**：v1请求、历史重建和 `canonicalBuyerOrderDigestInput` 仍需原格式2字节。总订单 buyerOrderDigest 的项目＋订单业务摘要保持原算法；不是所有摘要一起升版。

### 5.2 状态与竞争

同格式同参：PROCESSING 续跑；ACCEPTED／FINAL 回放；RETRYABLE 普通重发回放，仅当前 positive CAS 才重开。通过§3请求闭包验证后，异SKU／目标或跨格式同键全部 IDEMPOTENCY_CONFLICT。v2的数量不等于1、错误类型和未知字段在闭包解析阶段即参数错误，不为比较已有回执而放宽。格式0不能按格式2／3重建或开启尝试。

并发首创仍由永久键唯一索引裁决，落败方退出旧事务后在新事务重探测获胜回执、格式和指纹。v1／v2 同号竞争只能有一个身份获胜，即使命中同 SKU；获胜方最多一条订单／永久报价／资金预留，落败方不得以换版本方式绕过。

CAS 重试继续原 SKU、target 和已存在的永久报价；当前价／路由可以变化，但不能改原买方金额。新 buyerOrderNo 才能冻结新价格。同号更换请求商品编码要求查询原订单并处理原结果，不能指导调用方反复换版本试下单。

## 6. 数据增量与存量安全

基线主 changelog 末尾为063。实施时复核最新编号并追加下一未占用 changeSet，建议名 `064-platform-sku-order-request-identity`；**本轮不创建 SQL、不修改已执行052、不运行迁移**。

只修改供应链 `sc_fulfillment_acceptance_receipt`：

- 新增 `requested_platform_sku_code VARCHAR(64) NULL COMMENT '格式3首次请求的平台SKU编码；格式0/2为空'`。
- 保留 `buyer_sku_code VARCHAR(64) NOT NULL`，COMMENT改为“采购商商品编码快照：格式0/2为首次请求编码，格式3为服务端解析的BPD编码”。存量内容不变；格式3取首次真实 BPD，不填平台 SKU 或占位符。
- `fingerprint_version` 保持 SMALLINT/default0，COMMENT改为“受理请求指纹格式版本：0历史未验证不可重放，2采购商编码无用户格式，3平台SKU编码无用户格式”；原 CHECK 扩为允许0/2/3。
- 新 CHECK：格式3必须有非空／非空白 `requested_platform_sku_code`、真实 `platform_sku_id`、目标 Schema／版本；格式0/2的新列必须为NULL。原采购商编码非空和其它约束保留。中文 DO／DTO 注释与 DDL 相同业务语义。

不新增物理外键、表、业务唯一键、通用别名或产品类型配置。没有历史回填／格式2转3，不改订单／报价金额、资产、账本、路由引用或购买人身份。

冻结身份 Reader 支持格式2／3并返回对应原请求身份；存在但不可信记录必须失败关闭，不与“无回执”混为一谈。`StandardOrderIdentityMigrationService` 的旧格式离线重建只能处理它原定义的历史范围，显式保留格式3，不把它降为格式2或重算已验证指纹。审计与目标加解密规则保持。

## 7. 端到端安全接线

新增 Controller／DTO／严格解析入口仅是一个边界；以下必须在开放前一起具备：

- API 模块 `ProjectAuthOperations`：三条精确 POST、独立操作码、契约版本、项目状态策略和 v2保护前缀；SDK 实际为 `ProjectAppAuthSigner`／Feign拦截器，沿同一注册表签原始字节。
- `ProjectAppAuthConfiguration`／Filter：过滤器 URL注册覆盖 v2；前缀内未知操作失败关闭，no-store判断覆盖v2资产及错误响应。
- `ProjectAppCredentialService`：能力停用后的摘要查询例外同时精确认可 v1/v2 QUERY，不用字符串前缀放宽其它操作。
- `ProjectCallContextAccessor` 及两个项目 Facade：分别校验对应 v2 操作，不能将 v2上下文伪装成 v1操作码或借查询身份创建订单。
- server `application.yaml`：管理登录放行只新增三个精确路径，不开放整个 open-api；HMAC保护不能遗漏。
- `aifuli-gateway`：新增三个精确 POST 路由，无rewrite，原始请求／响应字节透传；其它方法／兄弟路径不开放。
- common `ApiAccessLogPathUtils`：v2根及子路径纳入已有敏感分类，原匹配边界不扩到名称相似的兄弟路径。Gateway／Servlet访问日志、拦截器及安全异常渲染同步受保护；不消费／输出目标和卡密正文、query或签名秘密。
- `script/deploy` 测试站点 Nginx模板：只增加v2三个精确路径，不开放 `/open-api/**`；原始正文／认证头／trace-id透传，1MiB上限及错误语义保持。实际外层入口在pro197，供应链／Gateway在pro143，不把修改pro143当作公网已通。

遵守 `2026-10-04-logging-standardization-spec.md` 与现有敏感保护，不借新增路径建设新的脱敏策略或全系统日志工程。日志只记录原已许可的定位摘要，业务／系统异常终结责任不重复，trace-id响应单值不变。

共同匹配工具改动影响其它进程的分类与异常保护，必须执行直接受影响共享框架／Gateway回归；不因此重构八进程配置，不新增Feign扫描或跨模块表访问。

## 8. 管理页面、目录查询与接入迁移

实际前端仓库为 `/Users/mac/codespace/supplychainhubweb`，不得使用旧Documents目录。v2正式开放后才调整 `src/views/supplier/distribution/` 下分销／项目 ProductList及 API 类型注释：

- 平台SKU编码作为“v2下单编码”；项目列表规格下也显示该码。
- BPD列改称“分销商品编号（BPD）”，标明“旧v1下单编码”；PLT、LST仍是各自内部关系／目录号，不混成SKU。
- 页面说明明确“v2提交平台SKU；旧v1保留BPD／历史编码；是否可下单以本项目选品、价格与实时准入为准”。不再一概宣称标准下单必须BPD。
- 保留响应 `canonicalBuyerSkuCode` 的真实BPD含义，不改值、不静默改名成SKU；`platformSkuCode`沿已有字段使用。

既有目录接口是内部RPC `BuyerCatalogQueryApi` 的page/changes，不是项目HMAC商品目录。本片不凭空新增项目目录HTTP或改变RPC身份／筛选／买方目录价语义；已返回的平台SKU码可供内部接入准备，项目商品授权仍服务端检查。

上线前盘点真实接入应用／调用版本、签名SDK和在途订单，确认新接入使用v2，旧订单继续原创建版本及任一授权查询版本。即使无订单也不能省略接入盘点。v1和内部RPC本片不设自动停用日期；任何停用须后续单独确认，不由本次“只允许SKU”追溯禁止历史回放。

## 9. 验证、实施顺序与回滚

### 9.1 必要自动化

沿既有测试文件扩展行为契约，先红后绿，不测试私有方法调用顺序：

- v2严格闭包与真实HMAC：SKU合法成功，BPD／PLT／LST值不走别名，错误字段／重复键／浮点数量／身份／价格覆盖拒绝；v1仍拒绝platformSkuCode，内部RPC字段不变。
- 多分销商同SKU、同分销商多项目价格／路由隔离；未选品／移除／MANUAL暂停退役拒绝；其它项目CONFIRMED不授权当前项目。
- 使用不对称供给成本／优先级的两个真实Offer，证明FIXED／SPECIFIED／ALL及选中供应商符合原规则，且对外不泄漏供应商。不得用只一候选的样例声称证明多供给路由。
- 同参／异参／跨版本同号／跨项目同号及并发首创；v1/v2只有一身份胜出，CAS只一次重开，资金／报价消费／Outbox无重复。
- 已成功后改价格、Schema、供给、SKU／商业关联状态，完全同参仍冻结回放；PROCESSING／合法CAS须当前准入，当前身份不符不能切SKU，原报价金额不变。
- 原码大小写变体与库等价规则、改商品／target、不可信历史、缺BPD在claim前无事实写入；格式2原字节与buyerOrderDigest黄金样例不变，格式3独立向量验证。
- 限频排队／恢复、错误／回滚、重启后格式3身份完整，不降为格式2，旧离线迁移不改格式3。
- 真实MySQL／正式Liquibase验证存量0/2内容与checksum不变、新格式CHECK／NULL／类型／注释、增量重放、唯一键并发与事务回滚；不通过改旧变更集绕门禁。
- v2三路径真实项目状态／能力矩阵，关闭能力后仅v1/v2摘要可查询，从未开通全部拒绝，错误操作上下文不可串用；供应链ArchUnit全部保持。
- 完整入口验证未签名拒绝、非POST／尾斜杠／兄弟路径／额外层级、原始字节、no-store、错误日志无目标／卡密／凭据与trace-id单值。共享框架/Gateway直接受影响回归不能只跑供应链。
- 前端真实SFC与渲染检查两列表编码、v1/v2说明和第一切片各状态；既有全量失败单列，不制造绿灯。

供应链运行适用Test、ContractTest、ArchitectureTest及 `supply-chain-integration` 下真实IntegrationTest。远端联调、供应商交易、卡密资产与资金验收另记 `docs/集成测试.md`，当前全部NOT_RUN，调查只读结果不是订单成功。

### 9.2 实施与发布顺序

1. 用户评审通过两份详细设计，先完成状态修复独立切片。
2. 再形成v2实施计划；先做兼容回执／格式3／Resolver与共享生命周期，保留v1；接线认证、日志、Gateway、模板、SDK与测试。
3. 自动化通过后，按独立授权对测试环境执行正式增量并发布兼容后端，再接Gateway／pro197精确入口；完成无交易保护探针后再切前端及接入方。
4. 用正常管理配置及获准交易完成SKU→路由→履约／查单／资产／资金联调；不SQL补目录或改订单状态制造成功。

有格式3写入前可撤销v2入口并退回旧制品；正式增量可保留，不为回滚删除列或收窄CHECK。有格式3写入后，**不能回退到仅识别0/2的旧受理版本**：可关闭新v2创建入口，但保留格式3读取、历史查询、幂等和在途恢复的兼容制品。不删除回执／订单／报价，不把格式3转2，不用旧版本重下单。支持格式3的回退制品和关闭入口方式必须在发布前准备。

## 10. 书面评审检查点

本设计推荐同时确认：新接入v2仅接收平台SKU；v1／内部RPC继续兼容；跨版本同项目同号创建冲突但查询互通；新增格式3及原请求SKU列；无BPD的首次请求在claim前失败；内部商业身份和全部当前准入不取消。

用户明确评审通过前，只能修改本设计与验收文档，不写Java／Vue／SQL、实施计划或远端配置。通过设计不自动等于批准共享迁移、发布、真实交易或停用旧接口；这些交付动作按后续明确授权执行。
