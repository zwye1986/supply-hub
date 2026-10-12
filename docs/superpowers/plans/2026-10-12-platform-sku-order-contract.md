# 平台 SKU v2 下单实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 对外独立 v2 只接受平台 SKU，保留 v1/RPC、永久项目键、冻结价格与原路由/资金协议。

**Architecture:** HTTP 两种封闭 DTO 适配同一 Facade 编排及 Lifecycle；catalog Resolver 只读定位真实 SKU/BPD/LST。格式3独立字节合同与回执列传播到恢复路径；注册表、过滤器、共享安全路径、Gateway、Nginx 同步精确接线。

**Tech Stack:** Java21、Spring、MyBatis Plus、Liquibase、MySQL8.4、JUnit5、Testcontainers、Vue3。

---

依据：`../specs/2026-10-12-platform-sku-order-contract-design.md` 已经书面通过。在当前 dev 工作区实施，不推送、部署或运行共享迁移。以下 J/T/R 分别为 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain`、该模块 `src/test/java/com/aifuli/module/supplychain`、该模块 `src/main/resources`；A 为 `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain`。

## Task 1：格式3与可信身份（先红后绿）

Files：`J/border/fulfillment/domain/StandardAcceptanceFingerprintPolicy.java`、`J/border/fulfillment/application/FrozenAcceptanceIdentityReader.java`、`J/tradecore/catalog/application/BuyerAcceptanceCatalogResolver.java`、`J/border/fulfillment/dal/dataobject/FulfillmentAcceptanceReceiptDO.java`；测试扩展 `T/border/fulfillment/domain/StandardAcceptanceFingerprintPolicyTest.java`，冻结回放在既有真实HTTP闭环文件验证，保留原 `ProjectStandardOrderFrozenReplayTest` 不变。

- [x] 固定独立字节向量断言：格式3依次编码 formatVersion=3、buyerId、projectCode、buyerOrderNo、productCodeType=PLATFORM_SKU、platformSkuCode、frozenPlatformSkuId、quantity、targetSchemaCode、targetSchemaVersion、targetDigestHex；原格式2和 buyerOrderDigest 黄金值不变。原码大小写/target不同必须异指纹。
- [x] Reader 区分 absent、trusted2、trusted3、untrusted；旧 read 仅允许格式2使用，新增 readIdentity 返回存在但不可信状态。保持原三参数冻结快照构造兼容现有测试。
- [x] Resolver 按 skuMapper.selectByCode → productMapper.selectByBuyerAndSku → 唯一 LST 只读定位；缺 SKU/BPD claim 前安全 SKU_NOT_SALEABLE，Schema 缺损系统失败。内部 CatalogIdentity.requestedCode 使用真实 BPD，不复用旧别名解析。
- [x] 格式3入口缺失先失败、实现后通过；最终全供应链API170/server2551回归含原冻结回放和黄金向量均通过，命令及日志见集成测试记录。

## Task 2：受理及恢复传播

Files：`J/border/fulfillment/application/FulfillmentAcceptanceLifecycle.java`、`J/application/facade/ProjectStandardOrderCommandFacade.java`、`J/border/fulfillment/application/StandardOrderIdentityMigrationService.java`；测试 `T/interfaceapi/open/project/StandardProjectHttpClosureIntegrationTest.java` 及既有限频Acceptance/Execution/Timer/Lifecycle与身份迁移集成。旧RPC Facade无须改契约，恢复不重建PreparedAcceptance，复用已持久化订单／请求／操作。

- [x] 扩展真实 HTTP/MySQL 用例：首次 SKU→BPD→项目价→多个 Offer 路由；格式3/原请求码/真实 SKU 冻结；缺 BPD 在claim前失败；跨版本同键冲突及跨版本查询互通。
- [x] PreparedAcceptance 的旧构造默认格式2；完整构造追加 short fingerprintVersion、String requestedPlatformSkuCode、Long frozenPlatformSkuId。claim 插入使用命令格式，格式3的 SKU 不依赖 gateListing；已有比较先校验格式再比较指纹。
- [x] 两种 HTTP 请求在 Facade 适配共享编排；冻结格式3比较本次原码，用冻结 SKU/Schema/BPD，回放不查现行身份；续跑后才查当前身份且不允许换 SKU。v1/RPC遇格式3同键直接冲突。
- [x] HTTP续跑／CAS传完整冻结身份；实际频控执行／恢复不重建命令、不重算指纹，复用原订单／操作。迁移Service及Mapper仅处理格式0，保留2/3。锁顺序、REQUIRES_NEW 报价、资金/未知恢复不变。
- [x] PROCESSING、CAS、价格/Schema/项目选品变更后回放、跨版本并发及v2 MOBILE挡板闭环通过；既有限频受理／执行／Timer／生命周期35项及身份迁移6项通过。进程级真实重启与远端交易仍NOT_RUN，不用这些结果冒充实机恢复。

## Task 3：增量约束

Files：`R/db/changelog/releases/1.0.0/064-platform-sku-order-request-identity.sql`、同目录 `db.changelog-1.0.0.yaml`；测试在既有迁移集成文件扩展。

- [x] 只追加064：requested_platform_sku_code nullable64；旧0/2必须NULL，3必须非空白码、真实SKU和Schema；原指纹CHECK扩0/2/3；DO与DDL注释一致。不改052或回填历史。
- [x] 既有格式0/2身份迁移回归、格式3不被迁移覆盖、NULL/空串/空格/制表/Unicode空白及不完整SKU/Schema边界、永久键竞争通过。正式66个变更集二次启动无新增／重复且checksum相同；旧SQL未改。未对共享存量运行升级。
- [x] 仅 disposable Testcontainers 执行正式Liquibase，不运行pro143共享库。

## Task 4：完整安全入口

Files：`A/api/auth/ProjectAuthOperations.java`、`J/interfaceapi/open/project/ProjectStandardHttpRequestParser.java`、该目录 v2 Controller/DTO、`J/application/facade/ProjectStandardOrderQueryFacade.java`、`J/infrastructure/auth/ProjectAppAuthFilter.java`、`ProjectAppCredentialService.java`、`R/application.yaml`、`aifuli-gateway/src/main/resources/application.yaml`、`aifuli-framework/aifuli-common/src/main/java/com/aifuli/framework/common/util/http/ApiAccessLogPathUtils.java`、`script/deploy/supplyhub-test/nginx/supplyhubtest.qihaofu.cn.conf`。

- [x] 扩展注册表/真实HMAC/严格闭包测试，独立码 CREATE_V2/QUERY_V2/ASSET_QUERY_V2及2.0.0；v1签名字节保持，跨操作签名拒绝。
- [x] 三条精确POST，DTO只 platformSkuCode，不接受buyerSkuCode或供应商/身份/价格覆盖；复用重复键、尾随JSON、严格数量与target校验，不猜前缀。
- [x] Filter注册随PROTECTED_PREFIXES覆盖v2，未知兄弟操作失败关闭；仅v1/v2摘要QUERY允许能力停用/SUSPENDED；两个Facade精确操作校验，不伪装v1上下文。
- [x] 共享路径分类保护v2根/子路径、未知路径安全URL保持版本；资产及错误no-store；日志/异常保护通过；Gateway原字节测试通过，Nginx模板精确接线已改但实机链路NOT_RUN。
- [x] 认证／HTTP／ArchUnit及common/Gateway直接回归通过；实际配置matchTrailingSlash=false，方法／尾斜杠／子路径／trace-id单值与原字节检查通过。

## Task 5：页面、证据与交付

Files（前端 `/Users/mac/codespace/supplychainhubweb`）：`src/views/supplier/distribution/distributor/detail/ProductList.vue`、项目 ProductList 实际所有者及相关API注释、既有SFC测试；后端 `docs/集成测试.md`、详细设计状态。

- [x] 本地两列表展示平台SKU为v2下单编码，BPD为分销商品编号/旧v1；PLT/LST不改含义。前端发布必须等待后端v2／064／外层入口可用，远端未开放。
- [x] 真实SFC12项通过，两列表正常／来源变化／过期／暂停／退役及1440px截图已检查；全量231通过1失败、54条类型基线失败单列。
- [x] 全供应链API170/server2551及31项架构、HTTP／MySQL与旧RPC／目录分批105项、共享36项已通过；联调文档记录失败批次与最终证据。交付前全量单测及前端定向检查再次通过，最终自审无阻塞；本地交付使用中文提交，不推送或部署。
- [x] 交付边界明确：不推送、共享迁移或发布；pro197/pro143实机联调和真实交易仍NOT_RUN；已有格式3不能回滚至仅0/2制品。
