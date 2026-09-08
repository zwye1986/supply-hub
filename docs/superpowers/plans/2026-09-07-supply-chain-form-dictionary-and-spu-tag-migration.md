# 供应链管理端表单字典与标品打标 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把供应商供货配置 / 分销商档案 / 平台 SPU 的「可售品类、商品类型、商品分类」从 `sc_product_category` 类目树 id 收回为三套独立封闭枚举（system 数据字典驱动前端展示），合作商品新增改为按 SPU 三码与供货配置三码整体相等命中。

**Architecture:** 新增 3 个供应链领域枚举（`SellableCategory`/`ProductTypeTag`/`ProductClassification`），system 新 Liquibase `002` 写数据字典，supply-chain 新 Liquibase `024` 把 `sc_supplier_supply_scope`、`sc_buyer_category_scope`、`sc_platform_spu` 的类目主键列换成枚举码列；应用层用 `ClosedEnums.parseRequired` 校验，删除不再使用的 `CategoryChainValidator`；REST 契约字段名同步改名，不改前端工程。

**Tech Stack:** Spring Boot / MyBatis-Plus（Lambda 查询）/ Liquibase（MySQL 8.4）/ JUnit 5 + Mockito + AssertJ / ArchUnit / Testcontainers。

**必读设计文档：**
- `docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md`（主文，本计划严格对齐其决策 d1–d10 与章节 1–8）
- `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md`（已收回 a6 类目树约定，本计划落地该收回）

**范围外（禁止做）：** 修改 `supplychainhubweb` 前端工程；建 `/scm` 适配层；回改 supply-chain `001`–`023` 或 system `001-supplier-form-dictionaries`；把 `sc_product_category` 同步进字典。

---

## File Structure

| 文件 | 职责 |
| --- | --- |
| `aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/releases/1.0.0/002-admin-form-dictionaries.sql` | 新建：三级枚举 + 分销商表单 5 类字典的幂等 INSERT/UPDATE |
| `aifuli-module-system/.../db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml` | 修改：注册 `002-admin-form-dictionaries` changeSet |
| `aifuli-module-system/.../test/.../dict/AdminFormDictionaryLiquibaseTest.java` | 新建：结构测试，镜像 `SupplyChainSupplierDictionaryLiquibaseTest` |
| `aifuli-module-system/.../test/.../dict/AdminFormDictionaryMigrationIntegrationTest.java` | 新建：Testcontainers MySQL 迁移测试，镜像 `SupplyChainSupplierDictionaryMigrationIntegrationTest` |
| `aifuli-module-supply-chain/.../tradecore/profile/domain/SellableCategory.java` | 新建：可售品类封闭枚举（10 值） |
| `aifuli-module-supply-chain/.../tradecore/profile/domain/ProductTypeTag.java` | 新建：商品类型封闭枚举（5 值） |
| `aifuli-module-supply-chain/.../tradecore/profile/domain/ProductClassification.java` | 新建：商品分类封闭枚举（3 值） |
| `aifuli-module-supply-chain/.../tradecore/buyer/domain/BuyerScopeKind.java` | 新建：分销商范围维度枚举 `SELLABLE`/`PRODUCT_TYPE` |
| `aifuli-module-supply-chain/.../db/changelog/releases/1.0.0/024-admin-form-dictionary-codes.sql` | 新建：供货配置/分销商范围/平台SPU 三码迁移 DDL+DML |
| `aifuli-module-supply-chain/.../db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml` | 修改：注册 `024-admin-form-dictionary-codes` changeSet |
| `aifuli-module-supply-chain/.../test/.../dict/AdminFormDictionaryCodesLiquibaseTest.java` | 新建：结构测试 |
| `aifuli-module-supply-chain/.../test/.../dict/AdminFormDictionaryCodesMigrationIntegrationTest.java` | 新建：Testcontainers 迁移测试 |
| `.../tradecore/supply/dal/dataobject/SupplierSupplyScopeDO.java` | 修改：`categoryL1/2/3Id` → `sellableCategory/productType/productClassification` |
| `.../tradecore/supply/application/SupplierSupplyScopeService.java` | 修改：去掉 `CategoryChainValidator`，三码校验+唯一性 |
| `.../tradecore/supply/application/SupplierProfileQueryService.java` | 修改：`SupplyScopeView`/`SupplierListItem` 改三码字符串，去掉类目名查找 |
| `.../tradecore/supply/application/SupplierCooperativeProductService.java` | 修改：`matchScope` 三码整体相等命中，`OfferListItem` 改三码字符串 |
| `.../interfaceapi/admin/SupplierAdminController.java` | 修改：`ScopeReqVO`、列表/合作商品筛选参数改三码字符串 |
| `.../tradecore/buyer/dal/dataobject/BuyerCategoryScopeDO.java` | 修改：`categoryId/categoryLevel` → `scopeKind/scopeCode` |
| `.../tradecore/buyer/application/BuyerAdminCommandService.java` | 修改：去掉 `CategoryChainValidator`，`replaceCategoryScopes` 用码 |
| `.../tradecore/buyer/application/BuyerAdminQueryService.java` | 修改：`BuyerDetail`/`BuyerListItem` 改 `List<String>` |
| `.../interfaceapi/admin/BuyerAdminController.java` | 修改：`BuyerSaveReqVO`、列表筛选参数改名 |
| `.../tradecore/catalog/dal/dataobject/PlatformSpuDO.java` | 修改：新增三码字段 |
| `.../tradecore/catalog/application/ProductCatalogCommandService.java` | 修改：`CreateSpuCommand` 三码必填校验，`primaryCategoryCode` 可空 |
| `.../interfaceapi/admin/M1MasterDataAdminController.java` | 修改：`CreateSpuReqVO` 三码必填，`primaryCategoryCode` 去 `@NotBlank` |
| `.../tradecore/catalog/application/PlatformSkuQueryService.java` | 修改：`SkuPageQuery`/`SkuListItem` 改三码，删除类目树遍历 |
| `.../interfaceapi/admin/PlatformSkuAdminController.java` | 修改：查询参数改三码字符串 |
| `.../tradecore/catalog/application/CategoryChainValidator.java` | 删除：不再有调用方 |
| `.../architecture/SupplyChainModuleArchitectureTest.java` | 修改：新增 `supply_matching_does_not_read_category_tree` 规则 |
| 各 `*ContractTest.java` / `*IntegrationTest.java` | 修改：JSON 字段名与请求体同步改名 |

---

### Task 1: system 数据字典 Liquibase `002-admin-form-dictionaries`

**Files:**
- Create: `aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/releases/1.0.0/002-admin-form-dictionaries.sql`
- Modify: `aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`
- Test: `aifuli-module-system/aifuli-module-system-server/src/test/java/com/aifuli/module/system/service/dict/AdminFormDictionaryLiquibaseTest.java`

- [ ] **Step 1: 写 SQL（镜像 `001-supplier-form-dictionaries.sql` 的幂等写法：按 `type` 判重插入，重复执行只同步名称/状态/备注）**

```sql
-- 供应链“新增分销商”表单数据字典 + 三级独立封闭枚举（可售品类/商品类型/商品分类）。
-- 目标库：system 主数据源；不要在 supply-chain-test 执行。
-- 设计：docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#3-分销商表单其它字典
-- 约束：字典值是供应链接口封闭枚举契约；脚本只同步已知值，不删除管理员额外创建的数据。
-- 不重复迁移 001 已落地的六类供应商字典（配送区域/发货时效/售后/结算方式/对接方式/供应商合作状态）。

SET @sc_dict_creator = 'system';

INSERT INTO system_dict_type
    (name, type, status, remark, creator, create_time, updater, update_time, deleted, deleted_time)
SELECT desired.name, desired.type, 0, desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0', NULL
FROM (
    SELECT '可售品类' name, 'supply_chain_sellable_category' type,
           '供应商供货配置与分销商档案共用；平台SPU同码打标' remark
    UNION ALL SELECT '商品类型', 'supply_chain_product_type',
                     '供应商供货配置与分销商档案共用；平台SPU同码打标'
    UNION ALL SELECT '商品分类', 'supply_chain_product_classification',
                     '仅供货配置行与平台SPU使用，分销商档案无此级'
    UNION ALL SELECT '分销等级', 'supply_chain_distribution_level',
                     '新增分销商：组织树等级，提交仍为整数'
    UNION ALL SELECT '合作模式', 'supply_chain_cooperation_mode',
                     '新增分销商：可多选'
    UNION ALL SELECT '分销商类型', 'supply_chain_distributor_type',
                     '新增分销商：单选'
    UNION ALL SELECT '供货择优倾向', 'supply_chain_supply_preference',
                     '新增分销商：不改变路由引擎'
    UNION ALL SELECT '分销商合作状态', 'supply_chain_buyer_status',
                     '新增分销商：不含归档态；文案与供应商合作状态区分'
) desired
WHERE NOT EXISTS (
    SELECT 1
    FROM system_dict_type existing
    WHERE CAST(existing.type AS BINARY) = CAST(desired.type AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_dict_type dict_type
JOIN (
    SELECT '可售品类' name, 'supply_chain_sellable_category' type,
           '供应商供货配置与分销商档案共用；平台SPU同码打标' remark
    UNION ALL SELECT '商品类型', 'supply_chain_product_type',
                     '供应商供货配置与分销商档案共用；平台SPU同码打标'
    UNION ALL SELECT '商品分类', 'supply_chain_product_classification',
                     '仅供货配置行与平台SPU使用，分销商档案无此级'
    UNION ALL SELECT '分销等级', 'supply_chain_distribution_level',
                     '新增分销商：组织树等级，提交仍为整数'
    UNION ALL SELECT '合作模式', 'supply_chain_cooperation_mode',
                     '新增分销商：可多选'
    UNION ALL SELECT '分销商类型', 'supply_chain_distributor_type',
                     '新增分销商：单选'
    UNION ALL SELECT '供货择优倾向', 'supply_chain_supply_preference',
                     '新增分销商：不改变路由引擎'
    UNION ALL SELECT '分销商合作状态', 'supply_chain_buyer_status',
                     '新增分销商：不含归档态；文案与供应商合作状态区分'
) desired ON CAST(desired.type AS BINARY) = CAST(dict_type.type AS BINARY)
SET dict_type.name = desired.name,
    dict_type.status = 0,
    dict_type.remark = desired.remark,
    dict_type.updater = @sc_dict_creator,
    dict_type.update_time = NOW()
WHERE dict_type.deleted = b'0';

INSERT INTO system_dict_data
    (sort, label, value, dict_type, status, color_type, css_class, remark,
     creator, create_time, updater, update_time, deleted)
SELECT desired.sort, desired.label, desired.value, desired.dict_type, 0,
       desired.color_type, '', desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0'
FROM (
    SELECT 1 sort, '权益商品' label, 'EQUITY' value,
           'supply_chain_sellable_category' dict_type, 'primary' color_type, '' remark
    UNION ALL SELECT 2, '实物商品', 'PHYSICAL', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 3, '电影快充', 'MOVIE', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 4, '京东', 'JD', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 5, '点餐', 'DINING', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 6, '外卖', 'TAKEOUT', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 7, '商超', 'SUPERMARKET', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 8, '盒马购', 'HEMA', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 9, '大润发', 'RTMART', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 10, '生活服务', 'LIFE', 'supply_chain_sellable_category', 'primary', ''

    UNION ALL SELECT 1, '权益商品', 'EQUITY_PRODUCT', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 2, '电影', 'MOVIE', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 3, '点餐', 'DINING', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 4, '京东', 'JD', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 5, '唯品会', 'VIP', 'supply_chain_product_type', 'success', ''

    UNION ALL SELECT 1, '数码电子', 'ELECTRONICS', 'supply_chain_product_classification', 'warning', ''
    UNION ALL SELECT 2, '虚拟卡券类', 'VIRTUAL_CARD', 'supply_chain_product_classification', 'warning', ''
    UNION ALL SELECT 3, '电影快票', 'MOVIE_TICKET', 'supply_chain_product_classification', 'warning', ''

    UNION ALL SELECT 1, '一级分销商', '1', 'supply_chain_distribution_level', 'primary', '提交为整数1'
    UNION ALL SELECT 2, '二级分销商', '2', 'supply_chain_distribution_level', 'primary', '提交为整数2'
    UNION ALL SELECT 3, '三级分销商', '3', 'supply_chain_distribution_level', 'primary', '提交为整数3'

    UNION ALL SELECT 1, '直营分销', 'DIRECT', 'supply_chain_cooperation_mode', 'success', ''
    UNION ALL SELECT 2, '代理分销', 'AGENT', 'supply_chain_cooperation_mode', 'success', ''
    UNION ALL SELECT 3, '渠道分销', 'CHANNEL', 'supply_chain_cooperation_mode', 'success', ''

    UNION ALL SELECT 1, '企业', 'ENTERPRISE', 'supply_chain_distributor_type', 'info', ''
    UNION ALL SELECT 2, '个人', 'PERSONAL', 'supply_chain_distributor_type', 'info', ''
    UNION ALL SELECT 3, '平台入驻', 'PLATFORM', 'supply_chain_distributor_type', 'info', ''

    UNION ALL SELECT 1, '稳定优先', 'STABLE', 'supply_chain_supply_preference', 'primary', '不改变路由引擎'
    UNION ALL SELECT 2, '价格优先', 'PRICE', 'supply_chain_supply_preference', 'primary', '不改变路由引擎'
    UNION ALL SELECT 3, '综合优先', 'COMPREHENSIVE', 'supply_chain_supply_preference', 'primary', '不改变路由引擎'

    UNION ALL SELECT 1, '正常', 'ACTIVE', 'supply_chain_buyer_status', 'success', ''
    UNION ALL SELECT 2, '暂停分销', 'SUSPENDED', 'supply_chain_buyer_status', 'warning', ''
    UNION ALL SELECT 3, '黑名单', 'BLACKLIST', 'supply_chain_buyer_status', 'danger', ''
) desired
WHERE NOT EXISTS (
    SELECT 1
    FROM system_dict_data existing
    WHERE CAST(existing.dict_type AS BINARY) = CAST(desired.dict_type AS BINARY)
      AND CAST(existing.value AS BINARY) = CAST(desired.value AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_dict_data dict_data
JOIN (
    SELECT 1 sort, '权益商品' label, 'EQUITY' value,
           'supply_chain_sellable_category' dict_type, 'primary' color_type, '' remark
    UNION ALL SELECT 2, '实物商品', 'PHYSICAL', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 3, '电影快充', 'MOVIE', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 4, '京东', 'JD', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 5, '点餐', 'DINING', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 6, '外卖', 'TAKEOUT', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 7, '商超', 'SUPERMARKET', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 8, '盒马购', 'HEMA', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 9, '大润发', 'RTMART', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 10, '生活服务', 'LIFE', 'supply_chain_sellable_category', 'primary', ''

    UNION ALL SELECT 1, '权益商品', 'EQUITY_PRODUCT', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 2, '电影', 'MOVIE', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 3, '点餐', 'DINING', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 4, '京东', 'JD', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 5, '唯品会', 'VIP', 'supply_chain_product_type', 'success', ''

    UNION ALL SELECT 1, '数码电子', 'ELECTRONICS', 'supply_chain_product_classification', 'warning', ''
    UNION ALL SELECT 2, '虚拟卡券类', 'VIRTUAL_CARD', 'supply_chain_product_classification', 'warning', ''
    UNION ALL SELECT 3, '电影快票', 'MOVIE_TICKET', 'supply_chain_product_classification', 'warning', ''

    UNION ALL SELECT 1, '一级分销商', '1', 'supply_chain_distribution_level', 'primary', '提交为整数1'
    UNION ALL SELECT 2, '二级分销商', '2', 'supply_chain_distribution_level', 'primary', '提交为整数2'
    UNION ALL SELECT 3, '三级分销商', '3', 'supply_chain_distribution_level', 'primary', '提交为整数3'

    UNION ALL SELECT 1, '直营分销', 'DIRECT', 'supply_chain_cooperation_mode', 'success', ''
    UNION ALL SELECT 2, '代理分销', 'AGENT', 'supply_chain_cooperation_mode', 'success', ''
    UNION ALL SELECT 3, '渠道分销', 'CHANNEL', 'supply_chain_cooperation_mode', 'success', ''

    UNION ALL SELECT 1, '企业', 'ENTERPRISE', 'supply_chain_distributor_type', 'info', ''
    UNION ALL SELECT 2, '个人', 'PERSONAL', 'supply_chain_distributor_type', 'info', ''
    UNION ALL SELECT 3, '平台入驻', 'PLATFORM', 'supply_chain_distributor_type', 'info', ''

    UNION ALL SELECT 1, '稳定优先', 'STABLE', 'supply_chain_supply_preference', 'primary', '不改变路由引擎'
    UNION ALL SELECT 2, '价格优先', 'PRICE', 'supply_chain_supply_preference', 'primary', '不改变路由引擎'
    UNION ALL SELECT 3, '综合优先', 'COMPREHENSIVE', 'supply_chain_supply_preference', 'primary', '不改变路由引擎'

    UNION ALL SELECT 1, '正常', 'ACTIVE', 'supply_chain_buyer_status', 'success', ''
    UNION ALL SELECT 2, '暂停分销', 'SUSPENDED', 'supply_chain_buyer_status', 'warning', ''
    UNION ALL SELECT 3, '黑名单', 'BLACKLIST', 'supply_chain_buyer_status', 'danger', ''
) desired ON CAST(desired.dict_type AS BINARY) = CAST(dict_data.dict_type AS BINARY)
         AND CAST(desired.value AS BINARY) = CAST(dict_data.value AS BINARY)
SET dict_data.sort = desired.sort,
    dict_data.label = desired.label,
    dict_data.status = 0,
    dict_data.color_type = desired.color_type,
    dict_data.css_class = '',
    dict_data.remark = desired.remark,
    dict_data.updater = @sc_dict_creator,
    dict_data.update_time = NOW()
WHERE dict_data.deleted = b'0';
```

- [ ] **Step 2: 注册 changeSet（紧跟 `001-supplier-form-dictionaries` 之后）**

在 `db.changelog-1.0.0.yaml` 追加：

```yaml
  - changeSet:
      id: 002-admin-form-dictionaries
      author: supply-chain
      runInTransaction: true
      changes:
        - sqlFile:
            path: 002-admin-form-dictionaries.sql
            relativeToChangelogFile: true
            encoding: UTF-8
            splitStatements: true
            stripComments: false
```

- [ ] **Step 3: 写结构测试（镜像 `SupplyChainSupplierDictionaryLiquibaseTest`）**

```java
package com.aifuli.module.system.service.dict;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * system Liquibase `002-admin-form-dictionaries` 结构与幂等写法校验。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#51-system}。
 */
class AdminFormDictionaryLiquibaseTest {

    private static final List<String> DICT_TYPES = List.of(
            "supply_chain_sellable_category",
            "supply_chain_product_type",
            "supply_chain_product_classification",
            "supply_chain_distribution_level",
            "supply_chain_cooperation_mode",
            "supply_chain_distributor_type",
            "supply_chain_supply_preference",
            "supply_chain_buyer_status");

    private static String dictionarySql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        dictionarySql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/002-admin-form-dictionaries.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersDictionaryMigration() {
        assertTrue(releaseChangelog.contains("id: 002-admin-form-dictionaries"));
        assertTrue(releaseChangelog.contains("002-admin-form-dictionaries.sql"));
    }

    @Test
    void scriptDefinesAllFormDictionaryTypes() {
        for (String dictType : DICT_TYPES) {
            assertTrue(dictionarySql.contains(dictType), () -> "缺少字典类型：" + dictType);
        }
    }

    @Test
    void valuesMatchClosedEnumNamesNotFrontendMockValues() {
        List.of("'EQUITY'", "'PHYSICAL'", "'MOVIE'", "'JD'", "'DINING'", "'TAKEOUT'",
                        "'SUPERMARKET'", "'HEMA'", "'RTMART'", "'LIFE'",
                        "'EQUITY_PRODUCT'", "'VIP'",
                        "'ELECTRONICS'", "'VIRTUAL_CARD'", "'MOVIE_TICKET'",
                        "'DIRECT'", "'AGENT'", "'CHANNEL'",
                        "'ENTERPRISE'", "'PERSONAL'", "'PLATFORM'",
                        "'STABLE'", "'PRICE'", "'COMPREHENSIVE'",
                        "'ACTIVE'", "'SUSPENDED'", "'BLACKLIST'")
                .forEach(value -> assertTrue(dictionarySql.contains(value), () -> "缺少枚举值：" + value));
        List.of("'equity'", "'direct'", "'enterprise'", "'physical'")
                .forEach(mock -> assertTrue(!dictionarySql.contains(mock), () -> "不得写入前端 mock 小写码：" + mock));
    }

    @Test
    void insertsAreIdempotentAndUpdatesAreScoped() {
        String normalized = dictionarySql.toLowerCase(Locale.ROOT);
        assertTrue(normalized.contains("where not exists"));
        assertTrue(dictionarySql.contains("CAST(existing.type AS BINARY) = CAST(desired.type AS BINARY)"));
        assertTrue(dictionarySql.contains(
                "CAST(existing.dict_type AS BINARY) = CAST(desired.dict_type AS BINARY)"));
    }

    private static Path findRepoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                            + "db/changelog/releases/1.0.0/001-supplier-form-dictionaries.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repo root not found");
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `mvn -pl aifuli-module-system/aifuli-module-system-server -am test -Dtest=AdminFormDictionaryLiquibaseTest`
Expected: `BUILD SUCCESS`，4 个测试全绿。

- [ ] **Step 5: Commit**

```bash
git add aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/releases/1.0.0/002-admin-form-dictionaries.sql \
        aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/db.changelog-master.yaml \
        aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml \
        aifuli-module-system/aifuli-module-system-server/src/test/java/com/aifuli/module/system/service/dict/AdminFormDictionaryLiquibaseTest.java
git commit -m "新增供应链表单字典 Liquibase 002：三级封闭枚举与分销商表单五类字典"
```

---

### Task 2: system 数据字典迁移集成测试（Testcontainers）

**Files:**
- Test: `aifuli-module-system/aifuli-module-system-server/src/test/java/com/aifuli/module/system/service/dict/AdminFormDictionaryMigrationIntegrationTest.java`

- [ ] **Step 1: 写测试（镜像 `SupplyChainSupplierDictionaryMigrationIntegrationTest`，复用其 `system_dict_type`/`system_dict_data` 建表与 `connection()`/`updateLiquibase()` 私有方法风格）**

```java
package com.aifuli.module.system.service.dict;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 在真实 MySQL 上验证 system Liquibase `002-admin-form-dictionaries`：首次全量写入、二次幂等。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#7-测试门禁}。
 */
@Testcontainers(disabledWithoutDocker = true)
class AdminFormDictionaryMigrationIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("system_test")
            .withUsername("system_test")
            .withPassword("system_test");

    @BeforeAll
    static void createYudaoDictionaryTables() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE system_dict_type (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      name VARCHAR(100) NOT NULL DEFAULT '',
                      type VARCHAR(100) NOT NULL DEFAULT '',
                      status TINYINT NOT NULL DEFAULT 0,
                      remark VARCHAR(500) NULL,
                      creator VARCHAR(64) NULL DEFAULT '',
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updater VARCHAR(64) NULL DEFAULT '',
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      deleted BIT(1) NOT NULL DEFAULT b'0',
                      deleted_time DATETIME NULL DEFAULT NULL,
                      PRIMARY KEY (id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            statement.execute("""
                    CREATE TABLE system_dict_data (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      sort INT NOT NULL DEFAULT 0,
                      label VARCHAR(100) NOT NULL DEFAULT '',
                      value VARCHAR(100) NOT NULL DEFAULT '',
                      dict_type VARCHAR(100) NOT NULL DEFAULT '',
                      status TINYINT NOT NULL DEFAULT 0,
                      color_type VARCHAR(100) NULL DEFAULT '',
                      css_class VARCHAR(100) NULL DEFAULT '',
                      remark VARCHAR(500) NULL,
                      creator VARCHAR(64) NULL DEFAULT '',
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updater VARCHAR(64) NULL DEFAULT '',
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      deleted BIT(1) NOT NULL DEFAULT b'0',
                      PRIMARY KEY (id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
        }
    }

    @Test
    void migrationWritesEightDictionariesOnlyOnce() throws Exception {
        updateLiquibase();
        assertEquals(8, queryCount("""
                SELECT COUNT(*) FROM system_dict_type
                WHERE type LIKE 'supply_chain_%' AND deleted = b'0'
                """));
        assertEquals(33, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type IN (
                    'supply_chain_sellable_category', 'supply_chain_product_type',
                    'supply_chain_product_classification', 'supply_chain_distribution_level',
                    'supply_chain_cooperation_mode', 'supply_chain_distributor_type',
                    'supply_chain_supply_preference', 'supply_chain_buyer_status')
                  AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '002-admin-form-dictionaries' AND AUTHOR = 'supply-chain'
                """));
        assertEquals("暂停分销", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_buyer_status' AND value = 'SUSPENDED' AND deleted = b'0'
                """));

        updateLiquibase();
        assertEquals(8, queryCount("""
                SELECT COUNT(*) FROM system_dict_type
                WHERE type LIKE 'supply_chain_%' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '002-admin-form-dictionaries' AND AUTHOR = 'supply-chain'
                """));
    }

    private static void updateLiquibase() throws Exception {
        try (Connection connection = connection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static int queryCount(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String queryText(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
```

注意：`system_dict_type`/`system_dict_data` 表若已被同包内其它 `@BeforeAll` 建表的测试类共享同一个 Testcontainers 静态容器缓存导致冲突，先确认 `SupplyChainSupplierDictionaryMigrationIntegrationTest` 是否复用容器（各测试类各自 `@Container` 独立生命周期即可，不需要共享）。

- [ ] **Step 2: 运行（需要本机 Docker）**

Run: `mvn -pl aifuli-module-system/aifuli-module-system-server -am test -Dtest=AdminFormDictionaryMigrationIntegrationTest`
Expected: `BUILD SUCCESS`（无 Docker 时该测试用 `disabledWithoutDocker = true` 自动跳过，不算失败）。

- [ ] **Step 3: Commit**

```bash
git add aifuli-module-system/aifuli-module-system-server/src/test/java/com/aifuli/module/system/service/dict/AdminFormDictionaryMigrationIntegrationTest.java
git commit -m "新增 system 表单字典 002 的 MySQL 8.4 迁移集成测试"
```

---

### Task 3: supply-chain 三个新封闭枚举

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/profile/domain/SellableCategory.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/profile/domain/ProductTypeTag.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/profile/domain/ProductClassification.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/domain/BuyerScopeKind.java`

（本仓库同类枚举如 `DistributorType`/`SupplyPreference`/`CooperationMode` 均无独立单测，靠消费方经 `ClosedEnums.parseRequired` 间接覆盖；下面几个新枚举遵循同一约定，不建独立测试文件。）

- [ ] **Step 1: 创建 `SellableCategory`**

```java
package com.aifuli.module.supplychain.tradecore.profile.domain;

/**
 * 可售品类封闭枚举：供应商供货配置、分销商档案（多选）、平台SPU 共用同一套代码，互相独立、不校验父子链。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#21-可售品类-sellablecategory}。
 */
public enum SellableCategory {
    EQUITY,
    PHYSICAL,
    MOVIE,
    JD,
    DINING,
    TAKEOUT,
    SUPERMARKET,
    HEMA,
    RTMART,
    LIFE
}
```

- [ ] **Step 2: 创建 `ProductTypeTag`**

```java
package com.aifuli.module.supplychain.tradecore.profile.domain;

/**
 * 商品类型封闭枚举。为避免与 L2 结算价或
 * {@link com.aifuli.module.supplychain.tradecore.supply.domain.SupplyMode} 混淆，类型名用 Tag 后缀。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#22-商品类型-producttypetag}。
 */
public enum ProductTypeTag {
    EQUITY_PRODUCT,
    MOVIE,
    DINING,
    JD,
    VIP
}
```

- [ ] **Step 3: 创建 `ProductClassification`**

```java
package com.aifuli.module.supplychain.tradecore.profile.domain;

/**
 * 商品分类封闭枚举：仅供货配置行与平台SPU使用，分销商档案无此级。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#23-商品分类-productclassification}。
 */
public enum ProductClassification {
    ELECTRONICS,
    VIRTUAL_CARD,
    MOVIE_TICKET
}
```

- [ ] **Step 4: 创建 `BuyerScopeKind`**

```java
package com.aifuli.module.supplychain.tradecore.buyer.domain;

/**
 * 分销商可售范围的维度：品类或类型；对应 {@code sc_buyer_category_scope.scope_kind}。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#43-分销商范围表}。
 */
public enum BuyerScopeKind {
    SELLABLE,
    PRODUCT_TYPE
}
```

- [ ] **Step 5: 编译确认**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/profile/domain/SellableCategory.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/profile/domain/ProductTypeTag.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/profile/domain/ProductClassification.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/domain/BuyerScopeKind.java
git commit -m "新增可售品类/商品类型/商品分类三级封闭枚举与分销商范围维度枚举"
```

---

### Task 4: supply-chain Liquibase `024-admin-form-dictionary-codes`

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/024-admin-form-dictionary-codes.sql`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/dict/AdminFormDictionaryCodesLiquibaseTest.java`

**前置事实（已核实当前真实表结构，020+021 迁移后）：**
- `sc_supplier_supply_scope` 当前列：`id, supplier_id, category_l1_id, category_l2_id, category_l3_id, max_quantity, min_quantity, version, created_by, created_at, updated_by, updated_at`，唯一键 `uk_sc_supplier_scope_triple (supplier_id, category_l1_id, category_l2_id, category_l3_id)`。供货模式已在 021 迁到 `sc_supplier_supply_scope_mode` 子表（`supply_scope_id` 逻辑引用本表 `id`，无 FK）。
- `sc_buyer_category_scope` 当前列：`id, buyer_id, category_id, category_level, created_at`，唯一键 `uk_sc_buyer_category_scope (buyer_id, category_id)`，CHECK `ck_sc_buyer_category_level CHECK (category_level IN (1, 2))`。
- `sc_platform_spu.primary_category_id` 当前是 `BIGINT NOT NULL`。

**决策 d10：测试库现有供货配置/分销商范围行不能可靠映射到新枚举码，本迁移直接清空这两张表的数据行（含子表 `sc_supplier_supply_scope_mode`）后再改列。不改 020/021 原文件。**

- [ ] **Step 1: 写迁移 SQL**

```sql
-- 供应链表单字典化：供货配置 / 分销商范围改三级独立枚举码，平台SPU打同一套标签。
-- 设计见 docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#52-供应链
-- 决策 d10：测试库现有范围行无法可靠映射到新枚举码，先清空再改列；不回改 001-023。

-- 1. 供应商供货配置：类目树三元组 -> 三级独立枚举码。
DELETE FROM sc_supplier_supply_scope_mode
WHERE supply_scope_id IN (SELECT id FROM sc_supplier_supply_scope);
DELETE FROM sc_supplier_supply_scope;

ALTER TABLE sc_supplier_supply_scope
    DROP KEY uk_sc_supplier_scope_triple;

ALTER TABLE sc_supplier_supply_scope
    DROP COLUMN category_l1_id,
    DROP COLUMN category_l2_id,
    DROP COLUMN category_l3_id;

ALTER TABLE sc_supplier_supply_scope
    ADD COLUMN sellable_category VARCHAR(32) NOT NULL COMMENT '可售品类封闭枚举，见SellableCategory' AFTER supplier_id,
    ADD COLUMN product_type VARCHAR(32) NOT NULL COMMENT '商品类型封闭枚举，见ProductTypeTag' AFTER sellable_category,
    ADD COLUMN product_classification VARCHAR(32) NOT NULL COMMENT '商品分类封闭枚举，见ProductClassification' AFTER product_type;

ALTER TABLE sc_supplier_supply_scope
    ADD UNIQUE KEY uk_sc_supplier_scope_triple (supplier_id, sellable_category, product_type, product_classification),
    ADD CONSTRAINT ck_sc_supplier_scope_sellable_category CHECK (sellable_category IN (
        'EQUITY', 'PHYSICAL', 'MOVIE', 'JD', 'DINING', 'TAKEOUT', 'SUPERMARKET', 'HEMA', 'RTMART', 'LIFE')),
    ADD CONSTRAINT ck_sc_supplier_scope_product_type CHECK (product_type IN (
        'EQUITY_PRODUCT', 'MOVIE', 'DINING', 'JD', 'VIP')),
    ADD CONSTRAINT ck_sc_supplier_scope_product_classification CHECK (product_classification IN (
        'ELECTRONICS', 'VIRTUAL_CARD', 'MOVIE_TICKET'));

-- 2. 分销商可售范围：类目主键 -> scope_kind + scope_code。
DELETE FROM sc_buyer_category_scope;

ALTER TABLE sc_buyer_category_scope
    DROP KEY uk_sc_buyer_category_scope;

ALTER TABLE sc_buyer_category_scope
    DROP COLUMN category_id,
    DROP COLUMN category_level;

ALTER TABLE sc_buyer_category_scope
    ADD COLUMN scope_kind VARCHAR(16) NOT NULL COMMENT 'SELLABLE可售品类 PRODUCT_TYPE商品类型' AFTER buyer_id,
    ADD COLUMN scope_code VARCHAR(32) NOT NULL COMMENT '对应枚举名，见SellableCategory/ProductTypeTag' AFTER scope_kind;

ALTER TABLE sc_buyer_category_scope
    ADD UNIQUE KEY uk_sc_buyer_category_scope (buyer_id, scope_kind, scope_code),
    ADD CONSTRAINT ck_sc_buyer_category_scope_kind CHECK (scope_kind IN ('SELLABLE', 'PRODUCT_TYPE'));

-- 3. 平台SPU：新增三码列（先可空，应用层强制必填校验）；primary_category_id 改为允许空，
--    不再是供货命中或加购筛选的依据，仅保留给主数据/旧类目工具查阅。
ALTER TABLE sc_platform_spu
    MODIFY COLUMN primary_category_id BIGINT NULL
        COMMENT '平台主类目主键，逻辑引用sc_product_category.id（历史字段，主数据保留，不用于供货命中或加购筛选）';

ALTER TABLE sc_platform_spu
    ADD COLUMN sellable_category VARCHAR(32) NULL COMMENT '可售品类封闭枚举，见SellableCategory，供货命中用' AFTER primary_category_id,
    ADD COLUMN product_type VARCHAR(32) NULL COMMENT '商品类型封闭枚举，见ProductTypeTag，供货命中用' AFTER sellable_category,
    ADD COLUMN product_classification VARCHAR(32) NULL COMMENT '商品分类封闭枚举，见ProductClassification，供货命中用' AFTER product_type;

ALTER TABLE sc_platform_spu
    ADD CONSTRAINT ck_sc_spu_sellable_category CHECK (sellable_category IS NULL OR sellable_category IN (
        'EQUITY', 'PHYSICAL', 'MOVIE', 'JD', 'DINING', 'TAKEOUT', 'SUPERMARKET', 'HEMA', 'RTMART', 'LIFE')),
    ADD CONSTRAINT ck_sc_spu_product_type CHECK (product_type IS NULL OR product_type IN (
        'EQUITY_PRODUCT', 'MOVIE', 'DINING', 'JD', 'VIP')),
    ADD CONSTRAINT ck_sc_spu_product_classification CHECK (product_classification IS NULL OR product_classification IN (
        'ELECTRONICS', 'VIRTUAL_CARD', 'MOVIE_TICKET'));
```

- [ ] **Step 2: 注册 changeSet（紧跟 `023-admin-invoice` 之后）**

```yaml
  - changeSet:
      id: 024-admin-form-dictionary-codes
      author: supply-chain
      changes:
        - sqlFile:
            path: 024-admin-form-dictionary-codes.sql
            relativeToChangelogFile: true
            encoding: UTF-8
            splitStatements: true
            stripComments: false
```

- [ ] **Step 3: 写结构测试**

```java
package com.aifuli.module.supplychain.dict;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * supply-chain Liquibase `024-admin-form-dictionary-codes` 结构校验：只改列不回改历史 changeSet。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#52-供应链}。
 */
class AdminFormDictionaryCodesLiquibaseTest {

    private static String migrationSql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        migrationSql = Files.readString(repoRoot.resolve(
                "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/024-admin-form-dictionary-codes.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersMigration() {
        assertTrue(releaseChangelog.contains("id: 024-admin-form-dictionary-codes"));
        assertTrue(releaseChangelog.contains("024-admin-form-dictionary-codes.sql"));
    }

    @Test
    void doesNotTouchHistoricalChangeSets() {
        for (int i = 1; i <= 23; i++) {
            String needle = "id: " + String.format("%03d", i);
            assertFalse(migrationSql.contains(needle), () -> "024 不得改动历史 changeSet：" + needle);
        }
    }

    @Test
    void supplierScopeMovesToThreeCodes() {
        assertTrue(migrationSql.contains("DROP COLUMN category_l1_id"));
        assertTrue(migrationSql.contains("ADD COLUMN sellable_category"));
        assertTrue(migrationSql.contains("ADD COLUMN product_type"));
        assertTrue(migrationSql.contains("ADD COLUMN product_classification"));
        assertTrue(migrationSql.contains(
                "uk_sc_supplier_scope_triple (supplier_id, sellable_category, product_type, product_classification)"));
    }

    @Test
    void buyerScopeMovesToKindAndCode() {
        assertTrue(migrationSql.contains("DROP COLUMN category_id"));
        assertTrue(migrationSql.contains("DROP COLUMN category_level"));
        assertTrue(migrationSql.contains("ADD COLUMN scope_kind"));
        assertTrue(migrationSql.contains("ADD COLUMN scope_code"));
    }

    @Test
    void spuGetsThreeCodesAndNullablePrimaryCategory() {
        assertTrue(migrationSql.contains("MODIFY COLUMN primary_category_id BIGINT NULL"));
        assertTrue(migrationSql.contains("ADD COLUMN sellable_category VARCHAR(32) NULL"));
    }

    private static Path findRepoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                            + "db/changelog/releases/1.0.0/023-admin-invoice.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repo root not found");
    }
}
```

- [ ] **Step 4: 运行**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -Dtest=AdminFormDictionaryCodesLiquibaseTest`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/024-admin-form-dictionary-codes.sql \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/dict/AdminFormDictionaryCodesLiquibaseTest.java
git commit -m "新增供应链 Liquibase 024：供货配置/分销商范围/平台SPU 改三级枚举码"
```

---

### Task 5: supply-chain `024` 迁移集成测试

**Files:**
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/dict/AdminFormDictionaryCodesMigrationIntegrationTest.java`

- [ ] **Step 1: 找一个现有 supply-chain Testcontainers 迁移测试作为模板**

Run: `grep -rl "Testcontainers(disabledWithoutDocker" aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/dict/ 2>/dev/null || echo "no template in dict package, search wider"`

若 `dict` 包内无现成模板，参考同模块任意 `*MigrationIntegrationTest`（复用其 MySQL 容器 + 依次执行 `db.changelog-master.yaml` 到 `023`、再执行 `024` 的模式）：先 `liquibase.update()` 跑到当前 HEAD（含 024），断言：

```java
package com.aifuli.module.supplychain.dict;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在真实 MySQL 上验证供应链 `024-admin-form-dictionary-codes`：首次 Run=1，二次 Run=0；
 * 新增列存在且 CHECK 约束生效。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#7-测试门禁}。
 */
@Testcontainers(disabledWithoutDocker = true)
class AdminFormDictionaryCodesMigrationIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain_test")
            .withUsername("supply_chain_test")
            .withPassword("supply_chain_test");

    @Test
    void migrationRunsOnceAndAddsThreeCodeColumns() throws Exception {
        updateLiquibase();
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '024-admin-form-dictionary-codes' AND AUTHOR = 'supply-chain'
                """));
        assertTrue(columnExists("sc_supplier_supply_scope", "sellable_category"));
        assertTrue(columnExists("sc_supplier_supply_scope", "product_type"));
        assertTrue(columnExists("sc_supplier_supply_scope", "product_classification"));
        assertTrue(columnExists("sc_buyer_category_scope", "scope_kind"));
        assertTrue(columnExists("sc_buyer_category_scope", "scope_code"));
        assertTrue(columnExists("sc_platform_spu", "sellable_category"));
        assertTrue(isNullable("sc_platform_spu", "primary_category_id"));

        updateLiquibase();
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '024-admin-form-dictionary-codes' AND AUTHOR = 'supply-chain'
                """));
    }

    private static void updateLiquibase() throws Exception {
        try (Connection connection = connection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static boolean columnExists(String table, String column) throws SQLException {
        return queryCount("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = '%s' AND column_name = '%s'
                """.formatted(table, column)) == 1;
    }

    private static boolean isNullable(String table, String column) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT is_nullable FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = '%s' AND column_name = '%s'
                     """.formatted(table, column))) {
            resultSet.next();
            return "YES".equalsIgnoreCase(resultSet.getString(1));
        }
    }

    private static int queryCount(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
```

如果实际模板类里 `db.changelog-master.yaml` 的加载方式或数据库名前缀与本模块约定不同（例如需要先创建 `sc_supplier`/`sc_buyer` 等被 020/021 依赖的父表数据、或需要额外 `Liquibase` 参数），照抄同目录下任一 `*MigrationIntegrationTest` 的 `@BeforeAll`/连接建立方式，保持一致，不要引入新的连接约定。

- [ ] **Step 2: 运行（需要本机 Docker）**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -Dtest=AdminFormDictionaryCodesMigrationIntegrationTest`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/dict/AdminFormDictionaryCodesMigrationIntegrationTest.java
git commit -m "新增供应链 024 迁移集成测试：三码列存在且幂等"
```

---

### Task 6: `SupplierSupplyScopeDO` + `SupplierSupplyScopeService`

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/dal/dataobject/SupplierSupplyScopeDO.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierSupplyScopeService.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierSupplyScopeServiceTest.java`

- [ ] **Step 1: 改 DO 字段**

```path=aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/dal/dataobject/SupplierSupplyScopeDO.java
```

用 `StrReplace` 把：

```java
    private Long categoryL1Id;
    private Long categoryL2Id;
    private Long categoryL3Id;
```

替换为：

```java
    /** 可售品类封闭枚举，见SellableCategory */
    private String sellableCategory;
    /** 商品类型封闭枚举，见ProductTypeTag */
    private String productType;
    /** 商品分类封闭枚举，见ProductClassification */
    private String productClassification;
```

同时把类头注释里 `设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#41-供货模式-supplymode}` 之后追加一行：

```java
 * 三码语义见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#41-rest}。
```

- [ ] **Step 2: 改写 `SupplierSupplyScopeService`（去掉 `CategoryChainValidator`，改三码校验）**

用 `Write` 整文件覆盖：

```java
package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.tradecore.profile.domain.ProductClassification;
import com.aifuli.module.supplychain.tradecore.profile.domain.ProductTypeTag;
import com.aifuli.module.supplychain.tradecore.profile.domain.SellableCategory;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeModeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeModeMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplyMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_SCOPE_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_SCOPE_MODE_INVALID;

/**
 * 供货配置整表替换。供货模式落在子表，一行对应多种模式；三码（可售品类/商品类型/商品分类）互相独立，
 * 不校验父子链，唯一性以供应商内三码组合判重。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#41-rest}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierSupplyScopeService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final SupplierMapper supplierMapper;
    private final SupplierSupplyScopeMapper scopeMapper;
    private final SupplierSupplyScopeModeMapper modeMapper;

    /**
     * 全量替换供应商供货配置。无 FK，必须先按现有 scope id 删除模式子表再删主表。
     * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#41-rest}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void replaceAll(String supplierCode, List<ScopeLine> lines, Long operatorId) {
        log.info("保存供货配置 supplierCode={} lineCount={} operatorId={}",
                supplierCode, lines == null ? 0 : lines.size(), operatorId);
        SupplierDO supplier = supplierMapper.selectByCodeForUpdate(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        Set<String> triples = new HashSet<>();
        List<ScopeLine> normalized = lines == null ? List.of() : lines;
        int modeCount = 0;
        for (ScopeLine line : normalized) {
            String sellableCategory = ClosedEnums.parseRequired(SellableCategory.class, line.sellableCategory()).name();
            String productType = ClosedEnums.parseRequired(ProductTypeTag.class, line.productType()).name();
            String productClassification =
                    ClosedEnums.parseRequired(ProductClassification.class, line.productClassification()).name();
            String key = sellableCategory + ":" + productType + ":" + productClassification;
            if (!triples.add(key)) {
                throw exception(SUPPLIER_SCOPE_CONFLICT);
            }
            List<String> modes = validatedModeCodes(line.supplyModes());
            modeCount += modes.size();
            if (line.minQuantity() == null || line.maxQuantity() == null
                    || line.minQuantity() < 1 || line.maxQuantity() < line.minQuantity()) {
                throw new IllegalArgumentException("Supply scope quantity is invalid");
            }
        }
        log.info("保存供货配置校验完成 supplierCode={} lineCount={} modeCount={} operatorId={}",
                supplierCode, normalized.size(), modeCount, operatorId);
        LocalDateTime now = databaseClock.now();
        List<Long> existingIds = scopeMapper.selectBySupplierId(supplier.getId()).stream()
                .map(SupplierSupplyScopeDO::getId)
                .toList();
        modeMapper.deleteByScopeIds(existingIds);
        scopeMapper.deleteBySupplierId(supplier.getId());
        try {
            for (ScopeLine line : normalized) {
                List<String> modes = validatedModeCodes(line.supplyModes());
                SupplierSupplyScopeDO scope = SupplierSupplyScopeDO.builder()
                        .supplierId(supplier.getId())
                        .sellableCategory(ClosedEnums.parseRequired(SellableCategory.class, line.sellableCategory()).name())
                        .productType(ClosedEnums.parseRequired(ProductTypeTag.class, line.productType()).name())
                        .productClassification(
                                ClosedEnums.parseRequired(ProductClassification.class, line.productClassification()).name())
                        .maxQuantity(line.maxQuantity())
                        .minQuantity(line.minQuantity())
                        .version(0L)
                        .createdBy(operatorId)
                        .createdAt(now)
                        .updatedBy(operatorId)
                        .updatedAt(now)
                        .build();
                scopeMapper.insert(scope);
                for (String mode : modes) {
                    modeMapper.insert(SupplierSupplyScopeModeDO.builder()
                            .supplyScopeId(scope.getId())
                            .supplyMode(mode)
                            .createdAt(now)
                            .build());
                }
            }
        } catch (DuplicateKeyException duplicate) {
            throw exception(SUPPLIER_SCOPE_CONFLICT);
        }
    }

    public List<SupplierSupplyScopeDO> list(String supplierCode) {
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        return scopeMapper.selectBySupplierId(supplier.getId());
    }

    /**
     * 空集合或未知模式码一律 {@code SUPPLY_SCOPE_MODE_INVALID}。
     * {@link SupplyMode#fromCode(String)} 的 {@link IllegalArgumentException} 包装后上抛，不吞异常。
     */
    private List<String> validatedModeCodes(List<String> supplyModes) {
        if (supplyModes == null || supplyModes.isEmpty()) {
            throw exception(SUPPLY_SCOPE_MODE_INVALID);
        }
        List<String> codes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String code : supplyModes) {
            SupplyMode mode;
            try {
                mode = SupplyMode.fromCode(code);
            } catch (IllegalArgumentException ex) {
                ServiceException invalid = exception(SUPPLY_SCOPE_MODE_INVALID);
                invalid.initCause(ex);
                throw invalid;
            }
            if (seen.add(mode.name())) {
                codes.add(mode.name());
            }
        }
        return codes;
    }

    /**
     * 供货配置一行：三码互相独立、不校验父子链，供应商内三码组合唯一；供货模式为多选。
     * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#41-rest}。
     */
    public record ScopeLine(String sellableCategory, String productType, String productClassification,
                            List<String> supplyModes, Long maxQuantity, Long minQuantity) {
    }
}
```

- [ ] **Step 3: 改写单测（构造函数少一个参数；断言字段改字符串；新增未知码测试）**

```java
package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierSupplyScopeService.ScopeLine;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeModeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeModeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_SCOPE_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供货配置整表替换：三码互相独立不校验父子链，供应商内三码组合唯一；模式写入子表。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#41-rest}。
 */
class SupplierSupplyScopeServiceTest {

    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private final SupplierSupplyScopeMapper scopeMapper = mock(SupplierSupplyScopeMapper.class);
    private final SupplierSupplyScopeModeMapper modeMapper = mock(SupplierSupplyScopeModeMapper.class);
    private SupplierSupplyScopeService service;

    @BeforeEach
    void setUp() {
        service = new SupplierSupplyScopeService(databaseClock, supplierMapper, scopeMapper, modeMapper);
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 19, 0));
        when(supplierMapper.selectByCodeForUpdate("SPL_A")).thenReturn(
                SupplierDO.builder().id(9L).supplierCode("SPL_A").build());
        when(scopeMapper.selectBySupplierId(9L)).thenReturn(List.of(
                SupplierSupplyScopeDO.builder().id(88L).supplierId(9L).build()));
        when(scopeMapper.insert(any(SupplierSupplyScopeDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SupplierSupplyScopeDO.class).setId(101L);
            return 1;
        });
    }

    @Test
    void replaceAllWritesModeToChildTableAndDeletesChildRowsFirst() {
        service.replaceAll("SPL_A", List.of(new ScopeLine(
                "EQUITY", "MOVIE", "MOVIE_TICKET", List.of("API_DIRECT"), 100L, 1L)), 7L);

        InOrder order = inOrder(modeMapper, scopeMapper);
        order.verify(modeMapper).deleteByScopeIds(List.of(88L));
        order.verify(scopeMapper).deleteBySupplierId(9L);

        ArgumentCaptor<SupplierSupplyScopeDO> scopeCaptor = ArgumentCaptor.forClass(SupplierSupplyScopeDO.class);
        verify(scopeMapper).insert(scopeCaptor.capture());
        assertThat(scopeCaptor.getValue().getSellableCategory()).isEqualTo("EQUITY");
        assertThat(scopeCaptor.getValue().getProductType()).isEqualTo("MOVIE");
        assertThat(scopeCaptor.getValue().getProductClassification()).isEqualTo("MOVIE_TICKET");

        ArgumentCaptor<SupplierSupplyScopeModeDO> modeCaptor =
                ArgumentCaptor.forClass(SupplierSupplyScopeModeDO.class);
        verify(modeMapper).insert(modeCaptor.capture());
        assertThat(modeCaptor.getValue().getSupplyScopeId()).isEqualTo(101L);
        assertThat(modeCaptor.getValue().getSupplyMode()).isEqualTo("API_DIRECT");
    }

    @Test
    void rejectsUnknownEnumCode() {
        assertThatThrownBy(() -> service.replaceAll("SPL_A", List.of(new ScopeLine(
                "NOT_A_CODE", "MOVIE", "MOVIE_TICKET", List.of("API_DIRECT"), 100L, 1L)), 7L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ENUM_NOT_OPEN.getCode());
    }

    @Test
    void rejectsDuplicateTripleWithinSameRequest() {
        assertThatThrownBy(() -> service.replaceAll("SPL_A", List.of(
                new ScopeLine("EQUITY", "MOVIE", "MOVIE_TICKET", List.of("API_DIRECT"), 100L, 1L),
                new ScopeLine("EQUITY", "MOVIE", "MOVIE_TICKET", List.of("H5"), 200L, 1L)), 7L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_SCOPE_CONFLICT.getCode());
    }
}
```

- [ ] **Step 4: 运行**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -Dtest=SupplierSupplyScopeServiceTest`
Expected: `BUILD SUCCESS`（此时其它引用旧字段的类还没改完，若整模块编译失败是预期的——继续做后续 Task 直到 Task 16 全部落地才整体编译通过；可以用 `-pl ... -am` 单独跑到本类所在包能编译的最小闭包，或者接受在完成 Task 6-16 之后统一编译验证）。

- [ ] **Step 5: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/dal/dataobject/SupplierSupplyScopeDO.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierSupplyScopeService.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierSupplyScopeServiceTest.java
git commit -m "供货配置改三级独立枚举码：SupplierSupplyScopeDO/Service 去掉类目树校验"
```

**注意：** 本 Task 起后续 Task 7-16 之间存在编译期互相依赖（同一批类引用同一批新字段名），建议按顺序连续执行完 Task 6-16 后再统一跑一次全模块 `mvn test`，而不是每个 Task 单独要求全绿。

---

### Task 7: `SupplierProfileQueryService`

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierProfileQueryService.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierProfileQueryServiceTest.java`

- [ ] **Step 1: 删除 `ProductCategoryMapper` 依赖与 `categoryName()`**

用 `StrReplace` 删除字段：

```java
    private final ProductCategoryMapper categoryMapper;
```

删除对应 import `com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper` 与 `com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO`（确认类内其它地方不再用到这两个类型后才删 import）。

删除方法：

```java
    private String categoryName(Long id) {
        ProductCategoryDO category = categoryMapper.selectById(id);
        return category == null ? null : category.getCategoryName();
    }
```

- [ ] **Step 2: 改 `SupplyScopeView`、`toScopeView`、`toScopeViews`**

把：

```java
    @Schema(description = "供货配置行")
    public record SupplyScopeView(
            @Schema(description = "供货配置内部主键") Long id,
            @Schema(description = "可售品类内部主键") Long categoryL1Id,
            @Schema(description = "可售品类名称") String categoryL1Name,
            @Schema(description = "商品类型内部主键") Long categoryL2Id,
            @Schema(description = "商品类型名称") String categoryL2Name,
            @Schema(description = "商品分类内部主键") Long categoryL3Id,
            @Schema(description = "商品分类名称") String categoryL3Name,
            @Schema(description = "供货模式枚举名列表") List<String> supplyModes,
            @Schema(description = "单次最大数量") Long maxQuantity,
            @Schema(description = "单次最小数量") Long minQuantity) {
    }
```

替换为：

```java
    @Schema(description = "供货配置行")
    public record SupplyScopeView(
            @Schema(description = "供货配置内部主键") Long id,
            @Schema(description = "可售品类封闭枚举名") String sellableCategory,
            @Schema(description = "商品类型封闭枚举名") String productType,
            @Schema(description = "商品分类封闭枚举名") String productClassification,
            @Schema(description = "供货模式枚举名列表") List<String> supplyModes,
            @Schema(description = "单次最大数量") Long maxQuantity,
            @Schema(description = "单次最小数量") Long minQuantity) {
    }
```

把：

```java
    private SupplyScopeView toScopeView(SupplierSupplyScopeDO scope, List<String> supplyModes) {
        return new SupplyScopeView(
                scope.getId(),
                scope.getCategoryL1Id(),
                categoryName(scope.getCategoryL1Id()),
                scope.getCategoryL2Id(),
                categoryName(scope.getCategoryL2Id()),
                scope.getCategoryL3Id(),
                categoryName(scope.getCategoryL3Id()),
                supplyModes,
                scope.getMaxQuantity(),
                scope.getMinQuantity());
    }
```

替换为：

```java
    private SupplyScopeView toScopeView(SupplierSupplyScopeDO scope, List<String> supplyModes) {
        return new SupplyScopeView(
                scope.getId(),
                scope.getSellableCategory(),
                scope.getProductType(),
                scope.getProductClassification(),
                supplyModes,
                scope.getMaxQuantity(),
                scope.getMinQuantity());
    }
```

- [ ] **Step 3: 改 `SupplierListItem` 与 `toListItem`（`NamedRef` 列表改纯字符串列表）**

把：

```java
    public record SupplierListItem(
            @Schema(description = "供应商内部主键") Long id,
            @Schema(description = "供应商永久业务编码") String supplierCode,
            @Schema(description = "供应商标准名称") String supplierName,
            @Schema(description = "可售品类去重") List<NamedRef> categories,
            @Schema(description = "商品类型去重") List<NamedRef> productTypes,
            @Schema(description = "商品分类去重") List<NamedRef> productCategories,
            @Schema(description = "供货模式去重，name 为枚举名") List<NamedRef> supplyModes,
            @Schema(description = "未退役合作商品数") long skuCount,
            @Schema(description = "合作状态：ACTIVE / SUSPENDED / BLACKLIST") String status,
            @Schema(description = "准入时间，Asia/Shanghai") LocalDateTime onboardedAt,
            @Schema(description = "最后修改时间，Asia/Shanghai") LocalDateTime updatedAt,
            String primaryContactName, String primaryContactMobile) {
```

替换为：

```java
    public record SupplierListItem(
            @Schema(description = "供应商内部主键") Long id,
            @Schema(description = "供应商永久业务编码") String supplierCode,
            @Schema(description = "供应商标准名称") String supplierName,
            @Schema(description = "可售品类去重，枚举名数组") List<String> sellableCategories,
            @Schema(description = "商品类型去重，枚举名数组") List<String> productTypes,
            @Schema(description = "商品分类去重，枚举名数组") List<String> productClassifications,
            @Schema(description = "供货模式去重，枚举名数组") List<String> supplyModes,
            @Schema(description = "未退役合作商品数") long skuCount,
            @Schema(description = "合作状态：ACTIVE / SUSPENDED / BLACKLIST") String status,
            @Schema(description = "准入时间，Asia/Shanghai") LocalDateTime onboardedAt,
            @Schema(description = "最后修改时间，Asia/Shanghai") LocalDateTime updatedAt,
            String primaryContactName, String primaryContactMobile) {
```

把 `toListItem` 中：

```java
        return new SupplierListItem(
                supplier.getId(),
                supplier.getSupplierCode(),
                supplier.getSupplierName(),
                uniqueNamed(scopeViews, SupplyScopeView::categoryL1Id, SupplyScopeView::categoryL1Name),
                uniqueNamed(scopeViews, SupplyScopeView::categoryL2Id, SupplyScopeView::categoryL2Name),
                uniqueNamed(scopeViews, SupplyScopeView::categoryL3Id, SupplyScopeView::categoryL3Name),
                uniqueSupplyModes(scopeViews),
```

替换为：

```java
        return new SupplierListItem(
                supplier.getId(),
                supplier.getSupplierCode(),
                supplier.getSupplierName(),
                uniqueCodes(scopeViews, SupplyScopeView::sellableCategory),
                uniqueCodes(scopeViews, SupplyScopeView::productType),
                uniqueCodes(scopeViews, SupplyScopeView::productClassification),
                uniqueCodes(scopeViews, view -> null),
```

不对，`supplyModes` 是一行多值不是一行一值，不能用 `uniqueCodes(scopeViews, view -> null)`。改为保留专门的模式去重方法。把上面第 4 行换成沿用现有 `uniqueSupplyModes(scopeViews)` 但让它返回 `List<String>`：

用 `StrReplace` 把辅助方法：

```java
    private static List<NamedRef> uniqueNamed(List<SupplyScopeView> scopes,
                                               java.util.function.Function<SupplyScopeView, Long> idFn,
                                               java.util.function.Function<SupplyScopeView, String> nameFn) {
        LinkedHashMap<Long, NamedRef> unique = new LinkedHashMap<>();
        for (SupplyScopeView scope : scopes) {
            Long id = idFn.apply(scope);
            if (id == null || unique.containsKey(id)) {
                continue;
            }
            unique.put(id, new NamedRef(id, nameFn.apply(scope)));
        }
        return List.copyOf(unique.values());
    }

    private static List<NamedRef> uniqueSupplyModes(List<SupplyScopeView> scopes) {
        LinkedHashMap<String, NamedRef> unique = new LinkedHashMap<>();
        for (SupplyScopeView scope : scopes) {
            if (scope.supplyModes() == null) {
                continue;
            }
            for (String mode : scope.supplyModes()) {
                if (mode == null || mode.isBlank() || unique.containsKey(mode)) {
                    continue;
                }
                unique.put(mode, new NamedRef(null, mode));
            }
        }
        return List.copyOf(unique.values());
    }
```

替换为：

```java
    private static List<String> uniqueCodes(List<SupplyScopeView> scopes,
                                             java.util.function.Function<SupplyScopeView, String> codeFn) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (SupplyScopeView scope : scopes) {
            String code = codeFn.apply(scope);
            if (code != null && !code.isBlank()) {
                unique.add(code);
            }
        }
        return List.copyOf(unique);
    }

    private static List<String> uniqueSupplyModes(List<SupplyScopeView> scopes) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (SupplyScopeView scope : scopes) {
            if (scope.supplyModes() == null) {
                continue;
            }
            for (String mode : scope.supplyModes()) {
                if (mode != null && !mode.isBlank()) {
                    unique.add(mode);
                }
            }
        }
        return List.copyOf(unique);
    }
```

新增 import `java.util.LinkedHashSet`（若尚未存在）。再把 `toListItem` 里第 4 行改回：

```java
                uniqueSupplyModes(scopeViews),
```

- [ ] **Step 4: 改 `matchingScopeSupplierIds` 与 `SupplierPageQuery`**

把：

```java
    private Set<Long> matchingScopeSupplierIds(List<Long> categoryL1Ids, List<String> supplyModes) {
        LambdaQueryWrapper<SupplierSupplyScopeDO> wrapper = new LambdaQueryWrapper<SupplierSupplyScopeDO>()
                .in(hasValues(categoryL1Ids), SupplierSupplyScopeDO::getCategoryL1Id, categoryL1Ids);
```

替换为：

```java
    private Set<Long> matchingScopeSupplierIds(List<String> sellableCategories, List<String> supplyModes) {
        LambdaQueryWrapper<SupplierSupplyScopeDO> wrapper = new LambdaQueryWrapper<SupplierSupplyScopeDO>()
                .in(hasValues(sellableCategories), SupplierSupplyScopeDO::getSellableCategory, sellableCategories);
```

把 `page()` 里调用处：

```java
        if (hasValues(query.categoryL1Ids()) || hasValues(query.supplyModes())) {
            Set<Long> supplierIds = matchingScopeSupplierIds(query.categoryL1Ids(), query.supplyModes());
```

替换为：

```java
        if (hasValues(query.sellableCategories()) || hasValues(query.supplyModes())) {
            Set<Long> supplierIds = matchingScopeSupplierIds(query.sellableCategories(), query.supplyModes());
```

把 `page()` 的 `log.info` 那一行 `categoryL1Ids={}` 占位改成 `sellableCategories={}`，参数改 `query.sellableCategories()`。

把：

```java
    public record SupplierPageQuery(
            String supplierName, String supplierCode,
            List<SupplierStatus> statuses, List<Long> categoryL1Ids, List<String> supplyModes,
            LocalDateTime updatedFrom, LocalDateTime updatedTo,
            Integer pageNo, Integer pageSize) {
    }
```

替换为：

```java
    public record SupplierPageQuery(
            String supplierName, String supplierCode,
            List<SupplierStatus> statuses, List<String> sellableCategories, List<String> supplyModes,
            LocalDateTime updatedFrom, LocalDateTime updatedTo,
            Integer pageNo, Integer pageSize) {
    }
```

`NamedRef` record 定义保留（`listSimple`/其它地方不再使用可以删除，若确认无其它引用则一并删除以避免死代码；先用 `grep -rn "NamedRef" aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierProfileQueryService.java` 确认已无引用后删除该 record 定义）。

- [ ] **Step 5: 改单测**

把 `SupplierProfileQueryServiceTest` 里：

```java
        when(scopeMapper.selectList(any())).thenReturn(List.of(
                SupplierSupplyScopeDO.builder().id(101L).supplierId(1L)
                        .categoryL1Id(1L).categoryL2Id(2L).categoryL3Id(3L).build()));
```

改为：

```java
        when(scopeMapper.selectList(any())).thenReturn(List.of(
                SupplierSupplyScopeDO.builder().id(101L).supplierId(1L)
                        .sellableCategory("EQUITY").productType("VIP").productClassification("VIRTUAL_CARD").build()));
```

把：

```java
        assertThat(row.categories()).containsExactly(new NamedRef(1L, "权益"));
        assertThat(row.productTypes()).containsExactly(new NamedRef(2L, "卡券"));
        assertThat(row.productCategories()).containsExactly(new NamedRef(3L, "电影快充"));
        assertThat(row.supplyModes()).containsExactly(new NamedRef(null, "BATCH"));
```

改为：

```java
        assertThat(row.sellableCategories()).containsExactly("EQUITY");
        assertThat(row.productTypes()).containsExactly("VIP");
        assertThat(row.productClassifications()).containsExactly("VIRTUAL_CARD");
        assertThat(row.supplyModes()).containsExactly("BATCH");
```

同样处理另一处 `.categoryL1Id(1L).categoryL2Id(2L).categoryL3Id(3L)` 与 `views.getFirst().categoryL1Name()).isEqualTo("权益")` 断言（改成 `views.getFirst().sellableCategory()).isEqualTo("EQUITY")`），并删除对 `ProductCategoryMapper`/`ProductCategoryDO` mock 与 import（若类构造器不再需要传 `categoryMapper`，同步删掉该 mock 字段与传参）。

- [ ] **Step 6: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierProfileQueryService.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierProfileQueryServiceTest.java
git commit -m "供应商查询改三码字符串列表，去掉类目名查找"
```

---

### Task 8: `SupplierAdminController`（供货配置与列表筛选）

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/SupplierAdminController.java`

- [ ] **Step 1: 改 `page()` 的 `categoryL1Ids` 筛选参数**

把：

```java
            @Parameter(description = "可售品类内部主键，可多选") @RequestParam(required = false) List<Long> categoryL1Ids,
            @Parameter(description = "供货模式枚举名，可多选") @RequestParam(required = false) List<String> supplyModes,
```

替换为：

```java
            @Parameter(description = "可售品类枚举名，可多选") @RequestParam(required = false) List<String> sellableCategories,
            @Parameter(description = "供货模式枚举名，可多选") @RequestParam(required = false) List<String> supplyModes,
```

把 `page()` 方法体里：

```java
        return success(queryService.page(new SupplierPageQuery(
                supplierName, supplierCode, statuses, categoryL1Ids, supplyModes,
                updatedFrom, updatedTo, pageNo, pageSize)));
```

替换为：

```java
        return success(queryService.page(new SupplierPageQuery(
                supplierName, supplierCode, statuses, sellableCategories, supplyModes,
                updatedFrom, updatedTo, pageNo, pageSize)));
```

同时把 `@Operation` 里的 `description = "筛选用多值 status/categoryL1Ids/supplyModes 与 updatedFrom/To。..."` 改成 `"筛选用多值 status/sellableCategories/supplyModes 与 updatedFrom/To。..."`。

- [ ] **Step 2: 改 `saveScopes()`**

把：

```java
    public CommonResult<Boolean> saveScopes(@PathVariable String supplierCode,
                                              @Valid @RequestBody SaveScopesReqVO request) {
        scopeService.replaceAll(supplierCode, request.getLines().stream()
                .map(line -> new ScopeLine(line.getCategoryL1Id(), line.getCategoryL2Id(),
                        line.getCategoryL3Id(), line.getSupplyModes(),
                        line.getMaxQuantity(), line.getMinQuantity()))
                .toList(), getLoginUserId());
        return success(true);
    }
```

替换为：

```java
    public CommonResult<Boolean> saveScopes(@PathVariable String supplierCode,
                                              @Valid @RequestBody SaveScopesReqVO request) {
        scopeService.replaceAll(supplierCode, request.getLines().stream()
                .map(line -> new ScopeLine(line.getSellableCategory(), line.getProductType(),
                        line.getProductClassification(), line.getSupplyModes(),
                        line.getMaxQuantity(), line.getMinQuantity()))
                .toList(), getLoginUserId());
        return success(true);
    }
```

同时把 `@Operation(summary = "保存供货配置", description = "... 类目链错误为 CATEGORY_CHAIN_INVALID。")` 里的最后一句删掉（不再有类目链校验），改成：

```java
    @Operation(summary = "保存供货配置",
            description = "全量替换。身份用 supplierCode。空 supplyModes 为 SUPPLY_SCOPE_MODE_INVALID；"
                    + "重复三码组合为 SUPPLIER_SCOPE_CONFLICT；未知枚举码为 ENUM_NOT_OPEN。")
```

- [ ] **Step 3: 改 `ScopeReqVO`**

把：

```java
    @Data
    @Schema(description = "供货配置行")
    public static class ScopeReqVO {
        @NotNull
        @Schema(description = "可售品类内部主键")
        private Long categoryL1Id;
        @NotNull
        @Schema(description = "商品类型内部主键")
        private Long categoryL2Id;
        @NotNull
        @Schema(description = "商品分类内部主键")
        private Long categoryL3Id;
        @NotEmpty
        @Schema(description = "供货模式枚举名列表，不可为空")
        private List<String> supplyModes;
        @NotNull @Min(1)
        @Schema(description = "单次最大数量")
        private Long maxQuantity;
        @NotNull @Min(1)
        @Schema(description = "单次最小数量")
        private Long minQuantity;
    }
```

替换为：

```java
    @Data
    @Schema(description = "供货配置行")
    public static class ScopeReqVO {
        @NotBlank
        @Schema(description = "可售品类枚举名")
        private String sellableCategory;
        @NotBlank
        @Schema(description = "商品类型枚举名")
        private String productType;
        @NotBlank
        @Schema(description = "商品分类枚举名")
        private String productClassification;
        @NotEmpty
        @Schema(description = "供货模式枚举名列表，不可为空")
        private List<String> supplyModes;
        @NotNull @Min(1)
        @Schema(description = "单次最大数量")
        private Long maxQuantity;
        @NotNull @Min(1)
        @Schema(description = "单次最小数量")
        private Long minQuantity;
    }
```

- [ ] **Step 4: 改 `listOffers()` 的类目筛选参数**

把：

```java
    public CommonResult<PageResult<OfferListItem>> listOffers(
            @PathVariable String supplierCode,
            @Parameter(description = "可售品类内部主键") @RequestParam(required = false) Long categoryL1Id,
            @Parameter(description = "商品类型内部主键") @RequestParam(required = false) Long categoryL2Id,
            @Parameter(description = "商品分类内部主键") @RequestParam(required = false) Long categoryL3Id,
            @Parameter(description = "页码，默认1") @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数，默认20") @RequestParam(required = false) Integer pageSize) {
        return success(cooperativeProductService.page(supplierCode, categoryL1Id, categoryL2Id,
                categoryL3Id, pageNo, pageSize));
    }
```

替换为：

```java
    public CommonResult<PageResult<OfferListItem>> listOffers(
            @PathVariable String supplierCode,
            @Parameter(description = "可售品类枚举名") @RequestParam(required = false) String sellableCategory,
            @Parameter(description = "商品类型枚举名") @RequestParam(required = false) String productType,
            @Parameter(description = "商品分类枚举名") @RequestParam(required = false) String productClassification,
            @Parameter(description = "页码，默认1") @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数，默认20") @RequestParam(required = false) Integer pageSize) {
        return success(cooperativeProductService.page(supplierCode, sellableCategory, productType,
                productClassification, pageNo, pageSize));
    }
```

- [ ] **Step 5: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/SupplierAdminController.java
git commit -m "供应商管理端 REST 改用三码字符串筛选与保存供货配置"
```

---

### Task 9: `SupplierCooperativeProductService`（三码整体命中）

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierCooperativeProductService.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierCooperativeProductServiceTest.java`

- [ ] **Step 1: 删除 `ProductCategoryMapper` 依赖与 `categoryName()`**

删除字段 `private final ProductCategoryMapper categoryMapper;`、其 import，以及方法：

```java
    private String categoryName(Long id) {
        if (id == null) {
            return null;
        }
        ProductCategoryDO category = categoryMapper.selectById(id);
        return category == null ? null : category.getCategoryName();
    }
```

（构造器由 `@RequiredArgsConstructor` 自动少一个参数，记得同步改测试的 `new SupplierCooperativeProductService(...)` 调用。）

- [ ] **Step 2: 改 `matchScope`（三码整体相等，SPU 未打码直接判失败）**

把：

```java
    private SupplierSupplyScopeDO matchScope(List<SupplierSupplyScopeDO> scopes, Long categoryId) {
        SupplierSupplyScopeDO matched = null;
        for (SupplierSupplyScopeDO scope : scopes) {
            if (scope.getCategoryL3Id().equals(categoryId)) {
                if (matched != null) {
                    throw exception(SUPPLIER_OFFER_OUT_OF_SCOPE);
                }
                matched = scope;
            }
        }
        if (matched == null) {
            throw exception(SUPPLIER_OFFER_OUT_OF_SCOPE);
        }
        return matched;
    }
```

替换为：

```java
    /**
     * SPU 三码须全部非空且与某条供货配置三码整体相等才命中；0 条或多条命中都判失败。
     * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#42-平台spu}。
     */
    private SupplierSupplyScopeDO matchScope(List<SupplierSupplyScopeDO> scopes, PlatformSpuDO spu) {
        if (spu.getSellableCategory() == null || spu.getProductType() == null
                || spu.getProductClassification() == null) {
            throw exception(SUPPLIER_OFFER_OUT_OF_SCOPE);
        }
        SupplierSupplyScopeDO matched = null;
        for (SupplierSupplyScopeDO scope : scopes) {
            boolean sameTriple = spu.getSellableCategory().equals(scope.getSellableCategory())
                    && spu.getProductType().equals(scope.getProductType())
                    && spu.getProductClassification().equals(scope.getProductClassification());
            if (sameTriple) {
                if (matched != null) {
                    throw exception(SUPPLIER_OFFER_OUT_OF_SCOPE);
                }
                matched = scope;
            }
        }
        if (matched == null) {
            throw exception(SUPPLIER_OFFER_OUT_OF_SCOPE);
        }
        return matched;
    }
```

把 `resolveLine()` 里调用处：

```java
        SupplierSupplyScopeDO scope = matchScope(scopes, spu.getPrimaryCategoryId());
```

替换为：

```java
        SupplierSupplyScopeDO scope = matchScope(scopes, spu);
```

- [ ] **Step 3: 改 `page()` 筛选参数与 `matchingScopeIds`**

把：

```java
    public PageResult<OfferListItem> page(String supplierCode, Long categoryL1Id, Long categoryL2Id,
                                          Long categoryL3Id, Integer pageNo, Integer pageSize) {
        log.info("分页查询合作商品 supplierCode={} categoryL1Id={} categoryL2Id={} categoryL3Id={} pageNo={} pageSize={}",
                supplierCode, categoryL1Id, categoryL2Id, categoryL3Id, pageNo, pageSize);
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        LambdaQueryWrapper<SupplierOfferDO> wrapper = new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode())
                .orderByDesc(SupplierOfferDO::getCreatedAt);
        if (categoryL1Id != null || categoryL2Id != null || categoryL3Id != null) {
            Set<Long> scopeIds = matchingScopeIds(supplier.getId(), categoryL1Id, categoryL2Id, categoryL3Id);
            if (scopeIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
            wrapper.in(SupplierOfferDO::getSupplyScopeId, scopeIds);
        }
```

替换为：

```java
    public PageResult<OfferListItem> page(String supplierCode, String sellableCategory, String productType,
                                          String productClassification, Integer pageNo, Integer pageSize) {
        log.info("分页查询合作商品 supplierCode={} sellableCategory={} productType={} productClassification={} "
                        + "pageNo={} pageSize={}",
                supplierCode, sellableCategory, productType, productClassification, pageNo, pageSize);
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        LambdaQueryWrapper<SupplierOfferDO> wrapper = new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode())
                .orderByDesc(SupplierOfferDO::getCreatedAt);
        if (sellableCategory != null || productType != null || productClassification != null) {
            Set<Long> scopeIds = matchingScopeIds(supplier.getId(), sellableCategory, productType,
                    productClassification);
            if (scopeIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
            wrapper.in(SupplierOfferDO::getSupplyScopeId, scopeIds);
        }
```

把：

```java
    private Set<Long> matchingScopeIds(Long supplierId, Long categoryL1Id, Long categoryL2Id, Long categoryL3Id) {
        Set<Long> ids = new HashSet<>();
        for (SupplierSupplyScopeDO scope : scopeMapper.selectBySupplierId(supplierId)) {
            if (categoryL1Id != null && !categoryL1Id.equals(scope.getCategoryL1Id())) {
                continue;
            }
            if (categoryL2Id != null && !categoryL2Id.equals(scope.getCategoryL2Id())) {
                continue;
            }
            if (categoryL3Id != null && !categoryL3Id.equals(scope.getCategoryL3Id())) {
                continue;
            }
            ids.add(scope.getId());
        }
        return ids;
    }
```

替换为：

```java
    private Set<Long> matchingScopeIds(Long supplierId, String sellableCategory, String productType,
                                       String productClassification) {
        Set<Long> ids = new HashSet<>();
        for (SupplierSupplyScopeDO scope : scopeMapper.selectBySupplierId(supplierId)) {
            if (sellableCategory != null && !sellableCategory.equals(scope.getSellableCategory())) {
                continue;
            }
            if (productType != null && !productType.equals(scope.getProductType())) {
                continue;
            }
            if (productClassification != null && !productClassification.equals(scope.getProductClassification())) {
                continue;
            }
            ids.add(scope.getId());
        }
        return ids;
    }
```

- [ ] **Step 4: 改 `OfferListItem` 与 `toListItem`**

把：

```java
    public record OfferListItem(
            @Schema(description = "供应商报价稳定业务编号") String offerNo,
            @Schema(description = "平台SPU永久业务编码") String platformSpuCode,
            @Schema(description = "平台SPU标准名称") String platformSpuName,
            @Schema(description = "平台SKU永久业务编码") String platformSkuCode,
            @Schema(description = "平台SKU标准名称") String platformSkuName,
            @Schema(description = "可售品类，逻辑引用sc_product_category.id，须为一级类目") Long categoryL1Id,
            @Schema(description = "可售品类名称") String categoryL1Name,
            @Schema(description = "商品类型，逻辑引用sc_product_category.id，须为品类子类目") Long categoryL2Id,
            @Schema(description = "商品类型名称") String categoryL2Name,
            @Schema(description = "商品分类，逻辑引用sc_product_category.id，须为类型子类目") Long categoryL3Id,
            @Schema(description = "商品分类名称") String categoryL3Name,
            @Schema(description = "L1 B端结算单价，CNY分，允许0") Long suggestPriceCents,
```

替换为：

```java
    public record OfferListItem(
            @Schema(description = "供应商报价稳定业务编号") String offerNo,
            @Schema(description = "平台SPU永久业务编码") String platformSpuCode,
            @Schema(description = "平台SPU标准名称") String platformSpuName,
            @Schema(description = "平台SKU永久业务编码") String platformSkuCode,
            @Schema(description = "平台SKU标准名称") String platformSkuName,
            @Schema(description = "可售品类封闭枚举名") String sellableCategory,
            @Schema(description = "商品类型封闭枚举名") String productType,
            @Schema(description = "商品分类封闭枚举名") String productClassification,
            @Schema(description = "L1 B端结算单价，CNY分，允许0") Long suggestPriceCents,
```

把 `toListItem()` 里：

```java
        return new OfferListItem(
                offer.getOfferNo(),
                spu == null ? null : spu.getSpuCode(),
                spu == null ? null : spu.getSpuName(),
                sku == null ? null : sku.getSkuCode(),
                sku == null ? null : sku.getSkuName(),
                scope == null ? null : scope.getCategoryL1Id(),
                categoryName(scope == null ? null : scope.getCategoryL1Id()),
                scope == null ? null : scope.getCategoryL2Id(),
                categoryName(scope == null ? null : scope.getCategoryL2Id()),
                scope == null ? null : scope.getCategoryL3Id(),
                categoryName(scope == null ? null : scope.getCategoryL3Id()),
                resolveSuggestPriceCents(offer.getPlatformSkuId()),
```

替换为：

```java
        return new OfferListItem(
                offer.getOfferNo(),
                spu == null ? null : spu.getSpuCode(),
                spu == null ? null : spu.getSpuName(),
                sku == null ? null : sku.getSkuCode(),
                sku == null ? null : sku.getSkuName(),
                scope == null ? null : scope.getSellableCategory(),
                scope == null ? null : scope.getProductType(),
                scope == null ? null : scope.getProductClassification(),
                resolveSuggestPriceCents(offer.getPlatformSkuId()),
```

- [ ] **Step 5: 改单测（补 `stubCreatePath` 与新增 SPU 未打码场景）**

把 `SupplierCooperativeProductServiceTest.stubCreatePath()` 里：

```java
        when(scopeMapper.selectBySupplierId(9L)).thenReturn(List.of(SupplierSupplyScopeDO.builder()
                .id(101L).supplierId(9L).categoryL1Id(1L).categoryL2Id(2L).categoryL3Id(3L)
                .maxQuantity(100L).minQuantity(1L).build()));
        when(skuMapper.selectByCode("MOVIE_100")).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("MOVIE_100").spuId(21L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder()
                .id(21L).primaryCategoryId(3L).status(CatalogEntityStatus.ACTIVE.getCode()).build());
```

替换为：

```java
        when(scopeMapper.selectBySupplierId(9L)).thenReturn(List.of(SupplierSupplyScopeDO.builder()
                .id(101L).supplierId(9L)
                .sellableCategory("MOVIE").productType("MOVIE").productClassification("MOVIE_TICKET")
                .maxQuantity(100L).minQuantity(1L).build()));
        when(skuMapper.selectByCode("MOVIE_100")).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("MOVIE_100").spuId(21L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder()
                .id(21L).sellableCategory("MOVIE").productType("MOVIE").productClassification("MOVIE_TICKET")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
```

删除 `new SupplierCooperativeProductService(...)` 构造调用里给 `mock(ProductCategoryMapper.class)` 的那一个参数（对应 Step 1 的构造器变化）。

新增测试方法：

```java
    @Test
    void rejectsWhenSpuMissingAnyOfTheThreeCodes() {
        stubCreatePath();
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder()
                .id(21L).sellableCategory("MOVIE").productType(null).productClassification("MOVIE_TICKET")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());

        assertThatThrownBy(() -> service.createPublished("SPL_A", List.of(
                new DraftLine("MOVIE_100", "FIXED", 9_800L, null, null, "API_DIRECT", null, null)), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_OFFER_OUT_OF_SCOPE.getCode());
    }
```

需要新增 import：`static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_OFFER_OUT_OF_SCOPE;`

- [ ] **Step 6: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierCooperativeProductService.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierCooperativeProductServiceTest.java
git commit -m "合作商品新增改为 SPU 三码与供货配置三码整体相等命中"
```

---

### Task 10: `BuyerCategoryScopeDO` + Mapper

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/dal/dataobject/BuyerCategoryScopeDO.java`

- [ ] **Step 1: 改字段**

把：

```java
    /** 类目主键，逻辑引用sc_product_category.id */
    private Long categoryId;
    /** 1品类 2类型 */
    private Short categoryLevel;
```

替换为：

```java
    /** 范围维度：SELLABLE可售品类 PRODUCT_TYPE商品类型，见BuyerScopeKind */
    private String scopeKind;
    /** 对应枚举名，见SellableCategory/ProductTypeTag */
    private String scopeCode;
```

同时把类头注释追加一行设计链接：

```java
 * 三码语义见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#43-分销商范围表}。
```

`BuyerCategoryScopeMapper` 的 `selectByBuyerId`/`deleteByBuyerId` 按 `buyerId` 查询，不涉及被改的两个字段，不需要修改。

- [ ] **Step 2: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/dal/dataobject/BuyerCategoryScopeDO.java
git commit -m "分销商范围表 DO 改用 scope_kind/scope_code 枚举码"
```

---

### Task 11: `BuyerAdminCommandService`

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/application/BuyerAdminCommandService.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/application/BuyerAdminCommandServiceTest.java`

- [ ] **Step 1: 去掉 `CategoryChainValidator` 依赖**

删除字段 `private final CategoryChainValidator categoryChainValidator;`、import，以及 `create()`/`update()` 里两处：

```java
        categoryChainValidator.requireBuyerScopes(nullToEmpty(command.categoryL1Ids()),
                nullToEmpty(command.categoryL2Ids()));
```

（两处都删；`nullToEmpty` 方法如果只被这两处调用，一并删除。）

- [ ] **Step 2: 新增 import**

```java
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerScopeKind;
import com.aifuli.module.supplychain.tradecore.profile.domain.ProductTypeTag;
import com.aifuli.module.supplychain.tradecore.profile.domain.SellableCategory;
```

- [ ] **Step 3: 改 `CreateBuyerCommand`/`UpdateBuyerCommand` 字段名**

把两个 record 里的：

```java
            List<Long> categoryL1Ids,
            List<Long> categoryL2Ids,
```

都替换为：

```java
            List<String> sellableCategories,
            List<String> productTypes,
```

- [ ] **Step 4: 改 `create()`/`update()` 里的调用参数名**

把两处：

```java
        replaceCategoryScopes(buyer.getId(), command.categoryL1Ids(), command.categoryL2Ids(), now);
```

替换为：

```java
        replaceCategoryScopes(buyer.getId(), command.sellableCategories(), command.productTypes(), now);
```

- [ ] **Step 5: 改写 `replaceCategoryScopes`/`insertCategoryScopes`**

把：

```java
    private void replaceCategoryScopes(Long buyerId, List<Long> categoryL1Ids, List<Long> categoryL2Ids,
                                       LocalDateTime now) {
        categoryScopeMapper.deleteByBuyerId(buyerId);
        insertCategoryScopes(buyerId, categoryL1Ids, CATEGORY_LEVEL_L1, now);
        insertCategoryScopes(buyerId, categoryL2Ids, CATEGORY_LEVEL_L2, now);
    }

    private void insertCategoryScopes(Long buyerId, List<Long> categoryIds, short level, LocalDateTime now) {
        if (categoryIds == null) {
            return;
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long categoryId : categoryIds) {
            if (categoryId != null) {
                unique.add(categoryId);
            }
        }
        for (Long categoryId : unique) {
            categoryScopeMapper.insert(BuyerCategoryScopeDO.builder()
                    .buyerId(buyerId)
                    .categoryId(categoryId)
                    .categoryLevel(level)
                    .createdAt(now)
                    .build());
        }
    }
```

替换为：

```java
    private void replaceCategoryScopes(Long buyerId, List<String> sellableCategories, List<String> productTypes,
                                       LocalDateTime now) {
        categoryScopeMapper.deleteByBuyerId(buyerId);
        insertCategoryScopes(buyerId, BuyerScopeKind.SELLABLE, SellableCategory.class, sellableCategories, now);
        insertCategoryScopes(buyerId, BuyerScopeKind.PRODUCT_TYPE, ProductTypeTag.class, productTypes, now);
    }

    private <E extends Enum<E>> void insertCategoryScopes(Long buyerId, BuyerScopeKind kind, Class<E> enumType,
                                                           List<String> codes, LocalDateTime now) {
        if (codes == null) {
            return;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String code : codes) {
            unique.add(ClosedEnums.parseRequired(enumType, code).name());
        }
        for (String code : unique) {
            categoryScopeMapper.insert(BuyerCategoryScopeDO.builder()
                    .buyerId(buyerId)
                    .scopeKind(kind.name())
                    .scopeCode(code)
                    .createdAt(now)
                    .build());
        }
    }
```

删除现在多余的常量 `CATEGORY_LEVEL_L1`/`CATEGORY_LEVEL_L2`（若无其它引用）。

- [ ] **Step 6: 改单测**

把测试构造服务的地方（`setUp()`）：

```java
        service = new BuyerAdminCommandService(
                databaseClock, buyerMapper, contactMapper, integrationModeMapper, cooperationModeMapper,
                categoryScopeMapper, fileRefMapper, accountOpeningService, categoryChainValidator,
                businessNoGenerator, envelopeCrypto);
```

替换为（少一个 `categoryChainValidator` 参数）：

```java
        service = new BuyerAdminCommandService(
                databaseClock, buyerMapper, contactMapper, integrationModeMapper, cooperationModeMapper,
                categoryScopeMapper, fileRefMapper, accountOpeningService,
                businessNoGenerator, envelopeCrypto);
```

删除字段 `private final CategoryChainValidator categoryChainValidator = mock(CategoryChainValidator.class);` 与其 import。

删除断言：

```java
        verify(categoryChainValidator).requireBuyerScopes(List.of(), List.of());
```

`command(...)` 辅助方法的 `List.of(), List.of()`（对应旧的 `categoryL1Ids/categoryL2Ids`）位置不变，只是语义变成 `sellableCategories/productTypes`，值仍是空列表，不需要改动构造参数顺序或数量。

新增测试验证未知码报错：

```java
    @Test
    void createRejectsUnknownSellableCategoryCode() {
        CreateBuyerCommand command = new CreateBuyerCommand(
                "测试分销商", List.of(new ContactLine("张三", "13800138000", "a@b.c")),
                null, null, null, null, null, 7, List.of(), List.of(),
                null, null, "测试科技有限公司", "91110000000000001X",
                "李四", "13900139000", "上海市浦东新区", "测试银行", "6222021234567890",
                null, null, null, null, List.of(), List.of(), List.of("NOT_A_CODE"), List.of(),
                0L, 1L, "buyer-admin-test");

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ENUM_NOT_OPEN.getCode());
    }
```

需要新增 import：`static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;`

- [ ] **Step 7: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/application/BuyerAdminCommandService.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/application/BuyerAdminCommandServiceTest.java
git commit -m "分销商建档改用可售品类/商品类型枚举码，去掉类目树校验"
```

---

### Task 12: `BuyerAdminQueryService`

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/application/BuyerAdminQueryService.java`

- [ ] **Step 1: 删除 `ProductCategoryMapper` 依赖与 `categoryName()`**

删除字段、import 与方法（同 Task 7 Step 1 的模式）。

- [ ] **Step 2: 改 `BuyerPageQuery`/`BuyerDetail`/`BuyerListItem`**

把 `BuyerPageQuery` 里 `List<Long> categoryL1Ids` 改为 `List<String> sellableCategories`。

把 `BuyerDetail` 里：

```java
            @Schema(description = "可售品类") List<NamedRef> categoryL1s,
            @Schema(description = "商品类型") List<NamedRef> categoryL2s,
```

改为：

```java
            @Schema(description = "可售品类，枚举名数组") List<String> sellableCategories,
            @Schema(description = "商品类型，枚举名数组") List<String> productTypes,
```

把 `BuyerListItem` 里：

```java
            @Schema(description = "可售品类") List<NamedRef> categoryL1s,
```

改为：

```java
            @Schema(description = "可售品类，枚举名数组") List<String> sellableCategories,
```

（若 `NamedRef` record 在本类无其它引用，一并删除定义。）

- [ ] **Step 3: 改 `get()` 里读范围表的逻辑**

把：

```java
        List<NamedRef> categoryL1s = new ArrayList<>();
        List<NamedRef> categoryL2s = new ArrayList<>();
        for (BuyerCategoryScopeDO scope : categoryScopeMapper.selectByBuyerId(buyer.getId())) {
            NamedRef ref = new NamedRef(scope.getCategoryId(), categoryName(scope.getCategoryId()));
            if (scope.getCategoryLevel() != null && scope.getCategoryLevel() == CATEGORY_LEVEL_L1) {
                categoryL1s.add(ref);
            } else {
                categoryL2s.add(ref);
            }
        }
```

替换为：

```java
        List<String> sellableCategories = new ArrayList<>();
        List<String> productTypes = new ArrayList<>();
        for (BuyerCategoryScopeDO scope : categoryScopeMapper.selectByBuyerId(buyer.getId())) {
            if (BuyerScopeKind.SELLABLE.name().equals(scope.getScopeKind())) {
                sellableCategories.add(scope.getScopeCode());
            } else if (BuyerScopeKind.PRODUCT_TYPE.name().equals(scope.getScopeKind())) {
                productTypes.add(scope.getScopeCode());
            }
        }
```

并把 `BuyerDetail` 构造调用处对应两个位置改成 `sellableCategories, productTypes`。

- [ ] **Step 4: 改 `matchingBuyerIds()`**

把：

```java
    private Set<Long> matchingBuyerIds(List<String> cooperationModes, List<Long> categoryL1Ids) {
        ...
        Set<Long> byCategory = null;
        if (hasValues(categoryL1Ids)) {
            byCategory = new HashSet<>();
            for (BuyerCategoryScopeDO row : categoryScopeMapper.selectList(
                    new LambdaQueryWrapper<BuyerCategoryScopeDO>()
                            .eq(BuyerCategoryScopeDO::getCategoryLevel, CATEGORY_LEVEL_L1)
                            .in(BuyerCategoryScopeDO::getCategoryId, categoryL1Ids))) {
                byCategory.add(row.getBuyerId());
            }
        }
```

替换为：

```java
    private Set<Long> matchingBuyerIds(List<String> cooperationModes, List<String> sellableCategories) {
        ...
        Set<Long> byCategory = null;
        if (hasValues(sellableCategories)) {
            byCategory = new HashSet<>();
            for (BuyerCategoryScopeDO row : categoryScopeMapper.selectList(
                    new LambdaQueryWrapper<BuyerCategoryScopeDO>()
                            .eq(BuyerCategoryScopeDO::getScopeKind, BuyerScopeKind.SELLABLE.name())
                            .in(BuyerCategoryScopeDO::getScopeCode, sellableCategories))) {
                byCategory.add(row.getBuyerId());
            }
        }
```

同步改 `page()` 里调用处 `matchingBuyerIds(query.cooperationModes(), query.categoryL1Ids())` → `matchingBuyerIds(query.cooperationModes(), query.sellableCategories())`，以及日志占位 `categoryL1Ids={}` → `sellableCategories={}`。

- [ ] **Step 5: 改 `toListItem()`**

把：

```java
        List<NamedRef> categoryL1s = new ArrayList<>();
        LinkedHashMap<Long, NamedRef> unique = new LinkedHashMap<>();
        for (BuyerCategoryScopeDO scope : categoryScopeMapper.selectByBuyerId(buyer.getId())) {
            if (scope.getCategoryLevel() != null && scope.getCategoryLevel() == CATEGORY_LEVEL_L1
                    && !unique.containsKey(scope.getCategoryId())) {
                unique.put(scope.getCategoryId(),
                        new NamedRef(scope.getCategoryId(), categoryName(scope.getCategoryId())));
            }
        }
        categoryL1s.addAll(unique.values());
```

替换为：

```java
        LinkedHashSet<String> sellableCategories = new LinkedHashSet<>();
        for (BuyerCategoryScopeDO scope : categoryScopeMapper.selectByBuyerId(buyer.getId())) {
            if (BuyerScopeKind.SELLABLE.name().equals(scope.getScopeKind())) {
                sellableCategories.add(scope.getScopeCode());
            }
        }
```

并把 `BuyerListItem` 构造调用处的 `categoryL1s` 改成 `List.copyOf(sellableCategories)`。新增 import `java.util.LinkedHashSet`（若尚未存在），移除不再使用的 `LinkedHashMap` import（若确认无其它用途）。删除常量 `CATEGORY_LEVEL_L1`（若无其它引用）。

新增 import：

```java
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerScopeKind;
```

- [ ] **Step 6: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/application/BuyerAdminQueryService.java
git commit -m "分销商查询改用 scope_kind/scope_code 枚举码，去掉类目名查找"
```

---

### Task 13: `BuyerAdminController`

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/BuyerAdminController.java`

- [ ] **Step 1: 改 `page()` 筛选参数**

把：

```java
            @Parameter(description = "可售品类内部主键，可多选") @RequestParam(required = false) List<Long> categoryL1Ids,
```

替换为：

```java
            @Parameter(description = "可售品类枚举名，可多选") @RequestParam(required = false) List<String> sellableCategories,
```

把 `page()` 方法体：

```java
        return success(queryService.page(new BuyerPageQuery(
                buyerName, buyerCode, distributionLevels, cooperationModes, categoryL1Ids,
                statuses, updatedFrom, updatedTo, pageNo, pageSize)));
```

替换为：

```java
        return success(queryService.page(new BuyerPageQuery(
                buyerName, buyerCode, distributionLevels, cooperationModes, sellableCategories,
                statuses, updatedFrom, updatedTo, pageNo, pageSize)));
```

同步把 `@Operation` 文档串 `categoryL1Ids` 改成 `sellableCategories`。

- [ ] **Step 2: 改 `BuyerSaveReqVO` 字段**

把：

```java
        @Schema(description = "可售品类内部主键")
        private List<Long> categoryL1Ids;
        @Schema(description = "商品类型内部主键")
        private List<Long> categoryL2Ids;
```

替换为：

```java
        @Schema(description = "可售品类枚举名，多选")
        private List<String> sellableCategories;
        @Schema(description = "商品类型枚举名，多选")
        private List<String> productTypes;
```

- [ ] **Step 3: 改 `toCreateCommand`/`toUpdateCommand`**

把两处都出现的：

```java
                request.getQualificationFileKeys(), request.getCategoryL1Ids(), request.getCategoryL2Ids(),
```

替换为：

```java
                request.getQualificationFileKeys(), request.getSellableCategories(), request.getProductTypes(),
```

- [ ] **Step 4: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/BuyerAdminController.java
git commit -m "分销商管理端 REST 改用可售品类/商品类型枚举名筛选与保存"
```

---

### Task 14: `PlatformSpuDO` + `ProductCatalogCommandService` + `M1MasterDataAdminController`

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/dal/dataobject/PlatformSpuDO.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/ProductCatalogCommandService.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/M1MasterDataAdminController.java`

- [ ] **Step 1: `PlatformSpuDO` 加三个字段**

把：

```java
    private Long primaryCategoryId;
    private String spuName;
```

替换为：

```java
    /** 平台主类目主键，逻辑引用sc_product_category.id（历史字段，主数据保留，不用于供货命中或加购筛选） */
    private Long primaryCategoryId;
    /** 可售品类封闭枚举，见SellableCategory，供货命中用 */
    private String sellableCategory;
    /** 商品类型封闭枚举，见ProductTypeTag，供货命中用 */
    private String productType;
    /** 商品分类封闭枚举，见ProductClassification，供货命中用 */
    private String productClassification;
    private String spuName;
```

- [ ] **Step 2: `ProductCatalogCommandService.CreateSpuCommand` 加三码，`primaryCategoryCode` 变可空**

新增 import：

```java
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.tradecore.profile.domain.ProductClassification;
import com.aifuli.module.supplychain.tradecore.profile.domain.ProductTypeTag;
import com.aifuli.module.supplychain.tradecore.profile.domain.SellableCategory;
```

把：

```java
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createSpu(CreateSpuCommand command) {
        BrandDO brand = requireBrand(command.brandCode());
        ProductCategoryDO category = requireCategory(command.primaryCategoryCode());
        LocalDateTime now = now();
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .spuCode(command.spuCode())
                .brandId(brand.getId())
                .primaryCategoryId(category.getId())
                .spuName(command.spuName())
                .subtitle(command.subtitle())
                .coverUrl(command.coverUrl())
                .useInstruction(command.useInstruction())
                .status(CatalogEntityStatus.DRAFT.getCode())
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        spuMapper.insert(spu);
        return spu.getSpuCode();
    }
```

替换为：

```java
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createSpu(CreateSpuCommand command) {
        BrandDO brand = requireBrand(command.brandCode());
        Long primaryCategoryId = hasText(command.primaryCategoryCode())
                ? requireCategory(command.primaryCategoryCode()).getId() : null;
        String sellableCategory = ClosedEnums.parseRequired(SellableCategory.class, command.sellableCategory()).name();
        String productType = ClosedEnums.parseRequired(ProductTypeTag.class, command.productType()).name();
        String productClassification =
                ClosedEnums.parseRequired(ProductClassification.class, command.productClassification()).name();
        LocalDateTime now = now();
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .spuCode(command.spuCode())
                .brandId(brand.getId())
                .primaryCategoryId(primaryCategoryId)
                .sellableCategory(sellableCategory)
                .productType(productType)
                .productClassification(productClassification)
                .spuName(command.spuName())
                .subtitle(command.subtitle())
                .coverUrl(command.coverUrl())
                .useInstruction(command.useInstruction())
                .status(CatalogEntityStatus.DRAFT.getCode())
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        spuMapper.insert(spu);
        return spu.getSpuCode();
    }
```

新增私有静态方法（若类内尚无同名 helper）：

```java
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
```

把：

```java
    public record CreateSpuCommand(String spuCode, String brandCode, String primaryCategoryCode,
                                   String spuName, String subtitle, String coverUrl,
                                   String useInstruction, Long operatorId) {
    }
```

替换为：

```java
    public record CreateSpuCommand(String spuCode, String brandCode, String primaryCategoryCode,
                                   String sellableCategory, String productType, String productClassification,
                                   String spuName, String subtitle, String coverUrl,
                                   String useInstruction, Long operatorId) {
    }
```

- [ ] **Step 3: `M1MasterDataAdminController` 调用点与 `CreateSpuReqVO`**

把：

```java
    public CommonResult<String> createSpu(@Valid @RequestBody CreateSpuReqVO request) {
        return success(catalogCommandService.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                request.getSpuCode(), request.getBrandCode(), request.getPrimaryCategoryCode(),
                request.getSpuName(), request.getSubtitle(), request.getCoverUrl(),
                request.getUseInstruction(), getLoginUserId())));
    }
```

替换为：

```java
    public CommonResult<String> createSpu(@Valid @RequestBody CreateSpuReqVO request) {
        return success(catalogCommandService.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                request.getSpuCode(), request.getBrandCode(), request.getPrimaryCategoryCode(),
                request.getSellableCategory(), request.getProductType(), request.getProductClassification(),
                request.getSpuName(), request.getSubtitle(), request.getCoverUrl(),
                request.getUseInstruction(), getLoginUserId())));
    }
```

把：

```java
    public static class CreateSpuReqVO {
        @NotBlank @Size(max = 32) @Schema(description = "SPU 编码", example = "TELCO_RECHARGE")
        private String spuCode;
        @NotBlank @Schema(description = "品牌编码", example = "CMCC")
        private String brandCode;
        @NotBlank @Schema(description = "主类目编码", example = "DIRECT_RECHARGE")
        private String primaryCategoryCode;
        @NotBlank @Size(max = 256) @Schema(description = "SPU 标准名称", example = "话费直充")
        private String spuName;
```

替换为：

```java
    public static class CreateSpuReqVO {
        @NotBlank @Size(max = 32) @Schema(description = "SPU 编码", example = "TELCO_RECHARGE")
        private String spuCode;
        @NotBlank @Schema(description = "品牌编码", example = "CMCC")
        private String brandCode;
        @Size(max = 32) @Schema(description = "主类目编码，历史字段，不用于供货命中或加购筛选，可不填")
        private String primaryCategoryCode;
        @NotBlank @Schema(description = "可售品类枚举名，供货命中用", example = "MOVIE")
        private String sellableCategory;
        @NotBlank @Schema(description = "商品类型枚举名，供货命中用", example = "MOVIE")
        private String productType;
        @NotBlank @Schema(description = "商品分类枚举名，供货命中用", example = "MOVIE_TICKET")
        private String productClassification;
        @NotBlank @Size(max = 256) @Schema(description = "SPU 标准名称", example = "话费直充")
        private String spuName;
```

- [ ] **Step 4: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/dal/dataobject/PlatformSpuDO.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/ProductCatalogCommandService.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/M1MasterDataAdminController.java
git commit -m "平台SPU创建改为三码必填打标，主类目编码变为历史可选字段"
```

---

### Task 15: `PlatformSkuQueryService` + `PlatformSkuAdminController`

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/PlatformSkuQueryService.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/PlatformSkuAdminController.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/catalog/application/PlatformSkuQueryServiceTest.java`

- [ ] **Step 1: 整文件改写 `PlatformSkuQueryService`（删除类目树遍历，改三码直接匹配）**

用 `Write` 整文件覆盖为：

```java
package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;

/**
 * 管理端标品 SKU 池分页查询。金额单位均为 CNY 分；0 元 L1 视为已定价。
 * 三码直接来自 {@code sc_platform_spu}，不再走类目树祖先链；供应商过滤按三码整体相等命中供货配置。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#42-平台spu}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSkuQueryService {

    private final PlatformSkuMapper skuMapper;
    private final PlatformSpuMapper spuMapper;
    private final PlatformSettlementPriceMapper priceMapper;
    private final PlatformSettlementPriceVersionMapper versionMapper;
    private final SupplierMapper supplierMapper;
    private final SupplierSupplyScopeMapper scopeMapper;
    private final SupplierOfferMapper offerMapper;
    private final BuyerMapper buyerMapper;
    private final BuyerProductMapper buyerProductMapper;
    private final BuyerProjectMapper buyerProjectMapper;
    private final ProjectListingMapper projectListingMapper;

    /**
     * 分页查询启用标品 SKU。供应商三码集合为空或候选 SPU 三码无一命中时返回空页；
     * 供应商/分销商/项目编码缺失抛业务错误。
     */
    public PageResult<SkuListItem> page(SkuPageQuery query) {
        log.info("查询标品SKU分页 keyword={} sellableCategory={} productType={} productClassification={} "
                        + "supplierCode={} buyerCode={} projectCode={} pageNo={} pageSize={}",
                query.keyword(), query.sellableCategory(), query.productType(), query.productClassification(),
                query.supplierCode(), query.buyerCode(), query.projectCode(),
                query.pageNo(), query.pageSize());

        Set<Long> includeSkuIds = null;
        Set<Long> excludeSkuIds = new HashSet<>();
        Set<String> supplierTripleKeys = null;
        if (hasText(query.supplierCode())) {
            SupplierDO supplier = supplierMapper.selectByCode(query.supplierCode());
            if (supplier == null) {
                throw exception(SUPPLIER_NOT_FOUND);
            }
            supplierTripleKeys = new HashSet<>();
            for (SupplierSupplyScopeDO scope : scopeMapper.selectBySupplierId(supplier.getId())) {
                supplierTripleKeys.add(tripleKey(scope.getSellableCategory(), scope.getProductType(),
                        scope.getProductClassification()));
            }
            if (supplierTripleKeys.isEmpty()) {
                return emptyPage();
            }
            List<SupplierOfferDO> offers = offerMapper.selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                    .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                    .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode()));
            for (SupplierOfferDO offer : offers) {
                if (offer.getPlatformSkuId() != null) {
                    excludeSkuIds.add(offer.getPlatformSkuId());
                }
            }
        }
        if (hasText(query.buyerCode())) {
            excludeSkuIds.addAll(activeBuyerProductSkuIds(query.buyerCode()));
        }
        if (hasText(query.projectCode())) {
            ProjectSkuFilter projectFilter = resolveProjectSkuFilter(query.projectCode());
            if (projectFilter.includeSkuIds().isEmpty()) {
                return emptyPage();
            }
            includeSkuIds = projectFilter.includeSkuIds();
            excludeSkuIds.addAll(projectFilter.listedSkuIds());
        }

        LambdaQueryWrapper<PlatformSpuDO> spuWrapper = new LambdaQueryWrapper<PlatformSpuDO>()
                .eq(PlatformSpuDO::getStatus, CatalogEntityStatus.ACTIVE.getCode())
                .eq(hasText(query.sellableCategory()), PlatformSpuDO::getSellableCategory, query.sellableCategory())
                .eq(hasText(query.productType()), PlatformSpuDO::getProductType, query.productType())
                .eq(hasText(query.productClassification()), PlatformSpuDO::getProductClassification,
                        query.productClassification());
        List<PlatformSpuDO> spus = spuMapper.selectList(spuWrapper);
        if (supplierTripleKeys != null) {
            Set<String> allowedKeys = supplierTripleKeys;
            spus = spus.stream()
                    .filter(spu -> allowedKeys.contains(tripleKey(
                            spu.getSellableCategory(), spu.getProductType(), spu.getProductClassification())))
                    .toList();
        }
        if (spus.isEmpty()) {
            return emptyPage();
        }

        Map<Long, PlatformSpuDO> spuById = new HashMap<>();
        Set<Long> spuIds = new HashSet<>();
        Set<Long> keywordSpuIds = new HashSet<>();
        boolean keywordPresent = hasText(query.keyword());
        for (PlatformSpuDO spu : spus) {
            spuById.put(spu.getId(), spu);
            spuIds.add(spu.getId());
            if (keywordPresent && spu.getSpuName() != null && spu.getSpuName().contains(query.keyword())) {
                keywordSpuIds.add(spu.getId());
            }
        }

        LambdaQueryWrapper<PlatformSkuDO> skuWrapper = new LambdaQueryWrapper<PlatformSkuDO>()
                .eq(PlatformSkuDO::getStatus, CatalogEntityStatus.ACTIVE.getCode())
                .in(PlatformSkuDO::getSpuId, spuIds)
                .orderByDesc(PlatformSkuDO::getCreatedAt);
        if (keywordPresent) {
            skuWrapper.and(w -> {
                w.like(PlatformSkuDO::getSkuName, query.keyword());
                if (!keywordSpuIds.isEmpty()) {
                    w.or().in(PlatformSkuDO::getSpuId, keywordSpuIds);
                }
            });
        }
        if (includeSkuIds != null) {
            skuWrapper.in(PlatformSkuDO::getId, includeSkuIds);
        }
        if (!excludeSkuIds.isEmpty()) {
            skuWrapper.notIn(PlatformSkuDO::getId, excludeSkuIds);
        }

        PageParam pageParam = new PageParam();
        pageParam.setPageNo(query.pageNo() == null ? 1 : query.pageNo());
        pageParam.setPageSize(query.pageSize() == null ? 20 : query.pageSize());
        PageResult<PlatformSkuDO> page = skuMapper.selectPage(pageParam, skuWrapper);

        List<SkuListItem> items = new ArrayList<>();
        for (PlatformSkuDO sku : page.getList()) {
            items.add(toListItem(sku, spuById.get(sku.getSpuId())));
        }
        return new PageResult<>(items, page.getTotal());
    }

    /**
     * 分销商必须存在；已有效关联的平台 SKU 从「新增分销商品」池中排除。
     */
    private Set<Long> activeBuyerProductSkuIds(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode);
        if (buyer == null) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        Set<Long> skuIds = new HashSet<>();
        List<BuyerProductDO> products = buyerProductMapper.selectList(new LambdaQueryWrapper<BuyerProductDO>()
                .eq(BuyerProductDO::getBuyerId, buyer.getId())
                .eq(BuyerProductDO::getStatus, BuyerProductStatus.ACTIVE.getCode()));
        for (BuyerProductDO product : products) {
            if (product.getPlatformSkuId() != null) {
                skuIds.add(product.getPlatformSkuId());
            }
        }
        return skuIds;
    }

    /**
     * 项目必须存在；SKU 须已是所属分销商的有效分销商品，且项目目录尚未可采购关联。
     */
    private ProjectSkuFilter resolveProjectSkuFilter(String projectCode) {
        BuyerProjectDO project = buyerProjectMapper.selectByProjectCode(projectCode);
        if (project == null) {
            throw exception(PROJECT_NOT_FOUND);
        }
        Set<Long> includeSkuIds = new HashSet<>();
        Map<Long, BuyerProductDO> productById = new HashMap<>();
        List<BuyerProductDO> products = buyerProductMapper.selectList(new LambdaQueryWrapper<BuyerProductDO>()
                .eq(BuyerProductDO::getBuyerId, project.getBuyerId())
                .eq(BuyerProductDO::getStatus, BuyerProductStatus.ACTIVE.getCode()));
        for (BuyerProductDO product : products) {
            productById.put(product.getId(), product);
            if (product.getPlatformSkuId() != null) {
                includeSkuIds.add(product.getPlatformSkuId());
            }
        }
        Set<Long> listedSkuIds = new HashSet<>();
        List<ProjectListingDO> listings = projectListingMapper.selectList(
                new LambdaQueryWrapper<ProjectListingDO>()
                        .eq(ProjectListingDO::getProjectId, project.getId())
                        .eq(ProjectListingDO::getStatus, ProjectListingStatus.AVAILABLE.getCode()));
        for (ProjectListingDO listing : listings) {
            BuyerProductDO product = productById.get(listing.getBuyerProductId());
            if (product != null && product.getPlatformSkuId() != null) {
                listedSkuIds.add(product.getPlatformSkuId());
            }
        }
        return new ProjectSkuFilter(includeSkuIds, listedSkuIds);
    }

    private SkuListItem toListItem(PlatformSkuDO sku, PlatformSpuDO spu) {
        return new SkuListItem(
                sku.getId(),
                sku.getSkuCode(),
                sku.getSkuName(),
                spu == null ? null : spu.getSpuCode(),
                spu == null ? null : spu.getSpuName(),
                spu == null ? null : spu.getSellableCategory(),
                spu == null ? null : spu.getProductType(),
                spu == null ? null : spu.getProductClassification(),
                resolveSuggestPriceCents(sku.getId()),
                sku.getFaceValue());
    }

    /**
     * 建议结算价取当前 L1 {@code unit_price_cents}；无身份或无版本则为空。0 不是 missing。
     */
    private Long resolveSuggestPriceCents(Long skuId) {
        PlatformSettlementPriceDO identity = priceMapper.selectById(skuId);
        if (identity == null || identity.getCurrentVersionId() == null) {
            return null;
        }
        PlatformSettlementPriceVersionDO version = versionMapper.selectById(identity.getCurrentVersionId());
        if (version == null) {
            return null;
        }
        return version.getUnitPriceCents();
    }

    private static String tripleKey(String sellableCategory, String productType, String productClassification) {
        return sellableCategory + "|" + productType + "|" + productClassification;
    }

    private static PageResult<SkuListItem> emptyPage() {
        return new PageResult<>(List.of(), 0L);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ProjectSkuFilter(Set<Long> includeSkuIds, Set<Long> listedSkuIds) {
    }

    public record SkuPageQuery(String keyword, String sellableCategory, String productType,
                               String productClassification, String supplierCode, String buyerCode,
                               String projectCode, Integer pageNo, Integer pageSize) {
    }

    /**
     * 标品 SKU 列表行。金额注释与 DDL COMMENT 对齐：面值为 C 端建议价，L1 为 B 端结算单价；
     * 三码直接来自 SPU，未打码的历史 SPU 三个字段均为 null。
     */
    public record SkuListItem(
            @Schema(description = "平台SKU内部主键") Long id,
            @Schema(description = "平台SKU永久业务编码") String skuCode,
            @Schema(description = "平台SKU标准名称") String skuName,
            @Schema(description = "平台SPU永久业务编码") String spuCode,
            @Schema(description = "平台SPU标准名称") String spuName,
            @Schema(description = "可售品类封闭枚举名") String sellableCategory,
            @Schema(description = "商品类型封闭枚举名") String productType,
            @Schema(description = "商品分类封闭枚举名") String productClassification,
            @Schema(description = "L1 B端结算单价，CNY分，允许0") Long suggestPriceCents,
            @Schema(description = "C端建议价，取自平台SKU标准面值，单位CNY分") Long marketPriceCents) {
    }
}
```

- [ ] **Step 2: 改 `PlatformSkuAdminController`**

把：

```java
    public CommonResult<PageResult<SkuListItem>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryL1Id,
            @RequestParam(required = false) Long categoryL2Id,
            @RequestParam(required = false) Long categoryL3Id,
            @RequestParam(required = false) String supplierCode,
            @RequestParam(required = false) String buyerCode,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Integer pageNo,
            @RequestParam(required = false) Integer pageSize) {
        return success(queryService.page(new SkuPageQuery(
                keyword, categoryL1Id, categoryL2Id, categoryL3Id,
                supplierCode, buyerCode, projectCode, pageNo, pageSize)));
    }
```

替换为：

```java
    public CommonResult<PageResult<SkuListItem>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sellableCategory,
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) String productClassification,
            @RequestParam(required = false) String supplierCode,
            @RequestParam(required = false) String buyerCode,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Integer pageNo,
            @RequestParam(required = false) Integer pageSize) {
        return success(queryService.page(new SkuPageQuery(
                keyword, sellableCategory, productType, productClassification,
                supplierCode, buyerCode, projectCode, pageNo, pageSize)));
    }
```

- [ ] **Step 3: 改单测**

在 `PlatformSkuQueryServiceTest` 的 `setUp()` 里，把：

```java
        service = new PlatformSkuQueryService(
                skuMapper, spuMapper, categoryMapper, priceMapper, versionMapper,
                mock(SupplierMapper.class), mock(SupplierSupplyScopeMapper.class),
                mock(SupplierOfferMapper.class), mock(BuyerMapper.class),
                buyerProductMapper, buyerProjectMapper, projectListingMapper);
```

替换为（去掉 `categoryMapper` 参数，并删除该 mock 字段与 import `ProductCategoryMapper`/`ProductCategoryDO`）：

```java
        service = new PlatformSkuQueryService(
                skuMapper, spuMapper, priceMapper, versionMapper,
                mock(SupplierMapper.class), mock(SupplierSupplyScopeMapper.class),
                mock(SupplierOfferMapper.class), mock(BuyerMapper.class),
                buyerProductMapper, buyerProjectMapper, projectListingMapper);
```

把两个已有测试里的 `PlatformSpuDO.builder()...primaryCategoryId(3L)...` 改为打三码，例如：

```java
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(8L).spuCode("MOVIE_TICKET").spuName("电影票")
                .sellableCategory("MOVIE").productType("MOVIE").productClassification("MOVIE_TICKET")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
```

把 `service.page(new PlatformSkuQueryService.SkuPageQuery("电影票", null, null, null, null, null, null, 1, 20))` 保持参数个数不变（原本 `categoryL1Id/L2Id/L3Id` 三个 `null` 位置现在对应 `sellableCategory/productType/productClassification` 三个 `null`，字面调用不用改，只是含义变了）。

把断言：

```java
        assertThat(row.categoryL3Id()).isEqualTo(3L);
        assertThat(row.categoryL1Name()).isEqualTo("权益");
```

替换为：

```java
        assertThat(row.sellableCategory()).isEqualTo("MOVIE");
        assertThat(row.productClassification()).isEqualTo("MOVIE_TICKET");
```

同样处理该测试文件里其余用到 `categoryMapper`/`ProductCategoryDO`/`categoryL1Id`/`categoryL1Name` 等的断言（先 `grep -n "categoryL\|stubCategories\|ProductCategory" PlatformSkuQueryServiceTest.java` 定位剩余引用后逐一替换或删除 `stubCategories()` 辅助方法）。

- [ ] **Step 4: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/PlatformSkuQueryService.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/PlatformSkuAdminController.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/catalog/application/PlatformSkuQueryServiceTest.java
git commit -m "标品SKU池筛选改用三码直接匹配，删除类目树祖先链遍历"
```

---

### Task 16: 删除 `CategoryChainValidator`

**Files:**
- Delete: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/CategoryChainValidator.java`
- Delete: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/catalog/application/CategoryChainValidatorTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/supply/SupplierAdminProfileIntegrationTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/BuyerAdminIntegrationTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/ProjectAdminIntegrationTest.java`

**前提确认：** 完成 Task 6（`SupplierSupplyScopeService`）与 Task 11（`BuyerAdminCommandService`）后，`CategoryChainValidator` 应已无生产代码调用方。先跑一次全局确认：

Run: `grep -rn "CategoryChainValidator" aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main`
Expected: 无输出（除即将被删除的自身文件）。

- [ ] **Step 1: 删除类与其单测**

```bash
rm aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/CategoryChainValidator.java
rm aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/catalog/application/CategoryChainValidatorTest.java
```

- [ ] **Step 2: 从 3 个集成测试的 `@Import({...})` 列表与 import 里移除 `CategoryChainValidator.class`**

`SupplierAdminProfileIntegrationTest.java` 第 8 行 import 与第 347 行左右 `@Import` 数组里的 `CategoryChainValidator.class,` 一并删除（注意删除元素后其前后的逗号，保持数组语法合法）。

`BuyerAdminIntegrationTest.java` 第 31 行 import 与第 429 行左右 `@Import` 数组里的 `CategoryChainValidator.class,` 同样处理。

`ProjectAdminIntegrationTest.java` 第 24 行 import 与第 282 行左右 `@Import` 数组里的 `CategoryChainValidator.class,` 同样处理。

- [ ] **Step 3: 编译确认无残留引用**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test-compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add -A aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/CategoryChainValidator.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/catalog/application/CategoryChainValidatorTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/supply/SupplierAdminProfileIntegrationTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/BuyerAdminIntegrationTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/ProjectAdminIntegrationTest.java
git commit -m "删除不再使用的 CategoryChainValidator：供货配置与分销商档案已改三码枚举"
```

---

### Task 17: 架构测试新增规则

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java`

- [ ] **Step 1: 新增 ArchRule 常量（放在其它 `static final ArchRule` 定义之间，风格一致）**

用 `StrReplace`，在 `facade_does_not_access_persistence` 定义之后插入：

```java
    /**
     * 供货命中（合作商品新增/匹配）与标品SKU池的供应商过滤禁止依赖 ProductCategoryMapper：
     * 三码是彼此独立的封闭枚举，不应回退去走类目树祖先链。
     * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#7-测试门禁}。
     */
    static final ArchRule supply_matching_does_not_read_category_tree = noClasses()
            .that().haveSimpleNameIn("SupplierCooperativeProductService", "PlatformSkuQueryService")
            .should().dependOnClassesThat().haveSimpleName("ProductCategoryMapper");
```

- [ ] **Step 2: 新增对应 `@Test` 方法（放在 `facadeDoesNotAccessPersistence` 之后）**

```java
    @Test
    void supplyMatchingDoesNotReadCategoryTree() {
        supply_matching_does_not_read_category_tree.check(importedClasses);
    }
```

- [ ] **Step 3: 运行**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -Dtest=SupplyChainModuleArchitectureTest`
Expected: `BUILD SUCCESS`（要求 Task 7/9/15 已经把这两个类的 `ProductCategoryMapper` 依赖删干净，否则这条新规则会失败——这正是它的作用）。

- [ ] **Step 4: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java
git commit -m "架构测试新增规则：供货命中与SKU供应商过滤禁止依赖类目树Mapper"
```

---

### Task 18: 契约测试字段名同步

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/SupplierAdminRestContractTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/SupplierSaveReqVOContractTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/BuyerAdminRestContractTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/PlatformSkuAdminContractTest.java`

**字段名映射表（旧 → 新，逐个 grep 后替换出现处；类型也变了，Long/List<Long> 全部变 String/List<String>）：**

| 场景 | 旧字段 | 新字段 |
| --- | --- | --- |
| 供货配置行（`ScopeReqVO`/`SupplyScopeView`） | `categoryL1Id`/`categoryL2Id`/`categoryL3Id`（+对应 Name） | `sellableCategory`/`productType`/`productClassification` |
| 供应商列表筛选与聚合 | `categoryL1Ids`（query 参数）；`categories`/`productTypes`/`productCategories`（响应，`{id,name}[]`） | `sellableCategories`（query 参数）；`sellableCategories`/`productTypes`/`productClassifications`（响应，`string[]`） |
| 合作商品列表筛选与行 | `categoryL1Id`/`categoryL2Id`/`categoryL3Id`（query+响应，+Name） | `sellableCategory`/`productType`/`productClassification`（query+响应） |
| 分销商档案（`BuyerSaveReqVO`/`BuyerDetail`） | `categoryL1Ids`/`categoryL2Ids`（提交，`Long[]`）；`categoryL1s`/`categoryL2s`（响应，`{id,name}[]`） | `sellableCategories`/`productTypes`（提交与响应均为 `string[]`） |
| 分销商列表筛选与行 | `categoryL1Ids`（query）；`categoryL1s`（响应） | `sellableCategories`（query 与响应皆为 `string[]`） |
| 平台 SPU 创建 | `primaryCategoryCode`（必填） | `sellableCategory`/`productType`/`productClassification`（新增必填）；`primaryCategoryCode` 变可选 |
| 标品 SKU 池 | `categoryL1Id`/`categoryL2Id`/`categoryL3Id`（query+响应，+Name） | `sellableCategory`/`productType`/`productClassification`（query+响应） |

- [ ] **Step 1: 逐文件 grep 定位需要改的断言/请求体**

Run: `grep -n "categoryL1\|categoryL2\|categoryL3\|primaryCategoryCode" aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/SupplierAdminRestContractTest.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/SupplierSaveReqVOContractTest.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/BuyerAdminRestContractTest.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/PlatformSkuAdminContractTest.java`

对每一处匹配，按上表把字段名与取值类型（`Long`/`1L` → `String`/`"EQUITY"` 等三码枚举名之一）改掉。请求体里原来传类目内部主键的地方（例如 `1L`、`2L`、`3L`）改成合法枚举名（如 `"EQUITY"`、`"MOVIE"`、`"MOVIE_TICKET"`），保证契约测试仍能通过 `@Valid` 校验与 mock service 的桩数据对齐（若测试里对 service 返回值做了 `when(...).thenReturn(...)` 桩数据，同步把桩数据里的 `SupplyScopeView`/`SupplierListItem`/`OfferListItem`/`BuyerDetail`/`BuyerListItem`/`SkuListItem` 构造参数改成新字段顺序，参照 Task 7/9/12/15 里给出的新 record 定义）。

- [ ] **Step 2: 运行每个契约测试确认编译期字段名已跟服务层新签名对齐**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -Dtest=SupplierAdminRestContractTest,SupplierSaveReqVOContractTest,BuyerAdminRestContractTest,PlatformSkuAdminContractTest`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/SupplierAdminRestContractTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/SupplierSaveReqVOContractTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/BuyerAdminRestContractTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/PlatformSkuAdminContractTest.java
git commit -m "契约测试字段名同步为三码枚举，不再要求 categoryL1Id 等类目树主键"
```

---

### Task 19: 集成测试请求体同步

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/supply/SupplierAdminProfileIntegrationTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/BuyerAdminIntegrationTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/ProjectAdminIntegrationTest.java`

- [ ] **Step 1: 定位并改造涉及供货配置/分销商范围/SPU 主类目的请求体与断言**

Run: `grep -n "categoryL1\|categoryL2\|categoryL3\|primaryCategoryCode\|createSpu\|saveScopes\|supply-scopes" aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/supply/SupplierAdminProfileIntegrationTest.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/BuyerAdminIntegrationTest.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/ProjectAdminIntegrationTest.java`

按 Task 18 同一张字段映射表，把测试里构造的 JSON 请求体（通常是 Java 字符串模板或 `Map.of(...)`）与断言路径改成新字段名与枚举名取值（例如把 `"categoryL1Id": 1` 改成 `"sellableCategory": "EQUITY"`）。若测试里事先往 `sc_platform_spu`/`sc_supplier_supply_scope`/`sc_buyer_category_scope` 插入测试夹具数据（`INSERT INTO ...` 或直接 `xxxMapper.insert(...)`），同步把夹具改成新列名与枚举值，保证 Testcontainers 真实 MySQL 上跑通建表 DDL（024 迁移后表结构）与业务断言一致。

- [ ] **Step 2: 运行（需要本机 Docker）**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -Dtest=SupplierAdminProfileIntegrationTest,BuyerAdminIntegrationTest,ProjectAdminIntegrationTest`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/supply/SupplierAdminProfileIntegrationTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/BuyerAdminIntegrationTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/ProjectAdminIntegrationTest.java
git commit -m "集成测试请求体与夹具同步改用三码枚举，适配 024 迁移后的表结构"
```

---

### Task 20: 全量回归与文档收尾

**Files:**
- 无新增文件；仅运行验证与（可选）追加联调记录到 `docs/集成测试.md`

- [ ] **Step 1: 全模块编译 + 单测（不含 Testcontainers 集成测试也应全绿）**

Run: `mvn -pl aifuli-module-system/aifuli-module-system-server,aifuli-module-supply-chain/aifuli-module-supply-chain-api,aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test`
Expected: `BUILD SUCCESS`

- [ ] **Step 2: 若本机有 Docker，补跑供应链集成测试 profile**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -Psupply-chain-integration`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: 按 `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁` 确认 `*ArchitectureTest`/`*ContractTest` 均已跑过**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -Dtest="*ArchitectureTest,*ContractTest"`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: 仅在用户明确要求部署验证时，才把 system `002` + supply-chain `024` 的联调结果补记到 `docs/集成测试.md`（追加新小节，不改动已有内容），格式参照文件里已有的 `023-admin-invoice` 联调记录小节。**

- [ ] **Step 5: 最终提交（若前面 19 个 Task 已逐一提交，这里通常无剩余改动；如有遗留格式化/清理，单独提交）**

```bash
git status --short
git add -A
git commit -m "供应链表单字典与标品打标：收尾清理与全量回归"
```

（如 `git status --short` 无输出，跳过本次 commit。）
