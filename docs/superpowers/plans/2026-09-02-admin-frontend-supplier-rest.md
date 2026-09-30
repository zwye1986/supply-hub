# 供应商管理端 REST 对齐 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 必须先完成 [foundation](./2026-09-02-admin-frontend-foundation.md)。

**Goal:** 把已有 `/supply-chain/suppliers` 扩成 09-02 §5 的 REST：多值筛选、`view=simple`、PATCH 状态、供货配置 `supplyModes[]`、合作商品新增即上架、证件数组；动词路径仅作兼容。

**Architecture:** 只改 `tradecore.supply` + `SupplierAdminController`。上架 = `OfferStatus.PUBLISHED` 写在身份行；不调用 `SupplierOfferPublicationService`（缺连接器不能写 `sc_supplier_offer_version`）。时效/结算/对接改为可落库封闭枚举。

**Tech Stack:** 见 [总览](./2026-09-02-admin-frontend-rest.md)。

---

## 文件结构

```
interfaceapi/admin/SupplierAdminController.java
tradecore/supply/application/
  SupplierProfileCommandService.java   # 枚举可存；integrationModes 列表；licenseFileKeys
  SupplierProfileQueryService.java     # 多值筛选、simple 视图、列表聚合列
  SupplierSupplyScopeService.java      # Controller 改为收 supplyModes[]
  SupplierCooperativeProductService.java # createPublished / patchStatus / updateCommercial
```

兼容别名保留：`PUT /{code}/status`、`PUT /status/batch`、`POST /{code}/offers/batch-draft`、`PUT /{code}/offers/{offerNo}/retire`。新测试不打这些路径。`batch-draft` 改为内部转调 `createPublished`（行为变为上架，方法名可留以免大改调用点）。

---

### Task 1: 封闭枚举可存（替换「非空即拒」）

**Files:**
- Modify: `SupplierProfileCommandService.java` 的 `validateClosedEnums`
- Modify: `SupplierProfileCommandServiceTest.java`
- Modify: `SupplierDO.java` 增加 `shippingSlaCode, shippingSlaCustom, settlementMethod`
- Modify: create/update 写入这些列 + `sc_supplier_integration_mode`

- [ ] **Step 1: 改失败测试**

把 `closedEnumsAreNotOpenYet` 整段替换为：

```java
@Test
void closedEnumsPersistKnownValuesAndRejectUnknown() {
    SupplierProfileCommandService.validateClosedEnums("NEXT_DAY", "MONTHLY", List.of("API", "H5"));
    SupplierProfileCommandService.validateClosedEnums(null, null, null);
    assertThatThrownBy(() -> SupplierProfileCommandService.validateClosedEnums("same-day", null, null))
            .isInstanceOf(ServiceException.class)
            .extracting(ex -> ((ServiceException) ex).getCode())
            .isEqualTo(ENUM_NOT_OPEN.getCode());
}

@Test
void customShippingSlaRequiresText() {
    assertThatThrownBy(() -> SupplierProfileCommandService.validateShippingSla("CUSTOM", null))
            .isInstanceOf(ServiceException.class)
            .extracting(ex -> ((ServiceException) ex).getCode())
            .isEqualTo(ENUM_NOT_OPEN.getCode());
    SupplierProfileCommandService.validateShippingSla("CUSTOM", "工作日18点前");
    SupplierProfileCommandService.validateShippingSla("NEXT_DAY", null);
}
```

`CreateSupplierProfileCommand` / `UpdateSupplierProfileCommand` 把 `String integrationMode` 改成 `List<String> integrationModes`。所有调用点（Controller、IT `createCommand`）改为 `List.of()` 或 `null`。

- [ ] **Step 2: 跑测确认失败** — `-Dtest=com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandServiceTest`

- [ ] **Step 3: 实现**

```java
static void validateClosedEnums(String shippingSlaCode, String settlementMethod, List<String> integrationModes) {
    ClosedEnums.parse(ShippingSlaCode.class, shippingSlaCode);
    ClosedEnums.parse(SettlementMethod.class, settlementMethod);
    if (integrationModes != null) {
        for (String mode : integrationModes) {
            ClosedEnums.parseRequired(IntegrationMode.class, mode);
        }
    }
}

static void validateShippingSla(String shippingSlaCode, String shippingSlaCustom) {
    ShippingSlaCode parsed = ClosedEnums.parse(ShippingSlaCode.class, shippingSlaCode);
    if (parsed == ShippingSlaCode.CUSTOM && (shippingSlaCustom == null || shippingSlaCustom.isBlank())) {
        throw exception(ENUM_NOT_OPEN);
    }
}
```

create/update：写入列；先删后插 `sc_supplier_integration_mode`。`CUSTOM` 以外把 `shipping_sla_custom` 置 null。

Controller `SupplierSaveReqVO`：`integrationMode` → `integrationModes`（`List<String>`）；增加 `shippingSlaCustom`。

- [ ] **Step 4: 跑测 + `SupplierAdminProfileIntegrationTest` 通过**

- [ ] **Step 5: Commit**（`feat(supply-chain): 供应商发货时效结算与对接方式可落库`）

---

### Task 2: 列表筛选、聚合列、`view=simple`、PATCH

**Files:**
- Modify: `SupplierProfileQueryService.java`
- Modify: `SupplierAdminController.java`
- Create: `src/test/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierProfileQueryServiceTest.java`（Mockito）
- Create: `src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/SupplierAdminRestContractTest.java`

- [ ] **Step 1: Query 单测**

```java
@Test
void pageQueryAcceptsMultiStatusAndModes() {
    // stub mapper：两行供应商，scopes 带 BATCH 与 API_DIRECT
    // query categoryL1Ids=[1], supplyModes=["BATCH"], statuses=[ACTIVE]
    // 断言只返回命中 BATCH 的 code
}

@Test
void simpleViewReturnsCodeAndNameOnly() {
    // listSimple 不强制分页
}
```

`SupplierPageQuery` 改为：

```java
public record SupplierPageQuery(
        String supplierName, String supplierCode,
        List<SupplierStatus> statuses, List<Long> categoryL1Ids, List<String> supplyModes,
        LocalDateTime updatedFrom, LocalDateTime updatedTo,
        Integer pageNo, Integer pageSize) {}
```

删除 `onboardedFrom/To` 作为列表主筛选（规格是 `updatedFrom/To`）。IT 若用旧字段，改为 updated。

`SupplierListItem` 增加 `Long id`，以及聚合：

```java
List<NamedRef> categories, productTypes, productCategories, supplyModes
```

`NamedRef` = `record NamedRef(Long id, String name)`。从该供应商全部 scope 去重。`skuCount` 保持未退役 Offer 数。排序 `updatedAt DESC`。

- [ ] **Step 2: Controller**

`GET /supply-chain/suppliers`：

```java
@GetMapping
public CommonResult<?> page(
        @RequestParam(required = false) String supplierName,
        @RequestParam(required = false) String supplierCode,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) List<Long> categoryL1Ids,
        @RequestParam(required = false) List<String> supplyModes,
        @RequestParam(required = false) LocalDateTime updatedFrom,
        @RequestParam(required = false) LocalDateTime updatedTo,
        @RequestParam(required = false) String view,
        @RequestParam(required = false) Integer pageNo,
        @RequestParam(required = false) Integer pageSize) {
    if ("simple".equals(view)) {
        return success(queryService.listSimple(supplierName, supplierCode));
    }
    List<SupplierStatus> statuses = status == null ? List.of()
            : status.stream().map(s -> ClosedEnums.parseRequired(SupplierStatus.class, s)).toList();
    // SupplierStatus 不是 profile 枚举：用 valueOf + catch → ENUM_NOT_OPEN
    return success(queryService.page(new SupplierPageQuery(...)));
}
```

`SupplierStatus.valueOf` 包 `ENUM_NOT_OPEN`。不要新增 `/simple-list`。

PATCH 成员：

```java
@PatchMapping("/{supplierCode}")
public CommonResult<Boolean> patch(@PathVariable String supplierCode,
                                   @Valid @RequestBody PatchSupplierReqVO request) {
    if (request.getStatus() != null) {
        commandService.changeStatus(supplierCode, SupplierStatus.valueOf(request.getStatus()), getLoginUserId());
    }
    return success(true);
}

@PatchMapping
public CommonResult<Boolean> patchBatch(@Valid @RequestBody BatchStatusReqVO request) {
    SupplierStatus target = SupplierStatus.valueOf(request.getStatus());
    if (target != SupplierStatus.ACTIVE && target != SupplierStatus.SUSPENDED) {
        throw exception(SUPPLIER_STATUS_CONFLICT);
    }
    for (String code : request.getSupplierCodes()) {
        commandService.changeStatus(code, target, getLoginUserId());
    }
    return success(true);
}
```

`BatchStatusReqVO` 字段改名为 `status`（JSON）。兼容 `PUT /status/batch` 仍读 `targetStatus`（可两个字段都接受：优先 `status`，否则 `targetStatus`）。

`PatchSupplierReqVO`：`private String status;`

保留 `PUT /{code}/status` 调同一 `changeStatus`。

- [ ] **Step 3: Contract 测试**（Jackson，不启 Spring）

```java
class SupplierAdminRestContractTest {
    @Test
    void saveReqSerializesCentsAndEnumNames() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SupplierAdminController.SupplierSaveReqVO vo = new SupplierAdminController.SupplierSaveReqVO();
        vo.setSupplierName("甲");
        vo.setOpeningBalanceCents(100L);
        vo.setIntegrationModes(List.of("API"));
        String json = mapper.writeValueAsString(vo);
        assertThat(json).contains("openingBalanceCents").contains("\"API\"").doesNotContain("create");
        assertThat(json).doesNotContain("accountBalance");
    }

    @Test
    void controllerMappingsAreNouns() {
        RequestMapping root = SupplierAdminController.class.getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/supply-chain/suppliers");
        boolean invoice = Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .anyMatch(m -> {
                    GetMapping get = m.getAnnotation(GetMapping.class);
                    return get != null && String.join(",", get.value()).contains("invoice");
                });
        assertThat(invoice).isFalse();
        boolean createPath = Arrays.stream(SupplierAdminController.class.getDeclaredMethods())
                .anyMatch(m -> {
                    PostMapping post = m.getAnnotation(PostMapping.class);
                    return post != null && String.join(",", post.value()).contains("create");
                });
        assertThat(createPath).isFalse();
    }
}
```

- [ ] **Step 4: 跑测通过并 Commit**（`feat(supply-chain): 供应商列表筛选与PATCH状态对齐REST`）

---

### Task 3: 档案字段 — 证件数组、详情 `id`

**Files:**
- Modify: `SupplierProfileCommandService` create/update 写入最多 5 个 LICENSE / QUALIFICATION 到 `sc_supplier_file_ref`；保留旧单列 `license_file_cipher` 不写新数据（读路径：file_ref 优先，空则回退单列）。
- Modify: `SupplierDetail` 增加 `Long id`、`List<String> licenseFileKeys`、`List<String> qualificationFileKeys`、`List<String> integrationModes`、`shippingSlaCustom`。GET 脱敏规则不变。
- Modify: `SupplierSaveReqVO`：`licenseFileKeys`、`qualificationFileKeys`（`@Size(max=5)`）；废弃单字段仍可入参：若数组空且单字段非空则当单元素数组。
- 入参 `capabilityTags`：**忽略，不落库**（规格 §5.2）。
- Modify: IT 不强制旧单字段。

- [ ] **Step 1: 单测** `requireFileKeys` 超过 5 抛 `IllegalArgumentException` 或 `ENUM_NOT_OPEN`——锁定：超过 5 → `IllegalArgumentException("最多5个文件")` 即可，Controller `@Size(max=5)` 拦一层。

- [ ] **Step 2: 实现加解密** 与现有 `license_file_cipher` 同一 AAD 模式：`supplier:{id}:LICENSE:{sort}` / `QUALIFICATION:{sort}`。

- [ ] **Step 3: IT** 创建时传 2 个 licenseFileKeys，GET 回来 size=2。

- [ ] **Step 4: Commit**

---

### Task 4: 供货配置 REST 收 `supplyModes[]`

**Files:**
- Modify: `SupplierAdminController.ScopeReqVO`：删除 `supplyMode`，改为 `@NotEmpty List<String> supplyModes`。
- Modify: mapping `new ScopeLine(..., line.getSupplyModes(), ...)`
- GET 不要再返回 `SupplierSupplyScopeDO`。改为 `List<SupplyScopeView>`（已含 `supplyModes`）。

- [ ] **Step 1: Contract 测试** JSON 含 `supplyModes` 不含路径 `/batch`。

- [ ] **Step 2: 空数组** → `SUPPLY_SCOPE_MODE_INVALID`。重复三元组仍 `SUPPLIER_SCOPE_CONFLICT`。链错误 `CATEGORY_CHAIN_INVALID`。

- [ ] **Step 3: IT** `replaceAll` 一行两种模式 `List.of("API_DIRECT","H5")`，再 GET 断言两个值；再插入两个相同三元组失败。

- [ ] **Step 4: Commit**（`feat(supply-chain): 供货配置一行支持多种供货模式`）

---

### Task 5: 合作商品新增即 PUBLISHED，REST 子资源

**Files:**
- Modify: `SupplierCooperativeProductService.java`
- Modify: `SupplierAdminController.java`
- Modify: `SupplierAdminProfileIntegrationTest`：`createDrafts` 后状态改为 `PUBLISHED`；断言不再 `containsOnly(DRAFT)`。

- [ ] **Step 1: 写单测**（Mockito mappers）`SupplierCooperativeProductServiceTest`

```java
@Test
void createInsertsPublishedNotDraft() {
    // 最小 stub：supplier、sku、spu、scope 命中
    // 调用 createPublished
    // captor SupplierOfferDO.assert status == PUBLISHED.getCode()
}

@Test
void patchStatusRejectsRetiredAndUnknown() { ... }

@Test
void updateCommercialOnPublishedOfferDoesNotInsertOfferVersion() {
    // verify offerVersionMapper.insert never called
    // 只 update draft_unit_price 等身份列
}
```

- [ ] **Step 2: 跑测失败**

- [ ] **Step 3: 实现**

把 `createDrafts` 实现改为设置 `OfferStatus.PUBLISHED`，方法可改名为 `createPublished` 并让 `createDrafts` 委托它（兼容 `batch-draft`）。数量若 null，从命中的 scope 继承 max/min。

新增：

```java
public void changeOfferStatus(String supplierCode, String offerNo, OfferStatus target, Long operatorId)
public void updateCommercial(String supplierCode, String offerNo, DraftLine line, Long operatorId)
```

`changeOfferStatus`：只允许 `PUBLISHED`↔`SUSPENDED`；`RETIRED` 走 `retire`。非法 → `SUPPLIER_STATUS_CONFLICT`。

`updateCommercial`：校验价格与 scope（与 insert 相同）；更新身份列；**禁止** `offerVersionMapper.insert`。

Controller：

```java
@PostMapping("/{supplierCode}/offers")
public CommonResult<List<String>> createOffers(..., @RequestBody BatchDraftReqVO request)
// body 字段改为 items：增加 OfferItemsReqVO { List<DraftReqVO> items }
// 同时接受 lines 以兼容 batch-draft 内部

@PutMapping("/{supplierCode}/offers/{offerNo}")
public CommonResult<Boolean> updateOffer(...)

@PatchMapping("/{supplierCode}/offers/{offerNo}")
public CommonResult<Boolean> patchOffer(..., PatchOfferReqVO { String status })

@DeleteMapping("/{supplierCode}/offers/{offerNo}")
public CommonResult<Boolean> deleteOffer(...) // 调 retire
```

`GET .../offers`：改为分页 `PageResult<OfferListItem>`，筛选类目三级；默认 page。`OfferListItem` 含平台编码名称、类目路径、`suggestPriceCents`（L1）、价格字段、`status` 枚举名。不要把 DO 直接暴露。

`POST .../offers/batch-draft` 委托 `createPublished`。

`PUT .../offers/{offerNo}/retire` 委托 `retire`。

- [ ] **Step 4: 跑 IT** 断言 live offer status 为 PUBLISHED；retire 后列表不含；PATCH SUSPENDED 后再 PUBLISHED。

- [ ] **Step 5: Commit**（`feat(supply-chain): 合作商品新增即上架并提供REST子资源`）

---

### Task 6: 架构与全量供应商回归

- [ ] **Step 1:**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=false \
  -Dtest=com.aifuli.module.supplychain.architecture.SupplyChainModuleArchitectureTest,com.aifuli.module.supplychain.interfaceapi.admin.SupplierAdminRestContractTest
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am verify -Psupply-chain-integration \
  -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=SupplierAdminProfileIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=true
```

Expected: PASS。ArchUnit：admin 仍不依赖 `supplieradapter`。

- [ ] **Step 2: Commit** 若还有文档/测试修补；否则本任务无提交。
