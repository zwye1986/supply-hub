# 订单展示修复实施计划

> **For agentic workers:** 使用 executing-plans 在当前会话逐项执行；不另建线程，不操作共享订单。

**Goal:** 成功订单隐藏失败处置，供应商列表/详情正确展示业务类型及关联 Offer 当前供货模式。

**Architecture:** 保持 supplier-orders 的 B 单粒度。查询服务批量读取 SKU/SPU/Offer，并复用现有业务类型映射；前后端分别过滤成功单摘要。供货模式仅作当前配置展示，无迁移或历史回填。

**Tech Stack:** Java 21、Spring、MyBatis Plus、JUnit 5、Testcontainers MySQL 8.4；Vue 3、Element Plus、Node test。

设计：`docs/superpowers/specs/2026-09-21-supplier-order-admin-readonly-design.md#8-订单展示修订2026-10-15已确认`。

## 1. 回归先行

- [x] 扩展 server 的 `border/order/application/SupplierOrderAdminQueryServiceTest.java`，通过 JSON 响应断言新字段，避免未实现方法导致编译错误：

```java
JsonNode row = new ObjectMapper().valueToTree(service.get("BO_1"));
assertThat(row.path("bizType").textValue()).isEqualTo("VIRTUAL_RECHARGE");
assertThat(row.path("supplyMode").textValue()).isEqualTo("DROP_SHIP");
```

- [x] 扩展 `OrderViewQueryServiceTest.java`，以总单成功且存在先失败后成功两次尝试验证两个摘要均为空；扩展 `OrderFailureViewQueryTest.java` 验证供应商成功无摘要、非成功仍保留。
- [x] 扩展前端 `tests/order-numbers.test.mjs`：实际 OrderApi 对列表/详情均接入 bizType/supplyMode，成功过滤旧后端摘要，非成功与缺值不被误处理。
- [x] 运行红阶段并保留失败证据：

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Dtest=SupplierOrderAdminQueryServiceTest,OrderViewQueryServiceTest,OrderFailureViewQueryTest -Dsurefire.failIfNoSpecifiedTests=false test
# 在 /Users/mac/codespace/supplychainhubweb
node --test tests/order-numbers.test.mjs tests/failure-closure.test.mjs
```

## 2. 最小实现

- [x] 在 `SupplierOrderAdminQueryService.java` 的响应末尾追加字段，并在页/详情统一组装时分别批量读取 SKU、SPU、Offer：

```java
String bizType = SupplyChainOrderType.CARD.name().equals(order.getOrderType()) && sku == null
        ? null : OrderViewMappings.bizType(order.getOrderType(),
                sku == null ? null : sku.getFulfillmentType(),
                spu == null ? null : spu.getProductType());
String supplyMode = offer == null ? null : offer.getOfferSupplyMode();
```

- [x] `OrderFailureViewQuery.read` 对 `BOrderFulfillmentStatus.SUCCEEDED` 直接返回 null；`OrderViewQueryService.assembleRows` 在平台成功时不生成任何失败摘要，兼容供应商详情模式改为按 attempt.offerId 查当前 Offer。
- [x] 前端 `src/api/order/types.ts` 声明新字段与未知业务类型，`src/api/order/index.ts` 使用新字段并过滤成功摘要：

```typescript
bizType: view.bizType || '',
supplyMode: view.supplyMode ?? undefined,
// 商品行沿同一业务类型，不把领域 CARD 强制断言成页面业务类型。
orderType: view.bizType || '',
```

- [x] 在 `src/views/supplier/order/supplier/index.vue` 的供货模式列和 `supplier/detail/index.vue` 的值旁注明“当前合作商品配置”；不修改筛选能力、金额、履约/资金命令或权限。

## 3. 关联与可见结果验证

- [x] 扩展现有 `OrderAdminIntegrationTest.java` 的真实数据库夹具，注册直接受影响查询依赖，插入 SKU/SPU/Offer 并验证列表详情一致、Offer 模式修改后旧单显示当前值、缺值不猜测。
- [x] 运行供应链全部纯单测/架构及适用订单、权限契约集成；按日志分别报告结果，不把未执行门禁当通过。
- [x] 前端运行目标/全量 Node 测试、受影响 ESLint/Prettier、类型检查与构建，区分既有失败。
- [x] 使用现有本地 Vite/浏览器工作流渲染真实页面，隔离供应商/Case写命令；检查供应商列表、成功供应商详情、成功平台详情及人工对照。截图保存在后端 `.amp/in/artifacts/` 并逐张查看，禁止暴露卡密或凭据。
- [x] 更新 `docs/集成测试.md` 的本地证据及发布后步骤。自审 diff，报告未提交/未推送/未部署状态，不自行发布。

执行结果：后端 API 125 + server 2309 + 集成/权限契约 43 项通过；前端目标 24 项通过，受影响静态检查和构建通过。全量前端 1 项既有测试失败、53 项既有类型诊断未修复。浏览器截图使用实际组件与隔离 GET 新字段/平台夹具，未执行部署后联调。详见 `docs/集成测试.md` 的 2026-10-15 订单展示小节。
