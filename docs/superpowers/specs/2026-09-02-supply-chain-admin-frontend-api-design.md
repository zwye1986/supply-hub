# 供应链管理端 REST（对齐前端页面）详细设计

## 1. 文档定位

本文根据 `supplychainhubweb` 已落地的供应链中台页面（`src/views/supplier`、`src/api/supplier`）定义管理端 REST，供前端关掉 mock 后对接。

前端范围仅三块：**供应商、分销商、项目**（含供货配置、合作商品、分销商品、项目商品、分销层级）。新增供应商供货配置、新增分销商的品类/类型/分类下拉改为 system 数据字典，不再用分类树。加价模板不在本文。`supplychainhubweb` 已有订单/售后/路由规则页面与 mock（`src/views/supplier/order`、`src/api/order`、`src/api/routing`），关掉 mock 后的平台订单只读、路由记录、供应商订单、路由规则 GET 投影分别见 `docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md`、`docs/superpowers/specs/2026-09-21-order-routing-records-admin-design.md`、`docs/superpowers/specs/2026-09-21-supplier-order-admin-readonly-design.md` 与 `docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md`；路由规则页保存（补丁发布）见 `docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md`。金额仍按 a2 用分，不按 mock 的元适配。平台列表不得用 `source=SUPPLIER` 冒充供应商订单聚合。路由规则页不得把 mock 的综合权重、项目/商品类型四级当成已生效配置；可写请求必须带 SKU、履约类型与有序候选。

与 `docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md` 冲突时：

| 主题 | 以谁为准 |
| --- | --- |
| 供应商 / 分销商 / 项目的管理端 REST 路径、字段、枚举、状态机 | **本文** |
| 供应商 / 分销商开票查询与保存 | `docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md` |
| 表单字典、供货/分销商品类编码、SPU 三码命中 | `docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md` |
| 金额口径、身份用 code、不按树拆账、L1/L2/L3 仍是结算价来源 | 08-28 与 `CONTEXT.md`；**无加价模板、销售价来源、`FOLLOW_TEMPLATE` 真实语义**以 `docs/superpowers/specs/2026-09-12-supply-chain-prd-scope-alignment-design.md` 为准（否定本文 a5「L2 默认来自加价模板」） |
| BuyerOrder / 资金预留 / 幂等 / 项目 appId | 既有内核，本文不改 |

代码、表名、日志继续使用 `Buyer` / `BuyerProject`。管理端中文文案用「分销商」「项目」。

词汇见仓库根目录 `CONTEXT.md`（分销商组织树、L2 手工覆盖、项目已结束）。

## 2. 已确认决策

| 编号 | 决策 |
| --- | --- |
| a1 | 本轮覆盖前端已有三块，不顺带定义加价/订单/售后/路由页面。 |
| a2 | 领域内核不动：金额 CNY 分、路径身份用业务 code。开票见 `docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md`。前端关 mock 后改 URL/字段对齐后端（由前端仓库完成，见 a13）。 |
| a3 | 扩展现有 `/admin-api/supply-chain/{resource}`，不新建 `/scm` 适配层，不废弃已落地的 `/suppliers`。 |
| a4 | 分销商组织树真实落库（一级/二级/三级、上级、下级）。树不参与拆账；只有项目所属 Buyer 与中台一本账结算。组织等级 ≠ 结算价 L1/L2/L3。 |
| a5 | L2 无手工覆盖时继承 L1（`FOLLOW_TEMPLATE` 只清覆盖，**本期无运行时模板层**，021 历史预留表停用，见 09-12 s4）。分销商品新增/改价可按 SKU 覆盖，覆盖后该 SKU 的 L2 以手填为准。页面文案「按分销价=销售建议价」对接为 `EQUAL_L1`（粘性覆盖，值=当前 L1 **B 端结算分**，不是 C 端 `marketPrice`）；「自定义」对接 `CUSTOM`。未传 `l2AdjustMode` 时后端仍默认 `FOLLOW_TEMPLATE`。 |
| a6 | 供货模式扩展为 5 种；发货时效 / 结算方式 / 对接方式可落库。可售品类 / 商品类型 / 商品分类为三级独立封闭枚举（system 字典），不是类目树 id；SPU 打同一套三码并按三码整体命中供货配置。清单与命中规则见 `docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md`。 |
| a7 | 合作商品新增即 `PUBLISHED`（上架）。下架=`SUSPENDED`，移除=`RETIRED`。 |
| a8 | 项目「已结束」=`CLOSED`，可恢复为进行中或已暂停。`ARCHIVED` 仍不可逆，本批页面不暴露。 |
| a9 | 项目启动后不可改关联分销商。结算方本期只允许「关联分销商」；「平台结算」拒绝。 |
| a10 | 项目新增 SKU 必须已是该分销商的分销商品（已有 `BuyerProduct`），禁止顺带创建。 |
| a11 | 不提供物理删除供应商/分销商/项目。开票为独立子资源，见 a15。 |
| a12 | 管理端对外契约用 REST：路径是名词资源，动作用 HTTP 方法。禁止 yudao 式 `/create` `/update` `/get` `/page` 以及新的动词路径（`/status` `/retire` `/batch`）。 |
| a13 | 2026-09-04 最新确认：本任务只交付后端菜单能力和接口 RBAC，不修改 `supplychainhubweb` 或其它前端工程。移除 `remaining.ts` 静态路由、消费 `system_menu` 动态菜单、接入按钮权限及业务 REST 由独立前端任务/团队完成；禁止为迁就现有 `/scm/**` 请求再做适配层。 |
| a14 | 运营后台功能权限见 `docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md`。三块页面按资源拆 `query/create/update/delete/update-status`；`supply-chain:admin` 仍为超集及未拆接口兜底。本文字段与路径不变。 |
| a15 | 供应商/分销商开票：`GET/PUT …/{code}/invoice`；无行则档案预填且不落库；保存不回写档案；开票表明文（含开户账号）。契约见 `docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md`。 |
| a16 | 供应商/分销商管理端列表与详情在通过对应 `query` 权限后，手机号、法人手机号、开户账号、证件 fileKey 等档案字段按原值回显；响应字段使用 `legalPersonMobile`、`bankAccount` 等业务名，不再使用 `*Masked`。库内既有信封加密不变，入口/异常/第三方调用日志仍须脱敏且禁止打印完整响应对象。 |
| a17 | 供应商供货配置的 `minQuantity`（允许最低起订量）为预留字段：继续接收、存储和回显，只做非空及正整数校验，不参与供货范围命中、商品筛选、供货上限校验、合作商品默认值或下单校验。合作商品自己的 `minQuantity` 仍为有效字段，未填写时固定取 1，不继承供货配置预留值。 |

## 3. 总体契约

### 3.1 路径与鉴权

网关前缀 ` /admin-api`。Controller `@RequestMapping`：

| 资源 | 映射 |
| --- | --- |
| 供应商 | `/supply-chain/suppliers`（已有，前向扩展） |
| 分销商 | `/supply-chain/buyers`（新建） |
| 项目 | `/supply-chain/projects`（新建） |
| 类目树 | `/supply-chain/categories`（新建查询） |
| 标品 SKU | `/supply-chain/products/skus`（新建查询） |

鉴权见 `docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md`：三块资源用 `supply-chain:{supplier|buyer|project}:{action}`，`supply-chain:admin` 为超集；未拆接口仍只认 `admin`。供应商/分销商档案的手机号、法人手机号、开户账号、证件 fileKey 等字段在通过对应 `query` 权限后按原值回显，以满足编辑表单回填；日志仍须脱敏，禁止记录完整请求/响应对象。查询接口不得在请求线程调用供应商 Adapter。

路径主键用业务 code：`{supplierCode}` / `{buyerCode}` / `{projectCode}`。响应同时带内部 `id`（Long PK），便于前端表格 row-key，**命令仍以 code 为准**。

`/admin-api` 由 Web starter 按 Controller 包名动态添加，并非写在各 Controller 的
`@RequestMapping` 中。供应链管理端位于 `**.interfaceapi.admin.**`，因此
`supply-chain-server` 必须显式配置 `aifuli.web.admin-api.controller` 为该包模式；裸路径
`/supply-chain/**` 不作为对外兼容入口。网关基础配置必须同时注册
`Path=/admin-api/supply-chain/**` → `grayLb://supply-chain-server`，并把该服务的
`/admin-api/supply-chain/v3/api-docs` 加入 Swagger 聚合。若环境 Nacos 覆盖完整路由列表，发布时须同步
同一条路由并以环境实请求复验，不能只以仓库基础配置通过代替。

### 3.1.1 REST 约定

| HTTP | 含义 |
| --- | --- |
| `GET` 集合 | 列表/筛选/分页。查询参数表达过滤与 `view=simple` 等投影，**不用** `/page`、`/simple-list`、`/get` |
| `GET` 成员 | 详情。身份在路径上，**不用** `?id=` |
| `POST` 集合 | 新建。批量新建把数组放在 body（`items`），**不用** `/create`、`/batch` |
| `PUT` 成员 | 整资源替换（档案、供货配置全量） |
| `PATCH` 成员 | 部分更新，含状态、改价 |
| `PATCH` 集合 | 批量部分更新（批量暂停/恢复） |
| `DELETE` 子资源 | **逻辑移除**（Offer/分销商品/项目目录项退役），不是物理删库、不是删供应商/分销商/项目主体 |

禁止出现在新路径中的片段：`create`、`update`、`delete`、`get`、`page`、`list`、`simple-list`、`code-list`、`parent-options`、`update-status`、`status/batch`、`retire`、`batch`、`batch-draft`、`price`。

已落地的 `PUT /supply-chain/suppliers/{supplierCode}/status`、`PUT /supply-chain/suppliers/status/batch`、`POST .../offers/batch-draft` **只作兼容别名**，新页面与新测试只打 REST；不要再扩这类动词路径。

### 3.2 金额、时间、分页

- 所有金额字段后缀 `Cents`，单位 CNY 分。前端展示「元」时自行 `/100`。
- 时间 ISO-8601 UTC。列表「日期」筛选项可收 `yyyy-MM-dd'T'HH:mm:ss` 区间。
- 列表默认 `pageNo=1`、`pageSize=20`。供应商/分销商按 `updatedAt` 倒序；合作/分销/项目商品按 `createdAt` 倒序。
- 响应包在既有 `CommonResult`；分页为 `PageResult{ list, total }`。

### 3.3 枚举 JSON

禁止数字魔法值出现在 REST 入出参。状态与模式一律英文常量（见第 4 章）。前端现有 `1/2/3` 与 `dropshipping` 等小写码由前端映射，后端不兼容两套。

### 3.4 文件

证件/执照/资质只收基础设施文件模块的 `fileKey`（或 key 数组），不在供应链模块做 multipart。通过对应资源 `query` 权限后，GET 按保存原值返回 `fileKey`，供编辑页回填；前端上传组件出 URL 的转换在前端完成。fileKey 仍禁止写入日志或第三方调用报文。

### 3.5 开票与删除

供应商/分销商开票为独立子资源，契约见
`docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md`。

- `GET/PUT /supply-chain/suppliers/{supplierCode}/invoice`
- `GET/PUT /supply-chain/buyers/{buyerCode}/invoice`

无开票行时 GET 用档案预填且不落库；PUT 不回写档案。详情接口不含开票块。禁止 `/invoice/get`、`/invoice/save`。

`DELETE /suppliers/{code}`、`DELETE /buyers/{code}`、`DELETE /projects/{code}` 不提供。档案用状态机；合作/分销/项目商品用移除（退役/下架目录项）。

### 3.6 OpenAPI（Swagger）

本批所有管理端 HTTP 接口必须带 springdoc OpenAPI 3 注解，风格对齐已有 `SupplierAdminController` / `BuyerAccountAdminController`，进入同一套管理端 Swagger（`/admin-api` 文档），不另开 `/scm` 文档。

| 位置 | 要求 |
| --- | --- |
| 类 | `@Tag(name = "管理后台 - 供应链…")` |
| 方法 | `@Operation(summary, description)`：summary 是中文能力名；description 写 REST 语义、身份用 code、金额 CNY 分、以及关键不变量 |
| 查询参数 / 路径参数 | `@Parameter` 或 `@Schema`，说明筛选/投影（如 `view=simple`） |
| 请求/响应 VO 与 record 字段 | `@Schema(description = "…")`，**与对应 DDL COMMENT 语义一致** |
| 枚举字段 | 写明允许的英文常量（如 `ACTIVE` / `SUSPENDED` / `BLACKLIST`） |
| 金额字段 | 写明单位为 CNY 分 |
| 兼容别名方法 | `@Operation` 标明「兼容别名，新页面不要调用」 |

`*ContractTest` 须断言管理端 Controller 类有 `@Tag`、每个对外映射方法有 `@Operation`。供应商/分销商必须映射 `GET/PUT …/invoice`；类目/标品/项目路径不得出现 `invoice`。禁止 `/invoice/get`、`/invoice/save`。

测试环境用 `AIFULI_SUPPLY_CHAIN_OPENAPI_ENABLED=true` 打开文档（默认 false）。打开后中台 `/v3/api-docs/**`、`/swagger-ui/**` 免登录。公网 Knife4j 入口是 `https://aifuli-test.qihaofu.cn/doc.html`，Nginx 必须把 `/doc.html`、`/webjars/`、`/v3/api-docs` 反代到网关 `58080`，否则该路径会落到管理端 SPA 显示 404。页面左上角选 `supply-chain-server`。`/admin-api/supply-chain/**` 业务接口仍需管理端登录。生产保持关闭。

## 4. 共享枚举与类目

### 4.1 供货模式 `SupplyMode`

扩展现有二者，Liquibase 追加 CHECK（禁止改已发布 changeset）：

| 常量 | 前端文案 | 原前端 mock 码 |
| --- | --- | --- |
| `API_DIRECT` | API直发 | `api` |
| `DROP_SHIP` | 一件代发 | `dropshipping` |
| `BATCH` | 批量发货 | `batch` |
| `PICKUP` | 门店自提 | `pickup` |
| `H5` | H5直发 | `h5` |

供货配置一行可多选（`supplyModes: string[]`）。唯一键是 `(supplierId, sellableCategory, productType, productClassification)` 三码，不得靠模式拆成两行。实现：范围主表去掉单值 `supply_mode`，子表 `sc_supplier_supply_scope_mode`。Offer 上仍是单值 `offerSupplyMode`（该 SKU 选定的一种）。三码枚举与命中见字典专文。

管理端供货模式下拉后续统一读取 system 数据字典 `supply_chain_supply_mode`；本次只配置字典，不修改前端工程。字典数据仍只负责标签、排序与展示颜色，提交值必须是上表五个枚举名；供应链后端继续用 `SupplyMode` 做封闭校验，不在请求线程远程调用 system 服务。

前端详情里曾经出现的「现货/代发/预售」数字枚举废弃，不入库。

### 4.2 其它封闭枚举

**供应商合作状态**（已有）：`ACTIVE` 正常、`SUSPENDED` 暂停供货、`BLACKLIST` 黑名单、`ARCHIVED` 归档（页面不提供）。迁移规则保持现实现。

**分销商合作状态**：`BuyerStatus` 增加 `BLACKLIST=30`。页面：`ACTIVE` 正常、`SUSPENDED` 暂停分销、`BLACKLIST` 黑名单。`ARCHIVED` 不暴露。黑名单可逆到正常或暂停；归档不可逆。黑名单与暂停的 Buyer 不得作为新项目的可采购主体（已有项目在途单可收敛）。

**项目状态** `BuyerProjectStatus`：

| 常量 | 库值 | 页面 |
| --- | --- | --- |
| `DRAFT` | 0 | 筹备中 |
| `ACTIVE` | 10 | 进行中 |
| `SUSPENDED` | 20 | 已暂停 |
| `CLOSED` | 30 | 已结束（可恢复） |
| `ARCHIVED` | 90 | 归档，页面不暴露 |

迁移：`DRAFT → ACTIVE|ARCHIVED`；`ACTIVE → SUSPENDED|CLOSED`；`SUSPENDED → ACTIVE|CLOSED|ARCHIVED`；`CLOSED → ACTIVE|SUSPENDED`。`CLOSED` 拒绝新受理；在途单可收敛。

**配送区域**：`NATIONWIDE` / `DESIGNATED`。

**发货时效** `shippingSlaCode`：`SAME_DAY` / `NEXT_DAY` / `HOURS_48` / `HOURS_72` / `CUSTOM`（`CUSTOM` 时 `shippingSlaCustom` 必填）。可空。

**结算方式** `settlementMethod`：`DAILY` / `WEEKLY` / `MONTHLY` / `QUARTERLY` / `PREPAID`。可空。

**对接方式** `integrationModes`：`API` / `H5` / `MANUAL`，多选数组。可空。

**供应价模式** `priceMode`：`FIXED` / `REALTIME` / `DISCOUNT`。管理端「新增供应商品」批量配置与合作商品行下拉统一读取 system 数据字典 `supply_chain_offer_price_mode`；本次只配置字典，不修改前端工程。字典只负责标签、排序与展示颜色，提交值必须是下表三个枚举名；供应链后端继续用 `OfferPriceMode` 做封闭校验，不在请求线程远程调用 system 服务。

| 字典值 | 页面文案 | 说明 |
| --- | --- | --- |
| `FIXED` | 固定值 | 直接填入固定供应价 |
| `REALTIME` | 实时更新 | 按供应商 API 实时返回价格（点餐/电影票） |
| `DISCOUNT` | 按供货折扣 | 按销售建议价 × 折扣率计算供应价 |

**合作商品状态**：`PUBLISHED` 上架、`SUSPENDED` 下架、`RETIRED` 已移除（列表不返回）。新增默认 `PUBLISHED`。管理端编辑已上架单价/模式只更新身份列，不写 OfferVersion（避免伪造缺连接器的版本）；商品列表与通用定价按身份列回退读取，见 `docs/superpowers/specs/2026-09-22-effective-supply-price-identity-fallback-design.md`。带连接器的可路由发布仍走既有 `SupplierOfferPublicationService`，那条链路改价必须发新 OfferVersion。

**分销等级** `distributionLevel`：`1` / `2` / `3`（仅组织深度，不是结算价层级）。

**合作模式** `cooperationModes`：`DIRECT` / `AGENT` / `CHANNEL`。

**分销商类型** `distributorType`：`ENTERPRISE` / `PERSONAL` / `PLATFORM`。

**供货择优倾向** `supplyPreference`：`STABLE` / `PRICE` / `COMPREHENSIVE`。只存档案，**不改变**路由引擎。本期标准商品路由保持既有静态优先级与原因码 `SELECTED_BY_STABLE_PRIORITY`，不启用 `PRICE_ASC` 或综合排序；见 09-12 范围对齐 s12。

**项目类型** `projectType` 以 PRD 8.3.1 为准，管理端可选值如下，只存档案：

| 字典值 | 页面文案 |
| --- | --- |
| `HOLIDAY_BENEFIT` | 节日福利 |
| `POINTS_REDEMPTION` | 积分兑换 |
| `EMPLOYEE_BENEFIT` | 员工福利 |
| `CUSTOMER_REWARD` | 客户回馈 |
| `FLEXIBLE_BENEFIT` | 弹性福利 |
| `OTHER` | 其他 |

历史值 `WELFARE` / `BENEFIT` / `CAMPAIGN` / `SUBSIDY` / `GROUP` 仅用于兼容迁移前数据，不进入管理端字典，不作为新增或编辑页面的可选项；数据库 CHECK 在兼容窗口内同时接受新旧值，避免已有记录阻塞发布，后续数据清洗须另立有明确业务映射的迁移任务。

#### 4.2.1 项目列表数据字典

项目管理列表查询栏的“项目类型”“项目状态”统一读取 system 数据字典，不在页面维护私有筛选常量：

| 查询字段 | 字典类型 | 字典值（标签） | 约束 |
| --- | --- | --- | --- |
| 项目类型 | `supply_chain_project_type` | `HOLIDAY_BENEFIT`（节日福利）、`POINTS_REDEMPTION`（积分兑换）、`EMPLOYEE_BENEFIT`（员工福利）、`CUSTOMER_REWARD`（客户回馈）、`FLEXIBLE_BENEFIT`（弹性福利）、`OTHER`（其他） | 多选；提交为 `projectType[]` |
| 项目状态 | `supply_chain_project_status` | `DRAFT`（筹备中）、`ACTIVE`（进行中）、`CLOSED`（已结束） | 多选；提交为 `status[]` |

`SUSPENDED`（已暂停）和 `ARCHIVED`（归档）仍是后端生命周期状态，但 PRD 8.3.1 未将其列为查询栏选项，因此不进入本字典。字典管理员可以调整标签、排序和展示颜色，但不得新增或修改键值；供应链后端继续以 `ProjectType` / `BuyerProjectStatus` 做封闭校验，请求线程不远程调用 system 服务。

**结算方** `settlementPartyType`：只接受 `DISTRIBUTOR`。`PLATFORM` → `PROJECT_PLATFORM_SETTLEMENT_NOT_ENABLED`。

**L2 调价方式** `l2AdjustMode`：`FOLLOW_TEMPLATE`（清除手工覆盖，结算价回退 L1；枚举名历史遗留，本期无模板）、`EQUAL_L1`（分销价=L1 平台默认定价）、`CUSTOM`（L1×percent/100 + amountCents）。现有页面「按分销价=销售建议价」对接 `EQUAL_L1`，见 a5。

**L3 调价方式** `l3AdjustMode`：`FOLLOW_L2`（不写 L3）、`EQUAL_L1`、`CUSTOM`。现有页面「按项目价=销售建议价」对接 `EQUAL_L1`（同样是 L1 结算分，不是 C 端销售价）。

**抬头类型** `titleType`（仅开票子资源）：`ENTERPRISE` / `PERSONAL`。详见开票设计。

非法常量 → `ENUM_NOT_OPEN`（替换原「非空即拒」的时效/结算/对接语义；未知值仍拒绝）。
`ClosedEnums` 抛出该码时，HTTP `msg` 必须带上枚举类型、实际提交值与允许值列表，例如
「未知枚举值：SellableCategory 不支持 FOO，允许值：EQUITY、PHYSICAL、MOVIE、JD、DINING、TAKEOUT、SUPERMARKET、HEMA、RTMART、LIFE」。
错误码目录短文案仍为「未知枚举值」，不得只把短文案返回给调用方。
SPU / 供货范围写入的 `productClassification` 不走 `ClosedEnums`：类目树 `category_code` 按 d34 落库，未知码为 `CATEGORY_NOT_FOUND`。

### 4.3 类目树与表单字典

`GET /supply-chain/categories`（树形集合，子节点嵌在 `children`；不要 `/tree`）仍返回已启用类目树，供主数据维护。

新增供应商供货配置、新增分销商、合作商品加购筛选**不读**该接口做祖先链校验。可售品类 / 商品类型选项来自 system 字典。
`productClassification` 写入允许字典枚举名或类目 `categoryCode`（见商品中心 d34）；命中仍按字符串全等。
不校验祖先链，不把 `equity` 等 mock 小写码或类目内部 id 当契约。详见
`docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md`。

`CATEGORY_CHAIN_INVALID` 仅保留给类目树主数据自身的父子关系；供货配置与分销商档案不再抛该码。

### 4.4 标品 SKU 分页

`GET /supply-chain/products/skus`

查询：`keyword`、`sellableCategory`、`productType`、`productClassification`、`pageNo`、`pageSize`。

`keyword` 为包含匹配，同时命中 SKU 名称、SKU 编码、SPU 名称、SPU 编码。新增分销商品弹窗的「名称/编号」搜索走该参数；省略或空白不筛选。

附加过滤：

- `supplierCode`：只返回 SPU 三码命中该供应商供货配置、且尚无未退役 Offer 的 SKU。
- `buyerCode`：只返回该分销商**尚未**关联的平台 SKU（新增分销商品）。不按分销商档案上的可售品类或商品类型再收窄；类型 Tab 由调用方传 `productType`。
- `projectCode`：只返回该项目所属分销商**已有** `BuyerProduct`、且项目目录尚未未退役关联的 SKU（新增项目商品）。缺少 `BuyerProduct` 的标品不得出现。

金额与展示字段：`suggestPriceCents`（L1 结算口径）、`marketPriceCents`（兼容既有 JSON 字段名；C 端建议价，实际取 `sc_platform_sku.suggested_sale_price`，若无则空）、`supplyPriceCents`（当前最低有效供货价，口径与通用定价最低供货价相同：供应商启用、Offer 已发布；当前版本有效则用版本单价，否则回退身份列，多条取最低；没有有效报价则为空，0 是有效价格，见 `docs/superpowers/specs/2026-09-22-effective-supply-price-identity-fallback-design.md`）、`categoryName`（SPU 主类目名称，归档或缺失则为空）。`categoryName` 只用于弹窗「分类」列展示，不参与筛选，不按类目祖先链过滤。`marketPriceCents` 不得取履约面值 `face_value` 或市场划线价 `market_price`；销售价来源以 09-12 范围对齐 §5 为准。点餐/电影 L1 可为 0。

商品管理聚合列表及新增分销商品选品只返回至少关联一个正常（`ACTIVE`）供应商未退役 Offer 的 SKU；暂停、黑名单、归档供应商及其独占商品不进入结果。新增供应商品选品 `excludeAddedSupplierOffers=true` 是例外，允许尚无任何供应商的 SKU 进入，以保留首个供应商关联闭环。所有场景的供应商展示与最低有效供货价均忽略非正常供应商。

## 5. 供应商

在现有 `SupplierAdminController` 上扩展筛选与字段，不改资源名。

### 5.1 列表 `GET /supply-chain/suppliers`

筛选：`supplierName`、`supplierCode`、`sellableCategories`（多，枚举名）、`supplyModes`（多）、`status`（多，枚举名）、`updatedFrom`、`updatedTo`、`pageNo`、`pageSize`。

名称下拉：同一集合加 `view=simple`，只返回状态为 `ACTIVE`（正常）的 `{ supplierCode, supplierName }[]`（可不分页）。暂停、黑名单、归档供应商不得作为商品列表筛选项。

列表行：`id`、`supplierCode`、`supplierName`、`status`、聚合自供货配置的 `sellableCategories[]` / `productTypes[]` / `productClassifications[]` / `supplyModes[]`（均为枚举名）、`skuCount`（未退役 Offer 数）、`onboardedAt`、`updatedAt`、主联系人 `contactName`/`contactMobile`（原值回显）。

行操作对应：详情、编辑、暂停、恢复、移入/移出黑名单。批量暂停/恢复：`PATCH /supply-chain/suppliers` body `{ supplierCodes, status }`，`status` 仅 `SUSPENDED` 或 `ACTIVE`。

### 5.2 详情 / 新增 / 编辑

- `GET /supply-chain/suppliers/{supplierCode}`
- `POST /supply-chain/suppliers`
- `PUT /supply-chain/suppliers/{supplierCode}`

字段对齐前端表单，JSON 用后端名：

| 前端 mock | 后端 |
| --- | --- |
| `name` | `supplierName` |
| `code` | `supplierCode`（创建忽略，系统生成 `SPL_` 前缀，保持现实现） |
| `contacts[].name/mobile/email` | 同左，至少一行非空（姓名+手机号） |
| `deliveryAreaType` | `deliveryRegionType` |
| `deliveryAreaId` | `deliveryRegionAdcodes[]`（6 位 adcode；`DESIGNATED` 至少 1 个）；详情另回 `deliveryRegions[{adcode,name}]`，将编码与行政区划名称配对 |
| `shippingTimeliness` | `shippingSlaCode` |
| `shippingTimelinessCustom` | `shippingSlaCustom` |
| `supportAfterSale` | `aftersalesSupported` |
| `settlementType` | `settlementMethod` |
| `settlementCycle` | `settlementCycleDays` |
| `integrationMethods` | `integrationModes[]` |
| `accessDate` | `onboardedAt` |
| `cooperationRemark` | `cooperationNote`（≤200） |
| `companyName` | `legalEntityName` |
| `socialCreditCode` | `creditCode`（与公司名联合唯一） |
| `businessLicenseUrls` | `licenseFileKeys[]`（最多 5） |
| `legalIdUrls` | `idFrontFileKey` + `idBackFileKey` |
| `otherCertUrls` | `qualificationFileKeys[]`（最多 5） |
| `accountBalance` | `openingBalanceCents`，**仅创建可写**（含 0）；编辑再带 → `SUPPLIER_OPENING_ALREADY_DONE`。GET 回只读 `accountBalanceCents`，兼容保留同值的 `settlementBalanceCents` |

详情响应还包含只读聚合字段：`spuCount` 为该供应商未退役 Offer 关联的平台 SPU 去重数，对应详情页“合作商品数（SPU）”；`scopeCount` 为供货配置行数；`primaryContactName` / `primaryContactMobile` 为主联系人；`accountBalanceCents` 为供应商当前账本余额，单位为 CNY 分，历史字段 `settlementBalanceCents` 返回相同值；`deliveryRegions` 将每个 `deliveryRegionAdcodes` 元素映射为 `{adcode,name}`，未知编码保留 `adcode` 且 `name` 为空。供应商列表的 `skuCount` 仍表示未退役合作商品 SKU 数，两种口径不得混用。

`GET /supply-chain/suppliers/{supplierCode}` 的 `SupplierDetail` 及其联系人、供货配置子项必须为每个对外 JSON 属性提供中文 OpenAPI `description`；枚举写明允许值，金额写明单位，`spuCount` 明确上述 SPU 去重统计口径，避免 Swagger/Knife4j 只显示字段名而无法判断业务语义。

#### 5.2.1 新增供应商数据字典

“新增供应商”表单的封闭选项统一由 system 服务的数据字典提供，不再把选项值视为前端私有常量。本任务只交付字典类型、字典数据及后端契约；前端读取字典、替换静态常量和完成类型转换由独立前端任务实施。

| 表单字段 | 字典类型 | 字典值（标签） | 控件与转换约束 |
| --- | --- | --- | --- |
| 配送区域 | `supply_chain_delivery_region_type` | `NATIONWIDE`（全国）、`DESIGNATED`（指定区域） | 单选；指定区域后的省市区 adcode 仍来自地区树接口，不进入字典 |
| 发货时效 | `supply_chain_shipping_sla_code` | `SAME_DAY`（当日发）、`NEXT_DAY`（次日发）、`HOURS_48`（48小时发）、`HOURS_72`（72小时发）、`CUSTOM`（自定义） | 单选；选择 `CUSTOM` 时继续填写 `shippingSlaCustom` |
| 是否支持售后 | `supply_chain_aftersales_supported` | `true`（是）、`false`（否） | 单选；system 字典值均为字符串，提交前必须显式转换为 Boolean |
| 结算方式 | `supply_chain_settlement_method` | `DAILY`（日结）、`WEEKLY`（周结）、`MONTHLY`（月结）、`QUARTERLY`（季结）、`PREPAID`（预付） | 单选 |
| 对接方式 | `supply_chain_integration_mode` | `API`（API直连）、`H5`（H5直连）、`MANUAL`（人工对接） | 多选，提交为 `integrationModes[]` |
| 合作状态 | `supply_chain_supplier_status` | `ACTIVE`（正常）、`SUSPENDED`（暂停供货）、`BLACKLIST`（黑名单） | 单选；`ARCHIVED` 是归档态，不允许在新增页面选择 |

字典值以本节和后端封闭枚举为准，前端现有 `nationwide`、`specified`、`same_day`、`48h` 以及状态 `1/2/3` 仅是历史 mock 值，不得写入 system 字典。字典管理员可以调整标签、排序和展示颜色，但不得新增或修改键值；否则选项虽可能被 `/system/dict-data/simple-list` 返回，供应链接口仍会按封闭枚举拒绝。

分销商表单、供货配置三级编码与 SPU 标签见
`docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md`（system 变更集 `002-admin-form-dictionaries`，不回改本节 `001-supplier-form-dictionaries`）。

字典迁移由 system 服务自己的 Liquibase 管理：主清单为 `aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/db.changelog-master.yaml`，变更集 SQL 为 `releases/1.0.0/001-supplier-form-dictionaries.sql`。system-server 引入 Liquibase 后，以主数据源执行该变更集并写入 `DATABASECHANGELOG`；不再通过 `script/deploy` 下的环境专用 SQL 作为正式迁移入口。SQL 自身仍按 `type` 和 `dict_type + value` 幂等，兼容上线前已经人工配置过这些字典的环境，并且只更新上述六类及其已知键值。数据字典由 Yudao system 服务全局管理，不按租户复制。

`capabilityTags`（生产型等）前端有、PRD 未进供货内核：本期不落库，忽略入参。

### 5.3 状态

`PATCH /supply-chain/suppliers/{supplierCode}` body `{ "status": "SUSPENDED" }`（或 `ACTIVE` / `BLACKLIST`）。二次确认由前端做，后端幂等条件更新。不要 `PUT .../status`。

### 5.4 供货配置

- `GET /supply-chain/suppliers/{supplierCode}/supply-scopes`
- `PUT /supply-chain/suppliers/{supplierCode}/supply-scopes` 全量替换

行：`sellableCategory`、`productType`、`productClassification`、`categoryId`、`supplyModes[]`、`maxQuantity`、`minQuantity`。三码与模式、数量缺一拦截；`maxQuantity`、`minQuantity` 分别只校验正整数，不建立大小关系。`minQuantity` 是允许最低起订量预留字段，仅存储、回显，不参与当前业务判断。改配置不改已发布 Offer；收窄只阻止新关联。命中规则见字典专文。

`categoryId` 是查询接口的回显增强字段（2026-09-21 新增，只读）：`productClassification` 为未归档类目编码（`sc_product_category.category_code`）时回填其内部主键，供前端类目树级联组件回显；为封闭枚举名（`ELECTRONICS`/`VIRTUAL_CARD`/`MOVIE_TICKET`）、类目编码已删除或类目已归档时为 `null`（归档即视为不可回显，前端兜底展示编码原样）。批量解析一次查库，不引入 N+1。`PUT` 保存接口不接收该字段，写入口径仍按 `productClassification` 编码提交。

### 5.5 合作商品

- `GET /supply-chain/suppliers/{supplierCode}/offers` 分页，筛选 `sellableCategory`/`productType`/`productClassification`
- `POST /supply-chain/suppliers/{supplierCode}/offers` body `{ items: [...] }` 批量新增，默认 `PUBLISHED`。未退役的同一 SKU 再提交仍拒绝，不改成更新。新增供应商品选品走 `GET /supply-chain/products/management-page`，并传 `excludeAddedSupplierOffers=true` 与 `supplierCode` 排除未退役供应商品；省略该开关时 `supplierCode` 仍表示只看该供应商已供商品。已退役 SKU 会再次出现。不按供货配置三码收窄，也不改变 `buyerCode` 排除未移除分销商品。`supplierCode` 空白返回 `SUPPLIER_CODE_REQUIRED`，供应商不存在返回 `SUPPLIER_NOT_FOUND`。
- `PUT /supply-chain/suppliers/{supplierCode}/offers/{offerNo}` 编辑（上架中改价只更新身份列，不写 OfferVersion；列表与定价按身份列回退）
- `PUT /supply-chain/suppliers/{supplierCode}/fulfillment-scores` 人工录入稳定性/时效评分（可按 SKU 子集）
- `PATCH /supply-chain/suppliers/{supplierCode}/offers/{offerNo}` `{ "status": "PUBLISHED"|"SUSPENDED" }` 上架/下架
- `DELETE /supply-chain/suppliers/{supplierCode}/offers/{offerNo}` 逻辑移除（`RETIRED`，列表不再返回）

行字段：平台 SPU/SKU 编码与名称、SPU 三码（`sellableCategory`/`productType`/`productClassification`）、`suggestPriceCents`、`priceMode`（字典 `supply_chain_offer_price_mode`）、`unitPriceCents`、`discountBp`（10000=100%）、`offerSupplyMode`、`maxQuantity`、`minQuantity`、`status`。`FIXED` 单价>0；`DISCOUNT` 折扣 ∈[1,10000] 且建议价>0；`REALTIME` 禁止填固定价。必须按三码整体唯一命中一条供货配置。

SKU 未填供货上限时继承供货配置 `maxQuantity`；未填最低起购量时固定取 1。供货配置 `minQuantity` 是预留字段，不得被合作商品继承。

履约能力评分挂点、批量评分与商品列表弹窗契约见 `docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md`。`PUT /supply-chain/suppliers/{supplierCode}/fulfillment-scores` 可只改稳定性或时效性一列，省略的列保持原值；新建合作商品默认 80；改价 `PUT .../offers/{offerNo}` 不碰评分。

### 5.6 开票信息

`GET/PUT /supply-chain/suppliers/{supplierCode}/invoice`。字段、预填、校验与分销商相同，见
`docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md`。

## 6. 分销商（Buyer）

新建 `BuyerAdminController`，映射 `/supply-chain/buyers`。不访问 Adapter，不改 `appId` 语义。

### 6.1 组织树

表 `sc_buyer` 增加 `parent_buyer_id`（可空）、`distribution_level`（1–3）。

规则：

1. 一级：无上级，`level=1`。
2. 二级：上级必须是一级。
3. 三级：上级必须是二级。禁止四级。
4. 禁止指向自己或子孙（环）。
5. 入参 `distributionLevel` 与上级深度必须一致，否则 `BUYER_PARENT_INVALID`。
6. 已有下级时禁止把等级改得与子节点冲突（例如一级改二级却仍挂着二级子节点）。
7. GET 下级返回当前分销商的全部后代节点，包括直属下级和孙级；组织树最多三级，因此最多向下查询两层。接口返回扁平列表，不在响应中嵌套子节点，层级关系以 `distributionLevel` 表达，层级页只读。
8. 下级列表排除 `ARCHIVED` 分销商，按 `createdAt DESC, id DESC` 全局稳定排序。查询后代时仍须沿全部结构节点向下遍历，避免归档的中间节点导致其未归档后代不可见。
9. 历史兼容：下级查询读取到当前分销商 `distributionLevel` 为空时按一级处理，仍最多向下查询两层。该兼容只用于读取历史数据，不放宽数据库 `NOT NULL`、`1–3` 检查约束和新增/编辑校验。

结算：不按树拆账、不继承上级账户。L2 按**该 Buyer 自己**的模板或 SKU 覆盖解析，不自动继承上级 L2。

### 6.2 列表与档案

`GET /supply-chain/buyers` 筛选：`buyerName`、`buyerCode`、`distributionLevels`、`cooperationModes`、`sellableCategories`、`status`、`updatedFrom`/`updatedTo`。名称筛选是包含匹配。

默认分页（不带 `view`）按组织树返回。根列表只放一级分销商；一级的 `children` 是它的二级分销商，二级的 `children` 是它的三级分销商，三级的 `children` 为空数组。列表行不返回 `parentBuyerCode`。`pageNo`、`pageSize`、`total` 只统计一级；二级和三级挂在对应一级下面，不占分页名额。同一层按 `updatedAt DESC, id DESC` 排序。`distributionLevel` 为空的历史数据按一级参与筛选和挂接。

筛选决定命中节点，不把树拍扁后再过滤：

1. 命中节点必须同时满足全部筛选条件。`cooperationModes`、`sellableCategories` 各自内部是「命中任一」，两个条件同时出现时取交集。
2. 命中一级时，带上该一级下全部能挂接的二级和三级。
3. 命中二级或三级时，带回其一级祖先。祖先本身可以不满足筛选条件。`children` 只保留通往命中节点的分支，命中节点的全部可挂接下级仍保留；不在这条分支上的同级不返回。
4. 无法沿未归档的「一级 → 二级 → 三级」挂接的节点从列表丢掉。上级已归档、上级不存在、等级和上级深度不一致，都算挂不回。三级不能直接挂到一级下面。
5. `GET /buyers/{buyerCode}/children` 仍是扁平后代列表，口径见 6.1，不随这次列表嵌套改变。

投影（同一集合，不用独立路径）：

- `view=simple`：名称/编号下拉 `{ buyerCode, buyerName }`
- `view=parent-options`：`excludeBuyerCode`、`forLevel`，供「上级分销商」下拉

`GET /supply-chain/buyers/{buyerCode}`、`POST /supply-chain/buyers`、`PUT /supply-chain/buyers/{buyerCode}` 档案字段对齐前端分销商表单：名称、联系人、分销品类/类型（`sellableCategories[]` / `productTypes[]` 枚举名，不是类目 id）、合作模式、类型、择优倾向、结算方式/周期、对接方式、合作开始日、说明、公司/信用代码/法人/地址/银行/证件、期初 `openingBalanceCents`（创建一次，允许 0）。详情中的 `legalPersonMobile`、`bankAccount`、`contacts[].mobile`、`primaryContactMobile` 及证件 fileKey 均按原值回显，不提供 `legalPersonMobileMasked` / `bankAccountMasked` 字段。字典见专文 §3。

详情响应中的 `productTypes[]` 为对象数组，字段固定为 `{ id, name }`：按已保存的 `scope_kind=PRODUCT_TYPE` 范围编码关联 `sc_product_type.type_code`，`id` 取商品类型业务编码，`name` 取商品类型名称，不暴露商品类型表内部自增主键。创建/编辑请求的 `productTypes[]` 仍提交商品类型业务编码；列表响应继续返回编码数组，避免改变列表筛选与既有投影契约。

分页列表行除基础档案字段外，还返回以下只读字段。数量和集合按**本次响应里可见的子树**汇总，不把被筛选掉的同级算进去：

- `children`：直属下级。一级装二级，二级装三级，三级为空数组；
- `productTypes[]`：本节点与可见下级的并集，来源仍是 `sc_buyer_category_scope` 中 `scope_kind=PRODUCT_TYPE` 的商品类型编码。`sellableCategories[]` 同样取并集，并且只读取 `scope_kind=SELLABLE`。两类范围互相独立，禁止相互推导。并集保留本节点原有顺序，再按子节点顺序追加未见过的值；
- `cooperationModes[]`：本节点与可见下级的合作模式并集，顺序规则与品类相同；
- `projectCount`：本节点未归档项目数，加上 `children` 已汇总的项目数。`ARCHIVED` 项目不计入；
- `skuCount`：本节点有效分销商品数，加上 `children` 已汇总的商品数。已移除分销商品不计入；
- `cooperationTime`：列表展示用合作时间，与详情 `cooperationStartedAt` 取同一回显口径，来源是 `sc_buyer.cooperation_started_at`。管理端日期选择器空值常以 Unix 0 提交。`null`、Unix 0、年份 `<=1970` 视为未填：创建回落当前业务时间（Asia/Shanghai），更新忽略该字段以免用空值覆盖已有合作开始时间；列表与详情读取历史脏值时回落到 `created_at`。已填写且年份晚于 1970 的值原样回传。名称、状态、等级、联系人和更新时间不参与汇总，仍是该节点自己的值。

列表先一次读出未归档分销商，在内存中筛选、挂接和分页；合作模式、可售品类、商品类型按这批分销商各查一次。项目数、SKU 数和主联系人只按当前页可见节点批量读取，禁止为每行追加查询。详情的 `sellableCategories[]` / `productTypes[]` 只返回已经保存的独立范围行，不按组织树汇总；历史记录缺少 `SELLABLE` 行时返回空数组，由前端按新字段重新保存或由有明确业务映射的数据修复任务补齐，后端不得根据商品类型猜测分销品类。

由于同一集合接口还兼容 `view=simple` 与 `view=parent-options` 投影，Java 运行时返回类型允许保持 `CommonResult<?>`；但 OpenAPI 的默认 `200` 响应必须显式声明为 `CommonResult<PageResult<BuyerListItem>>` 对应的具体文档模型，不能退化为 `CommonResultObject`。Swagger / Knife4j 的默认分页响应必须能展开 `BuyerListItem`，并展示上述全部列表字段。

期初写入既有采购商唯一账本，禁止写入供应商结算账户。管理端创建不要求前端传 evidence 文件；服务端使用受控 `entry_key=ADMIN_OPENING:{buyerId}:CNY`（或等价不可伪造键）走一次性开户。已存在账户再带余额 → 与供应商相同语义的已开户错误。编辑页余额只读。

`PATCH /supply-chain/buyers/{buyerCode}` `{ "status": "SUSPENDED"|"ACTIVE"|"BLACKLIST" }`。批量：`PATCH /supply-chain/buyers` `{ buyerCodes, status }`（暂停/恢复）。

OpenAPI 请求模型必须保持资源语义唯一：分销商批量状态请求模型命名为
`BuyerBatchStatusReqVO`，供应商批量状态请求模型命名为 `SupplierBatchStatusReqVO`，禁止使用会在
Springdoc `components.schemas` 中冲突的通用简单类名。生成契约必须分别展示 `buyerCodes` 与
`supplierCodes`，不得让两个接口引用同一请求 schema。

`GET /supply-chain/buyers/{buyerCode}/children`：全部下级列表（只读子资源），返回直属下级和孙级，不包含当前分销商。响应行为与字段约定如下：

- 返回扁平列表，按分销商 `createdAt DESC, id DESC` 全局稳定排序，不按父节点分组；
- 排除状态为 `ARCHIVED` 的下级，但查询后代时不因归档的中间节点中断遍历；
- 每行返回 `buyerCode`、`buyerName`、`distributionLevel`、`cooperationModes[]`、`projectCount`、`status`、`createdAt`；
- `projectCount` 只统计该分销商未归档项目，`ARCHIVED` 项目不计入；
- 合作模式和项目数必须针对本次结果集批量读取，禁止逐行 N+1 查询；
- 组织树最多三级：一级分销商最多执行直属二级、直属三级两次后代查询，二级分销商最多执行一次直属三级查询，三级分销商直接返回空列表。

`sc_buyer` 应建立以 `parent_buyer_id` 为前导列并覆盖创建时间稳定排序的索引，支撑逐层后代查询。

`GET /supply-chain/projects?buyerCode=`：该分销商的项目（不要再单独做 `GET /buyers/{code}/projects`，避免两套列表）。

编号：创建时系统生成 `buyerCode`（保持现有生成规则；若无管理端生成则新增 `BYR_` 前缀，与供应商 `SPL_` 并列）。前端 mock 的 `18-001` 格式不作为后端格式。

### 6.3 分销商品

即 `BuyerProduct` + 该 SKU 的 L2。

- `GET /supply-chain/buyers/{buyerCode}/products` 分页。可选查询参数 `productType`（动态商品类型编码，可多选）。兼容单个值；重复键 `productType=A&productType=B` 或逗号拼接 `productType=A,B` 均表示命中任一类型。省略、空白或拆分后无有效编码时不筛选；传入后只返回该分销商未移除、且关联平台 SPU 的 `productType` 落在入参集合内的分销商品。无命中返回空页。筛选不改变按 `updatedAt` 倒序，分页默认仍为 `pageNo=1`、`pageSize=20`。命中字段取列表行同源的平台 SPU，而不是供货配置。
- 项目新增商品弹窗调用同一分页接口时传可选 `projectCode`。后端先校验项目存在且归属路径中的 `buyerCode`；项目不存在或不属于该分销商均返回 `PROJECT_NOT_FOUND`。只排除**该项目** `sc_project_listing.status=AVAILABLE` 所引用的 `buyer_product_id`，不排除同一分销商其它项目的商品；已移除 listing 对应商品重新可选。排除条件与 `productType` 一起在数据库分页前生效，`total` 为过滤后数量。省略或空白 `projectCode` 保持原有分销商品列表语义及响应字段，不从请求来源或会话推断项目。项目商品详情列表仍走项目商品接口。
- 同一分页接口为项目新增商品弹窗增加可选 `keyword`（页面「商品 名称/编号」）和 `categoryCode`（页面「商品分类」）。`keyword` 去首尾空格后，对当前平台 SPU 的 `spu_name`、`spu_code` 及 SKU 的 `sku_name`、`sku_code` 做大小写不敏感的字面子串匹配，任一字段命中即可；空白不筛选，`%`、`_` 等输入按普通字符处理。`categoryCode` 是 `sc_product_category.category_code`；由 catalog 应用层的 `ProductCategoryCoverageService` 解析该类目及全部下级类目的 ID，分销商品查询不直接访问类目 Mapper，只匹配 `sc_platform_spu.primary_category_id` 落在此集合的商品。未知类目返回空页，不把类目编码当 `product_classification` 比较。两项条件与 `productType`、项目已加入商品排除条件取交集，均在数据库分页与计数前生效；`total` 为最终命中数量，排序沿用分销商品 `updated_at` 倒序。无新增条件的旧请求与金额响应保持原义。前端搜索、重置及分页直接传 `keyword`/`categoryCode`，停止对当前页做本地筛选。
- `POST /supply-chain/buyers/{buyerCode}/products` body `{ items: [...] }` 从标品关联；已存在则 `BUYER_PRODUCT_DUPLICATE`。新增分销商品弹窗的选品列表走 `GET /supply-chain/products/management-page`，并传入 `buyerCode` 排除未移除商品；「全部商品」由调用方把该分销商档案中的商品类型编码放进 `productType`。每行可带可选 `pricing`，字段与 `PUT /buyers/{buyerCode}/products/pricing` 的覆盖组相同。`pricing` 省略时只关联并按商品默认定价初始化；传入时在同一事务内保存分销商通用定价。任一条缺少平台默认定价时整单回滚，返回 `PLATFORM_DEFAULT_PRICING_MISSING`。销售价与服务费仍可标记继承。
- `PATCH /supply-chain/buyers/{buyerCode}/products/{buyerProductCode}` 改价（写/清 L2 覆盖，body 含 `l2AdjustMode` 等）
- `DELETE /supply-chain/buyers/{buyerCode}/products/{buyerProductCode}` 逻辑移除（不再出现在列表；已发布项目目录不回放删除，新项目不得再选）

列表列：SPU/SKU、SPU 三码、`marketPriceCents`（平台 SKU 市场划线价/吊牌价）、`suggestedSalePriceCents`（平台 SKU 销售建议价，取 `sc_platform_sku.suggested_sale_price`，0 为有效值）、`suggestPriceCents`（历史 L1 兼容字段，仍只取旧 L1）、`platformBasePriceCents`（项目旧调价模式的 B 端平台基价）、`pricingRule`、`taxIncluded`、`taxRateCode`、`taxRateDisplay`、`distPriceCents`、`projectPriceCents`、`finalPriceCents`、`priceSource`、`projectPriceCovered`。`platformBasePriceCents` 在当前分销商通用定价版本存在时取该版本引用的平台默认定价版本 `distribution_price_cents`，不得与另一个平台版本混读；没有通用版本的历史行取旧 L1。自通用定价启用后，`distPriceCents`、`pricingRule` 及 `finalPriceCents` 的分销商层输入优先取分销商 SKU 当前通用定价版本；仅当前通用版本缺失的历史行回退旧 L1/L2 链。通用版本存在但分销价或平台版本引用缺失时，列表当前分销价留空，无 L3 覆盖时最终价也留空，不回退旧价；旧 `suggestPriceCents` 仍可独立回显历史 L1。`pricingRule` 对通用定价展示“继承商品默认 / 等于市场价 / 自定义公式”，旧链仍兼容“继承L1 / 等于L1 / 自定义公式”。项目覆盖与最终价的详细优先级、税务快照及历史回退规则见 `docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#6-管理端接口`。列表不计算、不返回不含税分销价或税额字段。

项目新增商品弹窗的“销售建议价”列展示 `suggestedSalePriceCents`；调价公式与“等于 L1”的 B 端基数展示 `platformBasePriceCents`，文案标为“平台默认分销价”，不得将 C 端销售建议价或市场价代入结算公式。旧 `suggestPriceCents` JSON 字段保留原义，避免存量调用方静默改价。

`displayThresholdPercent` 可存（利润空间低于该百分比时 C 端隐藏的配置），**本轮不实现 C 端隐藏消费**，只落库。新增时省略则落空；改价时省略则不改该列。传入时必须是 0～100 的整数，否则 `L2_OVERRIDE_INVALID`。

前端未传 `l2AdjustMode` 时默认 `FOLLOW_TEMPLATE`。页面默认选项「按分销价=销售建议价」必须显式传 `EQUAL_L1`，覆盖值取当前 `suggestPriceCents`（L1），禁止用 `marketPriceCents`。

### 6.4 开票信息

`GET/PUT /supply-chain/buyers/{buyerCode}/invoice`。见
`docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md`。

## 7. 项目

新建 `ProjectAdminController`，映射 `/supply-chain/projects`。内部聚合仍是 `BuyerProject`。

### 7.1 档案

`GET /supply-chain/projects` 筛选：`projectName`、`projectCode`、`status[]`、`buyerCode`、`projectType[]`、`periodFrom`/`periodTo`。

`GET /supply-chain/projects?view=simple`：名称/编号下拉。

普通分页列表每行返回只读 `skuCount` 和 `projectPriceSkuCount`，与项目详情采用同一统计口径：只统计该项目 `status=AVAILABLE` 的项目商品；项目定价覆盖按当前通用定价版本显式覆盖分销价或旧冻结版本 L3 取并集，同一 SKU 只计一次。列表仅对本页项目批量读取商品、定价身份及当前版本，不逐卡调用详情，也不把缺失字段显示成真实的零。

`GET /supply-chain/projects/{projectCode}`、`POST /supply-chain/projects`、`PUT /supply-chain/projects/{projectCode}`：`projectName`、`buyerCode`、`projectType`、`startDate`、`endDate`、`settlementPartyType`（仅 `DISTRIBUTOR`）、`description`（≤500）。创建允许 `DRAFT` 或 `ACTIVE`（`POST` body 可带 `status`）；整档更新 body 可选带 `status`，未传时保持原状态，传入时按第 4.2 节状态机执行迁移，目标状态与当前状态相同时按幂等保存处理。`skuCount`、`projectPriceSkuCount` 只读统计。

`catalogMode` 默认 `MANAGED`，本批页面不展示。`appId`/`appKey` 本批不返回（避免密钥进入管理端列表）。

归属：项目创建后 `buyerCode` 永久不可变，`DRAFT` 也不得改归属；任何状态下显式提交不同分销商均返回 `PROJECT_BUYER_IMMUTABLE`。原草稿态可改归属的例外由[2026-09-27 业务编码规范化设计 §4](./2026-09-27-supply-chain-business-code-design.md#4-归属与不可变性)覆盖。

`PATCH /supply-chain/projects/{projectCode}` `{ "status": "ACTIVE"|"SUSPENDED"|"CLOSED" }` 按第 4.2 节迁移，作为状态操作的兼容入口；`PUT` 传入 `status` 时执行相同状态机校验。页面按钮：启动、暂停、恢复、结束。

### 7.2 项目商品

即 `ProjectListing` + L3 覆盖。

- `GET /supply-chain/projects/{projectCode}` 详情增加关联分销商档案配置的 `productTypes[]`（`{id,name}`），名称与分销商详情同源；由项目查询权限返回，避免项目页面额外依赖分销商查询权限。项目详情「分销商品」展示「全部商品」及这些商品类型 Tab。「全部商品」传全部非空类型编码，档案未配置类型时省略筛选参数。切换 Tab 时重置到第一页，说明区、商品列表及新增按钮仍在原位置；本次不改新增商品弹窗。
- 项目商品分页新增可选 `productType`（动态类型编码，单值、重复键或逗号拼接均可，命中任一类型）；省略、空白或拆分后无有效编码时不筛选。服务端先限定当前项目未移除 listing，再沿 `buyer_product_id → platform_sku_id → platform_spu.product_type` 在数据库分页前筛选，`total` 与分页均按筛选后的集合计算，排序仍按 listing `updated_at` 倒序；沿用 `pageSize=-1` 不分页时返回全部命中行及其真实 `total`。类型来自平台 SPU，不使用分销商档案类型替代商品真实类型；无命中返回空页。不传类型时筛选、分页和原有金额字段口径保持不变。
- 项目新增商品待选分页使用 `GET /supply-chain/buyers/{buyerCode}/products?projectCode={projectCode}`；由服务端按项目排除已加入商品并保持当前分销价等列表字段，不在前端用当前页项目商品过滤。
- `POST /supply-chain/projects/{projectCode}/products` body `{ items: [...] }`：SKU 必须已是所属 Buyer 的未移除分销商品，否则 `PROJECT_SKU_NOT_IN_BUYER_CATALOG`。默认 `l3AdjustMode=FOLLOW_L2`（不写 L3）。批量配置可同时带 L3。
- 每个 `items[]` 可选带 `pricing`，字段沿用新增分销商品的五组继承/覆盖请求，含 `distributionHideThresholdPercent` 与税务字段；省略时初始化全继承项目通用定价，传入时在新增 listing 的同一供应链事务内保存项目通用定价，任一行定价失败整批回滚。`pricing.distributionInherited=false` 且规则完整时，当前项目通用定价版本的 `distribution_price_cents` 是该 SKU 的项目专属价。旧 `l3AdjustMode` 与行内通用定价不得在同一行同时使用，避免两套 L3 来源冲突。
- `PATCH /supply-chain/projects/{projectCode}/products/{listingCode}` 改价（写/清 L3，发新 `ProjectListingVersion`）
- `DELETE /supply-chain/projects/{projectCode}/products/{listingCode}` 逻辑移除（目录项不再可采购；历史订单仍读冻结版本）

新增即对项目可采购（等价上架）。项目商品行沿 `listing.buyer_product_id → sc_buyer_product.platform_sku_id → sc_platform_sku.spu_id → sc_platform_spu` 增补 `spuCode`、`spuName`、`productType`、`productTypeName`、`productClassification`、`categoryName`、`marketPriceCents`：SPU 编码、名称、类型编码、供货命中分类码取当前 SPU 主数据；类型名称取 `sc_product_type.type_name`；分类显示名称取 SPU 主类目 `sc_product_category.category_name`；市场价取当前 SKU 的 `market_price`（市场划线价/吊牌价，CNY 分），不得由 `suggested_sale_price`、旧 L1 或 B 端分销价推算。关联主数据缺失时对应展示字段返回空值，0 分市场价是有效值；类型名称或类目名称缺失时保留编码供页面兜底显示。只增加列表展示字段，不改价格计算和冻结版本。列表原有金额：`suggestPriceCents`（历史 L1 兼容）、`suggestedSalePriceCents`（C 端 SKU 建议价）、`platformBasePriceCents`（B 端项目调价基数）、`distPriceCents`（当前有效分销价）、`projectPriceCents`、`finalPriceCents`、`priceSource`、`projectPriceCovered`。保留 `l3AdjustMode=EQUAL_L1` 请求枚举，但页面文案改为“等于平台默认分销价”；`CUSTOM` 以同一 B 端基数计算。

项目商品当前通用定价版本明确覆盖分销价（`distributionInherited=false`）时，列表 `projectPriceCents` 与 `finalPriceCents` 取该版本的 `distribution_price_cents`，`priceSource=L3`、`projectPriceCovered=true`；若计算结果因依赖缺失为空，金额保持空值，不回退旧冻结价。通用版本全继承时，旧冻结金额字段仍按原语义读取，旧 L3 覆盖仍显示为专属价。`projectPriceSkuCount` 与列表采用同一覆盖判断，对通用覆盖与旧 L3 取并集，每个 listing 只计一次。此处读取版本快照，不重算价格或改写历史订单。

项目商品分页行追加 `projectPricingConfig`：返回该 listing 的 `sc_project_sku_pricing.current_version_id` 所指向版本的 `override_json`，映射为与 `ProjectOverrides` 同字段、同值的强类型响应对象。对象保留分销价、销售价、服务费、利润隐藏、税务五组各自的 `*Inherited` 开关和覆盖规则；税务返回服务端归一化并保存后的元数据。已有综合定价版本且全部继承时返回五组 `*Inherited=true` 的对象；没有当前综合定价版本的历史行返回 `null`，不得以旧 `ProjectListingVersion` 猜测通用规则。只读取当前指针，不按最大版本号选取，也不以 `effective_config_json` 中的继承结果冒充项目覆盖配置；当前指针悬空、版本不属于该定价身份或 JSON 损坏时应报错，不静默降级。先限定项目并分页 listing，再批量读取本页定价身份与当前版本，避免逐行增加查询。此字段只增补查询响应，原有冻结价字段及订单快照语义不变；读取不触发重算、版本发布或外部字典调用。

该 GET 的 OpenAPI/Swagger 响应须提供具体的 `code`、`msg`、`data.total`、`data.list[]` 模型，列表每个字段均有业务说明；`projectPricingConfig` 的实际 Java 响应字段直接使用带注解的强类型模型，不依赖 `@Schema(implementation=...)` 将领域记录伪装为另一种类型。Swagger 必须展开五组继承标记、覆盖规则以及公式、服务费、利润隐藏和税务的子字段，说明 `true` 继承直接上游、`false` 使用该组覆盖。继承组的规则通常为 `null`，但分销价、销售价、服务费和利润隐藏组若提交时同时带有规则，保存记录可能保留该规则；响应须原样回显，而价格计算会忽略该组规则。税务组经请求归一化后，继承时元数据为 `null`。公式说明 `(基数 × percentage / 100) operator operand`、基数和运算符枚举、加减的元与乘除系数单位；服务费说明以商品小计为基数；利润隐藏说明百分数与阈值范围；税务说明是否含税、字典编码与保存时解析的名称。文档专用外层响应模型只描述现有 JSON 契约，不改变接口签名或保存逻辑；契约测试校验实际字段类型、生成的 schema 与序列化 JSON 一致，避免 Knife4j 将泛型分页响应或嵌套配置展示为无具体字段的对象。

`200` 响应须提供可直接查看的完整 JSON 示例，包含响应包装、分页、列表行所有字段和五个非空规则对象的全部子字段；继承形态通过五组字段说明和联调场景解释。示例中的金额单位为分，公式运算值单位遵循各规则说明；旧冻结价字段与新项目综合定价覆盖配置应明确区分。测试环境 Knife4j 4.5.0 对 OpenAPI `content.examples` 中已解析为 JSON 对象的 `value` 不渲染“响应示例”，故该接口将完整示例放在响应 `schema.example`，不用多个 `content.examples`；对象引用字段不标注会生成 `type: null` 的 `nullable=true`，避免 Knife4j 将 `projectPricingConfig` 及规则对象显示成 `null` 类型。以上仅修正文档生成，运行时配置为 `null` 的 JSON 语义不变。契约测试解析示例 JSON，对照实际响应模型的序列化字段集合逐层校验，并断言引用字段不会生成 `type: null`，防止示例遗漏字段或再次空白。

新增、改单条 L3 与旧批量 L3 试算/发布共用价格来源：存在分销商 SKU 当前通用定价版本时，`FOLLOW_L2` 冻结其 `distribution_price_cents`，按该版本的分销价继承标记保留 L1/L2 来源；`EQUAL_L1`/`CUSTOM` 的基数取该分销商版本引用的平台默认定价版本 `distribution_price_cents`。通用版本存在但所需金额为空或被引用的平台版本缺失时失败关闭，不回退旧价；只有分销商通用版本缺失的历史行才沿用旧 L1/L2。项目商品列表的当前分销价使用同一来源，已发布的项目冻结版本与历史订单不得回写。

改 L3 或改分销商 L2 覆盖**不回放**已发布版本；当前指针切换在同一本地事务完成。在途单继续用冻结快照。

## 8. 定价解析（本批管理端）

有效 B 端结算单价：

```text
若项目 listing 存在 L3 覆盖 → L3
否则若该 BuyerProduct 存在手工 L2 覆盖 → 覆盖 L2
否则 L1
均无 L1 → 标准商品不可发布 / 受理失败 PLATFORM_SETTLEMENT_PRICE_MISSING
```

本期无加价模板，「模板 L2」一层不存在。`FOLLOW_TEMPLATE` 清除手工覆盖后下一跳即 L1。

`EQUAL_L1`：覆盖值 = 当前 L1 **B 端结算分**，不是 C 端 `marketPrice`。`CUSTOM`：`round(L1结算分 * percent / 100) + amountCents`，结果必须 >0。percent 为整数百分比，100 表示等于 L1。折扣基点 `discountBp` 仅用于供应商 Offer 的 `DISCOUNT` 模式（10000=100%），不要与这里的 percent 混用。

O2O / INSTORE 仍 `buyerSettlementAmount = channelPayAmount`，不读三级价。

## 9. 错误码（增量）

沿用 08-28 第 11 章。新增：

| 码 | 含义 |
| --- | --- |
| `ENUM_NOT_OPEN` | 未知枚举值（替代「时效非空即拒」）。目录短文案不变；运行时 `msg` 须包含枚举类型、提交值与允许值 |
| `CATEGORY_CHAIN_INVALID` | 仅类目树主数据父子不合法；供货配置与分销商档案不用此码 |
| `BUYER_PARENT_INVALID` | 上级/等级不合法或成环 |
| `BUYER_PRODUCT_DUPLICATE` | 分销商品 SKU 已存在 |
| `L2_OVERRIDE_INVALID` | L2 覆盖公式或金额不合法 |
| `L3_OVERRIDE_INVALID` | L3 覆盖不合法 |
| `PROJECT_BUYER_IMMUTABLE` | 非草稿项目不可改分销商 |
| `PROJECT_PLATFORM_SETTLEMENT_NOT_ENABLED` | 平台结算未开放 |
| `PROJECT_STATUS_INVALID` | 非法项目状态迁移 |
| `PROJECT_SKU_NOT_IN_BUYER_CATALOG` | 项目 SKU 不是该分销商的分销商品 |
| `SUPPLY_SCOPE_MODE_INVALID` | 供货配置模式集合为空或含未知值 |
| `BUYER_OPENING_ALREADY_DONE` | 分销商期初已入账，禁止再改余额字段 |
| `BUYER_CREDIT_CODE_DUPLICATE` | 公司名称+统一社会信用代码联合重复 |
| `INVOICE_FIELD_INVALID` | 开票信息字段不完整或与抬头类型冲突 |
| `SUPPLIER_CODE_REQUIRED` | 排除已添加供应商品时必须指定供应商（`excludeAddedSupplierOffers=true` 但 `supplierCode` 空白） |

原 `SUPPLIER_ENUM_NOT_OPEN` 收敛为 `ENUM_NOT_OPEN` 的别名或停止新抛，实现时只保留一个码。

## 10. 测试门禁

`*Test`：

- `BuyerStatus` 含黑名单可逆；`BuyerProjectStatus` 含 `CLOSED` 可恢复、不能从草稿直接结束。
- 组织树：一级无上级、二/三级上级校验、成环拒绝。
- L3 > 手工 L2 > L1；`FOLLOW_TEMPLATE` 清除手工覆盖后回到 L1（无模板层）。
- 供货模式 5 值 + 一行多模式；三码元组仍唯一。
- 金额分；开票 `GET` 无行预填不落库；`PUT` 企业/个人校验；保存不回写档案。

`*IntegrationTest`：

- 供应商创建期初、编辑再带余额被拒。
- 供货配置收窄不改已发布 Offer，阻止新关联。
- 合作商品 `POST .../offers` 后状态为 `PUBLISHED`。
- 分销商品改价写 L2 覆盖；项目 listing 冻结后改 L2 不影响在途单。
- 项目 batch SKU 无 BuyerProduct 时失败。
- Liquibase 追加 5 模式 CHECK。
- 开票 `023` 建表；无行 GET 不 insert；PUT 后改档案仍返回开票行且不回写档案；开户账号明文落库。

`*ContractTest`：管理端 JSON 字段名与枚举名；供应商/分销商映射 `GET/PUT …/invoice`；类目/标品/项目路径无 `invoice`；Controller `@Tag` / 方法 `@Operation` 齐全（见 §3.6）。

配置契约还须断言 `application.yaml` 的管理端包模式能匹配 `interfaceapi.admin`，避免 Controller 因包名
偏离框架默认 `**.controller.admin.**` 而丢失 `/admin-api` 前缀；网关配置评审须同时检查供应链业务路由
与 Swagger 聚合路由。

`*ArchitectureTest`：Admin Controller 不访问供应商 Adapter；本期不得新增 `tradecore.pricing`；O2O / INSTORE 应用服务不得依赖 `SupplierRoutingEngine` 选供。完整边界见 09-12 范围对齐 §12。

## 11. 前端对齐清单

独立前端任务/团队按以下契约完成权限菜单接入；本后端任务不修改或构建前端工程。逐步说明、按钮对照表、联调账号与验收清单见
`docs/superpowers/specs/2026-09-06-supplychainhubweb-dynamic-menu-handoff-design.md`。

1. 删除 `remaining.ts` 中供应商、分销商、项目的无条件静态路由；登录后请求 `system-server` 权限信息并动态注册获准页面。后端兼容接收但忽略 `applicationCode`，前端不得依赖该参数过滤菜单。
2. 页面按钮接入 `v-hasPermi` 或等价指令，允许集合同时包含 `supply-chain:admin` 与对应细权限；刷新、直接粘贴 URL 均不得恢复未授权路由。
3. 保持当前业务 mock 开关，不把“菜单动态化”与“REST 数据联调”捆成一次不可回滚发布。菜单模型、站点隔离与联调门禁见 `docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md`。

关闭 `USE_SUPPLIER_MOCK` / `USE_DISTRIBUTOR_MOCK` 时再完成以下业务接口对齐；后端不提供 `/scm` 兼容：

1. URL 从 `/scm/supplier/page|get|create` 改为 REST：`GET/POST /supply-chain/suppliers`、`GET/PUT/PATCH /supply-chain/suppliers/{supplierCode}`，分销商/项目同理。
2. 身份从数字 `id` 改为 code（可用响应里的 `id` 仅作 row-key）。
3. 金额从元改为分，或在 API 层换算。
4. 状态从 `1/2/3` 改为枚举名；状态变更用 `PATCH`，不要 `/update-status`。
5. 品类/类型/分类从 mock 小写码改为 system 字典枚举名（`sellableCategory` 等）；不要拉 `GET /supply-chain/categories` 填供货配置或新增分销商。见字典专文 §8。
6. 供货模式从 `dropshipping` 改为 `DROP_SHIP` 等；发货时效/结算/对接小写码改为第 4.2 节常量。
7. 开票页签对接 `GET/PUT …/{code}/invoice`（`titleType` 英文枚举，用 `persisted` 区分预填）；去掉删除供应商/分销商/项目主体（子资源 `DELETE` 只用于移除商品）。不要提交 `settlementPartyType=PLATFORM`。
8. 项目加购 SKU 的标品弹窗改为只列出该分销商已有分销商品（带 `projectCode` 调 `GET /products/skus`）。
9. 「按分销价=销售建议价」→ `l2AdjustMode=EQUAL_L1`；「按项目价=销售建议价」→ `l3AdjustMode=EQUAL_L1`；基数是 L1 结算分。
10. 指定配送区域：把地区选择结果转成 6 位 `adcode` 数组，不要把系统地区内部 id 直接当行政区划码，除非二者已是同一 6 位码。
11. 证件上传：把基础设施文件模块的 `fileKey` 传给供应链，不要把可访问 URL 当 fileKey。

测试环境 REST 已验收，见 `docs/集成测试.md` 第 7.7 / 7.8 节。

## 12. 相对 08-28 / 现实现的变更摘要

- 管理端对外契约改为 REST（名词资源 + HTTP 方法）。已有 `PUT .../status` 等仅作兼容别名。
- 供货模式 2→5，配置行支持多模式。
- 发货时效/结算/对接从「非空即拒」改为封闭枚举可存。
- `BuyerStatus.BLACKLIST`；`BuyerProjectStatus.CLOSED`。
- Buyer 组织树（parent + level）。
- L2 允许 SKU 手工覆盖。
- 合作商品新增默认上架。
- 管理端 REST 以本文字段名为准；供应商已有接口做兼容扩展（新增查询参数与字段），不改已有成功用例的必填语义。
- a15：供应商/分销商开票独立子资源，见 `docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md`。
- a6 修订：供货/分销商品类改为字典三码，SPU 打标签后按三码命中，见 `docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md`。

## 13. 模块边界

| 模块 | 职责 |
| --- | --- |
| `tradecore.supply` | 供应商档案、5 模式、供货配置子表、Offer 上架默认、开票 |
| `tradecore.buyer` | 分销商档案、组织树、黑名单、联系人/证件扩展、开票 |
| `tradecore.catalog` | BuyerProduct、L2 覆盖版本、ProjectListing、L3 |
| `tradecore.account` | 分销商期初仍走采购商唯一账本；供应商期初仍走供应商结算账户 |
| `interfaceapi.admin` | 本文 REST；不编排供应商调用 |
| `routing` | 不读取 `supplyPreference=COMPREHENSIVE` |

Liquibase 只追加新 changeset；开票为 `023-admin-invoice`；表单字典与三码为 `024-admin-form-dictionary-codes`（system 侧 `002-admin-form-dictionaries`）；供货模式字典由 system 侧 `003-supply-mode-dictionary` 追加。禁止回改已执行变更集。
