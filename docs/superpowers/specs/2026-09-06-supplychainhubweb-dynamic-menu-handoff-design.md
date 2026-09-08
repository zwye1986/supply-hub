# supplychainhubweb 动态菜单与按钮权限 — 前端移交方案

给 `supplychainhubweb` 前端开发人员。后端菜单投影、细权限接口和测试环境联调已完成；本仓库**不修改**前端工程。请按本文改前端并自行发布。

后端契约来源：

- `docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md`（权限标识、菜单树、超集规则）
- `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md` 第 11 章（关 mock 是后续任务，不要和本方案捆在一起）
- 联调记录：`docs/集成测试.md` §7.13

## 1. 目标

登录供应链独立站点后：

1. 侧栏只显示当前账号在 `supply-chain-admin` 下有权的页面。
2. 页面按钮按细权限显示/隐藏；旧权限 `supply-chain:admin` 仍能看到全部按钮。
3. 没有对应 `*:query`、也没有 `supply-chain:admin` 时，该页不出现在侧栏，直接粘贴 URL 不能再靠静态路由进去。

本方案**不关 mock**，不改供应商/分销商/项目的 REST 字段对接。页面可以继续读本地 mock；只改「谁能进页、谁能看见按钮」。

## 2. 不要做

- 不要改商城后台工程；`applicationCode` 省略时后端仍当 `mall-admin`，商城站点不要传 `supply-chain-admin`。
- 不要为迁就现有 `/scm/**` 再做后端适配层。
- 不要关掉 `USE_SUPPLIER_MOCK` / `USE_DISTRIBUTOR_MOCK`（当前均为 `true`）。关 mock 是独立任务，清单见前端 API 设计第 11 章后半。
- 不要自己往 `system_menu` 插菜单；测试库已有 17 条（2 目录 + 3 页面 + 12 按钮）。
- 不要把按钮隐藏当成安全控制。接口仍由供应链 `@PreAuthorize` 返回业务码 `403`。
- 不要打现网网关 `58080` / `https://aifuli-test.qihaofu.cn` 验收本功能。菜单应用隔离在 **supplyhub 测试栈**。

## 3. 现状（前端仓库）

仓库：`supplychainhubweb`。

| 点 | 现状 | 问题 |
| --- | --- | --- |
| 权限信息 | `src/api/login/index.ts` 的 `getInfo()` 请求 `/system/auth/get-permission-info`，**不带** `applicationCode` | 后端默认 `mall-admin`，不会返回供应链菜单 |
| 动态路由 | `src/store/modules/user.ts` 把 `userInfo.menus` 写入 `ROLE_ROUTERS`；`src/store/modules/permission.ts` 用 `generateRoute` 注册 | 机制可用，但目前拿到的是商城菜单 |
| 静态三页 | `src/router/modules/remaining.ts` 末尾无条件挂了 `/supplier` 下供应商、分销商、项目 | 任何登录用户都能进这三页 |
| 按钮 | 供应链三个页面**没有** `v-hasPermi`；仓库其它模块已有该指令 | 仅 query 的账号仍能点新增/暂停 |
| 指令语义 | `src/directives/permission/hasPermi.ts`：数组里**任一**命中即显示；超管 `*:*:*` 放行 | 与后端 `hasAnyPermissions(admin, 细权限)` 一致，数组里要同时放 `admin` 和细权限 |
| Mock | `src/api/supplier/manage/index.ts`、`src/api/supplier/distribution/index.ts` | 本方案保持 `true` |

动态组件解析已支持后端 `component` 如 `supplier/manage/index`（`src/utils/routerHelper.ts` 的 `generateRoute`），菜单 SQL 已按此登记，不必再写一套静态 children。

## 4. 后端已就绪（前端可当契约）

### 4.1 接口

```http
GET /admin-api/system/auth/get-permission-info?applicationCode=supply-chain-admin
Authorization: Bearer <token>
tenant-id: 1
```

- **必须**显式传 `applicationCode=supply-chain-admin`。省略 = 商城菜单。
- 合法值只有 `mall-admin`、`supply-chain-admin`。其它值业务码 `1002001007`（管理端应用标识不合法），不会回退成全量菜单。
- 未登录：业务码 `401`。
- 应用标识只过滤菜单投影，伪造它拿不到未授予的接口权限。

响应仍是现有结构，前端继续用 `permissions` + `menus`：

| 字段 | 用途 |
| --- | --- |
| `user` / `roles` | 与现在相同 |
| `permissions` | 字符串集合，给 `v-hasPermi` / `userStore.permissions` |
| `menus` | 目录/页面树，给 `generateRoute`。按钮类型不会进这棵树 |

### 4.2 仅 `supply-chain:admin`（旧管理员）

权限数组会带上 `supply-chain:admin` 以及该应用全部按钮权限。`menus` 为整棵供应链树（根目录 + 分销目录 + 三页）。前端不必为旧管理员复制子菜单。

yudao 超管走框架 `*:*:*`，同样应看到三页。

### 4.3 仅细权限（例：只有 `supplier:query`）

- `menus` 只有供应商页（加祖先目录「供应链中台」以便成树）。目录节点的 `permission` 是 `supply-chain:admin`，**不会**因此把 `admin` 写进 `permissions`。
- `permissions` 不含 `supply-chain:admin`、不含 `update-status` 等未授予按钮。
- 供应商 GET 成功；供应商状态 `PATCH`、分销商 GET 为业务码 `403`。

### 4.4 无供应链权限

`menus` 为空（没有这三页）。供应链接口 `403`。首页等 `remaining.ts` 里原有的 `/index`、登录、403/404 仍可走静态路由。

### 4.5 菜单树与组件（已入库，勿改路径）

| 名称 | path | component | componentName | 页面 permission |
| --- | --- | --- | --- | --- |
| 供应链中台（目录） | `/supplier` | 空 | `SupplierCenter` | `supply-chain:admin` |
| 供应商管理 | `manage` | `supplier/manage/index` | `SupplierManage` | `supply-chain:supplier:query` |
| 分销管理（目录） | `distribution` | 空 | `SupplierDistribution` | 空 |
| 分销商管理 | `distributor` | `supplier/distribution/distributor/index` | `SupplierDistributor` | `supply-chain:buyer:query` |
| 项目管理 | `project` | `supplier/distribution/project/index` | `SupplierDistributionProject` | `supply-chain:project:query` |

完整前端路径与现在静态路由一致：

- `/supplier/manage`
- `/supplier/distribution/distributor`
- `/supplier/distribution/project`

表单/详情目前是页内弹窗，不是独立路由，不必向 `system_menu` 再加 PAGE。

### 4.6 权限标识

格式 `supply-chain:{resource}:{action}`。`resource`：`supplier` / `buyer` / `project`。`action`：`query` / `create` / `update` / `delete` / `update-status`。

注意：代码和表仍是 `Buyer`，页面文案是「分销商」。权限字符串是 `supply-chain:buyer:*`，不要写成 `distributor`。

`delete` 只表示逻辑移除**子资源**（合作商品 / 分销商品 / 项目商品），没有「删除供应商/分销商/项目主体」的权限。合作商品上架/下架走 `supplier:update`，不是 `update-status`。`update-status` 只用于主体暂停/恢复/拉黑（项目为进行中/暂停/已结束）。

## 5. 前端改动步骤

按顺序做。先能按角色出菜单，再藏按钮。每步都要能单独验收。

### 5.1 权限信息带上应用标识

**文件：** `src/api/login/index.ts`

把 `getInfo` 改为请求：

```ts
params: { applicationCode: 'supply-chain-admin' }
```

建议抽常量，例如 `SUPPLY_CHAIN_ADMIN_APPLICATION_CODE = 'supply-chain-admin'`，禁止散落其它拼写。

### 5.2 不要用商城投影的本地缓存顶替本次结果

**文件：** `src/store/modules/user.ts`

现在会读 `CACHE_KEY.USER`。若缓存里是旧的、不带 `applicationCode` 的 `get-permission-info` 结果，侧栏会继续没有供应链菜单或串了商城菜单。

要求：

- 本站点每次 `setUserInfoAction` 都必须用 `applicationCode=supply-chain-admin` 拉最新结果。
- 若继续写 `wsCache`，缓存键要能区分应用（例如带 `supply-chain-admin` 后缀），或登录后先清 `USER` / `ROLE_ROUTERS` 再请求。
- 不要在请求失败时静默沿用一份 `mall-admin` 菜单。

### 5.3 删除静态三页

**文件：** `src/router/modules/remaining.ts`

删除对象 `path: '/supplier'` 整段（约「供应商中台」到三个 children）。**只删这一段**。保留 `/index`、登录、403/404、个人中心等。

删完后，三页只能来自 `permissionStore.generateRoutes()` → `router.addRoute`。无权限用户粘贴 `/supplier/manage` 应落到现有 404（或项目统一无权限页），不能再进页面。

发布顺序：先保证 5.1/5.2 在测试环境返回菜单，再删静态路由上线。禁止「先删静态、权限接口还没带 applicationCode」。

### 5.4 按钮加 `v-hasPermi`

数组必须同时包含 `supply-chain:admin` 和对应细权限，例如：

```vue
<el-button v-hasPermi="['supply-chain:admin', 'supply-chain:supplier:create']">新增</el-button>
```

列表页进详情/查看：有 `*:query` 即可，不要用 `update` 挡住只读用户。

| 界面 | 建议文件 | 控件 | 指令允许集合 |
| --- | --- | --- | --- |
| 供应商列表 | `src/views/supplier/manage/index.vue` | 新增 | `admin` + `supplier:create` |
| 同上 | 同上 | 批量暂停、行内暂停/恢复/移入黑名单 | `admin` + `supplier:update-status` |
| 供应商详情 | `src/views/supplier/manage/detail/index.vue` | 暂停/恢复/黑名单 | `admin` + `supplier:update-status` |
| 供应商表单 | `src/views/supplier/manage/form/index.vue` | 保存（新增模式） | `admin` + `supplier:create` |
| 同上 | 同上 | 保存（编辑模式） | `admin` + `supplier:update` |
| 供货配置 | `src/views/supplier/manage/detail/SupplyConfig.vue` | 添加/保存配置 | `admin` + `supplier:update` |
| 合作商品 | `src/views/supplier/manage/detail/ProductList.vue` | 新增商品、编辑、上架下架 | `admin` + `supplier:update` |
| 同上 | 同上 | 移除 | `admin` + `supplier:delete` |
| 分销商列表 | `src/views/supplier/distribution/distributor/index.vue` | 新增 | `admin` + `buyer:create` |
| 同上 | 同上 | 批量暂停、行内暂停/恢复/黑名单 | `admin` + `buyer:update-status` |
| 分销商详情/表单 | `distributor/detail/index.vue`、`form/index.vue` | 同供应商，把 `supplier` 换成 `buyer` | |
| 分销商品 | `distributor/detail/ProductList.vue` | 新增/改价 | `admin` + `buyer:update` |
| 同上 | 同上 | 移除 | `admin` + `buyer:delete` |
| 项目列表 | `src/views/supplier/distribution/project/index.vue` | 创建项目 | `admin` + `project:create` |
| 项目详情 | `project/detail/index.vue` | 暂停/恢复/结束等状态 | `admin` + `project:update-status` |
| 项目表单 | `project/form/index.vue` | 保存新增 / 保存编辑 | `create` / `update` |
| 项目商品 | `project/detail/ProductList.vue` | 新增/改 L3 | `admin` + `project:update` |
| 同上 | 同上 | 移除 | `admin` + `project:delete` |

脚本里没有「删除供应商主体」按钮权限；若页面上还有删除主体，应隐藏或删除该入口（后端也不提供物理删除）。

### 5.5 联调环境

| 项 | 值 |
| --- | --- |
| 站点 / 网关 | `https://supplyhubtest.qihaofu.cn`（pro143 内网 `59080`） |
| 接口前缀 | `/admin-api`（与现有 `VITE_API_URL` 一致） |
| 租户头 | `tenant-id: 1` |
| 本机 `.env.local` / `.env.dev` | 不要再用 `https://aifuli-test.qihaofu.cn`（那是旧 systemd `58080`，不是这套菜单库） |

建议本机：

```text
VITE_BASE_URL='https://supplyhubtest.qihaofu.cn'
VITE_API_URL=/admin-api
```

登录、菜单、供应链接口都走同一网关。超管可用现有管理端账号（密码不写进文档，在 system 用户管理重置）。专用验收账号（密码同样不入库，可重置）：

| 账号 | 预期菜单 | 预期按钮 / 接口 |
| --- | --- | --- |
| `scrbacadmin` | 三页都有 | 仅旧 `supply-chain:admin`：按钮全可见；三块 GET 成功 |
| `scrbacsupq` | 只有供应商页 | 新增/暂停应隐藏；若绕过 UI，状态 PATCH 与分销商 GET 为 `403` |
| `scrbacnone` | 没有这三页 | 粘贴 `/supplier/manage` 不能进页；接口 `403` |

收权后：权限信息里的菜单会马上变；供应链进程对 `@ss.hasAnyPermissions` 有约 **1 分钟**缓存，接口 403 可能略晚于菜单消失。验收时等约 65 秒，不要当成前端 bug。

## 6. 验收清单

用 supplyhub 测试栈，不要打 `58080`。

1. 超管或 `scrbacadmin`：侧栏「供应链中台」下三页都在；能打开列表（mock 数据即可）。
2. 同一账号请求里 `permissions` 含 `supply-chain:admin`（超管可能是 `*:*:*`）。
3. `scrbacsupq`：只有供应商管理；没有分销商/项目；新增、批量暂停、黑名单隐藏；能进供应商列表/详情查看。
4. `scrbacnone`：侧栏无这三页；地址栏进 `/supplier/manage` 为 404 或统一无权限页，不是供应商页面。
5. 浏览器 Network：`get-permission-info` 的 query 含 `applicationCode=supply-chain-admin`。
6. 故意改成 `applicationCode=foo`：业务码 `1002001007`，页面不应当成「无菜单的成功登录」而不提示。
7. 登出再登录、强制刷新后，菜单仍按角色，不因 `wsCache` 串成商城菜单。
8. `USE_SUPPLIER_MOCK` / `USE_DISTRIBUTOR_MOCK` 仍为 `true`。

## 7. 后续（不在本次）

关 mock、把 `/scm` 换成 `/admin-api/supply-chain/**`、金额分/枚举/状态 PATCH：见 `2026-09-02-supply-chain-admin-frontend-api-design.md` 第 11 章。开票对接 `GET/PUT …/suppliers|buyers/{code}/invoice`，见 `2026-09-07-供应链管理端开票信息设计.md`。账户、路由、连接器、加价模板、订单、售后页面本轮没有细权限，也没有动态菜单。数据范围（按供应商/分销商/项目 code 过滤）是更后面的 C，不要在本次做授权 UI。
