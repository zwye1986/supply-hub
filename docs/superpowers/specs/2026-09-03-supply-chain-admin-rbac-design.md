# 供应链中台运营后台 RBAC 详细设计

## 1. 文档定位

本文定义**中台运营后台**的人如何登录、功能权限如何拆、接口如何拦，以及后端如何为独立站点 `supplychainhubweb` 提供基于 `system_menu` 的菜单、路由和按钮权限数据。它不定义采购项目 HMAC、渠道验签、C 端 `buyerUserId`，也不定义供应商/分销商门户账号。

本任务只交付后端菜单能力、system 库脚本和供应链接口 RBAC；不修改任何前端工程。`supplychainhubweb` 删除静态路由、接入动态菜单及按钮指令由独立前端任务/团队完成，本文仅定义其后端契约与发布前置。

管理端 REST 字段、状态机、路径仍以
`docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md`
为准。本文替换该文 §3.1 的单一 `supply-chain:admin` 方案，并补充 `system_menu` 历史应用字段及 `supplychainhubweb` 统一动态菜单设计：已登记页面资源改为细权限，未拆接口仍用 `supply-chain:admin`。

与 `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#37-事务恢复与权限规则`
中的 Facade `scope`（如 `CATALOG_READ`、`FULFILLMENT_EXCEPTION_RESOLVE`）不是同一套东西：那是采购商/渠道/系统身份的机器能力；本文是 `system-server` 管理端用户的 yudao 权限标识。禁止把 Facade scope 写成 `supply-chain:…` 或反过来。

词汇见仓库根目录 `CONTEXT.md`（中台运营人员、运营功能权限、运营数据范围）。

## 2. 已确认决策

| 编号 | 决策 |
| --- | --- |
| r1 | 只设计运营后台的人。不做外部门户登录，不出四套身份的总图切片。 |
| r2 | 账号复用 `system-server` 用户 / 角色 / 菜单 / token。中台库不建运营用户表、不发登录态。 |
| r3 | 当前只有独立站点 `supplychainhubweb` 消费动态菜单；与商城后台同一账号、同一 token、同一套角色权限。`applicationCode` 仅作为兼容参数保留，权限信息接口忽略其值并返回全局授权菜单；应用标识不作为后端授权凭证。 |
| r4 | **本轮落地 B**：按资源拆功能权限，数据全量。**最终目标 C**：再按供应商/分销商/项目 code 做数据范围。范围不编进权限字符串，不用 yudao 部门数据权限，不在 `sc_*` 上套框架 `@DataPermission`。 |
| r5 | 权限目录覆盖供应商、分销商、项目、商品中心与订单只读查询。类目树 / 标品 SKU 下拉只读。账户、路由、连接器、`/supplier-config`、`/master-data` 等未拆接口仍只认 `supply-chain:admin`。订单权限增量见 `docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md`。 |
| r6 | 动作：`query` / `create` / `update` / `delete` / `update-status`。`delete` 只表示逻辑移除子资源（合作商品 / 分销商品 / 项目商品），禁止物理删主体。`update-status` 只对**主体**状态机。 |
| r7 | 子资源跟主体走：供货配置、合作商品、分销商品、项目商品、开票不单开权限资源。合作商品上架/下架走 `supplier:update`，不是 `update-status`。开票契约见 `docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md`。 |
| r8 | `supply-chain:admin` 仍是所有供应链细权限的超集（`hasAnyPermissions(admin, 细权限)`），避免现网只勾了旧权限的账号突然 403。yudao 超管角色走框架放行。 |
| r9 | 本任务不修改 `supplychainhubweb` 或其它前端工程，只交付统一的权限信息接口和按钮权限数据。删除 `remaining.ts` 静态挂载、动态注册路由及接入 `v-hasPermi`（或等价指令）由独立前端任务/团队完成。 |
| r10 | `system_menu` 增加 `application_code`，既有菜单迁移为 `mall-admin`，供应链目录/页面/按钮写 `supply-chain-admin`。菜单结构与权限登记到 **system-server 的库**，使用可重复 SQL；禁止中台 Liquibase 写 `system_menu`。本轮不预置「商务/运营」等业务角色。 |
| r11 | 供应商/分销商档案 GET 在通过对应 `query` 权限后按原值回显手机号、开户账号和证件 fileKey，不另设「看明文」权限；本轮仍不增加导出能力。库内加密、履约资产访问控制与所有日志脱敏规则不变。 |
| r12 | 中台业务表没有 `tenant_id`。yudao 租户只约束谁能登录，不隔离供应商/分销商/项目数据。 |

## 3. 身份与鉴权边界

**中台运营人员**是 `system-server` 的管理端用户。`supplychainhubweb` 继续使用现有管理端登录，拿到与商城后台相同类型、可互通的 token；本轮不新增 OAuth2 客户端。网关将 `/admin-api/supply-chain/**` 转到 `supply-chain-server`。

`supply-chain-server` 从 `LoginUser` 取得系统用户 id，再由 `@ss` 通过 `PermissionCommonApi` 向外部 `system-server` 查询角色权限。`LoginUser.scopes` 是 OAuth scope，不是本文的 `supply-chain:*` 权限。权限 RPC 异常沿现有安全框架失败关闭，不降级为放行；供应链进程的一分钟权限判断缓存仍生效。

写操作继续把 `getLoginUserId()` 记入 `created_by` / `updated_by`，语义是系统用户 id，不是 Buyer、不是项目 app、不是 `buyerUserId`。

请求处理分两层：

1. **菜单投影**（本轮实现后端）：权限信息接口忽略兼容参数 `applicationCode`，返回当前角色在全部应用下拥有的菜单和按钮权限。前端消费该数据的改造不属于本任务；菜单投影也不替代接口鉴权。
2. **功能权限**（本轮实现）：`@ss.hasAnyPermissions(...)`，由 `system-server` 判定。yudao 为严格模式：权限标识必须在 `system_menu` 有对应菜单/按钮，否则非超管为无权限。

数据范围属于后续 C：本轮功能权限通过后仍可访问全部供应商、分销商和项目，不创建空的 AllowAll 协作点；C 必须另行设计分页前过滤与命令单资源校验。

禁止混用的身份：项目 `appId`+HMAC、渠道 Principal、采购商 C 端 `buyerUserId`。运营 token 不能当项目应用身份，也不能当渠道验签。

## 4. 权限标识

格式：`supply-chain:{resource}:{action}`。

| 标识 | 含义 |
| --- | --- |
| `supply-chain:supplier:query` | 列表、详情、供货配置、合作商品、开票查询、`view=simple` |
| `supply-chain:supplier:create` | 新增供应商主体（创建时可写期初） |
| `supply-chain:supplier:update` | 改档案、供货配置、开票保存；合作商品新增/改价/上架下架（含兼容 `batch-draft`） |
| `supply-chain:supplier:delete` | 逻辑移除合作商品（`DELETE` 与兼容 `retire`） |
| `supply-chain:supplier:update-status` | 主体暂停/恢复/拉黑（单条与批量，含兼容 `/status`） |
| `supply-chain:buyer:query` | 列表、详情、直接下级、分销商品、开票查询 |
| `supply-chain:buyer:create` | 新增分销商主体（创建时可写期初） |
| `supply-chain:buyer:update` | 改档案、开票保存；分销商品新增/改 L2 |
| `supply-chain:buyer:delete` | 逻辑移除分销商品 |
| `supply-chain:buyer:update-status` | 主体暂停/恢复/拉黑 |
| `supply-chain:project:query` | 列表、详情、项目商品 |
| `supply-chain:project:create` | 新增项目 |
| `supply-chain:project:update` | 改档案；项目商品发布/改 L3 |
| `supply-chain:project:delete` | 逻辑移除项目商品 |
| `supply-chain:project:update-status` | 主体进行中/暂停/已结束 |
| `supply-chain:order:query` | 平台订单列表与类型化详情，只读 |
| `supply-chain:admin` | 未拆接口兜底；并作为全部细权限的超集 |

不单开 `catalog:query`。`GET /supply-chain/categories` 在调用方持有三块任一 `query` 或 `supply-chain:admin` 时通过。

`GET /supply-chain/products/skus` 的基础访问条件同上；带资源过滤参数时还必须持有对应资源的 `query` 或 `supply-chain:admin`：`supplierCode` 对应 `supplier:query`，`buyerCode` 对应 `buyer:query`，`projectCode` 对应 `project:query`。同时传多个资源 code 时必须满足全部对应权限。使用专用的权限判定组件封装该组合规则，Controller 不散落字符串判断。

角色搭配（代码不强行捆绑，菜单说明里写清）：建项目需 `project:create` + `buyer:query`；从平台 SKU 加合作商品需 `supplier:update` 且下拉依赖类目/SKU 规则。

没有 `*:delete` 去删供应商/分销商/项目主体。

## 5. 方法与权限映射

类上不再单独一把 `@PreAuthorize("supply-chain:admin")`。每个映射方法显式注解。细权限方法一律
`@ss.hasAnyPermissions('supply-chain:admin', '<细权限>')`。

### 5.1 `SupplierAdminController` `/supply-chain/suppliers`

| 方法 | 权限 |
| --- | --- |
| `GET /`、`GET /{supplierCode}`、`GET /{supplierCode}/supply-scopes`、`GET /{supplierCode}/offers` | `supplier:query` |
| `POST /` | `supplier:create` |
| `PUT /{supplierCode}`、`PUT /{supplierCode}/supply-scopes`、`POST /{supplierCode}/offers`、`PUT /{supplierCode}/offers/{offerNo}`、`PATCH /{supplierCode}/offers/{offerNo}`、`POST /{supplierCode}/offers/batch-draft` | `supplier:update` |
| `DELETE /{supplierCode}/offers/{offerNo}`、`PUT /{supplierCode}/offers/{offerNo}/retire` | `supplier:delete` |
| `PATCH /{supplierCode}`、`PATCH /`、`PUT /{supplierCode}/status`、`PUT /status/batch` | `supplier:update-status` |

`PATCH /{supplierCode}/offers/{offerNo}` 是合作商品 `PUBLISHED↔SUSPENDED`，走 `update` 不是 `update-status`。

### 5.2 `BuyerAdminController` `/supply-chain/buyers`

| 方法 | 权限 |
| --- | --- |
| `GET /`、`GET /{buyerCode}`、`GET /{buyerCode}/children`、`GET /{buyerCode}/products` | `buyer:query` |
| `POST /` | `buyer:create` |
| `PUT /{buyerCode}`、`POST /{buyerCode}/products`、`PATCH /{buyerCode}/products/{buyerProductCode}` | `buyer:update` |
| `DELETE /{buyerCode}/products/{buyerProductCode}` | `buyer:delete` |
| `PATCH /{buyerCode}`、`PATCH /` | `buyer:update-status` |

`POST /supply-chain/buyer-accounts/opening-balance` 仍只认 `supply-chain:admin`（账户未拆）。创建分销商时带的期初走 `buyer:create`，与账户调账接口分开。

### 5.3 `ProjectAdminController` `/supply-chain/projects`

| 方法 | 权限 |
| --- | --- |
| `GET /`、`GET /{projectCode}`、`GET /{projectCode}/products` | `project:query` |
| `POST /` | `project:create` |
| `PUT /{projectCode}`、`POST /{projectCode}/products`、`PATCH /{projectCode}/products/{listingCode}` | `project:update` |
| `DELETE /{projectCode}/products/{listingCode}` | `project:delete` |
| `PATCH /{projectCode}` | `project:update-status` |

### 5.4 只读下拉

| 方法 | 权限 |
| --- | --- |
| `GET /supply-chain/categories` | 任一 `supplier:query` / `buyer:query` / `project:query`，或 `admin` |
| `GET /supply-chain/products/skus` | 基础条件同上；存在 `supplierCode` / `buyerCode` / `projectCode` 时，分别追加对应资源 `query`，多项取 AND；`admin` 为全部条件超集 |

### 5.5 未拆、仍仅 `supply-chain:admin`

仅这四个：`BuyerAccountAdminController`、`RoutingPolicyAdminController`、`SupplierConfigurationAdminController`、`M1MasterDataAdminController`。保持只校验 `supply-chain:admin`（超管仍放行）。旧 `/master-data/buyers` 等路径不改走细权限，避免两套入口语义分叉。

### 5.6 `OrderAdminController` `/supply-chain/orders`

`GET /` 与 `GET /{orderNo}` 均使用 `order:query`，并保留 `supply-chain:admin` 超集；本期没有订单写接口。详细契约见 `docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md`。

当前 `interfaceapi.admin` 一共 13 个 AdminController：上述 4 个未拆，其余资源均使用本文登记的细权限与 `admin` 超集。新增 AdminController 必须先改本文再挂注解，禁止默认一把 `admin` 而不登记。

## 6. 错误与日志

未登录：现有管理端登录校验，业务码 `401`（账号未登录）。

已登录、功能权限不足：`AccessDeniedException`，`CommonResult` 使用全局 `FORBIDDEN`（`403`，「没有该操作权限」）。不得改写成业务状态冲突或资源不存在。

本轮数据全量：资源不存在仍用现有业务错误（如供应商不存在），不与 `403` 混淆。

C 落地后：功能权限过、范围不含该 code 时失败关闭。具体用 `403` 还是伪装 404 在 C 的规格里单开，本文不提前拍板。本轮不实现数据范围分支。

入口日志：`userId`、HTTP 方法、路径、业务 code、权限决策（通过/拒绝）。拒绝路径防风暴（按 userId+权限标识聚合或限频）。即使管理端响应按原值回显档案字段，入口、异常和第三方调用日志仍必须脱敏，禁止记录完整请求/响应对象。

## 7. 后续数据范围边界（C，本轮不实现）

本轮没有运营数据范围：查询不追加 code 条件，命令不因业务 code 拒绝。为避免制造没有约束力的空抽象，本轮**不**增加 AllowAll 接口、不创建范围表、不写 C 的 Liquibase。

C 的详细设计至少必须拆成两类能力：

- 列表查询在数据库 `count` 和分页之前追加允许的 `supplierCode` / `buyerCode` / `projectCode` 条件，禁止查询后在内存删行。
- 详情和命令在读取或锁定目标资源后，按资源类型与业务 code 失败关闭；跨资源操作须定义项目→分销商、商品→供应商等关联范围如何合并。

授权实体预期按 `system_user_id + 资源类型 + 业务 code` 建模并挂在中台库，权限标识不改名。禁止用 yudao 部门/岗位数据权限过滤 `sc_supplier` / `sc_buyer` / `sc_buyer_project`。具体端口、表结构、403/404 语义在 C 的规格中确认后再实现。

## 8. 后端菜单能力与前端接入契约

### 8.1 应用隔离模型

`system_menu` 新增非空字段 `application_code`，DDL `COMMENT` 与 DTO `@Schema` 统一写为“管理端应用标识”：

| 值 | 含义 |
| --- | --- |
| `mall-admin` | 现有商城管理端菜单；历史数据迁移后的默认值 |
| `supply-chain-admin` | 独立站点 `supplychainhubweb` 的菜单 |

`GET /system/auth/get-permission-info` 保留可选查询参数 `applicationCode` 仅用于兼容已经上线的前端请求，
后端必须忽略其值并始终合并返回用户在全部管理端应用下已授权的菜单与权限。传入
`mall-admin`、`supply-chain-admin`、未知值或省略参数的响应语义完全一致，不再因未知值返回
`1002001007`。当前只有 `supplychainhubweb` 消费动态菜单，因此权限信息接口不再承担多前端菜单投影职责；
该参数不参与 `@PreAuthorize`，也不能获得角色未授予的接口权限。

权限信息必须拆开计算“已授予权限”与“用于路由的菜单”，不能继续让一个会被修改的 `menuList` 同时承担两者：

1. 从角色菜单关系得到完整菜单集合，先计算真实 `grantedPermissions`。
2. 不读取请求中的 `applicationCode`，始终使用全部启用菜单作为投影范围。普通细权限用户按父子关系自动补齐获准页面的祖先目录；补祖先只用于组成路由树，不把祖先的 permission 加进 `grantedPermissions`，也不再检查历史应用标识。
3. 供应链根目录的 permission 固定为 `supply-chain:admin`。若真实 `grantedPermissions` 含该值，则从该根目录按父子关系展开全部启用 DIR/PAGE 和按钮权限，不得再读取 `application_code` 判断范围；用户是 yudao 超管时，展开全部启用菜单与权限。
4. 权限数组合并全部应用的已授予权限及上一步的超集展开。后端 `PermissionCommonApi` 仍按完整角色权限独立判定。

菜单管理、角色分配接口同样忽略前端传入的 `applicationCode` 并返回统一菜单集合。菜单创建时不接受
前端指定应用：根节点使用历史默认值 `mall-admin`，子节点继承父节点的兼容字段值；菜单更新时保留原值。
不得再校验父子菜单的应用值，也不得用该字段过滤列表或阻断父子挂接。`MenuDO`、保存/响应 VO 和数据库列
本轮仅为兼容既有数据与部署 SQL 保留，错误码 `1002001007`、`1002001008` 退出菜单接口运行路径。

### 8.2 供应链菜单树

在 system 库登记根目录「供应链中台」及资源页面菜单、按钮，全部标记 `application_code=supply-chain-admin`。根目录 permission 为 `supply-chain:admin`；页面菜单的 permission 为对应 `*:query`；有写能力的页面再挂 create / update / delete / update-status，订单页只登记查询 PAGE。组件 path 必须能被 `supplychainhubweb` 的动态组件解析器加载：

| 页面 | 路由 |
| --- | --- |
| 供应商管理 | `/supplier/manage` |
| 分销商管理 | `/supplier/distribution/distributor` |
| 项目管理 | `/supplier/distribution/project` |
| 订单管理 | `/supplier/orders` |

独立前端任务应删除 `supplychainhubweb` 的 `src/router/modules/remaining.ts` 中供应商、分销商、项目三页的无条件静态挂载，并为订单页补齐对应组件；登录后调用带应用标识的权限信息接口，将返回的页面菜单交给现有动态路由加载机制。无 `*:query` 且无 `supply-chain:admin` 时，该页不应出现在侧栏且路由不注册；直接输入 URL 应进入统一无权限/404 页面，不能退回静态路由。本任务不实施或构建验证这些前端改动。

独立前端任务应让页面按钮按资源使用 `v-hasPermi` 或该仓库等价指令。每个按钮的允许集合必须同时包含 `supply-chain:admin` 和对应细权限，保持 r8；只有 UI 隐藏不算安全控制，接口仍必须由 §5 的 `@PreAuthorize` 拒绝。

### 8.3 旧 `admin` 兼容、角色与脚本

不预置业务角色。旧 `supply-chain:admin` 菜单及其 `system_role_menu` 关系必须原样保留；供应链根目录复用同一 permission。权限信息接口按 §8.1 的超集规则展开整棵供应链菜单，因此不依赖一次性的角色子树复制，也不要求角色 UI 恰好勾中所有子节点。yudao 超管无需勾细项，按应用返回全部启用菜单。

实现时落地两个 system 库脚本，禁止写入中台 changelog：

1. `script/deploy/aifuli-test/sql/system-menu-application-schema.sql`：只做可向后兼容的 schema 扩展，为 `system_menu` 增加非空且默认 `mall-admin` 的 `application_code`，可重复执行；旧版 system 代码必须能在该列存在时继续工作。测试环境 MySQL 不支持 `ADD COLUMN IF NOT EXISTS`，脚本必须通过 `information_schema.COLUMNS` 判定后使用 `PREPARE` 执行 DDL，已存在时执行无副作用语句。
2. `script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql`：在新版 system 已支持菜单字段后执行，可重复创建供应链菜单树并保留旧 `admin` 节点及角色关联；不得按 permission 全局更新其它应用的同名记录。脚本中的用户变量、派生表字面量与 `system_menu` 字段可能使用不同的 `utf8mb4` 排序规则；所有 `application_code` 与 `@sc_app` 的判重/定位，以及按钮插入和同步中的 permission 比较，都必须使用 `BINARY` 精确比较，不能依赖连接默认排序规则，否则测试库会报 `Illegal mix of collations`。

两个脚本的执行目标都是 `aifuli-supplyhub-test` 命名空间对应的测试环境 **system 数据源**（当前库名 `aifuli-mall-test_hub`），不是旧 `aifuli-mall-test`，也不是 `supply-chain-test`。本任务发布顺序固定为：先执行 schema 扩展脚本 → 发布新版 `system-server` → 执行供应链菜单与角色迁移脚本 → 通过 system 服务或明确运维步骤清理 `permission_menu_ids`、`menu_role_ids` 等相关缓存 → 等待或重启供应链进程使一分钟权限判断缓存失效 → 发布 `supply-chain-server`。独立前端任务只能在这些后端步骤验收通过后发布；禁止在 system 尚未过滤应用时提前插入供应链 PAGE 菜单，也禁止前端先删静态路由再提供动态菜单。

发布 `supply-chain-server` 时不得把工作区未提交的 `022-database-local-time` 打进本任务镜像。`c49324f5` 已用变更集 id `021-supplier-admin-plaintext-echo` 执行过 `021-supplier-admin-plaintext-echo.sql`；合并后的 `f432e497` 把同一 SQL 编号为 `022-supplier-admin-plaintext-echo`。该变更集无论 id 是 `021` 还是 `022`，都必须带 `preConditions: onFail: MARK_RAN`，在 `sc_supplier.legal_person_id_no` 已存在时标记已执行，禁止重复 `ADD COLUMN`。本任务镜像只含 RBAC Java 与该兼容 precondition，不执行本地时间迁移。

## 9. 测试门禁

对齐 `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁`。

`*ArchitectureTest`：

- `interfaceapi.admin` 每个对外 `@RequestMapping` 方法有 `@PreAuthorize`。
- `SupplierAdminController` / `BuyerAdminController` / `ProjectAdminController` 的方法注解含对应细权限字符串，并含 `supply-chain:admin`。
- 未拆 AdminController 的权限字符串仅为 `supply-chain:admin`（可与超管放行并存）。
- 管理端包不得依赖 yudao 数据权限注解去标 `sc_*`。
- `system_menu.application_code` 是菜单维护兼容字段，不得影响权限信息接口结果，也不得成为供应链业务接口的授权条件。

`*Test`：

- HTTP 方法 → 权限动作对照（§5）与「合作商品 PATCH 走 update」断言。
- SKU 查询组合权限：无资源 code、单资源 code、多资源 code、`admin` 超集。
- 统一菜单投影：省略 `applicationCode`、传历史合法值或未知值都合并返回全部应用的已授权菜单。
- 菜单维护忽略应用参数：在 `supply-chain-admin` 历史父节点下新增目录/菜单时，即使前端省略或传入其它 `applicationCode` 也成功，落库兼容值继承父节点；列表过滤参数不改变返回集合。
- 旧 `supply-chain:admin` 角色无需复制子树关系即可获得供应链目录、页面和按钮；普通细权限角色能补齐目录但不会因此获得 `admin`；重复执行 SQL 不产生重复菜单或角色关系。

`*ContractTest`：使用已启用 Spring Method Security 的测试上下文和 stub `PermissionCommonApi`，不得只反射注解文本：

- 仅 `supply-chain:admin` 与仅对应细权限都能通过；无权限、错资源权限返回 `403`。
- `get-permission-info` 保留可选 `applicationCode` 参数但忽略其值；省略、合法值和未知值均返回相同的跨应用聚合结果，菜单组件字段稳定。
- 权限信息响应中的权限数组同时支持未来前端按 `admin` 与细权限 OR 语义消费。

不要求本切片拉起真实 `system-server` 与供应链服务组成全自动环境，但真实联调必须写入 `docs/集成测试.md`：

- 仅 `supply-chain:admin`：供应商、分销商、项目资源接口与订单只读接口仍全部成功（r8）。
- 仅 `supplier:query`：供应商 GET 成功，供应商 PATCH 状态 `403`，分销商 GET `403`。
- 未登录：业务 `401`。
- 已登录无任何供应链权限：`403`。
- 同一用户省略 `applicationCode`，或传 `mall-admin`、`supply-chain-admin`、未知值时，返回完全相同的已授权菜单和权限。
- 角色权限变更后按发布规则处理缓存，并验证收权与授权都在约定时间内生效。

动态路由、直接粘贴 URL 和按钮显示属于独立前端任务的验收范围，不纳入本任务门禁。

## 10. 明确不做

- 中台自建运营账号、密码、会话。
- 本轮数据范围表与授权 UI。
- 任何前端工程代码，包括 `remaining.ts`、动态路由加载、按钮指令和业务 mock 开关。
- 账户调账、路由、连接器、主数据旧入口的细权限。
- 看明文手机号/银行卡、导出。
- 关闭 `supplychainhubweb` 的供应商/分销商 mock，或在本切片顺带完成 REST 字段对接。
- 把 Facade `scope` 与 yudao 权限合并。
- 按 yudao `tenant_id` 切分中台主数据。

## 11. 实现顺序（本切片，规格批准后）

1. system 菜单历史应用字段落地：`application_code` 数据模型、管理端菜单 VO/查询和缓存继续保留；权限信息接口改为忽略兼容参数并统一聚合。
2. system 库 schema 扩展脚本先加兼容列；发布新版 `system-server` 后再执行供应链菜单脚本。
3. 供应链权限常量、三个 Controller、类目/SKU 的方法级 `@PreAuthorize` 和 SKU 组合权限组件。
4. ArchitectureTest、方法安全 ContractTest、菜单隔离/SQL 幂等测试。
5. 按 §8.3 的后端顺序部署，完成真实菜单投影、角色、缓存、旧 `admin` 与 `403` 联调，并更新 `docs/集成测试.md`。
6. 将统一动态路由和按钮权限契约移交独立前端任务/团队；前端可继续传 `applicationCode=supply-chain-admin`，但不得依赖其过滤效果；前端发布不属于本任务。
