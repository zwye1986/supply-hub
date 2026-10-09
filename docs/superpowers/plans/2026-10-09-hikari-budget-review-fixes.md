# Hikari 预算链路评审修复实施计划

**目标**：修复代码评审列出的依赖残留、预算旁路、恢复失败重跑及验证缺口，不改业务重试资格与非预算行为。

**依据**：`docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md` §4.3 评审修复协议、§4.4、§5.1、§6.3；用户已确认沿用现有包装器方案。

## 1. 连接与 control（同会话、失败关闭）
- [x] 在 configuration 测试包增加安装故障、半 SET、control 超期、SESSION 原 1 秒与二次收紧、prepare/metadata/isValid 返回超期、拒绝驱动 unwrap 测试；先运行确认 RED。
- [x] 修改 `StandardBudgetConnectionWrapper.java`：保存原值、统一前后 guard/network、封闭 control、存活 Statement 归还、幂等 cleanup/失败累积。
- [x] 增加 JDBC metadata/ResultSet 受控适配，封堵间接获取原 Connection/Statement。

## 2. Statement 超时与大更新
- [x] 在 `StandardBudgetReviewRegressionTest` / `StandardBudgetStatementWrapperTest` 覆盖原 queryTimeout=1、0/更大 setter、close 原值恢复、恢复故障和 executeLargeUpdate 各重载；Prepared/Callable 覆盖无参入口，首批已确认 RED。
- [x] 修改三个 StandardBudget Statement 包装器，control 后重新计算 q，执行返回后 guard，缓存归还前恢复 queryTimeout，禁止解包底层 Statement。

## 3. Spring cleanup 与真实隔离
- [x] 用真实 JdbcTransactionManager + 协调器故障注入测试 autoCommit/isolation 恢复失败，断言 cleanupFailed=true、执行次数=1、资源释放；readOnly 覆盖真实 manager 的 commit/rollback 清理。
- [x] 路由构造失败与淘汰异常保留 cause/suppressed；提前捕获驱动 Connection，避免 Hikari eviction 后 abort 仅落到已关闭代理。
- [x] 将真实 MySQL 路由测试纳入 `*IntegrationTest`，从恢复失败触发完整链路；查询 processlist、反复借用、占满最大容量并断言精确恢复。

## 4. 八进程依赖与选池
- [x] 八个 server/gateway POM 在 Nacos Config 入口排除 Druid 依赖。
- [x] Aifuli/System/Infra/Member 的动态 master/slave 配置显式 Hikari 类型；增加仓库配置/运行 classpath 回归门禁。
- [x] 执行全仓 `dependency:tree '-Dincludes=com.alibaba:druid*'` 和 JDK21 全仓 compile。无 Druid 命中，两条命令 BUILD SUCCESS。

## 5. 验证与联调证据
- [x] JDK21 Maven reactor 执行针对性回归、供应链默认全部单测/架构测试；仅跳过其它模块单测。最新预算针对性 72 项、供应链默认 2017 项通过。
- [x] 运行 `supply-chain-integration` 的预算集成测试和适用真实 MySQL/Liquibase 回归。预算 5 + Liquibase 3 + ClaimRecovery 13，共 21 项通过。
- [x] 更新 `docs/集成测试.md`，分开记录自动化结果、实机/八进程待验项；不宣称 B03 已解阻。
