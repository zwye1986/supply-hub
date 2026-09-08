# 管理端 REST 共享内核 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 本计划是四份中的第 1 份。完成后再执行 supplier / buyer / project。

**Goal:** 落地 09-02 规格所需的封闭枚举、错误码、Liquibase `021`、三级定价纯函数、类目树与标品 SKU 查询，并让现有供应商供货配置在去掉单值 `supply_mode` 后仍能写入。

**Architecture:** 共享枚举放 `tradecore.profile.domain`，避免 buyer 依赖 supply。定价解析是无 IO 纯函数。Liquibase 只追加 `021`，禁止改 `020`。供货配置子表与 `SupplierSupplyScopeService` 必须同一次提交，否则空库迁移后现有 IT 插入会失败。

**Tech Stack:** 见 [总览](./2026-09-02-admin-frontend-rest.md)。

---

## 文件结构

```
api: SupplyChainErrorCodeConstants.java, SupplyChainErrorMappingTest.java
server db: 021-admin-frontend-alignment.sql, db.changelog-1.0.0.yaml
server domain:
  tradecore/profile/domain/{ClosedEnums,ShippingSlaCode,SettlementMethod,IntegrationMode,
    CooperationMode,DistributorType,SupplyPreference,ProjectType,SettlementPartyType,
    L2AdjustMode,L3AdjustMode,PriceSource}.java
  tradecore/supply/domain/SupplyMode.java
  tradecore/buyer/domain/{BuyerStatus,BuyerProjectStatus}.java
server catalog:
  SettlementPriceResolver.java
  CategoryQueryService.java
  PlatformSkuQueryService.java
  CategoryChainValidator.java
  dal for new tables (DO + Mapper)
server supply:
  SupplierSupplyScopeDO.java (去掉 supplyMode)
  SupplierSupplyScopeModeDO.java + Mapper
  SupplierSupplyScopeService.java
  SupplierAdminController.java (ScopeLine 暂用 List.of(单值))
  SupplierProfileQueryService.java (SupplyScopeView.supplyModes)
interfaceapi/admin:
  CategoryAdminController.java
  PlatformSkuAdminController.java
tests: 各 *Test；Liquibase/Instore *IntegrationTest changeSetCount 20→21
docs: 集成测试.md 第 5 节
```

路径前缀一律：

- API：`aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/`
- Server 主代码：`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/`
- Server 测试：`.../src/test/java/com/aifuli/module/supplychain/`
- Liquibase：`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/`

下文路径写相对 `com/aifuli/module/supplychain/` 或 Liquibase 目录。

---

### Task 1: `SupplyMode` 五值

**Files:**
- Modify: `tradecore/supply/domain/SupplyMode.java`
- Create: `src/test/java/com/aifuli/module/supplychain/tradecore/supply/domain/SupplyModeTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.aifuli.module.supplychain.tradecore.supply.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplyModeTest {

    @Test
    void acceptsFiveClosedModesAndRejectsLegacyMockCodes() {
        assertThat(SupplyMode.fromCode("API_DIRECT")).isEqualTo(SupplyMode.API_DIRECT);
        assertThat(SupplyMode.fromCode("DROP_SHIP")).isEqualTo(SupplyMode.DROP_SHIP);
        assertThat(SupplyMode.fromCode("BATCH")).isEqualTo(SupplyMode.BATCH);
        assertThat(SupplyMode.fromCode("PICKUP")).isEqualTo(SupplyMode.PICKUP);
        assertThat(SupplyMode.fromCode("H5")).isEqualTo(SupplyMode.H5);
        assertThatThrownBy(() -> SupplyMode.fromCode("dropshipping"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupplyMode.fromCode("api"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 跑测确认失败**

Run:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
cd /Users/mac/Documents/codeSpace/code_bak/aifuli-mall-pro
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=false -Dtest=com.aifuli.module.supplychain.tradecore.supply.domain.SupplyModeTest
```

Expected: FAIL（`BATCH` 不存在或 `fromCode` 抛错）。

- [ ] **Step 3: 最小实现**

把 `SupplyMode` 改成：

```java
public enum SupplyMode {
    API_DIRECT("API直发"),
    DROP_SHIP("一件代发"),
    BATCH("批量发货"),
    PICKUP("门店自提"),
    H5("H5直发");
    // chineseName / fromCode 保持现有循环实现
}
```

- [ ] **Step 4: 跑测通过** — 同上命令，Expected: Tests run: 1, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/domain/SupplyMode.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/supply/domain/SupplyModeTest.java
git commit -m "$(cat <<'EOF'
feat(supply-chain): 扩展供货模式为五种封闭值

管理端供货配置与合作商品需要对齐前端五模式，不再只支持 API 直发与一件代发。
EOF
)"
```

---

### Task 2: 错误码增量与 `ENUM_NOT_OPEN` 别名

**Files:**
- Modify: `aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/error/SupplyChainErrorCodeConstants.java`
- Modify: `aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/error/SupplyChainErrorMappingTest.java`

- [ ] **Step 1: 写失败测试**

在 `SupplyChainErrorMappingTest` 的 `@CsvSource` 末尾追加（052 码不变，本任务同时改文案）：

```
"1050001057,DO_NOT_RETRY",
"1050001058,DO_NOT_RETRY",
"1050001059,DO_NOT_RETRY",
"1050001060,DO_NOT_RETRY",
"1050001061,DO_NOT_RETRY",
"1050001062,DO_NOT_RETRY",
"1050001063,DO_NOT_RETRY",
"1050001064,DO_NOT_RETRY",
"1050001065,DO_NOT_RETRY",
"1050001066,DO_NOT_RETRY",
"1050001067,DO_NOT_RETRY",
"1050001068,DO_NOT_RETRY",
"1050001069,DO_NOT_RETRY",
"1050001070,DO_NOT_RETRY",
"1050001071,DO_NOT_RETRY",
"1050001072,DO_NOT_RETRY",
"1050001073,DO_NOT_RETRY",
"1050001074,DO_NOT_RETRY",
"1050001075,DO_NOT_RETRY"
```

并新增：

```java
@Test
void enumNotOpenAliasesSupplierEnumNotOpen() {
    assertThat(SupplyChainErrorCodeConstants.ENUM_NOT_OPEN)
            .isSameAs(SupplyChainErrorCodeConstants.SUPPLIER_ENUM_NOT_OPEN);
    assertThat(SupplyChainErrorCodeConstants.ENUM_NOT_OPEN.getCode()).isEqualTo(1_050_001_052);
    assertThat(SupplyChainErrorCodeConstants.ENUM_NOT_OPEN.getMsg()).isEqualTo("未知枚举值");
}
```

- [ ] **Step 2: 跑测确认失败**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api -am test -DskipTests=false -Dtest=com.aifuli.module.supplychain.error.SupplyChainErrorMappingTest
```

Expected: FAIL（缺常量或 `RETRY_DIRECTIVES` 未覆盖；`everyDeclaredErrorCodeHasRetryDirective` 也会红）。

- [ ] **Step 3: 实现**

1. 把 `SUPPLIER_ENUM_NOT_OPEN` 文案改为 `"未知枚举值"`。
2. 紧挨着声明 `ErrorCode ENUM_NOT_OPEN = SUPPLIER_ENUM_NOT_OPEN;`（必须同一实例，不能再 `error(052, ...)`）。
3. 在 `SUPPLIER_OFFER_OUT_OF_SCOPE` 后追加：

```java
ErrorCode CATEGORY_CHAIN_INVALID = error(1_050_001_057, "品类/类型/分类不是启用类目的祖先链");
ErrorCode BUYER_PARENT_INVALID = error(1_050_001_058, "上级/等级不合法或成环");
ErrorCode BUYER_PRODUCT_DUPLICATE = error(1_050_001_059, "分销商品 SKU 已存在");
ErrorCode L2_OVERRIDE_INVALID = error(1_050_001_060, "L2 覆盖公式或金额不合法");
ErrorCode L3_OVERRIDE_INVALID = error(1_050_001_061, "L3 覆盖不合法");
ErrorCode PROJECT_BUYER_IMMUTABLE = error(1_050_001_062, "非草稿项目不可改分销商");
ErrorCode PROJECT_PLATFORM_SETTLEMENT_NOT_ENABLED = error(1_050_001_063, "平台结算未开放");
ErrorCode PROJECT_STATUS_INVALID = error(1_050_001_064, "非法项目状态迁移");
ErrorCode PROJECT_SKU_NOT_IN_BUYER_CATALOG = error(1_050_001_065, "项目 SKU 不是该分销商的分销商品");
ErrorCode SUPPLY_SCOPE_MODE_INVALID = error(1_050_001_066, "供货配置模式集合为空或含未知值");
ErrorCode BUYER_OPENING_ALREADY_DONE = error(1_050_001_067, "分销商期初已入账，禁止再改余额字段");
ErrorCode BUYER_PROFILE_NOT_FOUND = error(1_050_001_068, "分销商不存在");
ErrorCode PROJECT_NOT_FOUND = error(1_050_001_069, "项目不存在");
ErrorCode BUYER_PRODUCT_NOT_FOUND = error(1_050_001_070, "分销商品不存在");
ErrorCode PROJECT_LISTING_NOT_FOUND = error(1_050_001_071, "项目商品不存在");
ErrorCode BUYER_STATUS_CONFLICT = error(1_050_001_072, "分销商状态不允许该操作");
ErrorCode PLATFORM_SETTLEMENT_PRICE_MISSING = error(1_050_001_073, "标准商品缺少平台默认定价");
ErrorCode BUYER_CREDIT_CODE_DUPLICATE = error(1_050_001_074, "公司名称+统一社会信用代码联合重复");
ErrorCode PROJECT_PERIOD_INVALID = error(1_050_001_075, "项目结束日早于开始日");
```

4. `RETRY_DIRECTIVES` 为上述每一项添加 `entry(..., RetryDirective.DO_NOT_RETRY)`。`ENUM_NOT_OPEN` 不要重复 entry（与 052 同码）。

- [ ] **Step 4: 跑测通过** — 同 Step 2，Expected: PASS

- [ ] **Step 5: Commit**（`fix(supply-chain): 收敛未知枚举错误码并追加管理端目录`）

---

### Task 3: `BuyerStatus` 黑名单

**Files:**
- Modify: `tradecore/buyer/domain/BuyerStatus.java`
- Create: `src/test/java/com/aifuli/module/supplychain/tradecore/buyer/domain/BuyerStatusTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.aifuli.module.supplychain.tradecore.buyer.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuyerStatusTest {

    @Test
    void blacklistIsReversibleAndCannotArchive() {
        assertThat(BuyerStatus.fromCode((short) 30)).isEqualTo(BuyerStatus.BLACKLIST);
        assertThat(BuyerStatus.ACTIVE.canTransitionTo(BuyerStatus.BLACKLIST)).isTrue();
        assertThat(BuyerStatus.SUSPENDED.canTransitionTo(BuyerStatus.BLACKLIST)).isTrue();
        assertThat(BuyerStatus.BLACKLIST.canTransitionTo(BuyerStatus.ACTIVE)).isTrue();
        assertThat(BuyerStatus.BLACKLIST.canTransitionTo(BuyerStatus.SUSPENDED)).isTrue();
        assertThat(BuyerStatus.BLACKLIST.canTransitionTo(BuyerStatus.ARCHIVED)).isFalse();
        assertThat(BuyerStatus.ACTIVE.canTransitionTo(BuyerStatus.ARCHIVED)).isTrue();
        assertThat(BuyerStatus.ARCHIVED.canTransitionTo(BuyerStatus.ACTIVE)).isFalse();
    }
}
```

- [ ] **Step 2: 跑测确认失败** — `-Dtest=com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatusTest`

- [ ] **Step 3: 实现** — 增加 `BLACKLIST((short) 30, "黑名单")`。`canTransitionTo` 与 `SupplierStatus` 同构：`ARCHIVED` 不可逆；禁止黑名单→归档；启用/暂停↔黑名单；启用↔暂停；启用/暂停→归档。

- [ ] **Step 4: 跑测通过**

- [ ] **Step 5: Commit**（`feat(supply-chain): 分销商合作状态增加可逆黑名单`）

---

### Task 4: `BuyerProjectStatus.CLOSED`

**Files:**
- Modify: `tradecore/buyer/domain/BuyerProjectStatus.java`
- Modify: `src/test/java/com/aifuli/module/supplychain/tradecore/buyer/domain/BuyerProjectStatusTest.java`

- [ ] **Step 1: 扩展现有测试**（保留原断言，追加 CLOSED）

```java
@Test
void closedIsRecoverableAndDraftCannotClose() {
    assertThat(BuyerProjectStatus.fromCode((short) 30)).isEqualTo(BuyerProjectStatus.CLOSED);
    assertThat(BuyerProjectStatus.ACTIVE.canTransitionTo(BuyerProjectStatus.CLOSED)).isTrue();
    assertThat(BuyerProjectStatus.SUSPENDED.canTransitionTo(BuyerProjectStatus.CLOSED)).isTrue();
    assertThat(BuyerProjectStatus.CLOSED.canTransitionTo(BuyerProjectStatus.ACTIVE)).isTrue();
    assertThat(BuyerProjectStatus.CLOSED.canTransitionTo(BuyerProjectStatus.SUSPENDED)).isTrue();
    assertThat(BuyerProjectStatus.CLOSED.canTransitionTo(BuyerProjectStatus.ARCHIVED)).isFalse();
    assertThat(BuyerProjectStatus.DRAFT.canTransitionTo(BuyerProjectStatus.CLOSED)).isFalse();
    assertThat(BuyerProjectStatus.DRAFT.canTransitionTo(BuyerProjectStatus.ACTIVE)).isTrue();
    assertThat(BuyerProjectStatus.DRAFT.canTransitionTo(BuyerProjectStatus.ARCHIVED)).isTrue();
}
```

- [ ] **Step 2: 跑测确认失败** — `-Dtest=com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatusTest`

- [ ] **Step 3: 实现**

```java
public enum BuyerProjectStatus {
    DRAFT((short) 0),
    ACTIVE((short) 10),
    SUSPENDED((short) 20),
    CLOSED((short) 30),
    ARCHIVED((short) 90);
    // fromCode 不变

    public boolean canTransitionTo(BuyerProjectStatus target) {
        if (target == null || this == target || this == ARCHIVED) {
            return false;
        }
        return switch (this) {
            case DRAFT -> target == ACTIVE || target == ARCHIVED;
            case ACTIVE -> target == SUSPENDED || target == CLOSED;
            case SUSPENDED -> target == ACTIVE || target == CLOSED || target == ARCHIVED;
            case CLOSED -> target == ACTIVE || target == SUSPENDED;
            case ARCHIVED -> false;
        };
    }
}
```

`BuyerProjectAccessResolver` 已只放行 `ACTIVE`，CLOSED 无需改生产代码；现有 resolver 单测保持。

- [ ] **Step 4: 跑测通过**

- [ ] **Step 5: Commit**（`feat(supply-chain): 项目增加可恢复的已结束状态`）

---

### Task 5: 共享封闭枚举与 `ClosedEnums`

**Files:**
- Create: `tradecore/profile/domain/` 下本任务列出的全部枚举 + `ClosedEnums.java`
- Create: `src/test/java/com/aifuli/module/supplychain/tradecore/profile/domain/ClosedEnumsTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.aifuli.module.supplychain.tradecore.profile.domain;

import com.aifuli.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClosedEnumsTest {

    @Test
    void blankIsNullAndUnknownThrowsEnumNotOpen() {
        assertThat(ClosedEnums.parse(ShippingSlaCode.class, null)).isNull();
        assertThat(ClosedEnums.parse(ShippingSlaCode.class, "  ")).isNull();
        assertThat(ClosedEnums.parse(ShippingSlaCode.class, "NEXT_DAY"))
                .isEqualTo(ShippingSlaCode.NEXT_DAY);
        assertThatThrownBy(() -> ClosedEnums.parse(ShippingSlaCode.class, "same-day"))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ENUM_NOT_OPEN.getCode());
        assertThat(ClosedEnums.parseRequired(L2AdjustMode.class, "EQUAL_L1"))
                .isEqualTo(L2AdjustMode.EQUAL_L1);
        assertThatThrownBy(() -> ClosedEnums.parseRequired(L2AdjustMode.class, null))
                .isInstanceOf(ServiceException.class);
    }
}
```

- [ ] **Step 2: 跑测确认失败**

- [ ] **Step 3: 实现**（每个枚举仅 `enum` 常量，无库值）

| 类型 | 常量 |
| --- | --- |
| `ShippingSlaCode` | `SAME_DAY`,`NEXT_DAY`,`HOURS_48`,`HOURS_72`,`CUSTOM` |
| `SettlementMethod` | `DAILY`,`WEEKLY`,`MONTHLY`,`QUARTERLY`,`PREPAID` |
| `IntegrationMode` | `API`,`H5`,`MANUAL` |
| `CooperationMode` | `DIRECT`,`AGENT`,`CHANNEL` |
| `DistributorType` | `ENTERPRISE`,`PERSONAL`,`PLATFORM` |
| `SupplyPreference` | `STABLE`,`PRICE`,`COMPREHENSIVE` |
| `ProjectType` | `WELFARE`,`BENEFIT`,`CAMPAIGN`,`SUBSIDY`,`GROUP` |
| `SettlementPartyType` | `DISTRIBUTOR`,`PLATFORM` |
| `L2AdjustMode` | `FOLLOW_TEMPLATE`,`EQUAL_L1`,`CUSTOM` |
| `L3AdjustMode` | `FOLLOW_L2`,`EQUAL_L1`,`CUSTOM` |
| `PriceSource` | `L1`,`L2`,`L3` |

`ClosedEnums.java`：

```java
package com.aifuli.module.supplychain.tradecore.profile.domain;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;

public final class ClosedEnums {
    private ClosedEnums() {
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.trim());
        } catch (IllegalArgumentException ex) {
            throw exception(ENUM_NOT_OPEN);
        }
    }

    public static <E extends Enum<E>> E parseRequired(Class<E> type, String name) {
        E value = parse(type, name);
        if (value == null) {
            throw exception(ENUM_NOT_OPEN);
        }
        return value;
    }
}
```

中文注释：职责是把 REST 字符串变成封闭枚举；未知值统一 `ENUM_NOT_OPEN`。设计见 `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#42-其它封闭枚举`。

- [ ] **Step 4: 跑测通过**

- [ ] **Step 5: Commit**

---

### Task 6: 定价解析纯函数

**Files:**
- Create: `tradecore/catalog/application/SettlementPriceResolver.java`
- Create: `src/test/java/com/aifuli/module/supplychain/tradecore/catalog/application/SettlementPriceResolverTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.profile.domain.L2AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.L3AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import org.junit.jupiter.api.Test;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.L2_OVERRIDE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SETTLEMENT_PRICE_MISSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementPriceResolverTest {

    private final SettlementPriceResolver resolver = new SettlementPriceResolver();

    @Test
    void prefersL3ThenManualL2ThenTemplateL2ThenL1() {
        assertThat(resolver.resolve(30L, 20L, 15L, 10L))
                .isEqualTo(new SettlementPriceResolver.ResolvedPrice(30L, PriceSource.L3));
        assertThat(resolver.resolve(null, 20L, 15L, 10L))
                .isEqualTo(new SettlementPriceResolver.ResolvedPrice(20L, PriceSource.L2));
        assertThat(resolver.resolve(null, null, 15L, 10L))
                .isEqualTo(new SettlementPriceResolver.ResolvedPrice(15L, PriceSource.L2));
        assertThat(resolver.resolve(null, null, null, 10L))
                .isEqualTo(new SettlementPriceResolver.ResolvedPrice(10L, PriceSource.L1));
        assertThatThrownBy(() -> resolver.resolve(null, null, null, null))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PLATFORM_SETTLEMENT_PRICE_MISSING.getCode());
    }

    @Test
    void equalL1UsesBSettlementCentsAndCustomRounds() {
        assertThat(resolver.applyL2(L2AdjustMode.EQUAL_L1, 999L, null, null)).isEqualTo(999L);
        assertThat(resolver.applyL2(L2AdjustMode.CUSTOM, 1000L, 80, 50L)).isEqualTo(850L);
        assertThat(resolver.applyL2(L2AdjustMode.CUSTOM, 101L, 50, 0L)).isEqualTo(51L);
        assertThatThrownBy(() -> resolver.applyL2(L2AdjustMode.CUSTOM, 100L, 0, 0L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(L2_OVERRIDE_INVALID.getCode());
        assertThat(resolver.applyL3(L3AdjustMode.FOLLOW_L2, 100L, null, null)).isNull();
        assertThat(resolver.applyL3(L3AdjustMode.EQUAL_L1, 777L, null, null)).isEqualTo(777L);
    }

    @Test
    void followTemplateClearsOverride() {
        assertThat(resolver.applyL2(L2AdjustMode.FOLLOW_TEMPLATE, 1000L, 120, 1L)).isNull();
    }
}
```

- [ ] **Step 2: 跑测确认失败**

- [ ] **Step 3: 实现** `SettlementPriceResolver`（无 Spring 依赖，无 Mapper）

```java
package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.profile.domain.L2AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.L3AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import org.springframework.stereotype.Component;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.L2_OVERRIDE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.L3_OVERRIDE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SETTLEMENT_PRICE_MISSING;

/**
 * 管理端有效 B 端结算价解析。L3 &gt; 手工 L2 &gt; 模板 L2 &gt; L1。
 * 设计见 docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#8-定价解析本批管理端
 */
@Component
public class SettlementPriceResolver {

    public record ResolvedPrice(long unitPriceCents, PriceSource source) {
    }

    public ResolvedPrice resolve(Long l3Cents, Long l2OverrideCents, Long l2TemplateCents, Long l1Cents) {
        if (l3Cents != null) {
            return new ResolvedPrice(l3Cents, PriceSource.L3);
        }
        if (l2OverrideCents != null) {
            return new ResolvedPrice(l2OverrideCents, PriceSource.L2);
        }
        if (l2TemplateCents != null) {
            return new ResolvedPrice(l2TemplateCents, PriceSource.L2);
        }
        if (l1Cents != null) {
            return new ResolvedPrice(l1Cents, PriceSource.L1);
        }
        throw exception(PLATFORM_SETTLEMENT_PRICE_MISSING);
    }

    /** FOLLOW_TEMPLATE 返回 null 表示清除覆盖。EQUAL_L1/CUSTOM 结果必须 &gt;0。 */
    public Long applyL2(L2AdjustMode mode, long l1Cents, Integer percent, Long amountCents) {
        return switch (mode) {
            case FOLLOW_TEMPLATE -> null;
            case EQUAL_L1 -> requirePositive(l1Cents, true);
            case CUSTOM -> requirePositive(custom(l1Cents, percent, amountCents, true), true);
        };
    }

    public Long applyL3(L3AdjustMode mode, long l1Cents, Integer percent, Long amountCents) {
        return switch (mode) {
            case FOLLOW_L2 -> null;
            case EQUAL_L1 -> requirePositive(l1Cents, false);
            case CUSTOM -> requirePositive(custom(l1Cents, percent, amountCents, false), false);
        };
    }

    private static long custom(long l1Cents, Integer percent, Long amountCents, boolean l2) {
        if (percent == null || percent < 1) {
            throw exception(l2 ? L2_OVERRIDE_INVALID : L3_OVERRIDE_INVALID);
        }
        long add = amountCents == null ? 0L : amountCents;
        return Math.round(l1Cents * (percent / 100.0d)) + add;
    }

    private static long requirePositive(long cents, boolean l2) {
        if (cents <= 0) {
            throw exception(l2 ? L2_OVERRIDE_INVALID : L3_OVERRIDE_INVALID);
        }
        return cents;
    }
}
```

`CUSTOM` 的 percent 非法时 L3 也走 `L2_OVERRIDE_INVALID` 不合适。把 `custom` 拆成带 error 参数，L3 路径抛 `L3_OVERRIDE_INVALID`。

- [ ] **Step 4: 跑测通过**

- [ ] **Step 5: Commit**

---

### Task 7: Liquibase `021` 与 changeset 计数

**Files:**
- Create: `021-admin-frontend-alignment.sql`（完整见下）
- Modify: `db.changelog-1.0.0.yaml` 在 `020-supplier-admin-profile` 后追加 `021-admin-frontend-alignment`
- Modify: `SupplyChainLiquibaseIntegrationTest`：`changeSetCount` `20L`→`21L`；`tableNames` 增加本 SQL 新建表；`columnNames` 增加 `sc_buyer.parent_buyer_id`、`sc_buyer_project.status` 仍存在即可
- Modify: `InstoreProjectCashierRouteMigrationIntegrationTest`、`InstoreMemberEntryRouteMigrationIntegrationTest`：`20L`→`21L`

- [ ] **Step 1: 先改测试断言为 21**，跑 `SupplyChainLiquibaseIntegrationTest` 确认失败（仍是 20）。

- [ ] **Step 2: 写入 `021-admin-frontend-alignment.sql`**

完整 SQL（注释必须与 DTO 语义一致）：

```sql
-- 管理端 REST 对齐：五模式、分销商树、项目 CLOSED、BuyerProduct/L2/L3、L1 价格版本
-- 设计见 docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md

ALTER TABLE sc_supplier
    ADD COLUMN shipping_sla_code VARCHAR(16) NULL COMMENT '发货时效封闭枚举' AFTER aftersales_supported,
    ADD COLUMN shipping_sla_custom VARCHAR(64) NULL COMMENT '发货时效自定义说明，仅 CUSTOM 使用' AFTER shipping_sla_code,
    ADD COLUMN settlement_method VARCHAR(16) NULL COMMENT '结算方式封闭枚举' AFTER shipping_sla_custom,
    ADD CONSTRAINT ck_sc_supplier_shipping_sla CHECK (
        shipping_sla_code IS NULL OR shipping_sla_code IN ('SAME_DAY','NEXT_DAY','HOURS_48','HOURS_72','CUSTOM')),
    ADD CONSTRAINT ck_sc_supplier_settlement_method CHECK (
        settlement_method IS NULL OR settlement_method IN ('DAILY','WEEKLY','MONTHLY','QUARTERLY','PREPAID'));

CREATE TABLE sc_supplier_integration_mode (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商对接方式行主键',
    supplier_id BIGINT NOT NULL COMMENT '供应商内部主键，逻辑引用sc_supplier.id',
    integration_mode VARCHAR(16) NOT NULL COMMENT '对接方式：API、H5、MANUAL',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_integration_mode (supplier_id, integration_mode),
    CONSTRAINT ck_sc_supplier_integration_mode CHECK (integration_mode IN ('API','H5','MANUAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商对接方式多选';

CREATE TABLE sc_supplier_file_ref (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商证件文件行主键',
    supplier_id BIGINT NOT NULL COMMENT '供应商内部主键，逻辑引用sc_supplier.id',
    file_role VARCHAR(16) NOT NULL COMMENT 'LICENSE执照 QUALIFICATION资质',
    sort_no INT NOT NULL COMMENT '同一角色内排序，从0开始',
    file_cipher VARBINARY(512) NOT NULL COMMENT '文件fileKey密文',
    key_version VARCHAR(32) NOT NULL COMMENT '信封密钥版本',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_file_role_sort (supplier_id, file_role, sort_no),
    CONSTRAINT ck_sc_supplier_file_role CHECK (file_role IN ('LICENSE','QUALIFICATION')),
    CONSTRAINT ck_sc_supplier_file_sort CHECK (sort_no >= 0 AND sort_no <= 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商执照与资质文件引用';

CREATE TABLE sc_supplier_supply_scope_mode (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供货配置模式行主键',
    supply_scope_id BIGINT NOT NULL COMMENT '供货配置主键，逻辑引用sc_supplier_supply_scope.id',
    supply_mode VARCHAR(32) NOT NULL COMMENT '供货模式',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_scope_mode (supply_scope_id, supply_mode),
    KEY idx_sc_scope_mode_scope (supply_scope_id),
    CONSTRAINT ck_sc_scope_mode_value CHECK (
        supply_mode IN ('API_DIRECT','DROP_SHIP','BATCH','PICKUP','H5'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供货配置一行上的多种供货模式';

INSERT INTO sc_supplier_supply_scope_mode (supply_scope_id, supply_mode, created_at)
SELECT id, supply_mode, created_at FROM sc_supplier_supply_scope;

ALTER TABLE sc_supplier_supply_scope DROP CHECK ck_sc_supplier_scope_mode;
ALTER TABLE sc_supplier_supply_scope DROP COLUMN supply_mode;

ALTER TABLE sc_supplier_offer DROP CHECK ck_sc_offer_supply_mode;
ALTER TABLE sc_supplier_offer
    ADD CONSTRAINT ck_sc_offer_supply_mode CHECK (
        offer_supply_mode IS NULL OR offer_supply_mode IN (
            'API_DIRECT','DROP_SHIP','BATCH','PICKUP','H5'));

ALTER TABLE sc_buyer DROP CHECK ck_sc_buyer_status;
ALTER TABLE sc_buyer
    ADD COLUMN parent_buyer_id BIGINT NULL COMMENT '上级分销商主键，逻辑引用sc_buyer.id，一级为空' AFTER status,
    ADD COLUMN distribution_level SMALLINT NOT NULL DEFAULT 1 COMMENT '组织等级：1一级 2二级 3三级' AFTER parent_buyer_id,
    ADD COLUMN distributor_type VARCHAR(16) NULL COMMENT '分销商类型：ENTERPRISE企业 PERSONAL个人 PLATFORM平台' AFTER distribution_level,
    ADD COLUMN supply_preference VARCHAR(16) NULL COMMENT '供货择优倾向，不改变路由引擎' AFTER distributor_type,
    ADD COLUMN settlement_method VARCHAR(16) NULL COMMENT '结算方式封闭枚举' AFTER supply_preference,
    ADD COLUMN settlement_cycle_days INT NULL COMMENT '结算周期天数' AFTER settlement_method,
    ADD COLUMN cooperation_started_at DATETIME(3) NULL COMMENT '合作开始时间，UTC' AFTER settlement_cycle_days,
    ADD COLUMN cooperation_note VARCHAR(200) NULL COMMENT '合作说明' AFTER cooperation_started_at,
    ADD COLUMN legal_entity_name VARCHAR(256) NULL COMMENT '公司名称' AFTER cooperation_note,
    ADD COLUMN credit_code VARCHAR(32) NULL COMMENT '统一社会信用代码' AFTER legal_entity_name,
    ADD COLUMN legal_person_name VARCHAR(64) NULL COMMENT '法人姓名' AFTER credit_code,
    ADD COLUMN legal_person_mobile_cipher VARBINARY(256) NULL COMMENT '法人手机号密文' AFTER legal_person_name,
    ADD COLUMN contact_address VARCHAR(512) NULL COMMENT '联系地址' AFTER legal_person_mobile_cipher,
    ADD COLUMN bank_name VARCHAR(128) NULL COMMENT '开户行' AFTER contact_address,
    ADD COLUMN bank_account_cipher VARBINARY(512) NULL COMMENT '开户账号密文' AFTER bank_name,
    ADD COLUMN bank_account_key_version VARCHAR(32) NULL COMMENT '开户账号信封密钥版本' AFTER bank_account_cipher,
    ADD COLUMN document_key_version VARCHAR(32) NULL COMMENT '证件信封密钥版本' AFTER bank_account_key_version,
    ADD COLUMN id_front_cipher VARBINARY(512) NULL COMMENT '法人身份证正面fileKey密文' AFTER document_key_version,
    ADD COLUMN id_back_cipher VARBINARY(512) NULL COMMENT '法人身份证反面fileKey密文' AFTER id_front_cipher,
    ADD CONSTRAINT ck_sc_buyer_status CHECK (status IN (10, 20, 30, 90)),
    ADD CONSTRAINT ck_sc_buyer_level CHECK (distribution_level IN (1, 2, 3)),
    ADD CONSTRAINT ck_sc_buyer_distributor_type CHECK (
        distributor_type IS NULL OR distributor_type IN ('ENTERPRISE','PERSONAL','PLATFORM')),
    ADD CONSTRAINT ck_sc_buyer_preference CHECK (
        supply_preference IS NULL OR supply_preference IN ('STABLE','PRICE','COMPREHENSIVE')),
    ADD CONSTRAINT ck_sc_buyer_settlement_method CHECK (
        settlement_method IS NULL OR settlement_method IN ('DAILY','WEEKLY','MONTHLY','QUARTERLY','PREPAID')),
    ADD CONSTRAINT ck_sc_buyer_settlement_cycle CHECK (
        settlement_cycle_days IS NULL OR settlement_cycle_days >= 1),
    ADD UNIQUE KEY uk_sc_buyer_legal (legal_entity_name, credit_code);

CREATE TABLE sc_buyer_contact (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商联系人主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    sort_no INT NOT NULL COMMENT '排序，0为主联系人',
    contact_name VARCHAR(64) NOT NULL COMMENT '联系人姓名',
    contact_mobile VARCHAR(32) NOT NULL COMMENT '联系人手机号',
    contact_email VARCHAR(128) NULL COMMENT '联系人邮箱',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_contact_sort (buyer_id, sort_no),
    CONSTRAINT ck_sc_buyer_contact_sort CHECK (sort_no >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商运营联系人';

CREATE TABLE sc_buyer_integration_mode (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商对接方式行主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    integration_mode VARCHAR(16) NOT NULL COMMENT '对接方式',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_integration_mode (buyer_id, integration_mode),
    CONSTRAINT ck_sc_buyer_integration_mode CHECK (integration_mode IN ('API','H5','MANUAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商对接方式多选';

CREATE TABLE sc_buyer_cooperation_mode (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商合作模式行主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    cooperation_mode VARCHAR(16) NOT NULL COMMENT '合作模式',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_cooperation_mode (buyer_id, cooperation_mode),
    CONSTRAINT ck_sc_buyer_cooperation_mode CHECK (cooperation_mode IN ('DIRECT','AGENT','CHANNEL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商合作模式多选';

CREATE TABLE sc_buyer_category_scope (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商可售类目行主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    category_id BIGINT NOT NULL COMMENT '类目主键，逻辑引用sc_product_category.id',
    category_level SMALLINT NOT NULL COMMENT '1品类 2类型',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_category_scope (buyer_id, category_id),
    CONSTRAINT ck_sc_buyer_category_level CHECK (category_level IN (1, 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商可售品类与商品类型';

CREATE TABLE sc_buyer_file_ref (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商证件文件行主键',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    file_role VARCHAR(16) NOT NULL COMMENT 'LICENSE执照 QUALIFICATION资质',
    sort_no INT NOT NULL COMMENT '同一角色内排序',
    file_cipher VARBINARY(512) NOT NULL COMMENT '文件fileKey密文',
    key_version VARCHAR(32) NOT NULL COMMENT '信封密钥版本',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_file_role_sort (buyer_id, file_role, sort_no),
    CONSTRAINT ck_sc_buyer_file_role CHECK (file_role IN ('LICENSE','QUALIFICATION')),
    CONSTRAINT ck_sc_buyer_file_sort CHECK (sort_no >= 0 AND sort_no <= 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商执照与资质文件引用';

ALTER TABLE sc_buyer_project DROP CHECK ck_sc_buyer_project_status;
ALTER TABLE sc_buyer_project
    ADD COLUMN project_type VARCHAR(16) NULL COMMENT '项目类型封闭枚举' AFTER catalog_mode,
    ADD COLUMN start_date DATE NULL COMMENT '项目开始日' AFTER project_type,
    ADD COLUMN end_date DATE NULL COMMENT '项目结束日' AFTER start_date,
    ADD COLUMN settlement_party_type VARCHAR(16) NOT NULL DEFAULT 'DISTRIBUTOR' COMMENT '结算方，本期仅DISTRIBUTOR' AFTER end_date,
    ADD COLUMN description VARCHAR(500) NULL COMMENT '项目说明' AFTER settlement_party_type,
    ADD CONSTRAINT ck_sc_buyer_project_status CHECK (status IN (0, 10, 20, 30, 90)),
    ADD CONSTRAINT ck_sc_buyer_project_type CHECK (
        project_type IS NULL OR project_type IN ('WELFARE','BENEFIT','CAMPAIGN','SUBSIDY','GROUP')),
    ADD CONSTRAINT ck_sc_buyer_project_settlement_party CHECK (
        settlement_party_type IN ('DISTRIBUTOR')),
    ADD CONSTRAINT ck_sc_buyer_project_period CHECK (
        end_date IS NULL OR start_date IS NULL OR end_date >= start_date);

CREATE TABLE sc_platform_settlement_price (
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU主键，逻辑引用sc_platform_sku.id',
    current_version_id BIGINT NULL COMMENT '当前L1版本主键',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (platform_sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台SKU默认结算价身份';

CREATE TABLE sc_platform_settlement_price_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'L1版本主键',
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU主键',
    version_no INT NOT NULL COMMENT '从1递增',
    unit_price_cents BIGINT NOT NULL COMMENT 'L1 B端结算单价，CNY分，允许0',
    currency CHAR(3) NOT NULL COMMENT '币种，一期CNY',
    published_by BIGINT NULL COMMENT '发布人',
    published_at DATETIME(3) NOT NULL COMMENT '发布时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_l1_version (platform_sku_id, version_no),
    CONSTRAINT ck_sc_l1_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_l1_price CHECK (unit_price_cents >= 0),
    CONSTRAINT ck_sc_l1_version_no CHECK (version_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台默认定价不可变版本';

CREATE TABLE sc_buyer_product (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分销商品身份主键',
    buyer_product_code VARCHAR(32) NOT NULL COMMENT '分销商品稳定业务编码',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    platform_sku_id BIGINT NOT NULL COMMENT '平台SKU主键',
    display_threshold_percent INT NULL COMMENT '利润空间低于该百分比时C端隐藏，本批不消费',
    status SMALLINT NOT NULL COMMENT '10有效 90已移除',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_product_code (buyer_product_code),
    UNIQUE KEY uk_sc_buyer_product_sku (buyer_id, platform_sku_id),
    KEY idx_sc_buyer_product_buyer (buyer_id, status),
    CONSTRAINT ck_sc_buyer_product_status CHECK (status IN (10, 90)),
    CONSTRAINT ck_sc_buyer_product_threshold CHECK (
        display_threshold_percent IS NULL OR display_threshold_percent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商对平台SKU的分销商品身份';

CREATE TABLE sc_buyer_product_settlement_price (
    buyer_product_id BIGINT NOT NULL COMMENT '分销商品主键',
    current_version_id BIGINT NULL COMMENT '当前模板L2版本，空表示尚无模板',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (buyer_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商品模板L2身份';

CREATE TABLE sc_buyer_product_settlement_price_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '模板L2版本主键',
    buyer_product_id BIGINT NOT NULL COMMENT '分销商品主键',
    version_no INT NOT NULL COMMENT '从1递增',
    unit_price_cents BIGINT NOT NULL COMMENT '模板L2，CNY分',
    currency CHAR(3) NOT NULL COMMENT '币种CNY',
    published_at DATETIME(3) NOT NULL COMMENT '发布时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_l2_template_version (buyer_product_id, version_no),
    CONSTRAINT ck_sc_l2t_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_l2t_price CHECK (unit_price_cents >= 0),
    CONSTRAINT ck_sc_l2t_version_no CHECK (version_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='加价模板发布的L2不可变版本';

CREATE TABLE sc_buyer_product_l2_override (
    buyer_product_id BIGINT NOT NULL COMMENT '分销商品主键',
    current_version_id BIGINT NULL COMMENT '当前手工覆盖版本，空表示跟模板',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (buyer_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商品手工L2覆盖身份';

CREATE TABLE sc_buyer_product_l2_override_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '手工L2覆盖版本主键',
    buyer_product_id BIGINT NOT NULL COMMENT '分销商品主键',
    version_no INT NOT NULL COMMENT '从1递增',
    adjust_mode VARCHAR(16) NOT NULL COMMENT 'EQUAL_L1或CUSTOM',
    percent INT NULL COMMENT 'CUSTOM时的整数百分比，100等于L1',
    amount_cents BIGINT NULL COMMENT 'CUSTOM时加减的CNY分',
    resolved_unit_price_cents BIGINT NOT NULL COMMENT '解析后的覆盖L2，CNY分',
    created_by BIGINT NULL COMMENT '操作人',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_l2o_version (buyer_product_id, version_no),
    CONSTRAINT ck_sc_l2o_mode CHECK (adjust_mode IN ('EQUAL_L1','CUSTOM')),
    CONSTRAINT ck_sc_l2o_price CHECK (resolved_unit_price_cents > 0),
    CONSTRAINT ck_sc_l2o_version_no CHECK (version_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分销商品手工L2覆盖不可变版本';

CREATE TABLE sc_project_listing (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目商品主键',
    listing_code VARCHAR(32) NOT NULL COMMENT '项目商品稳定业务编码',
    project_id BIGINT NOT NULL COMMENT '采购项目主键，逻辑引用sc_buyer_project.id',
    buyer_product_id BIGINT NOT NULL COMMENT '分销商品主键，逻辑引用sc_buyer_product.id',
    current_version_id BIGINT NULL COMMENT '当前已发布版本',
    status SMALLINT NOT NULL COMMENT '10可采购 90已移除',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_project_listing_code (listing_code),
    UNIQUE KEY uk_sc_project_listing_product (project_id, buyer_product_id),
    KEY idx_sc_project_listing_project (project_id, status),
    CONSTRAINT ck_sc_project_listing_status CHECK (status IN (10, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目对分销商品的采购目录项';

CREATE TABLE sc_project_listing_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目商品版本主键',
    listing_id BIGINT NOT NULL COMMENT '项目商品主键',
    version_no INT NOT NULL COMMENT '从1递增',
    buyer_settlement_unit_price BIGINT NOT NULL COMMENT '冻结的B端结算单价，CNY分',
    settlement_price_level VARCHAR(8) NOT NULL COMMENT 'L1 L2 L3',
    price_source VARCHAR(32) NOT NULL COMMENT 'INHERITED或PROJECT_OVERRIDE',
    l3_adjust_mode VARCHAR(16) NOT NULL COMMENT 'FOLLOW_L2 EQUAL_L1 CUSTOM',
    l3_percent INT NULL COMMENT 'CUSTOM百分比',
    l3_amount_cents BIGINT NULL COMMENT 'CUSTOM加减分',
    currency CHAR(3) NOT NULL COMMENT 'CNY',
    published_by BIGINT NULL COMMENT '发布人',
    published_at DATETIME(3) NOT NULL COMMENT '发布时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_plv (listing_id, version_no),
    CONSTRAINT ck_sc_plv_level CHECK (settlement_price_level IN ('L1','L2','L3')),
    CONSTRAINT ck_sc_plv_source CHECK (price_source IN ('INHERITED','PROJECT_OVERRIDE')),
    CONSTRAINT ck_sc_plv_l3_mode CHECK (l3_adjust_mode IN ('FOLLOW_L2','EQUAL_L1','CUSTOM')),
    CONSTRAINT ck_sc_plv_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_plv_price CHECK (buyer_settlement_unit_price >= 0),
    CONSTRAINT ck_sc_plv_version_no CHECK (version_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目商品不可变结算价版本';
```

YAML 追加方式与 `020` 完全相同，`id: 021-admin-frontend-alignment`。

`SupplyChainLiquibaseIntegrationTest.tableNames()` `.contains(...)` 增加：

`sc_supplier_supply_scope_mode`, `sc_supplier_integration_mode`, `sc_supplier_file_ref`, `sc_buyer_contact`, `sc_buyer_product`, `sc_project_listing`, `sc_platform_settlement_price_version`

`.columnNames()` 增加 `sc_buyer.parent_buyer_id`, `sc_buyer_project.settlement_party_type`

- [ ] **Step 3: 跑集成确认通过**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am verify -Psupply-chain-integration \
  -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=SupplyChainLiquibaseIntegrationTest,InstoreProjectCashierRouteMigrationIntegrationTest,InstoreMemberEntryRouteMigrationIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=true
```

Expected: 三测试 PASS，`changeSetCount=21`。若 `DROP COLUMN supply_mode` 后下一步 Task 8 未完成，先不要提交 021 单独上主干——与 Task 8 同一提交。

- [ ] **Step 4: 与 Task 8 一起 Commit**（见 Task 8）

---

### Task 8: 供货配置改写子表（与 021 同提交）

**Files:**
- Create: `tradecore/supply/dal/dataobject/SupplierSupplyScopeModeDO.java`
- Create: `tradecore/supply/dal/mysql/SupplierSupplyScopeModeMapper.java`
- Modify: `SupplierSupplyScopeDO.java` 删除 `supplyMode`
- Modify: `SupplierSupplyScopeService.java`
- Modify: `SupplierAdminController.java`：`new ScopeLine(..., List.of(line.getSupplyMode()), ...)`
- Modify: `SupplierProfileQueryService.java`：`SupplyScopeView.supplyMode` → `List<String> supplyModes`；列表筛选 join 子表
- Modify: `SupplierAdminProfileIntegrationTest`：`new ScopeLine(..., List.of("API_DIRECT"), ...)`

- [ ] **Step 1: 改 IT 的 `ScopeLine` 调用为 `List.of("API_DIRECT")`**，编译失败证明 record 尚未改。

- [ ] **Step 2: 实现**

`ScopeLine` 改为：

```java
public record ScopeLine(Long categoryL1Id, Long categoryL2Id, Long categoryL3Id,
                        List<String> supplyModes, Long maxQuantity, Long minQuantity) {
}
```

`replaceAll`：校验 `supplyModes` 非空；每个值 `SupplyMode.fromCode`；空或未知 → `SUPPLY_SCOPE_MODE_INVALID`（未知也可让 `fromCode` 抛 IAE 再包成该错误）。插入 scope 后循环插入 mode 行。`deleteBySupplierId` 前先按 scope id 删 mode（无 FK，必须先删子表）。

`list`：查 scope 后按 id 批量查 mode，组装时不要把 DO 直接回给 Controller（下一计划再改 GET DTO）。本任务 Query 的 `toScopeView` 填 `supplyModes`。

`matchingScopeSupplierIds`：有 `supplyMode` 过滤时 `in` 子查询 mode 表，不要读已删除列。

Mode DO：`@TableName("sc_supplier_supply_scope_mode")` 字段 `id, supplyScopeId, supplyMode, createdAt`。

Mapper：`selectByScopeIds(Collection<Long> ids)`、`deleteByScopeIds`。

- [ ] **Step 3: 跑**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=false \
  -Dtest=com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandServiceTest
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am verify -Psupply-chain-integration \
  -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=SupplierAdminProfileIntegrationTest,SupplyChainLiquibaseIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=true
```

Expected: 单测 PASS；IT 建档+供货配置仍绿（`createDrafts` 此时仍是 DRAFT，下一计划才改）。

- [ ] **Step 4: Commit Task 7+8**

```bash
git commit -m "$(cat <<'EOF'
feat(supply-chain): 追加管理端对齐表结构并将供货模式改为多选子表

为分销商树、项目已结束和三级定价准备 schema，同时避免供货配置靠模式拆行。
EOF
)"
```

---

### Task 9: 编号前缀 `BYR_` / `BPD_` / `PRJ_` / `PLT_`

**Files:**
- Modify: `infrastructure/identity/BusinessNoPrefixes.java`
- Modify: `src/test/java/com/aifuli/module/supplychain/border/instore/domain/InstoreStatusEnumsTest.java`（已断言 `ALL.contains(IEC)`，追加四前缀）

- [ ] **Step 1: 测试**

```java
assertThat(BusinessNoPrefixes.ALL).contains(
        BusinessNoPrefixes.BYR, BusinessNoPrefixes.BPD,
        BusinessNoPrefixes.PRJ, BusinessNoPrefixes.PLT);
```

放进现有 `InstoreStatusEnumsTest` 或新建 `BusinessNoPrefixesTest`。前缀字符串必须是 4 字符（含下划线）以便 `prefix+ULID<=32`。

- [ ] **Step 2: 实现**

```java
public static final String BYR = "BYR_";
public static final String BPD = "BPD_";
public static final String PRJ = "PRJ_";
public static final String PLT = "PLT_";
```

加入 `ALL`。

- [ ] **Step 3: 跑测通过并 Commit**（`feat(supply-chain): 增加分销商与项目商品业务编号前缀`）

---

### Task 10: 类目祖先链校验 + 类目树 GET

**Files:**
- Create: `tradecore/catalog/application/CategoryChainValidator.java`
- Create: `tradecore/catalog/application/CategoryQueryService.java`
- Create: `interfaceapi/admin/CategoryAdminController.java`
- Create: `src/test/java/com/aifuli/module/supplychain/tradecore/catalog/application/CategoryChainValidatorTest.java`

- [ ] **Step 1: 写失败测试**（Mockito `ProductCategoryMapper`）

```java
@Test
void rejectsBrokenAncestryAndInactive() {
    ProductCategoryMapper mapper = mock(ProductCategoryMapper.class);
    CategoryChainValidator validator = new CategoryChainValidator(mapper);
    ProductCategoryDO l1 = category(1L, null, (short) 1, CatalogEntityStatus.ACTIVE.getCode());
    ProductCategoryDO l2 = category(2L, 1L, (short) 2, CatalogEntityStatus.ACTIVE.getCode());
    ProductCategoryDO l3 = category(3L, 99L, (short) 3, CatalogEntityStatus.ACTIVE.getCode());
    when(mapper.selectById(1L)).thenReturn(l1);
    when(mapper.selectById(2L)).thenReturn(l2);
    when(mapper.selectById(3L)).thenReturn(l3);
    assertThatThrownBy(() -> validator.requireActiveChain(1L, 2L, 3L))
            .isInstanceOf(ServiceException.class)
            .extracting(ex -> ((ServiceException) ex).getCode())
            .isEqualTo(CATEGORY_CHAIN_INVALID.getCode());
}

@Test
void acceptsLinkedActiveThreeLevels() {
    // l3.parent=l2, l2.parent=l1, l1.parent=null, levels 1/2/3
}
```

- [ ] **Step 2: 实现 validator** — 三级都必须 `ACTIVE`；level 分别为 1/2/3；`l1.parentId==null`；`l2.parentId==l1.id`；`l3.parentId==l2.id`。否则 `CATEGORY_CHAIN_INVALID`。

`CategoryQueryService.tree()`：`selectList` 全部 `ACTIVE`，按 `sort,id` 排，内存组 `children`。记录：

```java
public record CategoryNode(Long id, String categoryCode, String categoryName,
                           Short categoryLevel, Long parentId, List<CategoryNode> children) {}
```

Controller：

```java
@Tag(name = "管理后台 - 供应链类目")
@RestController
@RequestMapping("/supply-chain/categories")
@PreAuthorize("@ss.hasPermission('supply-chain:admin')")
public class CategoryAdminController {
    @GetMapping
    @Operation(summary = "启用类目树")
    public CommonResult<List<CategoryNode>> tree() {
        return success(queryService.tree());
    }
}
```

禁止路径 `/tree`。

把 `SupplierSupplyScopeService.validateChain` 改调 `CategoryChainValidator`（错误码从 `SUPPLIER_SCOPE_CONFLICT` 变为 `CATEGORY_CHAIN_INVALID`）。三元组重复仍是 `SUPPLIER_SCOPE_CONFLICT`。

- [ ] **Step 3: 跑** `CategoryChainValidatorTest` 与 `SupplierAdminProfileIntegrationTest`（链合法时仍绿）。

- [ ] **Step 4: Commit**

---

### Task 11: 标品 SKU 分页 GET

**Files:**
- Create: `tradecore/catalog/application/PlatformSkuQueryService.java`（及内部 records）
- Create: `tradecore/catalog/dal/mysql/PlatformSettlementPriceMapper.java` + Version DO/Mapper（Lombok `@TableName` 对应 021）
- Create: `interfaceapi/admin/PlatformSkuAdminController.java`
- Create: `src/test/java/com/aifuli/module/supplychain/tradecore/catalog/application/PlatformSkuQueryServiceTest.java`（Mockito）

- [ ] **Step 1: 测试**

```java
@Test
void filtersByKeywordAndOmitsSuggestPriceWhenL1Missing() {
    // sku+spu mock；price mapper 返回 null
    // keyword 命中 skuName
    // 断言 suggestPriceCents == null, marketPriceCents == faceValue（face_value 作为 C 端建议价；无独立市场价列）
}
```

锁定：`marketPriceCents` = `sc_platform_sku.face_value`（面值分）；没有则仍返回该列。`suggestPriceCents` = 当前 L1 `unit_price_cents`，无身份/无版本则为 null。点餐/电影 L1 允许 0，0 不是 missing。

- [ ] **Step 2: 实现查询**（LambdaQueryWrapper，禁止新 `@Select`）

`GET /supply-chain/products/skus` 查询参数：`keyword`,`categoryL1Id`,`categoryL2Id`,`categoryL3Id`,`supplierCode`,`buyerCode`,`projectCode`,`pageNo`,`pageSize`。

过滤语义（09-02 §4.4）：

- 仅 ACTIVE SKU + ACTIVE SPU。
- 类目：SPU.`primaryCategoryId` 等于 L3；若只传 L1/L2，则 primary 的祖先匹配（先 load 类目再 `in` id 集合）。
- `supplierCode`：存在未删除供货配置命中该 L3，且不存在该供应商下 `status != RETIRED` 的 Offer。
- `buyerCode`：不存在该 Buyer 下 `status=10` 的 `sc_buyer_product`。
- `projectCode`：存在所属 Buyer 的有效 BuyerProduct，且不存在该项目 `status=10` 的 `sc_project_listing`。

本任务用 Mapper 把过滤写完（表已空，单测 mock）。分页 `pageNo` 默认 1，`pageSize` 默认 20，倒序 `sku.createdAt`（规格合作商品按 createdAt；标品池同样 createdAt 倒序）。

Controller `@RequestMapping("/supply-chain/products/skus")` 只有集合 GET，不要 `/page`。

响应行：`id, skuCode, skuName, spuCode, spuName, categoryL1Id/Name, categoryL2Id/Name, categoryL3Id/Name, suggestPriceCents, marketPriceCents`。

- [ ] **Step 3: 跑单测通过。** ArchUnit：`admin_controllers_do_not_depend_on_supplier_adapter` 仍绿。

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=false \
  -Dtest=com.aifuli.module.supplychain.architecture.SupplyChainModuleArchitectureTest
```

- [ ] **Step 4: Commit**

---

### Task 12: 新表 DO/Mapper 骨架（供后续计划编译）

**Files:** 为 021 中尚未在 Task 11 创建的表补齐 DO+Mapper（BuyerProduct、L2、ProjectListing、Buyer 扩展列等到 buyer/project 计划再扩 `BuyerDO`/`BuyerProjectDO` 字段——**本任务就把字段加到现有 DO**，避免半截实体）。

- [ ] **Step 1:** 扩展 `BuyerDO`：`parentBuyerId, distributionLevel, distributorType, supplyPreference, settlementMethod, settlementCycleDays, cooperationStartedAt, cooperationNote, legalEntityName, creditCode, legalPersonName, legalPersonMobileCipher, contactAddress, bankName, bankAccountCipher, bankAccountKeyVersion, documentKeyVersion, idFrontCipher, idBackCipher`。

- [ ] **Step 2:** 扩展 `BuyerProjectDO`：`projectType, startDate, endDate, settlementPartyType, description`。`startDate`/`endDate` 用 `LocalDate`。

- [ ] **Step 3:** 新建 BuyerProduct / ProjectListing / L2 / L1 其余 Mapper。每个 Mapper 只需 `BaseMapperX` + `selectByCode` / `selectByBuyerAndSku`。

- [ ] **Step 4:** 编译

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=true
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**（`feat(supply-chain): 补齐管理端新表对象与采购商档案列`）

---

### Task 13: 联调文档

**Files:** `docs/集成测试.md` 第 5 节「待联调流程」中供应商那条，改为：

```
- 管理端 REST（规格 2026-09-02）：供应商/分销商/项目对齐前端页面。自动化门禁见各 *Test / *IntegrationTest / *ContractTest / *ArchitectureTest。前端 `supplychainhubweb` 关 mock 后按规格第 11 章改 URL 与分/枚举。开票页签无后端。加价模板/订单/售后/路由页面仍待后续切片。
```

- [ ] **Step 1: 改文档**
- [ ] **Step 2: Commit**（`docs(supply-chain): 记录管理端 REST 联调范围`）

---

## 本计划自检

| 规格章节 | 任务 |
| --- | --- |
| §4.1 五模式 + 子表 | Task 1, 7, 8 |
| §4.2 枚举/状态机 | Task 3–5 |
| §4.3 类目树 | Task 10 |
| §4.4 SKU 池 | Task 11 |
| §8 定价 | Task 6 |
| §9 错误码 | Task 2 |
| Liquibase 021 | Task 7 |
| §13 表归属 | Task 12 |
| 前端第 11 章 | 不改前端；Task 13 文档 |

**明确不做：** Controller 业务编排（除类目/SKU GET）、合作商品上架、分销商/项目写路径。
