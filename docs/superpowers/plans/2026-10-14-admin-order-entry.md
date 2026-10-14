# 管理端录入真实标准订单实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 平台订单管理页可以为真实项目录入单件标准 SKU 订单，复用权威下单链路。

**Architecture:** 管理端权限替代应用认证作为独立入口授权；服务端解析项目归属，共享平台SKU受理编排。项目商品投影提供真实目标 Schema，前端提交严格v2闭包，不接触密钥或价格。

**Tech Stack:** Java21、Spring Boot、MyBatis Plus、MySQL/Testcontainers、JUnit5、Vue3、Element Plus、Node test、Vite。

---

### Task 1: 后端测试先行与身份边界

文件：`interfaceapi/admin/OrderEntryAdminController.java`、`interfaceapi/admin/SupplyChainAdminPermissions.java`、`tradecore/buyer/application/BuyerProjectAccessResolver.java`、`application/facade/ProjectStandardOrderCommandFacade.java`（均在供应链server Java主源码包下）；测试扩展既有 `StandardProjectHttpClosureIntegrationTest.java`。

- [ ] 在既有 disposable MySQL HTTP fixture 中为录入用户配置方法权限，发送 `POST /admin-api/supply-chain/orders/entry/projects/{projectCode}`；断言受理、同参不重复冻结/Outbox、异目标冲突、开放v2同参回放。额外断言未登录/只读403、能力关闭与项目停用拒绝。
- [ ] 执行 `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Psupply-chain-integration -Dit.test=StandardProjectHttpClosureIntegrationTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false verify`，确认新增管理端请求在旧代码404。
- [ ] 新增管理员控制器与权限，正文调用 `parser.parseCreateV2(rawBody)`；按项目码取得可信快照并检查标准商品能力；调用 `facade.createFromAdmin(project, request)`。共享编排只改身份输入，不改受理逻辑。
- [ ] 复跑上述场景确认通过，检查原签名入口仍通过。提交只包含本任务文件，中文提交说明；不提交用户已有文档改动，不推送。

### Task 2: 候选与 Schema

文件：管理员录入控制器、`tradecore/catalog/application/ProjectListingQueryService.java`、系统菜单SQL。

- [ ] 测试仅录入权限即可读取 ACTIVE 项目分页/本项目关联商品；商品响应真实 Schema，不返回凭证。正文非法字段、数量字符串/非1拒绝。
- [ ] 项目候选复用 `ProjectPageQuery` 并固定 ACTIVE，商品复用 `ProjectListingQueryService.page`；列表行追加来自 `PlatformSkuDO` 的目标 Schema 编码/版本。菜单SQL新增 `supply-chain:order:create` 按钮幂等种子。
- [ ] 运行供应链单元/架构以及本HTTP集成fixture；检查SQL仅维护system菜单且不执行共享库写入。

### Task 3: 前端测试先行与弹窗

前端根目录 `/Users/mac/codespace/supplychainhubweb`。文件：`src/api/order/entry.ts`、`src/views/supplier/order/platform/OrderEntryDialog.vue`、`src/views/supplier/order/platform/index.vue`、`tests/order-entry.test.mjs`。

- [ ] 使用既有 `mountProductList` SFC harness 编写卡券真实提交、账号目标、未知结果保留同号、CAS、确认取消、项目切换乱序与重复点击测试，运行 `node --test tests/order-entry.test.mjs` 看新增功能缺失失败。
- [ ] API类型定义闭包：`{buyerOrderNo:string,platformSkuCode:string,quantity:1,target:Record<string,string>,previousReceiptVersion?:number}`。新增候选查询及创建请求，不使用订单mock。
- [ ] 弹窗公开 `open()`，按服务端 Schema 构造目标，项目与商品分页；UUID订单号、真实交易确认、回执状态/错误展示及明确CAS按钮。平台列表仅新增权限按钮和 success 刷新。
- [ ] 运行 `node --test tests/order-entry.test.mjs` 与 `node --test tests/*.test.mjs`，确认通过；运行受影响 eslint/prettier 检查与 `pnpm build:dev`。

### Task 4: 渲染与交付记录

- [ ] 用既有Vite/Element Plus fixture方式渲染真实弹窗；本地无业务网络替身展示卡券、账号、拒绝回执，Playwright操作并截图，用view_media检查。临时fixture与截图放仓库本地忽略的 `.amp/in/`，保留用户预览服务器。
- [ ] 运行供应链完整单元/架构、受影响HTTP集成/契约、前端回归；检查实际测试数量与失败，不隐瞒问题。
- [ ] `docs/集成测试.md` 追加本轮步骤/证据，保留原用户修改。记录未进行真实供应商、共享部署与菜单SQL执行。
- [ ] 检查两仓diff及状态，只提交本轮代码/设计/计划/测试；最终交付明确本地状态、上线还需菜单权限配置/发布，不推送或部署。
