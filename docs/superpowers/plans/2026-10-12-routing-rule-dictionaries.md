# 路由规则系统字典实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供路由模式与规则状态两个 system 字典，不修改前端或路由业务逻辑。

**Architecture:** system-server 追加独立 Liquibase 变更集并提供同源热更 SQL，仅补缺失数据，保留已有管理员配置。复用字典精简列表 HTTP 接口，供应链继续使用封闭枚举与本地表约束。

**Tech Stack:** MySQL 8.4、Liquibase、JUnit 5、Testcontainers、MyBatis Plus、Spring MVC、Maven/JDK 21。

---

设计：`docs/superpowers/specs/2026-10-12-routing-rule-dictionaries-design.md`。

### Task 1: 先验证真实迁移契约

文件：`aifuli-module-system/aifuli-module-system-server/src/test/java/com/aifuli/module/system/service/dict/AdminFormDictionaryMigrationIntegrationTest.java`。

- [x] 扩展原首次/二次迁移测试，断言两个类型、四个精确编码/标签/排序/颜色及 `015-routing-rule-dictionaries` 唯一迁移记录；已有主清单包含菜单迁移，补齐测试库菜单前置条件，不修改生产菜单 SQL。
- [x] 独立断言模式聚合值为 `AUTO:自动路由:1:0:,FIXED:固定供应商:2:0:`，状态聚合值为 `ENABLED:启用:1:0:success,DISABLED:停用:2:0:info`；期望值不从待实现 SQL 推导。
- [x] JDK21标准 test 命令被既有system旧单测编译错误阻断；按 `docs/集成测试.md` 本次记录中的隔离编译命令执行目标真实MySQL测试，确认因为路由字典数量为0而失败，不把编译错误或Docker跳过视为红灯。日志 `.amp/in/routing-dict-red-isolated.log`。

### Task 2: 实现迁移和重放契约

文件：system 资源目录 `db/changelog/releases/1.0.0/015-routing-rule-dictionaries.sql`、`db.changelog-1.0.0.yaml`，热更文件 `script/deploy/aifuli-test/sql/system-supply-chain-routing-rule-dict.sql`。

- [x] SQL 使用 INSERT SELECT WHERE NOT EXISTS，精确定位未删除行，不添加 UPDATE 或 DELETE；具体两类四项取设计§2。
- [x] 主清单追加以下变更集；热更文件保持 SQL 同源。

```yaml
  - changeSet:
      id: 015-routing-rule-dictionaries
      author: supply-chain
      runInTransaction: true
      changes:
        - sqlFile:
            path: 015-routing-rule-dictionaries.sql
            relativeToChangelogFile: true
            encoding: UTF-8
            splitStatements: true
            stripComments: false
```

- [x] 原测试中修改既有模式标签/排序/颜色后直接重放 SQL 两次，验证 `人工自动:9:0:warning:custom:保留数据配置:manual` 保留；逻辑删除 DISABLED 后验证新活跃行补齐且原删除行仍在；使用真实 MyBatis Mapper/Service + MockMvc 请求 `/system/dict-data/simple-list` 验证响应，无 mock 字典数据。另验证先热更后正式迁移安全登记。
- [x] 重跑同一 system 真实 MySQL 测试，失败/错误/跳过均0；日志 `.amp/in/routing-dict-final.log`。

### Task 3: 回归与交付记录

文件：`docs/集成测试.md`、上述设计与计划。

- [x] 执行供应链门禁：`mvn -q -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Psupply-chain-integration '-Dtest=com.aifuli.module.supplychain.**.*Test,!**/*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=RoutingRuleAdminIntegrationTest,RoutingRuleAdminWriteIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify`。2689项通过，失败/错误/跳过均0；不运行其他模块单测。
- [x] 使用 `cmp` 验证正式 SQL 与热更 SQL 一致，执行 `git diff --check`，核对无前端、旧迁移或业务实现改动。
- [x] 集成文档记录命令、实际结果、部署前置条件、字典缓存重载、前端未接入边界；更新设计与本计划执行状态。未获明确授权不推送、不部署、不写共享数据库；本次不自动提交。
