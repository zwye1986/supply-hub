# 供货配置变更移除合作商品实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans 按步骤实施；本会话直接执行，不创建或清理宿主工作区。

**Goal:** 商品类型或分类编辑时原合作商品退役，与配置及定价原子提交。

**Architecture:** ScopeLine/HTTP/前端透传id；保存前验证身份和完整请求，以旧配置判定目标；合作商品服务复用退役与升序SKU事件；保留列表只读重匹配。

**Tech Stack:** Java21、Spring、MyBatis Plus、JUnit5、Testcontainers MySQL8.4/Liquibase、Vue3/TypeScript、Node test。

## 1. 设计与失败测试
- [x] 设计先落盘：`docs/superpowers/specs/2026-10-20-supply-scope-change-retirement-design.md`。
- [x] 在`SupplierSupplyScopeMatchingIntegrationTest`添加真实保存变更导致退役断言，通过JSON携带id而允许旧实现忽略未知字段，确认状态断言RED；新增正常保存保留、旧引用兼容、其它配置仍覆盖、回滚和并发。
- [x] HTTP/单测补id与局部RC、非法身份和升序事件验证。

## 2. 后端实现
- [x] `SupplierSupplyScopeService`：`ScopeLine(Long id, ...)`，保留六参数Java构造入口；RC守卫和供应商锁后验证行id/旧客户端精确三元组映射；计算变更id后调用合作商品批量退役；重建保留旧行id。
- [x] `SupplierCooperativeProductService`：新增包内范围变更退役方法，旧引用有效时按身份、悬空时按旧范围唯一匹配，涉及编辑的歧义拒绝；所有目标状态先写入，再SKU去重排序发布事件；手动retire复用写入与事件方法。
- [x] `SupplierAdminController.ScopeReqVO.id`和映射；更新OpenAPI说明。
- [x] 单测 `SupplierSupplyScopeServiceTest`构造依赖与契约按新接口对齐；旧读侧历史重建测试用夹具模拟历史行为，不用新的编辑保存制造悬空。

## 3. 前端
- [x] `/Users/mac/codespace/supplychainhubweb/src/api/supplier/manage/types.ts`：可选id。
- [x] `SupplyConfig.vue`：保存携带id，加载保存旧类型/分类快照，保存前判断并二次确认；添加页面提示。必要的纯投影/判定置于邻近`utils/supply-scope.ts`便于执行测试。
- [x] Node真实SFC测试447项、目标ESLint及build:dev通过；ts:check仍56项原有/并行改动诊断，与还原本次文件的临时副本基线一致，无新增诊断。

## 4. 验证与文档
- [x] JDK21 Maven定向RED/GREEN，最终final-4共2571项全绿；API125+server2357+适用集成/契约89，不跳过供应链普通测试。
- [x] `git diff --check`，复核生产diff、旧引用归属、同步事件回滚、无生命周期绕过。
- [x] 在`docs/集成测试.md`追加命令、失败诊断和实际通过结果；本地与部署验收区分，记录未运行项。
- [x] 本地交付，不提交、不推送、不部署；验证证据在`/tmp/scope-retirement-evidence/`，实机前后端验收NOT_RUN。
