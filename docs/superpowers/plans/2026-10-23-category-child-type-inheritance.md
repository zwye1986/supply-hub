# 新建子分类继承商品类型实施计划

> **For agentic workers:** 按测试先行在当前会话逐项执行；不拆分代理，不修改前端。

**Goal:** 新建商品子分类时完整继承直接父级商品类型，请求不能覆盖，并提供 Swagger 接口说明。

**Architecture:** 沿用 `CategoryCommandService.create` 的分类写锁、父分类行锁和事务，在现有类型校验前选择类型来源。根分类取请求，子分类取父级。控制器仅对根分类要求提交类型，不新增接口、错误码或数据库字段。

**Tech Stack:** Java 21、Spring MVC、MyBatis Plus、JUnit 5、Swagger/OpenAPI、MySQL Testcontainers。

设计依据：`docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md` §3.3、§4.2。

## 1. 回归测试先行

- [x] 在既有 `CategoryCommandServiceTest` 添加子分类多选完整继承、请求异值不能覆盖、历史单值兼容、父级无类型拒绝测试；断言保存后的类型值与顺序，不只断言调用成功。
- [x] 在既有 `CategoryAdminContractTest` 覆盖子分类无类型可调用与根分类无类型拒绝，以及生成 OpenAPI 中的继承说明。
- [x] 在既有 `ProductCenterIntegrationTest` 中创建子分类、孙分类并查询详情，验证实际 JSON 列、多级直接父级继承及异值请求不能覆盖。
- [x] 用 Java 21 执行目标测试，确认未修改实现时因缺少继承行为而失败。

## 2. 最小实现和 Swagger

- [x] 修改 `tradecore/catalog/application/CategoryCommandService.java` 的创建路径：

```java
List<String> validatedTypeCodes = parent == null
        ? validateAndResolveTypeCodes(command.typeCodes(), command.productType())
        : validateAndResolveTypeCodes(parent.getTypeCodes(), parent.getProductType());
if (parent != null && validatedTypeCodes.isEmpty()) {
    throw exception(CATEGORY_PRODUCT_TYPE_REQUIRED);
}
```

- [x] 修改 `interfaceapi/admin/CategoryAdminController.java`：仅 `parentCategoryCode` 为空且未提交有效类型时返回原必填错误；补充 `@Operation` 及类型/父级字段 `@Schema` 说明，字段基本语义仍与 DDL COMMENT 一致。
- [x] 保持更新接口逻辑、日志、事务配置及所有权限不变。

## 3. 验证和交付

- [x] 使用 Java 21 执行单元、架构、分类契约和真实 MySQL 商品中心集成测试（实际执行供应链全量单元/架构与扩展权限契约，2639 项通过，详见 `docs/集成测试.md`）：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -B -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dtest=CategoryCommandServiceTest,CategoryQueryServiceTest,SupplyChainModuleArchitectureTest \
  -Dit.test=CategoryAdminContractTest,ProductCenterIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify
```

- [x] 将实际结果、接口请求示例、错误边界与待执行的共享环境验收追加到 `docs/集成测试.md`，保留原有未提交内容。
- [x] 检查 `git diff --check`，交付 Swagger 字段及调用说明；只交付本地修改，不推送、不部署、不写共享数据库。
