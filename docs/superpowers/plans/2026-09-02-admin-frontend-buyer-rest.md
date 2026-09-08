# 分销商管理端 REST Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 必须先完成 [foundation](./2026-09-02-admin-frontend-foundation.md) 与 [supplier](./2026-09-02-admin-frontend-supplier-rest.md)（类目链与 SKU 查询已可用）。

**Goal:** 新建 `/supply-chain/buyers`：档案 CRUD、组织树、黑名单、采购商账本期初、分销商品与手工 L2。管理端中文叫分销商，代码仍是 Buyer。

**Architecture:** 新服务在 `tradecore.buyer` 与 `tradecore.catalog`（BuyerProduct/L2）。期初只调 `BuyerAccountOpeningService`，禁止写 `sc_supplier_settlement_account`。不创建租户绑定。树校验纯函数可单测。`supplyPreference` 只存档，禁止改 routing 包。

**Tech Stack:** 见 [总览](./2026-09-02-admin-frontend-rest.md)。

---

## 文件结构

```
tradecore/buyer/domain/BuyerHierarchy.java          # 等级/上级规则
tradecore/buyer/application/BuyerAdminCommandService.java
tradecore/buyer/application/BuyerAdminQueryService.java
tradecore/catalog/application/BuyerProductCommandService.java
tradecore/catalog/application/BuyerProductQueryService.java
interfaceapi/admin/BuyerAdminController.java
tests: BuyerHierarchyTest, BuyerAdmin*Test, BuyerProduct*Test, BuyerAdminRestContractTest, BuyerAdminIntegrationTest
```

加密 AAD：`buyer:{id}:legalPersonMobile` 等，仿供应商。

---

### Task 1: 组织树规则

**Files:**
- Create: `tradecore/buyer/domain/BuyerHierarchy.java`
- Create: `src/test/java/com/aifuli/module/supplychain/tradecore/buyer/domain/BuyerHierarchyTest.java`

- [ ] **Step 1: 测试**

```java
class BuyerHierarchyTest {
    @Test
    void levelOneMustHaveNoParent() {
        assertThat(BuyerHierarchy.validate(1, null, null)).isEqualTo(new BuyerHierarchy.Result(1, null));
        assertThatThrownBy(() -> BuyerHierarchy.validate(1, 9L, parent(1)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_PARENT_INVALID.getCode());
    }

    @Test
    void levelTwoRequiresLevelOneParent() {
        BuyerHierarchy.validate(2, 9L, parent(1));
        assertThatThrownBy(() -> BuyerHierarchy.validate(2, 9L, parent(2)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BuyerHierarchy.validate(2, null, null))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void levelThreeRequiresLevelTwoParentAndForbidsFour() {
        BuyerHierarchy.validate(3, 8L, parent(2));
        assertThatThrownBy(() -> BuyerHierarchy.validate(3, 8L, parent(1)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BuyerHierarchy.validate(4, 8L, parent(3)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void rejectsSelfParent() {
        assertThatThrownBy(() -> BuyerHierarchy.rejectCycle(10L, 10L, List.of()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void rejectsParentThatIsOwnDescendant() {
        // current=10, newParent=11, descendantsOf10 contains 11
        assertThatThrownBy(() -> BuyerHierarchy.rejectCycle(10L, 11L, List.of(11L)))
                .isInstanceOf(ServiceException.class);
        BuyerHierarchy.rejectCycle(10L, 12L, List.of(11L));
    }

    private static BuyerHierarchy.ParentRef parent(int level) {
        return new BuyerHierarchy.ParentRef(1L, level);
    }
}
```

- [ ] **Step 2: 失败后实现**

```java
public final class BuyerHierarchy {
    private BuyerHierarchy() {}

    public record ParentRef(Long id, int distributionLevel) {}
    public record Result(int level, Long parentBuyerId) {}

    public static Result validate(int distributionLevel, Long parentBuyerId, ParentRef parent) {
        if (distributionLevel < 1 || distributionLevel > 3) {
            throw exception(BUYER_PARENT_INVALID);
        }
        if (distributionLevel == 1) {
            if (parentBuyerId != null) {
                throw exception(BUYER_PARENT_INVALID);
            }
            return new Result(1, null);
        }
        if (parentBuyerId == null || parent == null) {
            throw exception(BUYER_PARENT_INVALID);
        }
        if (distributionLevel == 2 && parent.distributionLevel() != 1) {
            throw exception(BUYER_PARENT_INVALID);
        }
        if (distributionLevel == 3 && parent.distributionLevel() != 2) {
            throw exception(BUYER_PARENT_INVALID);
        }
        return new Result(distributionLevel, parentBuyerId);
    }

    public static void rejectCycle(Long currentBuyerId, Long newParentId, Collection<Long> descendantIds) {
        if (newParentId == null) {
            return;
        }
        if (newParentId.equals(currentBuyerId) || descendantIds.contains(newParentId)) {
            throw exception(BUYER_PARENT_INVALID);
        }
    }

    /** 已有子节点时，新等级必须仍能当这些子节点的合法上级。 */
    public static void rejectLevelConflictWithChildren(int newLevel, List<Integer> childLevels) {
        for (int child : childLevels) {
            if (child == newLevel + 1) {
                continue;
            }
            throw exception(BUYER_PARENT_INVALID);
        }
    }
}
```

无子节点时 `childLevels` 空，直接通过。有二级子则父必须保持一级（`child==2` 要求 `newLevel==1`）。

- [ ] **Step 3: 跑测通过并 Commit**（`feat(supply-chain): 校验分销商组织树等级与成环`）

---

### Task 2: 档案命令 — 创建/编辑/状态/期初

**Files:**
- Create: `BuyerAdminCommandService.java`
- Create: `src/test/java/.../buyer/application/BuyerAdminCommandServiceTest.java`
- Modify: 联系人/文件/合作模式/对接/类目 Mapper（foundation 已建表）

- [ ] **Step 1: 单测（Mockito）**

```java
@Test
void createGeneratesByrCodeAndOpensBuyerAccountNotSupplier() {
    when(businessNoGenerator.next(BusinessNoPrefixes.BYR)).thenReturn("BYR_TESTCODE000000000000001");
    when(buyerMapper.insert(any())).thenAnswer(inv -> {
        BuyerDO row = inv.getArgument(0);
        row.setId(5L);
        return 1;
    });
    service.create(command(0L));
    verify(accountOpeningService).open(any());
    verifyNoInteractions(supplierSettlementAccountMapper); // 不要注入该 mapper；测试用 verify 账户服务入参 buyerCode
}

@Test
void updateRejectsOpeningBalanceWhenAccountExists() {
    when(accountMapper.selectByBuyerId(5L)).thenReturn(existingAccount());
    assertThatThrownBy(() -> service.update(updateWithOpening(0L)))
            .extracting(ex -> ((ServiceException) ex).getCode())
            .isEqualTo(BUYER_OPENING_ALREADY_DONE.getCode());
}

@Test
void changeStatusUsesBuyerStatusMachine() {
    // ACTIVE → BLACKLIST ok；ARCHIVED → ACTIVE 抛 BUYER_STATUS_CONFLICT
}
```

`BuyerAccountOpeningService.open` 在账户已存在时幂等返回，**不能**靠它拦截编辑余额。编辑路径：若 `openingBalanceCents != null` 一律 `BUYER_OPENING_ALREADY_DONE`（创建后账户已存在）。

创建路径：`openingBalanceCents == null` 视为 0。插入 Buyer 后：

```java
byte[] digest = sha256("OPENING_BALANCE:" + buyer.getId() + ":CNY");
accountOpeningService.open(new OpeningBalanceCommand(
        buyer.getBuyerCode(), Money.cny(cents), operatorId,
        "ADMIN_OPENING:" + buyer.getId() + ":CNY",
        digest,
        "管理端分销商期初",
        traceId, buyer.getBuyerCode()));
```

`BuyerAccountOpeningService.validate` 要求 evidence 非空，以上满足。账本 `entry_key` 仍是服务内部的 `OPENING_BALANCE:{id}:CNY`，与采购商唯一账本既有幂等键一致。

`create` **不得** `BuyerTenantBindingMapper.insert`。`buyerType="SAAS_MALL"`。`shortName` = 名称（截断 64）。`distributionLevel` 默认 1。`status` 默认 ACTIVE。信用代码+公司名重复 → `BUYER_CREDIT_CODE_DUPLICATE`（捕获 DuplicateKey 或先查）。

联系人至少一行姓名+手机，否则 `SUPPLIER_CONTACT_REQUIRED`（规格未单列分销商联系人码，复用该码，文案已是「至少保留一行有效联系人」）。

类目 id 调 `CategoryChainValidator`：品类必须 level=1 ACTIVE；类型必须 level=2 且 parent 为已选品类之一。

日志：入口打 `buyerName/openingBalanceCents/operatorId`；敏感字段不打明文。

- [ ] **Step 2: 实现服务** `TRANSACTION_MANAGER = "supplyChainTransactionManager"`。

`changeStatus`：`BuyerStatus.valueOf` 失败 → `ENUM_NOT_OPEN`；`!canTransitionTo` → `BUYER_STATUS_CONFLICT`；找不到 → `BUYER_PROFILE_NOT_FOUND`。使用 `selectByBuyerCodeForUpdate`。

批量 PATCH 仅 `ACTIVE`/`SUSPENDED`。

更新树：加载父行、收集直接子 `distribution_level`、所有子孙 id（递归查询 `parent_buyer_id`，最多三层所以两次 select 即可），调用 `BuyerHierarchy`。

- [ ] **Step 3: 跑单测通过并 Commit**

---

### Task 3: 档案查询 + Controller REST

**Files:**
- Create: `BuyerAdminQueryService.java`
- Create: `BuyerAdminController.java`
- Create: `BuyerAdminRestContractTest.java`

- [ ] **Step 1: Contract**

```java
@Test
void mappingsAreNounsUnderBuyers() {
    assertThat(BuyerAdminController.class.getAnnotation(RequestMapping.class).value())
            .containsExactly("/supply-chain/buyers");
    // 无 create/page/get/invoice/status/batch 路径片段
}
```

检查每个 `@GetMapping/@PostMapping/@PutMapping/@PatchMapping/@DeleteMapping` 的 value。允许 `/{buyerCode}`、`/{buyerCode}/children`、`/{buyerCode}/products`、`/{buyerCode}/products/{buyerProductCode}`。禁止 `/projects` 子路径（项目列表用 query `buyerCode`）。

- [ ] **Step 2: Controller 方法**

| HTTP | 路径 | 方法 |
| --- | --- | --- |
| GET | `/supply-chain/buyers` | `page`；`view=simple` 返回 `{buyerCode,buyerName}[]`；`view=parent-options` 收 `excludeBuyerCode`,`forLevel` |
| GET | `/{buyerCode}` | 详情，含 `id`、脱敏、只读 `accountBalanceCents`、下级不嵌套 |
| POST | `/` | 返回 `buyerCode` |
| PUT | `/{buyerCode}` | 整档替换 |
| PATCH | `/{buyerCode}` | `{status}` |
| PATCH | `/` | `{buyerCodes,status}` 仅暂停/恢复 |
| GET | `/{buyerCode}/children` | 直接子列表 |

筛选：`buyerName,buyerCode,distributionLevels,cooperationModes,categoryL1Ids,status,updatedFrom,updatedTo`。排序 `updatedAt DESC`。

`parent-options`：返回可作为 `forLevel` 上级的 Buyer（forLevel=2 → 只要一级；forLevel=3 → 只要二级）；排除 `excludeBuyerCode` 及其子孙。

黑名单/暂停的 Buyer 详情仍可 GET；新项目归属校验在 project 计划。

权限 `@PreAuthorize("@ss.hasPermission('supply-chain:admin')")`。

- [ ] **Step 3: 跑 Contract + Command 单测，Commit**（`feat(supply-chain): 提供分销商管理端REST档案接口`）

---

### Task 4: 集成测试 — 期初与树

**Files:**
- Create: `src/test/java/com/aifuli/module/supplychain/tradecore/buyer/BuyerAdminIntegrationTest.java`

模式复制 `SupplierAdminProfileIntegrationTest`：Testcontainers + `@Import` 本计划服务、`BuyerAccountOpeningService`、`AccountLedgerWriter`、加密、`ProductCatalogCommandService`、`CategoryChainValidator`。

- [ ] **Step 1: 写测试（先红）**

```java
@Test
void createsOpeningOnceAndRejectsSecondBalanceAndParentRules() {
    String l1 = command.create(levelOne("一级", 0L));
    assertThat(l1).startsWith("BYR_");
    BuyerAccountDO account = accountMapper.selectByBuyerId(buyerMapper.selectByBuyerCode(l1).getId());
    assertThat(account.getBalance()).isZero();
    assertThatThrownBy(() -> command.update(updateWithOpening(l1, 0L)))
            .extracting(ex -> ((ServiceException) ex).getCode())
            .isEqualTo(BUYER_OPENING_ALREADY_DONE.getCode());

    assertThatThrownBy(() -> command.create(levelTwoWithoutParent()))
            .extracting(ex -> ((ServiceException) ex).getCode())
            .isEqualTo(BUYER_PARENT_INVALID.getCode());

    String l2 = command.create(levelTwo("二级", l1));
    assertThat(query.children(l1)).extracting(BuyerChildItem::buyerCode).containsExactly(l2);
    assertThatThrownBy(() -> command.update(changeLevelToTwoButKeepChild(l1)))
            .extracting(ex -> ((ServiceException) ex).getCode())
            .isEqualTo(BUYER_PARENT_INVALID.getCode());

    command.changeStatus(l1, BuyerStatus.BLACKLIST, 1L);
    command.changeStatus(l1, BuyerStatus.ACTIVE, 1L);
}
```

- [ ] **Step 2: 跑 failsafe 失败后补 `@Import` 直到绿**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am verify -Psupply-chain-integration \
  -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=BuyerAdminIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=true
```

- [ ] **Step 3: Commit**（`test(supply-chain): 覆盖分销商期初与组织树集成路径`）

---

### Task 5: 分销商品 + L2 覆盖

**Files:**
- Create: `BuyerProductCommandService.java` / `BuyerProductQueryService.java`
- Create: `BuyerProductCommandServiceTest.java`
- Controller 子资源挂在 `BuyerAdminController`

- [ ] **Step 1: 单测**

```java
@Test
void addDuplicateSkuFails() {
    // 已有 status=10 的同一 sku → BUYER_PRODUCT_DUPLICATE
}

@Test
void retiredSkuCanBeReaddedWithNewCode() {
    // status=90 的行：规格未写是否复用。锁定：移除后同一 (buyer,sku) 唯一键仍在。
    // 因此 DELETE 只改 status=90，再 POST 同一 SKU 必须复活该行（改回 10）而不是 insert。
    // 复活时生成新价格覆盖清空为 FOLLOW_TEMPLATE。
}

@Test
void customL2WritesOverrideAndFollowTemplateClears() {
    // applyL2 CUSTOM → insert override version, set current_version_id
    // FOLLOW_TEMPLATE → current_version_id = null
    // 模板表 current 不变
}

@Test
void equalL1UsesSettlementL1NotFaceValue() {
    // L1=800, faceValue=10000, EQUAL_L1 resolved=800
}
```

无 L1 时 `EQUAL_L1`/`CUSTOM` → `PLATFORM_SETTLEMENT_PRICE_MISSING`。`FOLLOW_TEMPLATE` 无 L1 仍允许清除覆盖。

`displayThresholdPercent` 随 PATCH 可存，查询原样返回；不实现 C 端隐藏。

列表金额：`SettlementPriceResolver.resolve(单项目L3或null, override, template, l1)`。

若该 BuyerProduct 被 **恰好一个** 未移除项目 listing 覆盖，则 `projectPriceCents` 取该 listing 当前版本单价，`projectNames` 单元素；多个项目有 L3 时 `projectPriceCents=null`，`projectPriceCovered=true`，`projectNames` 列出名称。`priceSource` 按 resolve。`pricingRule` 文案锁定：

- 有手工覆盖 EQUAL_L1 → `"等于L1"`
- 有手工覆盖 CUSTOM → `"自定义公式"`
- 否则有模板 L2 → `"模板"`
- 否则 → `"模板"`（无模板也展示该字，表示跟默认/L1，前端可接受；不要用 mock 的英文）

- [ ] **Step 2: Controller**

```java
@GetMapping("/{buyerCode}/products")
@PostMapping("/{buyerCode}/products")  // body { items: [ { platformSkuCode, l2AdjustMode, percent, amountCents, displayThresholdPercent } ] }
@PatchMapping("/{buyerCode}/products/{buyerProductCode}")
@DeleteMapping("/{buyerCode}/products/{buyerProductCode}")
```

POST 默认 `l2AdjustMode=FOLLOW_TEMPLATE`。新增即有效（status=10）。编号 `BPD_`。

DELETE：status=90；已发布 `sc_project_listing` **不回放删除**（版本行不动，listing 仍在直到项目侧 DELETE）。新项目不得再选：SKU 池 `buyerCode` 过滤只排除 status=10。

- [ ] **Step 3: 集成测试追加方法** `addsProductAndOverridesL2WithoutTouchingTemplateRow`：插入 L1 版本 → POST product → PATCH CUSTOM → 查 override 单价；再 PATCH FOLLOW_TEMPLATE → current_version_id 空。

- [ ] **Step 4: Commit**（`feat(supply-chain): 分销商品关联与手工L2覆盖`）

---

### Task 6: 架构

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=false \
  -Dtest=com.aifuli.module.supplychain.architecture.SupplyChainModuleArchitectureTest
```

追加规则（写在 `SupplyChainModuleArchitectureTest`）：

```java
static final ArchRule admin_controllers_do_not_depend_on_supplier_adapter = /* 已有，覆盖新 Controller */;

static final ArchRule catalog_pricing_does_not_write_ledger = noClasses()
        .that().resideInAPackage("..tradecore.catalog..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("..tradecore.ledger..", "..tradecore.account.dal..");
```

`BuyerProductCommandService` 不得依赖 ledger。`BuyerAdminCommandService` 可以依赖 `BuyerAccountOpeningService`（account.application），不要直接 Mapper 改余额。

- [ ] 跑 ArchUnit 绿后 Commit（`test(supply-chain): 禁止目录定价写入采购商账本`）
