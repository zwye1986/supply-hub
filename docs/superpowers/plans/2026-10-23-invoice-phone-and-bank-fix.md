# 供应商与分销商开票信息修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 两端电话选填且允许清空，个人抬头保留银行信息；不修改前端。

**Architecture:** 复用 `InvoiceFieldRules` 修正共享规则，两个开票 DO 允许电话空值覆盖，追加 Liquibase 069 放开两张开票表的电话非空约束。保持 GET 映射、档案预填、权限、事务及日志不变。

**Tech Stack:** Java 21、Spring Boot、MyBatis Plus、Liquibase、JUnit 5、MySQL 8.4 / Testcontainers。

---

## 1. 设计与红灯

- [x] 更新 `docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md` §6.1、§7、§10，先确定电话可空、个人银行字段保留、个人信用代码仍清空。
- [x] 运行原有 `InvoiceFieldRulesTest,BuyerInvoiceServiceTest,SupplierInvoiceServiceTest`，建立 12 项通过的基线。
- [x] 更新单测的旧清空断言，覆盖两种抬头的 `null`、空串、纯空白电话，以及个人银行值去首尾空白后保留；保留抬头、企业字段及枚举拒绝测试。供应商既有单测无需修改。
- [x] 扩展 `tradecore/buyer/BuyerAdminIntegrationTest.java` 与 `tradecore/supply/SupplierAdminInvoiceIntegrationTest.java` 的已有开票案例，并加入两类抬头的新增与清空电话往返案例。使用真实 Mapper，断言保存响应、随后查询、数据库记录一致，不能以 Mapper mock 代替空值更新验证。
- [x] 执行定向单测及上述集成案例，确认旧实现在电话可空、个人银行保留处失败；仅修改规则与 DDL 后再次验证旧电话未清除的红灯。

## 2. 最小实现

生产文件均位于 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/`。

- [x] 修改 `src/main/java/com/aifuli/module/supplychain/tradecore/profile/application/InvoiceFieldRules.java`：

```java
if (title == null) {
    throw exception(INVOICE_FIELD_INVALID);
}
if (type == InvoiceTitleType.PERSONAL) {
    return new Normalized(type, title, phoneValue, null,
            blankToNull(bankName), blankToNull(bankAccount), blankToNull(invoiceAddress));
}
```

- [x] 在 `tradecore/buyer/dal/dataobject/BuyerInvoiceDO.java` 与 `tradecore/supply/dal/dataobject/SupplierInvoiceDO.java` 的 `phone` 字段增加 `@TableField(updateStrategy = FieldStrategy.ALWAYS)`，移除银行字段“个人须清空”的旧注释，保留 DDL 一致字段语义与设计链接。
- [x] 新增 `src/main/resources/db/changelog/releases/1.0.0/069-invoice-phone-optional.sql`，并在同目录 `db.changelog-1.0.0.yaml` 末尾注册：

```sql
ALTER TABLE sc_supplier_invoice MODIFY COLUMN phone VARCHAR(32) NULL COMMENT '电话';
ALTER TABLE sc_buyer_invoice MODIFY COLUMN phone VARCHAR(32) NULL COMMENT '电话';
```

## 3. 绿灯与交付

- [x] JDK21 下执行全部供应链单元与架构测试（仅跳过其它模块单测），API 127 项、server 2428 项通过：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -B -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am '-Dtest=com.aifuli.module.supplychain.**.*Test,!**/*IntegrationTest,!**/*ContractTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] 在 Testcontainers 独占 MySQL 中执行供应商/分销商完整管理端回归、完整 Liquibase 清单及 HTTP 权限/路径契约，81 项通过；最终与全部单元/架构合并运行，见 `docs/集成测试.md` 本次记录。定向命令：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -B -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Psupply-chain-integration -Dtest=InvoiceFieldRulesTest,BuyerInvoiceServiceTest,SupplierInvoiceServiceTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=BuyerAdminIntegrationTest,SupplierAdminInvoiceIntegrationTest,BuyerAdminRestContractTest,SupplierAdminRestContractTest,SupplyChainAdminPermissionContractTest,SupplyChainAdminMethodSecurityContractTest -Dfailsafe.failIfNoSpecifiedTests=false verify
```

- [x] 额外运行 `SupplyChainLiquibaseIntegrationTest`：2 个既有全局计数断言失败；以 HEAD 原清单复测同样失败（期望 67/实际 70、期望 128/实际 130），本次新增 069 后变更集为 71，表数仍为 130。保留该失败，不修改无关旧断言。
- [x] 用一次性本地数据库验证已有 023 表和非空电话记录升级后不丢数据；临时容器和数据库已删除。
- [x] 在 `docs/集成测试.md` 追加本次步骤、红绿灯证据、真实执行结果与共享环境尚未验证的边界，保留原有未提交内容。
- [x] `git diff --check` 与范围审阅通过，前端工作树无变化；未提交、推送、部署或迁移共享数据库。
