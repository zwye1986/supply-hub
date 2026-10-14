# 管理端录入真实标准订单实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 平台订单管理页可以为真实项目录入单件标准 SKU 订单，复用权威下单链路。

**Architecture:** 管理端权限替代应用认证作为独立入口授权；服务端解析项目归属，共享平台SKU受理编排。项目商品投影提供真实目标 Schema，前端提交严格v2闭包，不接触密钥或价格。

**Tech Stack:** Java21、Spring Boot、MyBatis Plus、MySQL/Testcontainers、JUnit5、Vue3、Element Plus、Node test、Vite。

**执行结果（2026-10-14）：** 本地实现与验证完成。供应链2529项、共享路径/异常/网关42项通过；前端录入22项通过、构建及受影响静态检查通过。前端全量364通过/1项既有缺失文件失败，类型检查仍有53项既有诊断，未把既有失败记为通过。真实组件六种截图已检查；网络/候选为隔离夹具，未执行共享部署、菜单SQL或真实供应商交易。具体命令与发布后步骤见 `docs/集成测试.md#2026-10-14-管理端录入真实标准订单`。最终交付仅本地提交，不推送；集成文档只提交本轮追加部分，保留用户已有修改。

---

### Task 1: 后端测试先行与身份边界

文件：`interfaceapi/admin/OrderEntryAdminController.java`、`interfaceapi/admin/SupplyChainAdminPermissions.java`、`tradecore/buyer/application/BuyerProjectAccessResolver.java`、`application/facade/ProjectStandardOrderCommandFacade.java`（均在供应链server Java主源码包下）；测试扩展既有 `StandardProjectHttpClosureIntegrationTest.java`。

- [x] 在既有 disposable MySQL HTTP fixture 中为录入用户配置方法权限，发送 `POST /admin-api/supply-chain/orders/entry/projects/{projectCode}`；断言受理、同参不重复冻结/Outbox、异目标冲突、开放v2同参回放。额外断言未登录/只读403、能力关闭与项目停用拒绝。
- [x] 执行 `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Psupply-chain-integration -Dit.test=StandardProjectHttpClosureIntegrationTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false verify`，确认新增管理端请求在旧代码404。
- [x] 新增管理员控制器与权限，正文调用 `parser.parseCreateV2(rawBody)`；按项目码取得可信快照并检查标准商品能力；调用 `facade.createFromAdmin(project, request)`。共享编排只改身份输入，不改受理逻辑。
- [x] 复跑上述场景确认通过，检查原签名入口仍通过。提交只包含本任务文件，中文提交说明；不提交用户已有文档改动，不推送。

### Task 2: 候选与 Schema

文件：管理员录入控制器、`tradecore/catalog/application/ProjectListingQueryService.java`、系统菜单SQL。

- [x] 测试仅录入权限即可读取 ACTIVE 项目分页/本项目关联商品；商品响应真实 Schema，不返回凭证。正文非法字段、数量字符串/非1拒绝。
- [x] 项目候选复用 `ProjectPageQuery` 并固定 ACTIVE，商品复用 `ProjectListingQueryService.page`；列表行追加来自 `PlatformSkuDO` 的目标 Schema 编码/版本。菜单SQL新增 `supply-chain:order:create` 按钮幂等种子。
- [x] 运行供应链单元/架构以及本HTTP集成fixture；检查SQL仅维护system菜单且不执行共享库写入。

### Task 3: 前端测试先行与弹窗

前端根目录 `/Users/mac/codespace/supplychainhubweb`。文件：`src/api/order/entry.ts`、`src/views/supplier/order/platform/OrderEntryDialog.vue`、`src/views/supplier/order/platform/index.vue`、`tests/order-entry.test.mjs`。

- [x] 使用既有 `mountProductList` SFC harness 编写卡券真实提交、账号目标、未知结果保留同号、CAS、确认取消、项目切换乱序与重复点击测试，运行 `node --test tests/order-entry.test.mjs` 看新增功能缺失失败。
- [x] API类型定义闭包：`{buyerOrderNo:string,platformSkuCode:string,quantity:1,target:Record<string,string>,previousReceiptVersion?:number}`。新增候选查询及创建请求，不使用订单mock。
- [x] 弹窗公开 `open()`，按服务端 Schema 构造目标，项目与商品分页；UUID订单号、真实交易确认、回执状态/错误展示及明确CAS按钮。平台列表仅新增权限按钮和 success 刷新。
- [x] 运行 `node --test tests/order-entry.test.mjs` 与 `node --test tests/*.test.mjs`，录入测试通过、全量如实记录1项既有失败；运行受影响 eslint/prettier 检查与 `pnpm build:dev`。

### Task 4: 渲染与交付记录

- [x] 用既有Vite/Element Plus fixture方式渲染真实弹窗；本地无业务网络替身展示卡券、账号、拒绝回执，Playwright操作并截图，用view_media检查。临时fixture与截图放仓库本地忽略的 `.amp/in/`，保留用户预览服务器。
- [x] 运行供应链完整单元/架构、受影响HTTP集成/契约、前端回归；检查实际测试数量与失败，不隐瞒问题。
- [x] `docs/集成测试.md` 追加本轮步骤/证据，保留原用户修改。记录未进行真实供应商、共享部署与菜单SQL执行。
- [x] 检查两仓diff及状态，只提交本轮代码/设计/计划/测试；最终交付明确本地状态、上线还需菜单权限配置/发布，不推送或部署。

### Task 5: 用户补充要求——Liquibase 菜单与 admin 显式授权

设计：`docs/superpowers/specs/2026-10-14-admin-order-entry-design.md#5-数据与交付`。当前编号最大015，追加016菜单与017授权，不修改旧迁移。system-server资源根目录为 `aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/releases/1.0.0/`。

- [x] 扩展既有 `aifuli-module-system/aifuli-module-system-server/src/test/java/com/aifuli/module/system/service/dict/AdminFormDictionaryMigrationIntegrationTest.java` 的真实MySQL夹具，提供订单页面及最小授权表；使用非1的用户/角色/租户编号。新增断言完整主清单产生正确录入按钮、只授权admin已关联的启用super_admin、保留其它授权，先确认未实现时按钮数量0而失败。
- [x] 执行 `mvn -pl aifuli-module-system/aifuli-module-system-server -am -Dtest=AdminFormDictionaryMigrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，只运行受影响system集成，不执行其它模块纯单测。
- [x] 新增 `016-supply-chain-order-entry-menu.sql`：父页面解析、`WHERE NOT EXISTS`插入、按精确应用/权限收敛属性；在release YAML末尾注册 `sqlFile`，前置条件HALT校验列、有效订单页面唯一、有效录入按钮至多1行。
- [x] 新增 `017-supply-chain-order-entry-admin-grant.sql`：`system_users JOIN system_user_role JOIN system_role`，用户名admin与角色码super_admin都二进制比较；启用/未删除/租户相同，`SELECT DISTINCT role_id, tenant_id`去重，按有效租户/角色/菜单组合判重插入。YAML登记单独变更集，无合格admin或按钮时HALT，不新建任何角色/用户绑定。
- [x] 仅删除旧人工脚本的录入按钮定义，保留迁移指针与前后既有菜单块。
- [x] 复跑完整system集成；直接重放SQL及移除隔离库变更集记录后重放，覆盖漂移、软删除、大小写、跨租户错误关系、非目标角色、缺页面/缺合格admin/重复菜单失败关闭，确认SQL不会误授或增重复行。另用低id错误父页面先红后绿，确保实际定位与前置条件一致。
- [x] 更新 `docs/集成测试.md` 本轮段落与验证结果；确认001–015已提交SQL与YAML前缀逐字节未变，运行供应链架构门禁。只提交本任务文件与本轮文档内容，保留既有未提交部署记录；不推送/部署/写共享库。

补充结果：Java21 / MySQL8.4 完整system迁移集成4项、供应链架构31项全部通过，无失败/错误/跳过。发布后步骤由人工录入菜单/赋权改为system-server执行016/017后刷新admin权限；缺前置身份/菜单会阻止启动。未执行共享环境迁移或部署。
