# 供应链管理端 REST（对齐前端页面）详细设计

## 1. 文档定位

本文根据 `supplychainhubweb` 已落地的供应链中台页面（`src/views/supplier`、`src/api/supplier`）定义管理端 REST，供前端关掉 mock 后对接。

前端范围仅三块：**供应商、分销商、项目**（含供货配置、合作商品、分销商品、项目商品、分销层级）。新增供应商供货配置、新增分销商的品类/类型/分类下拉改为 system 数据字典，不再用分类树。加价模板 / 订单 / 售后 / 路由页面尚未建设，不在本文。

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

**合作商品状态**：`PUBLISHED` 上架、`SUSPENDED` 下架、`RETIRED` 已移除（列表不返回）。新增默认 `PUBLISHED`。编辑已上架单价/模式必须发新 OfferVersion。

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

附加过滤：

- `supplierCode`：只返回 SPU 三码命中该供应商供货配置、且尚无未退役 Offer 的 SKU。
- `buyerCode`：只返回该分销商**尚未**关联的平台 SKU（新增分销商品）。
- `projectCode`：只返回该项目所属分销商**已有** `BuyerProduct`、且项目目录尚未未退役关联的 SKU（新增项目商品）。缺少 `BuyerProduct` 的标品不得出现。

金额字段：`suggestPriceCents`（L1 结算口径）、`marketPriceCents`（兼容既有 JSON 字段名；C 端建议价，实际取 `sc_platform_sku.suggested_sale_price`，若无则空）。`marketPriceCents` 不得取履约面值 `face_value` 或市场划线价 `market_price`；销售价来源以 09-12 范围对齐 §5 为准。点餐/电影 L1 可为 0。

## 5. 供应商

在现有 `SupplierAdminController` 上扩展筛选与字段，不改资源名。

### 5.1 列表 `GET /supply-chain/suppliers`

筛选：`supplierName`、`supplierCode`、`sellableCategories`（多，枚举名）、`supplyModes`（多）、`status`（多，枚举名）、`updatedFrom`、`updatedTo`、`pageNo`、`pageSize`。

名称下拉：同一集合加 `view=simple`，返回 `{ supplierCode, supplierName }[]`（可不分页）。

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
- `POST /supply-chain/suppliers/{supplierCode}/offers` body `{ items: [...] }` 批量新增，默认 `PUBLISHED`
- `PUT /supply-chain/suppliers/{supplierCode}/offers/{offerNo}` 编辑（上架中改价发新版本）
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

`GET /supply-chain/buyers` 筛选：`buyerName`、`buyerCode`、`distributionLevels`、`cooperationModes`、`sellableCategories`、`status`、`updatedFrom`/`updatedTo`。

投影（同一集合，不用独立路径）：

- `view=simple`：名称/编号下拉 `{ buyerCode, buyerName }`
- `view=parent-options`：`excludeBuyerCode`、`forLevel`，供「上级分销商」下拉

`GET /supply-chain/buyers/{buyerCode}`、`POST /supply-chain/buyers`、`PUT /supply-chain/buyers/{buyerCode}` 档案字段对齐前端分销商表单：名称、联系人、分销品类/类型（`sellableCategories[]` / `productTypes[]` 枚举名，不是类目 id）、合作模式、类型、择优倾向、结算方式/周期、对接方式、合作开始日、说明、公司/信用代码/法人/地址/银行/证件、期初 `openingBalanceCents`（创建一次，允许 0）。详情中的 `legalPersonMobile`、`bankAccount`、`contacts[].mobile`、`primaryContactMobile` 及证件 fileKey 均按原值回显，不提供 `legalPersonMobileMasked` / `bankAccountMasked` 字段。字典见专文 §3。

详情响应中的 `productTypes[]` 为对象数组，字段固定为 `{ id, name }`：按已保存的 `scope_kind=PRODUCT_TYPE` 范围编码关联 `sc_product_type.type_code`，`id` 取商品类型业务编码，`name` 取商品类型名称，不暴露商品类型表内部自增主键。创建/编辑请求的 `productTypes[]` 仍提交商品类型业务编码；列表响应继续返回编码数组，避免改变列表筛选与既有投影契约。

分页列表行除基础档案字段外，还返回以下只读聚合字段：

- `productTypes[]`：`sc_buyer_category_scope` 中 `scope_kind=PRODUCT_TYPE` 的商品类型枚举名；`sellableCategories[]` 继续只读取 `scope_kind=SELLABLE`，两者互相独立，禁止相互推导；
- `projectCount`：该分销商未归档项目数，`ARCHIVED` 项目不计入；
- `skuCount`：该分销商状态为有效的分销商品数，已移除分销商品不计入；
- `cooperationTime`：列表展示用合作时间，与详情 `cooperationStartedAt` 取同一回显口径，来源是 `sc_buyer.cooperation_started_at`。管理端日期选择器空值常以 Unix 0 提交。`null`、Unix 0、年份 `<=1970` 视为未填：创建回落当前业务时间（Asia/Shanghai），更新忽略该字段以免用空值覆盖已有合作开始时间；列表与详情读取历史脏值时回落到 `created_at`。已填写且年份晚于 1970 的值原样回传。

分页聚合必须按当页 Buyer 集合批量读取，禁止为每行追加项目数、SKU 数等 N+1 查询。详情的 `sellableCategories[]` / `productTypes[]` 只返回已经保存的独立范围行；历史记录缺少 `SELLABLE` 行时返回空数组，由前端按新字段重新保存或由有明确业务映射的数据修复任务补齐，后端不得根据商品类型猜测分销品类。

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

- `GET /supply-chain/buyers/{buyerCode}/products` 分页。可选查询参数 `productType`（动态商品类型编码）。省略或空白不筛选；传入后只返回该分销商未移除、且关联平台 SPU 的 `productType` 与入参相等的分销商品。无命中返回空页。筛选不改变按 `updatedAt` 倒序，分页默认仍为 `pageNo=1`、`pageSize=20`。口径与合作商品列表按 `productType` 收窄候选后再分页一致，命中字段取列表行同源的平台 SPU，而不是供货配置。
- `POST /supply-chain/buyers/{buyerCode}/products` body `{ items: [...] }` 从标品关联；已存在则 `BUYER_PRODUCT_DUPLICATE`
- `PATCH /supply-chain/buyers/{buyerCode}/products/{buyerProductCode}` 改价（写/清 L2 覆盖，body 含 `l2AdjustMode` 等）
- `DELETE /supply-chain/buyers/{buyerCode}/products/{buyerProductCode}` 逻辑移除（不再出现在列表；已发布项目目录不回放删除，新项目不得再选）

列表列：SPU/SKU、SPU 三码、`suggestPriceCents`（L1）、`pricingRule`（文案：继承L1 / 等于L1 / 自定义公式）、`distPriceCents`（有效 L2；无手工覆盖时等于当前 L1）、`projectPriceCents`（若恰有一个项目 L3 则给出，否则空）、`finalPriceCents`（按 L3>L2>L1 对该行展示，项目价取「任意覆盖」时以 `projectPriceCovered` + `projectNames[]` 说明）、`priceSource`（`L1`/`L2`/`L3`）、`projectPriceCovered`。

`displayThresholdPercent` 可存（利润空间低于该百分比时 C 端隐藏的配置），**本轮不实现 C 端隐藏消费**，只落库。

前端未传 `l2AdjustMode` 时默认 `FOLLOW_TEMPLATE`。页面默认选项「按分销价=销售建议价」必须显式传 `EQUAL_L1`，覆盖值取当前 `suggestPriceCents`（L1），禁止用 `marketPriceCents`。

### 6.4 开票信息

`GET/PUT /supply-chain/buyers/{buyerCode}/invoice`。见
`docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md`。

## 7. 项目

新建 `ProjectAdminController`，映射 `/supply-chain/projects`。内部聚合仍是 `BuyerProject`。

### 7.1 档案

`GET /supply-chain/projects` 筛选：`projectName`、`projectCode`、`status[]`、`buyerCode`、`projectType[]`、`periodFrom`/`periodTo`。

`GET /supply-chain/projects?view=simple`：名称/编号下拉。

`GET /supply-chain/projects/{projectCode}`、`POST /supply-chain/projects`、`PUT /supply-chain/projects/{projectCode}`：`projectName`、`buyerCode`、`projectType`、`startDate`、`endDate`、`settlementPartyType`（仅 `DISTRIBUTOR`）、`description`（≤500）。创建允许 `DRAFT` 或 `ACTIVE`（`POST` body 可带 `status`）；整档更新 body 可选带 `status`，未传时保持原状态，传入时按第 4.2 节状态机执行迁移，目标状态与当前状态相同时按幂等保存处理。`skuCount`、`projectPriceSkuCount` 只读统计。

`catalogMode` 默认 `MANAGED`，本批页面不展示。`appId`/`appKey` 本批不返回（避免密钥进入管理端列表）。

归属：`DRAFT` 可改 `buyerCode`；`ACTIVE`/`SUSPENDED`/`CLOSED` 改归属 → `PROJECT_BUYER_IMMUTABLE`。

`PATCH /supply-chain/projects/{projectCode}` `{ "status": "ACTIVE"|"SUSPENDED"|"CLOSED" }` 按第 4.2 节迁移，作为状态操作的兼容入口；`PUT` 传入 `status` 时执行相同状态机校验。页面按钮：启动、暂停、恢复、结束。

### 7.2 项目商品

即 `ProjectListing` + L3 覆盖。

- `GET /supply-chain/projects/{projectCode}/products`
- `POST /supply-chain/projects/{projectCode}/products` body `{ items: [...] }`：SKU 必须已是所属 Buyer 的未移除分销商品，否则 `PROJECT_SKU_NOT_IN_BUYER_CATALOG`。默认 `l3AdjustMode=FOLLOW_L2`（不写 L3）。批量配置可同时带 L3。
- `PATCH /supply-chain/projects/{projectCode}/products/{listingCode}` 改价（写/清 L3，发新 `ProjectListingVersion`）
- `DELETE /supply-chain/projects/{projectCode}/products/{listingCode}` 逻辑移除（目录项不再可采购；历史订单仍读冻结版本）

新增即对项目可采购（等价上架）。列表金额：`suggestPriceCents`、`distPriceCents`、`projectPriceCents`、`finalPriceCents`、`priceSource`、`projectPriceCovered`。页面「按项目价=销售建议价」对接时显式传 `l3AdjustMode=EQUAL_L1`。

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
