# 可售目录评审修复实施计划

> **For agentic workers:** 使用 `executing-plans` 当前会话逐项实施，不委派 subagent，不提交／推送／部署或外呼真实供应商。

**Goal:** 修复确认的后端 10 项及前端两项，连接容量按用户选择仅扩大共享池。

**Architecture:** 保留独立 Reader、预算数据源和领域事务；默认池 40，不预留或分池。管理入口拒绝外层事务，冻结回放先 claim 后当前解析，项目 Reader 首冻前及最终授权。扫描封闭限频与完整 MDC 作用域。

**Tech Stack:** Java 21 / Spring / MySQL / JUnit；Vue / TypeScript / Node 内建测试。

路径 J、T、W 沿用原计划 §1。保留已有未提交工作。设计入口为 `docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#31-2026-10-10-评审修复边界用户确认方案-3`。

## Task 1：容量及管理事务入口
- [x] RED：数据源配置测试断言默认 40；受理并发夹具移除专用 40。已有默认 10 并发失败证据保留。
- [x] RED：管理真实集成在 TransactionTemplate 调用四入口，断言 IllegalTransactionStateException 且无领域写入。
- [x] GREEN：SupplyChainDataSourceProperties 默认 40；四管理入口活动事务守卫；不改预算借连接和事务装配。
- [x] 回归：配置、管理集成、默认配置 10 并发受理及架构。

## Task 2：冻结回放与项目准入
- [x] RED：两 Facade 冻结 claim 回放不得 resolveTargetSchema/registry/encrypt。
- [x] GREEN：冻结分支仅指纹，claim 执行后当前身份与校验／加密。
- [x] RED：项目 A UNKNOWN/UNAVAILABLE、聚合可用仍首冻前拒绝；MANUAL 零共享写。
- [x] GREEN：RequestCoordinator 增加可信项目输入与 Reader；HTTP 跳过投影，RPC 保留。
- [x] 回归：Facade/准备单测，Closure MANUAL UNKNOWN HTTP 成功、RPC 拒绝和映射损坏回放。

## Task 3：扫描停止／因果／限频／MDC
- [x] RED：首条中断后余项不启动；执行上下文无旧 originTraceId，退出完整恢复。
- [x] RED：ListAppender 验证首次因果、持续抑制、业务无堆栈、窗口汇总／恢复与查询限频。
- [x] GREEN：每条中断检查、新 MDC；四桶 60 秒窗口、完整遍历恢复、单终结边界。
- [x] 回归：SweepService/Lifecycle 单测、真实扫描集成、直接日志配置回归。

## Task 4：前端与 AUTO 整链
- [x] RED：Node 内建测试编译 TS 提示，DEFERRED 扫描关闭不能承诺自动发布；四保存路径 reject 显示未确认、不重发。
- [x] GREEN：catalogSaveResult.ts 与四 Vue 保存调用处。
- [x] RED/GREEN：Closure 真实管理保存→AUTO 发布→签名成功，不 SQL 补商业版本/AVAILABLE；断言订单／报价／路由／预留及金额。
- [x] 回归：Node／定向 lint／vue-tsc，供应链适用完整 Test/Integration/Contract/Architecture。

## 命令与证据

后端沿用原计划 §2 的 JDK21 Maven 离线命令，仅供应链单测。前端 `node --test tests/catalog-save-result.test.mjs`。日志存批准临时目录，RED/GREEN 和未运行范围更新 `docs/集成测试.md`，纠正原 Task7/Task12 过度完成声明。逐片只声明实际验证范围。
