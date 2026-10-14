# 华昇错误分类与失败闭环实施计划

设计依据：`docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md`。
用户已授权评审并补完未提交实现；保留已有工作，不提交、不推送、不部署、不调用真实供应商、不动存量资金。

## 1. 固定评审发现与失败回归

- 现有纯策略／Codec／恢复集成测试扩展：取消与价格保护、历史成功阻止释放、V2 候选四次与重启保真、5／15／30／60 秒退避、异常不计数、已建单永久退出。
- 人工命令使用真实 MySQL、Liquibase、Spring 事务验证开始标记与 watchdog 原子性、租约围栏、幂等、过期、第四次有效核实、资金及审计回滚。
- 原始未提交改动先保存本地快照；测试红灯、修复与最终门禁分别留证。

## 2. 自动流程与共同动账边界

- 保持所有 Checkpoint 复制的候选与保护事实，完善原始错误端点／发送证据及安全错误分类。
- 四次有效 7014 自动失败，合法取消为 BOrder CANCELLED；历史成功、待核资产、提卡、身份冲突、已扣款和资金状态矛盾禁止释放。
- 失败事务按原预留剩余金额释放，完整组合幂等，不重复状态日志；完成日志在提交后。

## 3. 人工两步命令（独立工作单元）

- 修复受理／开始／结果／watchdog 的真实短事务，HTTP 外呼在事务外；迟到结果不可恢复资格。
- 核实共享候选退避／有效次数，允许第 4 次核实形成新鲜证据，但不动账。
- 失败确认复核受保护证据、版本、代次、资金与完整历史；真实 actor 结案，命令审计同事务。
- 前向 SQL 类型、CHECK、权限部署 SQL、异常与日志规范补齐；不运行共享迁移。

## 4. 查询契约与管理端（独立工作单元）

- 后端管理平台／供应商订单保留 MANUAL／CANCELLED，追加可选原因、价差、资金摘要及 Case 关联；项目 v1／v2／RPC 只追加公共原因。
- 前端在 `/Users/mac/codespace/supplychainhubweb` 接入真实契约，详情内两步命令，权限与后端操作条件共同控制，切换与重复提交有围栏。
- 复用真实 mapper／SFC 测试，类型检查、构建、代表状态浏览器截图并检查；不扩展业务类型／供货模式缺口。

## 5. 合并验证与交付

- 先定向红绿回归，再全量供应链 Test／Contract／Architecture；真实 MySQL 相关履约、资金、回调、重启、迁移与命令并发门禁。
- 避免多个 Maven 进程共享 target；完整测试使用 JDK21、Docker/Testcontainers，不运行旧的破坏性根脚本。
- 更新 `docs/集成测试.md`，区分本地自动化、前端渲染和未执行的真实联调。
- 本地未提交交付，报告实际结果、限制与待授权发布步骤。

## 6. 接管集成结果（2026-10-13）

- 步骤 1～4 已完成本地实现与集成，保留此前正确实现及前端工作；不从零重写，不重下 PLACE。
- 自动未建单四份持久 QUERY 证明、取消／退款零交付、价差审计、历史资产／成功围栏和同事务预留释放已有真实 MySQL 回归。
- 人工两步命令、活动意图唯一键、指纹重放、短事务 STARTED 与 watchdog、代次／新鲜度、迟到证据、真实 actor 与结案回滚已集成。
- 供应链全量 Test／Contract／Architecture 2779 项通过（API 171、server 2608）；相关 11 类 MySQL Integration 91 项通过。Case 17 项另定向复跑，包括并发确认恰一条 RELEASE、账户预留检查与并发 COMMIT 互斥。完整命令、红绿灯及发布后待验收记录见 `docs/集成测试.md#2026-10-13-华昇错误分类与失败关单接管集成本地未发布`。
- 前端既有实现已独立验证：定向 8 项通过，构建成功；全量 240／241 与 54 项类型错误均有 HEAD 基线对照，无新增。本线程未覆盖或另改前端实现。离线真实 SFC 渲染、强确认与页面围栏已检视，不代表真实后端／权限／供应商联调。
- 未执行真实交易、共享迁移、提交、推送或部署。后端达到本任务本地门禁；全仓联合全绿仍受前端基线阻断，真实联调与发布验收仍 NOT_RUN，不能宣称已上线或已处置历史原单。

### 本次接管实际补完的文件范围

后端 Java 路径均位于 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/`：

- `border/fulfillment/application/FulfillmentCaseAdminService.java`、`FulfillmentCaseVerifyExecutionService.java`：事务代理、命令围栏、核实共享候选／退避、确认代次顺序、原单号与资金安全检查；`huashengflow/HuashengCaseVerifyTimerHandler.java`、`HuashengCaseVerifyFinalizeTimerHandler.java`：异常传播与持久收尾。
- `border/fulfillment/application/SupplierOrderLifecycle.java`、`huashengflow/HuashengFulfillmentLifecycle.java`：独立加密事实、历史保护、冲突回调留证、锁后共同关单与同事务释放。
- `border/fulfillment/application/huashengflow/HuashengCheckpoint.java`、`HuashengCheckpointCodec.java`、`HuashengFlowPolicy.java`、`HuashengErrorClassification.java`：V1/V2 兼容、候选复制、查询序次与有效次数分离、退避、最后合法查询收敛。
- `border/fulfillment/dal/dataobject/FulfillmentCaseActionDO.java`、`dal/mysql/FulfillmentCaseActionMapper.java`、`domain/FulfillmentCaseActionCodes.java`：字符串密钥版本、当前锁读及集中命令／执行状态码。
- `interfaceapi/admin/FulfillmentCaseAdminController.java`：内部 ADMIN 与细粒度 RBAC、真实 actor、no-store；`supplieradapter/huasheng/protocol/HuashengBusinessError.java`、`HuashengSecurityReason.java`：协议所有权与明确安全原因。
- `tradecore/account/application/FundReservationService.java`：只读展示与事务内锁定快照分开，border 不进入账户／账本 DAL。
- `src/main/resources/db/changelog/releases/1.0.0/066-huasheng-error-classification-and-case-action.sql`：字符串密钥类型、独立结果事实、命令唯一意图及 NULL 安全 CHECK；不改旧迁移。
- `src/test/java/.../huashengflow/{FulfillmentCaseIntegrationTest,HuashengFlowPolicyTest,HuashengRecoveryIntegrationTest,HuashengRestartIntegrationTest}.java`、`.../infrastructure/{FulfillmentCaseSchemaIntegrationTest,configuration/HuashengFulfillmentConfigurationTest}.java`：复现与防回归。配置测试补真实依赖挡板；旧恢复测试只修到期前置与资金断言，不删安全断言。
- 最新设计文档、本计划与 `docs/集成测试.md`：先更新关键实现决策，再记录本地验证与待授权实机步骤。

原有查询 DTO／视图、菜单权限、供应商取消状态及前端 mapper／页面／测试等改动保留并验证；上述范围是接管增量，不将此前的全部工作归为本线程新写。
