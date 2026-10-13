# 目录真实来源校验与管理状态纠正详细设计

日期：2026-10-12。状态：**用户已书面确认评审通过；第一切片已本地实现并验证，未推送、未发布。** 本地154项定向/集成/契约/架构及前端23项定向通过，真实5态渲染已检查；全量前端既有失败详见`docs/集成测试.md`末尾执行记录。评审确认不包含共享数据库写入、推送、部署或真实供应商交易授权。

本设计为第一独立切片，不依赖 SKU 下单 v2 改造。第二切片见 [平台 SKU 下单与历史兼容详细设计](2026-10-12-platform-sku-order-contract-design.md)。调查复现与修复后门禁分开记录，本地通过不代表远端部署或联调通过。

## 1. 已证问题、业务解释与修改目标

### 1.1 已证问题

受检后端基线为 `db67e3cb`，前端为 `061a86f`，实际前端仓库为 `/Users/mac/codespace/supplychainhubweb`。用户已说明此前代码提交并部署到 pro143；本轮调查另外核实供应链镜像 `aifuli/supply-chain-server:db67e3cb-amd64`，提取部署类字节码确认与源码一致。

2026-10-12 北京时间 20:27:26 与 20:27:54 的只读一致性快照中，`SKU_011_001` 对应 AUTO 目录已 PUBLISHED，商业版本来源、当前分销定价版本、投影 JSON 来源均为 3，定价 READY；存储投影 AVAILABLE、原因空，并未过期。`basis_version=5`。

`BuyerAcceptanceCatalogReadService.read` 却比较 `projection.basisVersion` 与 `BuyerSkuPricing.currentVersionId`，把 `5 != 3` 解释为 SOURCE_CHANGED，覆盖为 UNKNOWN。页面按 UNKNOWN 显示“待重新评估”，并直接显示原因码。**根因是后端混用了计数与业务身份，不是该快照中的价格或供货再次失效。**

真实读服务的隔离复现有五种输入：新鲜同源却不同计数、计数恰好相等、真实价格前移、同源但过期、旧 JSON 来源且计数恰好相等。旧实现分别暴露误报、过期原因被遮盖、漏报真实投影来源陈旧；两个控制场景符合预期。调查证据登记于 `docs/集成测试.md` 的“2026-10-12 目录来源误判与平台SKU下单设计评审”条目。

### 1.2 目标与不变项

- 修复计数误用，同时补齐真实投影来源校验，不能只删除比较而漏掉旧投影。
- 管理读、RPC 读项和 RPC `availableOnly` 的 AUTO 新鲜度规则一致；保留管理字段与 RPC 响应的既有用途。
- 原因对运营可理解，稳定原因码留作诊断；UNKNOWN 不等于下架、确定无货或必须人工干预。
- 普通 GET 保持只读，不发布、不刷新、不续期、不改价格／状态／游标；不增加外呼和供给事实检查。
- 不改变项目最终准入、首次永久报价、资金、路由、供应商履约、MANUAL 生命周期、后台扫描参数或启停状态。

## 2. 方案选择与适用设计

采用**复用既有 `basis_json` 真实来源引用，统一 AUTO 读侧判定**。优点是无需 DDL、回填或重算全部目录，已保存的来源即可用于判断；代价是 Java 与 MySQL JSON 取值必须严格一致并由真实 Mapper 集成验证。

不采用只删 `basisVersion` 比较：商业版本和当前价格相同，也可能配上来源更旧的供给投影，仍会误显示 AVAILABLE。不采用新增投影来源列：当前已有 JSON 来源，本次没有检索容量或索引证据要求增加冗余列及迁移。

评审通过后，本设计细化并修正 `2026-10-09-catalog-business-minimum-design.md` §7 和 `2026-10-09-saleable-catalog-order-acceptance-design.md` 中“投影陈旧安全”的读契约；不替代刷新／发布、最终当前供给 Reader 或冻结回放设计。

## 3. 字段语义与来源契约

- `basis_version`：评估依据摘要变化计数。摘要含目录、定价来源、供给／路由资格等；改变路由可递增而定价版本不变。不得用于定价身份比较，也不得强制回写为当前价格 ID。
- `sc_buyer_listing_version.source_buyer_pricing_version_id`：自动商业快照来源的分销通用定价版本主键；MANUAL 可为空。
- `basis_json.sourceBuyerPricingVersionId`：本次供给评估实际使用的分销通用定价版本主键；未有价格时可为 JSON null。
- `sc_buyer_sku_pricing.current_version_id`：当前分销通用定价版本主键；不与商业版本主键、CAS version 或 `basis_version` 比较。
- `evaluated_at / valid_until`：最近成功评估与下次必须重建的库时间，Asia/Shanghai；有效区间上界不包含截止时刻。
- 管理响应 `sourceBuyerPricingVersionId` 继续指商业快照来源，不悄悄改成投影来源；本切片不新增对外来源字段。

读取 AUTO JSON 时仅提取本次判断所需的 `listingId`、`sourceBuyerPricingVersionId`，复用现有 `BuyerAvailabilityBasis` 所在责任边界；不重新序列化整个 JSON 后比 digest，因为现存 digest 不是 JSON 哈希。无需重新查询各项目／Offer，也不能把 JSON 中的 CONFIRMED 当作远程库存或履约成功证据。

非空 JSON 须为对象；对已存在字段先验证：`listingId` 为正整数且等于本 LST，来源为正整数或明确 JSON null。已声明却类型错误、非正数、超出 Java Long、错归属等属于数据完整性系统故障，保留原因传播，不吞成业务 UNKNOWN。通过已存在字段的完整性校验后，NULL JSON、缺少上述任一键或尚无评估时间视为未具备可验证评估，输出 NOT_EVALUATED，不补当前来源。来源键存在且值为JSON null不等于缺键。MANUAL 不解析 AUTO JSON，不要求其具有该结构。

## 4. AUTO 判定顺序与时间边界

### 4.1 前置完整性

先保留已有 LST/buyer/SKU/BPD 归属、current 商业版本归属和投影归属校验；PUBLISHED 缺 current、引用悬空或跨归属仍是系统故障。对要读取的非空 AUTO basis 执行 §3 类型和归属校验。不能因定价不 READY 就掩盖已发现的损坏引用。

### 4.2 有效状态与原因优先级

管理端及 RPC AUTO 读项采用以下相同顺序，所有时间判断使用该次读取取得的库时钟：

1. 缺 LST：管理端 UNKNOWN / NOT_EVALUATED；RPC 维持不返回不存在目录的行为。
2. SUSPENDED / RETIRED：UNKNOWN，原因分别 SUSPENDED / RETIRED；生命周期不变。RPC 原候选范围仍只含已发布／暂停，不新增退役行。
3. 当前定价身份缺失、current 为空或状态非 READY：UNKNOWN / DEFERRED_PRICE。READY 状态不许可把空 current 当作有效来源。
4. basis 无法证明曾有评估（§3 的正常缺失情形）：UNKNOWN / NOT_EVALUATED。
5. basis 实际来源不等于当前定价来源，或存在的商业版本实际来源不等于当前定价来源：UNKNOWN / SOURCE_CHANGED。JSON null 与非空 current 不相等；计数值无论是否相等都不参与。
6. `valid_until` 为空或 `valid_until <= now`，或存在的商业版本 `valid_from` 为空／晚于 now，或其非空 `valid_to <= now`：UNKNOWN / PROJECTION_EXPIRED。
7. 其余情况沿用存储 `availability / reason_code`。AVAILABLE 的空原因不强制展示 CONFIRMED；真实 UNKNOWN／UNAVAILABLE 及供给原因保持。

同时来源变化和过期时优先 SOURCE_CHANGED；仅过期必须准确显示 PROJECTION_EXPIRED。`valid_from == now` 可进入有效期，`valid_to == now` 或 `valid_until == now` 已失效。

DRAFT 不被 GET 发布。没有商业版本时只跳过商业来源／商业期限比较，仍校验价格、basis 与投影期限；新鲜供给评估可以 AVAILABLE，而商业状态仍 DRAFT。这两个维度必须分开，不能由供给正常推导已发布／已获准下单。

MANUAL 和原无管理模式的历史 RPC 兼容行为保持，不套用 AUTO JSON、来源或 TTL。RPC AUTO 非 READY 当前会返回 UNAVAILABLE，实施后按以上口径返回 UNKNOWN；这是明确的读契约修正，保留其非可用和 `availableOnly` 排除结果，必须有契约回归。

### 4.3 RPC 筛选与返回一致

`availableOnly=true` 保留已有商业 PUBLISHED、存储投影 AVAILABLE、定价 READY 要求；AUTO 另要求可验证 basis、三处真实定价来源一致、投影期限与商业有效期均有效。SUSPENDED 不得筛入。MANUAL 筛选维持原规则，不扩历史准入。

这些条件必须同时用于 selectPage 与 countPage；分页前在数据库筛选，不取一页后用 Java 删除。返回项及 changes 中的当前项使用相同 AUTO 状态决策，不能出现“SQL 筛入但返回 UNKNOWN”。一次 page 的 SQL 列表、total、输出共用同一库时钟；在同一供应链只读 RR 事务内读取，避免两次查询分别看到刷新前后的不同快照。changes 本来返回当前读项而非事件历史快照，此语义不变。

SQL 必须区分 JSON 整数、字符串 `"3"`、JSON null 与缺键，不能依赖宽松 CAST 把损坏字符串当版本 ID。对不完整／损坏 basis 安全排除；普通读项的类型／归属故障仍按系统错误收敛，不凭筛选排除结果伪称数据正常。

## 5. 实现责任边界与影响范围

以 server 的 `src/main/java/com/aifuli/module/supplychain/` 为 Java 路径基准：

- `tradecore/catalog/application/BuyerAvailabilityBasis`：给出受控来源提取规则，保持现有写入格式／摘要算法不变。
- `BuyerAcceptanceCatalogReadService` 与 `BuyerCatalogQueryService`：复用同一 AUTO 状态决策。只在 catalog 内抽取这个确有多个调用者的决策，不建立通用状态框架；决策输入为来源／时间／状态值，不依赖 Mapper、Spring、外呼。
- `tradecore/catalog/readmodel/BuyerCatalogReadRow`：追加判定所需 basis、商业有效期等内部字段，不暴露供应商成本／秘密；既有 DTO 字段语义不变。
- `tradecore/catalog/dal/read/BuyerCatalogReadMapper`：当前 HEAD 使用 `@Select` 内嵌动态 SQL，**不是现存 XML**。本次复杂 JSON／多表读模型按仓库规范迁至 XML，统一 AUTO 筛选片段；先保持行为迁移并回归，再修改判定。无需 Lambda 不能表达的通用封装，不改分页顺序、搜索范围或无关 JOIN 行为。
- `BuyerProductQueryService` 保持管理分页、价格与字段职责；不借本片改页排序或定价算法。

不增加 DDL，不修改 060 或其它已执行变更集，不写回历史 basis、不追加目录事件。保持独立供应链数据源／事务管理器和 ArchUnit 边界。

本片不改启动、RPC 注册、事务管理器、缓存、安全配置或后台线程池。日志若有新增／修改，遵循 `2026-10-04-logging-standardization-spec.md`：损坏数据保留因果、只在终结入口记录一次，不输出 basis 全文、目标、凭据或卡密。

## 6. 管理页面表现

仅在实际前端仓库 `/Users/mac/codespace/supplychainhubweb` 的 `src/views/supplier/distribution/distributor/detail/ProductList.vue` 和其既有测试范围实现：

- UNKNOWN 标签改为“供给待确认”；SUSPENDED／RETIRED 的“暂停／退役”优先规则保留。
- SOURCE_CHANGED 显示“定价来源已更新，需重新评估”。
- PROJECTION_EXPIRED 显示“评估结果已过期，需获取最新状态”。
- NOT_EVALUATED 显示“尚无有效供给评估”。
- DEFERRED_PRICE 显示“分销定价尚未就绪”。
- SUSPENDED／RETIRED／BUYER_PRODUCT_REMOVED 分别显示“目录已人工暂停”／“目录已退役”／“分销商品已移除”；其它真实路由原因未有已核实中文映射时显示“供给条件尚未确认”，原稳定码保留在提示详情，不能猜测正常／缺货。

“刷新状态”按钮和真实同步重算接口不变。说明该操作在条件满足时仍可发布／更新目录；后台自动刷新是否启用以实际配置为准，不由 UNKNOWN 文案承诺。保留“目录层投影不代表各项目实时授权”。

第一切片不提前把 BPD 下单文案改成已支持 SKU；v2 尚未交付时仍须准确描述当前 v1。不新增菜单、轮询或项目实时资格看板。

## 7. 验证与完成标准

评审通过后先扩展既有 `BuyerAcceptanceCatalogReadServiceTest` 等测试，使用非对称版本值：

1. counter=5、商业来源=JSON 来源=current=3、新鲜：AVAILABLE，无 SOURCE_CHANGED；counter 改成任意其它正常值仍相同。
2. counter=current=3、JSON 来源=2、商业来源=3、新鲜：UNKNOWN / SOURCE_CHANGED。此例必须击穿“只删除错误比较”的实现。
3. JSON=current=3、商业来源=2：UNKNOWN / SOURCE_CHANGED；current 前移至4同样拒绝旧评估。
4. 同源、counter不同、截止等于 now：UNKNOWN / PROJECTION_EXPIRED；截止前1毫秒有效，截止后无效；商业开始／结束两侧也分别覆盖。
5. JSON／来源正常缺失、真实存储 UNKNOWN／UNAVAILABLE、非 READY、暂停、退役、MANUAL 无 basis 与 TTL，逐一核对不误报绿灯；损坏 JSON 类型／归属走系统失败。

扩展既有 RPC freshness / catalog 测试，以真实 MySQL、正式 Liquibase、Mapper 验证 JSON 类型、来源失配、商业期限、availableOnly 的项／total一致、changes 当前项、同一读视图及正常 MANUAL；GET 前后商业版本、投影、游标、Outbox 数量和值不变。

运行供应链纯逻辑 Test、适用 ContractTest、ArchitectureTest 与 `-Psupply-chain-integration` 的受影响 IntegrationTest，不跳供应链门禁。前端运行既有真实 SFC 测试、定向 lint／format／类型检查；实际渲染并检查正常、真实来源变化、过期、暂停／退役状态截图。全量基线失败分别记录，不能用调查探针替代修复后的门禁。

pro143 联调须另外取得发布／配置及共享状态操作授权后执行：先核制品，再读新鲜同源列表；可控的新来源变化、恢复及过期验证通过正常管理流程进行，不 SQL 造 AVAILABLE。保存请求／响应、只读来源快照和截图；不因修复目录提示就宣称华昇已真实交付。

完成标准：同源新鲜不再误报，真实陈旧不漏报，三类读端一致且 GET 零写，MANUAL／生命周期及 HTTP 最终准入回归通过。默认30秒投影有效期与扫描 fixed-delay 的间隙仍可能产生真实 UNKNOWN；本片不扩 TTL 或保证页面永不出现待确认。

## 8. 分期发布、回滚与评审门禁

先交付本片后端判定与前端原因展示，独立验证／发布，不等待 SKU 契约。无数据迁移；异常时可回退相应制品，既有目录版本／价格／资金不重写。回退旧后端会恢复已知误判，须如实记录。

用户已确认真实 JSON 来源规则、AUTO 非 READY 统一 UNKNOWN、中文标签及 SQL／Java 一致性门禁。实施计划见 [目录来源纠正实施计划](../plans/2026-10-12-catalog-source-freshness-correction.md)；自动化和远端验收结果随执行登记，不把评审通过作为实现通过。
