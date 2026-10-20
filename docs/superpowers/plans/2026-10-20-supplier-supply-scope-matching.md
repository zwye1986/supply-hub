# 供应商供货范围匹配统一实施计划

**Goal:** 取消供货匹配的可售品类条件，新增/编辑与两个选品入口统一按类型及分类祖先覆盖匹配。

**Architecture:** 复用catalog类目解析器，提取无状态SupplierSupplyScopeMatcher；写入口映射无匹配/歧义错误，查询在分页前过滤不可匹配SPU。不改DDL、共享框架或前端。

**Tech Stack:** Java21、Spring、MyBatis Plus、JUnit5、Testcontainers MySQL8.4、Liquibase、ArchUnit。

设计入口：`docs/superpowers/specs/2026-10-20-supplier-supply-scope-matching-design.md`。

## 任务与检查点

- [x] 读取架构约束、三服务与既有测试；在当前dev目录保留已有文档改动。既有三服务单测68项基线通过。
- [x] 先落设计并在旧设计增加明确替代链接。
- [x] 在SupplierCooperativeProductServiceTest补跨品类新增/编辑与歧义；PlatformSkuQueryServiceTest补跨品类选品；ProductManagementPageQueryTest补无供货范围空页。运行三类测试保存实际RED日志（6项断言失败、0 error）。
- [x] 新增tradecore/supply/application/SupplierSupplyScopeMatcher及其23项纯测，保留父分类覆盖、最具体优先和最深歧义。
- [x] SupplierCooperativeProductService.matchScope委托匹配器；PlatformSkuQueryService.page移除supplierTripleKeys，注入ProductCategoryCoverageService并使用同算法。
- [x] ProductCatalogQueryService注入SupplierSupplyScopeMapper与ProductCategoryCoverageService；仅excludeAddedSupplierOffers=true场景在SPU分页前交集范围ID；更新两个选品Controller与供应商新增/编辑OpenAPI说明。
- [x] 补独占MySQL集成10项，验证分页total/翻页、成对组合隔离、跨品类三SKU按父配置新增、编辑、默认下架/无正式版本、退役后选品、歧义与批量回滚。
- [x] 最终单一连贯门禁通过：API125+server2352普通/架构测试，7套件69项适用集成/契约，共2546项全绿；其它模块不执行单测。日志final-gate-3.log，原XML与Java哈希已归档。
- [x] 自审diff及文档，更新docs/集成测试.md记录实际命令/结果、未部署边界，交付未提交变更。没有独立子代理审查，不宣称独立双审。

## 执行命令

仓库根目录，设置`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`，将其bin加入PATH。命令日志保存在`/tmp/supply-scope-match-evidence/`。

定向RED/GREEN：

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=SupplierCooperativeProductServiceTest,PlatformSkuQueryServiceTest,ProductManagementPageQueryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

供应链普通/架构回归：

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  '-Dtest=com.aifuli.module.supplychain.**.*Test,!**.*IntegrationTest,!**.*ContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

集成/契约：新增测试SupplierSupplyScopeMatchingIntegrationTest，原SupplierOfferSalePriceIntegrationTest、ProductCenterIntegrationTest、SupplierOfferLifecycleIntegrationTest和ProductCenterContractTest、SupplierAdminRestContractTest、PlatformSkuAdminContractTest定向运行；选择存在的类名，不把未发现测试视为通过。

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=SupplierSupplyScopeMatchingIntegrationTest,SupplierOfferSalePriceIntegrationTest,ProductCenterIntegrationTest,SupplierOfferLifecycleIntegrationTest,ProductCenterContractTest,SupplierAdminRestContractTest,PlatformSkuAdminContractTest \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

全程不访问业务数据库、不发远端写请求、不提交/推送/部署。
