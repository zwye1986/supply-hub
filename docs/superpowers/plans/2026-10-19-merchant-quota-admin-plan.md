# 商户共享额度管理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付已确认的独立额度管理与真实追加审计，不更改准入、释放或生产启用状态。

**Architecture:** configure 是配置与审计唯一事务所有者；AdminService 负责读取与管理命令适配，Controller 负责登录身份、参数与权限。前端独立页面消费同一契约；存量历史只提示缺失，不回填。

**Tech Stack:** Java21、Spring、MyBatis Plus、Liquibase、MySQL8.4/Testcontainers、Vue3、Element Plus、Node test。

---

## 任务1：事务审计（先红后绿）

文件：`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/fulfillment/MerchantQuotaIntegrationTest.java`；同模块 `tradecore/supply/application/MerchantQuotaService.java`、`dal/dataobject/MerchantQuotaAuditDO.java`、`dal/mysql/MerchantQuotaAuditMapper.java`；`src/main/resources` 现有 changelog 下追加070。

- [x] 扩展真实MySQL用例：创建10/3、调整7/2后审计版本为0/1且before为10/3；用隔离库CHECK约束拒绝审计验证配置完整回滚；同版本竞争只有一条新审计。原触发器方案因测试账号无SUPER权限改用CHECK，不调整MySQL全局权限。
- [x] 使用JDK21执行 `mvn -o -B -ntp -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Psupply-chain-integration -Dtest=MerchantQuotaPolicyTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MerchantQuotaIntegrationTest#configurationHistoryIsAppendOnlyAndAuditFailureRollsBackBothCreateAndUpdate -Dfailsafe.failIfNoSpecifiedTests=true verify`；无审计表时1项断言失败/0错误，不使用skip参数制造通过。
- [x] 新增审计表/Mapper，在 configure 中捕获before，配置写入后插入审计，afterCommit记录事实；保持准入与释放代码不变。
- [x] 复跑用例，审计失败后配置/版本不变，正常操作留下前后值；公开管理入口兼测存量历史与CAS。

## 任务2：管理HTTP闭环

文件：同模块 `tradecore/supply/application/MerchantQuotaAdminService.java`、`interfaceapi/admin/MerchantQuotaAdminController.java`、`SupplyChainAdminPermissions.java`；API模块 `error/SupplyChainErrorCodeConstants.java`；测试 `interfaceapi/admin/MerchantQuotaAdminContractTest.java`。

- [x] 先加公开入口测试：分页精确过滤、无历史firstRecordedVersion=null、首次真实调整firstRecordedVersion>0；MockMvc真实方法授权验证query不能create/update，操作人只能来自登录上下文。
- [x] 先运行用例确认缺少能力，再实现page/detail/create/update/history及独立错误码。分页/历史GET使用一致快照；PUT按id解析不可变身份；所有Long标识/版本响应转字符串。初次HTTP测试为缺类编译失败，不记为行为RED。
- [x] 复跑契约和集成测试；补并发首次创建竞争，配置短事务READ_COMMITTED＋唯一键裁决，重复创建映射冲突，不吞系统失败。HTTP契约3项定向通过，并纳入最终组合复验。

## 任务3：独立管理页面

前端文件：`src/api/supplier/merchant-quota/index.ts`、`src/views/supplier/merchant-quota/index.vue`、`tests/merchant-quota.test.mjs`；后端菜单 `script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql`。

- [x] 使用现有真实SFC测试壳写无默认额度、必填原因、只读权限、更新只提交额度/版本/原因、冲突后不可重放、存量历史提示和晚响应测试；先因页面文件不存在6项失败，再实现后6项通过，不冒充既有缺陷反例。
- [x] 页面沿用现有样式，最小实现上述契约；菜单只新增幂等定义，不执行共享库写入。
- [x] 11:54时Node421项通过且类型54项与基线相同；并行商品建议价变更后20:50重跑435项通过，Prettier/ESLint定向通过；最新`pnpm ts:check`退出2、56项错误，无本页面错误。保留并行改动，不展开无关类型修复；前端新测试只经公开控件/按钮绑定验证，不依赖私有setupState。
- [x] Vite＋本机Chrome/CDP实测创建、调整、两种历史完整度、冲突/未确认写失败、只读权限；7张最终截图逐一检查，等加载/旧提示退场后重拍。仅模拟API/权限，不宣称真实登录/网关联调。

## 任务4：回归与交付记录

- [x] 同步五处迁移计数与空库/时间断言；最终组合五类迁移14项全部通过，保持72个changeSet/133表/320列DATETIME(3)，不改旧迁移。
- [x] 12:22最终verify退出0，2505项单测/架构及237项所选集成/契约全部通过；按本轮日志和报告mtime核对保全252＋17类XML。另20:50 monitor根配置/TraceFilter30项通过，不累计作供应链测试。
- [x] 更新 `docs/集成测试.md`（含§8.12）、本计划与局部证据/未执行共享环境步骤；自审和两仓diff检查通过。保留此前HTTP切片和其它并行改动。本管理切片仍本地未提交，未经授权不推送、部署、运行共享SQL或启用真实供给。

## 接续核验：以实际代码为准

- [x] 读取用户提供的两个公开线程的对话及工具记录，对照后端HEAD `686b4e5b`、两仓未提交实现与上述交付证据；仁跃闭环已提交，管理切片已实现，不重复开发，也不覆盖并行商品建议价改动。
- [x] 补管理HTTP边界回归：详情/历史权限拒绝、精确筛选透传、历史长整型字符串。补真实SFC历史抽屉切换商户与关闭重开后的旧成功/失败响应围栏；仅补既有合同验收，不把直接通过的测试伪称缺陷RED。独立进程去掉历史成功/失败围栏的两组变异均为2项断言失败，正常源码下8项额度测试通过。
- [x] 重新执行全供应链单测/架构和上述17类集成/契约组合：22:39 verify退出0，2505项单测/架构及237项组合通过；新增契约编写晚于组合编译，22:43再串行复验管理HTTP4项与主架构31项通过，不累计重复数。前端最新443项Node测试与定向格式/ESLint通过；类型仍56项错误、退出1，无额度模块诊断。按本轮日志/报告分类与mtime保全，不引用旧XML。
- [x] 同步`docs/集成测试.md`的接续结果与剩余外部门禁。完整MySQL门禁、真实登录/菜单/网关、生产额度/供应商向量/重投证据仍未完成；不凭空推进供应商Metadata或开启生产路径。
