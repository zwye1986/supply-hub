# 前后端业务闭环联合核验（2026-10-03）

## 1. 结论与核验边界

用户要求结合真实前端综合分析。核验后端 `/Users/mac/Documents/codeSpace/supply-hub` 与前端 `/Users/mac/Documents/codeSpace/supplychainhubweb` 的 dev 分支实际实现；后端分析基准 `08aa290`，前端 `69f6903`。自动首冻另有正在实施的未提交代码，其最终测试与复审以 `docs/集成测试.md#781-标准项目直接下单自动首冻2026-10-03实施中` 的完成记录为准。

**下一步第一优先级是接通管理页面选品与订单受理商品目录，而不是继续堆配置页面。** 真实页面已经能写分销商品 `sc_buyer_product` 与项目选品 `sc_project_listing`，标准订单却从另一套 `sc_buyer_listing` 解析 `buyerSkuCode` 并要求已发布且可用。前端没有建立/发布这套记录的调用入口。既有种子与 HTTP 测试预置数据会掩盖此断点，自动首冻也不会创建该目录。

大部分基础配置和路由是真接口，但配置可保存不等于可发布、可被订单消费。华昇当前只有密码协议与凭据基础支持，没有生产 metadata/履约 executor；通知与售后亦未完成真实业务动作。

本次是源码和接口静态核验，未访问运行中菜单/配置，未做浏览器操作、数据库配置、外部供应商调用或部署。缺失接口调用、载荷错配等为确认的代码事实；菜单及外部配置风险附有触发条件，不能当作现网故障结论。历史单测结果不替代本次全页面验收。

## 2. 已接真实接口的主链

- 商品：`src/api/product/index.ts:205` 起的商品管理接口对应 `ProductAdminController`；通用默认价格由 `GenericBatchPricingService` 初始化。
- 供应商合作商品：真实 offers 接口；`SupplierOfferPublicationService:246` 起的上架逻辑实际检查连接器/能力、建立供给库存并发布不可变版本。
- 分销商与项目：实际 Vue 调用 `getBuyerProductPage/createBuyerProducts/saveBuyerProductPricing` 及项目选品、发布和通用定价接口。遗留 `USE_DISTRIBUTOR_MOCK=true` 只包部分旧方法，主页面没有调用它们，不能据开关判定整条主链是 mock。
- 项目应用：应用开通、轮换、吊销为真实接口；身份来自项目签名认证。管理端没有下单按钮符合标准项目入口设计，不是缺陷。
- 路由：规则新增、更新、删除、启停均会发布后继生效版本；运行时按实际生效策略选路，分销商/项目/供应商使用业务 code 匹配，没有确认 id/code 错配。
- 直充限频：真实管理 API；精确手机号只走 POST 正文、ID/CAS 保留 WireLong、前后端有权限与过期保护。未做人工验收。
- 标准项目入口：创建订单、查单、资产查询均有真实签名 HTTP Controller；资产可通过项目轮询交付，主动通知属于另一条尚未完成的链。

本节“真实”只说明实现与调用存在，不表示全新配置已经端到端通过。

## 3. 阻断从零配置闭环的缺口

### 3.1 页面选品与受理目录脱节（首要）

前端分销选品 `distribution/distributor/detail/ProductSelectDialog.vue:612` 调 `DistributorApi.createBuyerProducts`（API `distribution/index.ts:271`），后端 `BuyerProductCommandService:226` 创建 BPD_ / `sc_buyer_product` 并初始化通用定价。项目选品 `distribution/project/detail/ProductSelectDialog.vue:490` 调项目 products 接口，`ProjectListingCommandService:203` 创建 PLT_ / `sc_project_listing`，并可发布其版本。

订单 `FulfillmentAcceptanceLifecycle:148`、`:521` 却按 buyerId + buyerSkuCode 读取并锁定 LST_ / `sc_buyer_listing`；`:540` 起要求 current version 已发布且有效，`:549` 起要求 availability AVAILABLE。不存在时回执为 LISTING_VERSION_EXPIRED / REJECTED_RETRYABLE。

BuyerListing 由 `ProductCatalogCommandService:200` 另行创建；现有管理接口是 `M1MasterDataAdminController:131` 的 `/supply-chain/master-data/listings`、`:139` 的 `/listings/publish`。前端 API 与供应链页面未发现这两个调用或 listingNo。不得将 ProjectListing 主键代替 BuyerListing 主键。

**修复前必须明确：** 下单 buyerSkuCode 的权威来源和稳定性；两套目录的对应关系；发布、暂停、退役的责任；BuyerListing 商业版本与通用定价冻结的分工；旧单使用冻结身份重放的兼容。先写详细设计再实现，不能擅自把 BPD_ 编号当 buyerSkuCode 或自动复制价格。

### 3.2 QNM 单槽凭据从空环境无法全 UI 准备

当前唯一生产 metadata 为 QNM，继承 SINGLE_SLOT。`ConnectorFormDialog.vue:24` 起严格显示/提交槽引用；`ConnectorCredentialBindingValidator:34` 起拒绝用凭据集代替槽。现有 CredentialPanel 只能创建凭据集与 ENV/ENVELOPE 项，前端配置 API 只查询槽，没有创建槽方法。

后端 `SupplierConfigurationAdminController:113` 已提供槽创建，`SupplierConfigurationCommandService:88` 起仅接受 ENV 的 env: 引用。已有槽环境可以走真实连接器链；空环境则仍需额外 API/部署准备。新建凭据集成功不能算 QNM 接入完成。

### 3.3 华昇尚不能选配或执行

`SupplierConfigurationQueryService:59` 从实际 metadata registry 返回目录；生产只有 QnmGameOrderV1Metadata。CompositeOperationExecutor 只有 QnmSupplierAdapter 具体实现；HUA_SHENG 目录只有 HuashengCrypto，尚无实际注册的 metadata、HTTP/token/parser/executor。

Task1 密码协议与 Task2 多密钥凭据支持已完成，不能据此宣称华昇可履约。正式协议金额单位、重复确认/取券保证、7014 补单、有效期及额外资产字段仍需证据，见 `2026-10-02-huasheng-fulfillment-adapter-design.md#124-第三切片实施前协议证据核验2026-10-03` 与 §12.5。旧系统内部成功记录不能替代协议保证。

## 4. 配置与运营中确认的契约问题

### 4.1 合作商品非固定价能保存却不能上架

Vue 提供 FIXED/DISCOUNT/REALTIME；`SupplierCooperativeProductService:179`、`:274` 仅 FIXED 写 draftUnitPrice；`SupplierOfferPublicationService:287` 上架强制非空，非固定模式的结算价派生尚未定义。先以 FIXED 验基准闭环，非固定价需先明确业务契约；不能用 mock 实时价格补齐。

### 4.2 券码资产管理详情有字段错配与空值异常

`SupplierOrderLifecycle:730` 起将交付值写 cardCode/cardSecret。OperationAssetFact 对仅卡号的事实推断为 VOUCHER_CODE。`OrderViewQueryService:441`、`:442` 却读取 voucherCode/voucherUrl，缺字段返回 null，`:447`–`:448` 的 List.copyOf 会抛空值异常。该路径在有相应资产时可导致平台详情 500，尚未通过本次运行复现。

CARD_CODE_SECRET 不受这个具体问题影响；项目资产 decoder 兼容 cardCode，不能扩大成项目资产 API 必然失败。后续应统一类型化资产持久化与查询契约，兼容历史记录，再补 HTTP/查询测试。通用券码/券链接还被页面固定贴“取票码/取餐码”标签。

### 4.3 主动通知只记日志而标记已投递

`BuyerEventRpcDispatchHandler:35` 起只解析并记录日志；`OutboxDeliveryEngine:135` 的 HTTP 分支也仅记录未实现，随后 `:141` publish。依赖主动通知的调用方不能完成通知闭环，并可能看到伪成功投递状态。项目查单/资产查询可先独立验收，不能将轮询成功称为通知成功。已有通知设计与联调约束见 `docs/集成测试.md` 标准项目通知章节。

### 4.4 分销商移入黑名单调用不支持该状态的端点

列表 `distribution/distributor/index.vue:317`、详情 `detail/index.vue:563` 一律调用批量 PATCH /buyers；`BuyerAdminController:214` 起批量仅支持 ACTIVE/SUSPENDED，BLACKLIST 被拒。单体 PATCH /buyers/{buyerCode} 已支持 BLACKLIST，应对齐既有接口；暂停与恢复不受该问题影响。

### 4.5 在途路由被投影成失败

页面允许 PROCESSING 订单查路由记录；`OrderViewQueryService:228` 起将全部非 SUCCEEDED 尝试计作失败，含 WAITING/PENDING/PROCESSING/MANUAL；步骤与概要也映成 FAILED。页面因而误报“路由失败”，不代表实际策略发布或选路失败。

### 4.6 场景模板与售后仍是内存 mock

电影/点餐/H5 的 TemplateList 调 `src/api/pricing/index.ts:16` 起的内存 mock；保存成功只改模块数组，完整刷新重置，选项还使用固定分销商/项目。平台、分销、项目通用 SKU 定价是真接口，不能扩大成全部价格 mock。

售后列表/详情/审核由 `src/api/order/index.ts:41`、`:310` 起进入内存 mock，页面“审核完成”不退款、不记账。shipOrder 也为 mock，但对应表单未被当前订单页面引用，不能将闲置方法认作首张自动卡券阻断点。平台补发/退款按钮目前明确禁用。

### 4.7 供应商订单投影与资金工作台不完整

前端 `src/api/order/index.ts:233` 起从旧 supplier-orders 尝试视图转换，丢失结算状态、数量、外部订单号等字段，硬置业务类型；部分类型在字典中显示“-”。管理详情的供应商资产展示不完整。

供应链页面仅显示档案余额，没有预留/提交/释放/账本流水工作台。供应链独立账本事实必须由本模块 API/自动化或数据库核验；FMS 页面不代表供应链财务闭环完成。

## 5. 需运行环境核验的条件风险

- **菜单组件：** `script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql:181`、`:197` 指向 supplier/order/index，前端该文件不存在，实际是 supplier/order/platform/index.vue 等；routerHelper:186 起找不到组件返回 undefined。若运行环境使用该 SQL，订单页不可正常加载；实际 system 菜单未知。菜单修复由部署 SQL 写 system 库，不允许供应链进程跨库修改。
- **适配器白名单：** AdapterTypeAllowList 默认空且 fail-closed，上架检查名单；仓库配置未找到生产设置。外部 Nacos/ENV 可注入，因此只列联调前置核对，不断言现网缺配置。
- **外呼约束：** 核对独立供应链进程实际 endpoint allow-hosts、凭据 ENV 与网络许可，不读取或输出秘密。
- **权限体验：** 路由写按钮没有前端权限门控、下拉请求异常变空数组；Controller 有权限，属于体验与诊断问题，不是已确认越权。
- **冻结身份重放：** 既有 Facade 从当前 Schema 生成指纹，再 claim；Schema/身份变化后同原始订单 payload 的重放问题另有记录，不能由价格首冻测试覆盖，见首冻设计 §6。

## 6. 调整后的推进顺序与完成判据

1. **完成正在实施的自动首冻切片。** 真实 HTTP/MySQL 验首次首冻、同号与 CAS、并发、停用窗口、报价留存与资金回滚，完成规格和质量复审；仍需预置 BuyerListing，必须标明边界。
2. **首先设计并接通“UI 选品 → 订单受理目录”。** 明确 §3.1 语义，再实现前后端/版本生命周期一致性。同步解决订单菜单旧路径、QNM 单槽准备入口、黑名单既有端点错配，以及券码管理详情；这些项各按设计与实际影响测试，不能一次猜测重构领域。
3. **从空环境建立一条全配置自动化基准链。** 先用已实现 QNM、FIXED、quantity=1、已知支持的标准卡券或直充 SKU；真实管理 Controller 建商品/供给/分销/项目/凭证/路由，不依赖直接 SQL 补 BuyerListing；真实签名下单、Timer + 本地供应商 HTTP 挡板、查单/资产/账本断言。基准链不等于华昇验收。
4. **实施华昇协议和履约。** 取得正式协议，按既有 Task3–4 设计先行实现多步骤、查单恢复与回调，并复用上一步配置基准验华昇目标 SKU。未知结果不得猜失败、重下或切供应商。
5. **开展真实环境验收。** 先核实有效菜单和配置，再管理端全新配置、签名下单、供应商终态/资产与资金核对。未执行保持未验收。
6. **补运营闭环。** 优先真实主动通知与重投/签名，再完善路由状态、订单投影与资金审计工作台；售后退款/补发、场景模板、非固定价分别确认契约实施，不把内存 mock 成功当作财务动作。

最小业务完成判据是：一个全新 SKU/分销商/项目通过真实管理操作完成配置，签名下单取得唯一订单，正确路由到可执行供给，自动完成终态；同号重放不多下单、不多扣款，项目能取得原资产，跨项目隔离有效；管理端能查看对应平台单/B 单/资产，资金预留与提交或释放恰一次。通知和售后分别验收，不能随首单通过一并标完成。
