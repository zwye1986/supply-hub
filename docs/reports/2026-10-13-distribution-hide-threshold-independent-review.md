# 分销商品隐藏阈值独立复核报告与解决方案

复核日期：2026-10-13（Asia/Shanghai）。本报告是源码复核及修复方案草案，不代表修复已实现或部署。

## 1. 结论与复核范围

**原任务的主要结论成立：保存使用通用定价版本，列表及编辑回填使用旧阈值列，读写来源错位。** 输入 2 表示 2%，保存请求没有漏传或除以 100；保存事务成功后，新版本记录该规则，但列表返回的旧列仍可能为空。输入框为空不能证明新规则未保存。

**这也是规则被后续编辑改写的问题。** 在其他必填项校验通过时，重开后阈值留空并再次确定，会发送 `hideInherited=true`，将当前隐藏组改为继承商品默认规则。该行为不是“保持原规则”。本轮以“等于市场价”公式复现；自定义公式还存在参数无法回填、表单可能阻止再次保存的问题，不能断言所有商品都能留空直接保存。

本轮还确认同一编辑路径存在完整配置丢失风险：自定义公式不能可靠回填；未编辑的销售价与服务费强制继承；隐藏组销售区阈值未提交；不含税覆盖遗漏税率编码会被后端降级为继承。因此建议修复当前版本完整配置的读取、回填、再保存闭环，而不只补一个数字。

复核对象与基线：

- 后端：`/Users/mac/.codex/worktrees/bd7d/supply-hub`，HEAD `7c4b24585692c80d02bcee6faf3854a81b259678`，复核开始时工作区干净。
- 前端：`/Users/mac/codespace/supplychainhubweb`，HEAD `b85e09aefbd59108de8759200a5d8c8c0d6cf99c`。存在订单相关未提交改动，本轮未修改前端文件。
- 原任务：[Amp：分销隐藏阈值回显错位](https://ampcode.com/threads/T-01a1342b-1cab-7471-ae36-06ecda052d60)。其内容用于确定待复核事项，本报告结论来自独立源码检查和执行验证。
- 本轮未连接业务数据库、未向真实服务发送保存请求、未验证已部署代码版本、未检查真实用户端应用。附件截图未登录读取。

## 2. 输入、保存、查询和回填证据

后端文件链接均位于当前工作树；前端链接指向用户确认的当前仓库。行号为本轮复核时的行号。

| 环节 | 当前实现及证据 | 判断 |
| --- | --- | --- |
| 输入 | [DistributionPriceForm.vue:40](/Users/mac/codespace/supplychainhubweb/src/views/supplier/distribution/distributor/detail/DistributionPriceForm.vue:40) 绑定 `distributionHideThresholdPercent`，范围 0～100、两位小数 | 2 表示 2%，0 也是显式数值 |
| 请求构造 | [ProductList.vue:347](/Users/mac/codespace/supplychainhubweb/src/views/supplier/distribution/distributor/detail/ProductList.vue:347) 数值非空时发送 `hideInherited=false` 和原数值；空值发送 `hideInherited=true` | 输入单位与请求一致；空值具有改变继承方式的业务含义 |
| 保存调用 | [前端 API:289](/Users/mac/codespace/supplychainhubweb/src/api/supplier/distribution/index.ts:289)、[BuyerAdminController:314](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/BuyerAdminController.java:314) 使用 `PUT /supply-chain/buyers/{buyerCode}/products/pricing` | 保存接口正确 |
| 请求映射 | [GenericPricingRequestMapper:61](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/pricing/GenericPricingRequestMapper.java:61) 生成隐藏组覆盖对象，销售区阈值未传则为 null | 本次覆盖为分销区 2、销售区 null，不与之前的隐藏组局部合并 |
| 配置解析 | [ProjectPricingConfigMerger:18](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/pricing/ProjectPricingConfigMerger.java:18) 按五组继承/覆盖选择上游或当前请求 | 这是完整五组配置的保存语义，不是“未提交则保留原组” |
| 版本发布 | [GenericBatchPricingService:227](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/pricing/GenericBatchPricingService.java:227)、[同文件:502](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/pricing/GenericBatchPricingService.java:502) 发布版本并切换身份指针 | 新规则写入 `sc_buyer_sku_pricing_version`，本路径不更新旧展示阈值列 |
| 提交与目录刷新 | [BuyerSelectionCatalogAdminCoordinator:69](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/BuyerSelectionCatalogAdminCoordinator.java:69) 无外层事务调用定价事务，成功返回后再刷新目录；响应标记 `COMMITTED` | 已保存与目录是否刷新成功是两个结果，刷新失败不能据此断言保存回滚 |
| 列表查询 | [BuyerProductQueryService:289](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/BuyerProductQueryService.java:289) 已加载当前新版本及 `override_json`，但第 337 行阈值仍取 `product.getDisplayThresholdPercent()` | 断点在读模型；查询手中已有新版本却未投影隐藏配置 |
| 编辑回填 | [ProductList.vue:286](/Users/mac/codespace/supplychainhubweb/src/views/supplier/distribution/distributor/detail/ProductList.vue:286)、[同文件:400](/Users/mac/codespace/supplychainhubweb/src/views/supplier/distribution/distributor/detail/ProductList.vue:400) 从列表旧字段回填，打开弹窗不请求分销商定价详情 | 旧列空则输入空；旧列 7 则误填 7 |

保存到新版本的事实分为三层，不能互相代替：

1. `override_json`：用户的五组继承标记和原始覆盖配置。用于编辑还原。
2. `effective_config_json` / `hide_rule_json`：合并上游后的有效配置。用于解释当前规则。
3. `effective_hide_threshold_percent`：两个有效阈值取最大值的计算结果。不能冒充用户原先填写的分销区数值。

例如分销区 2、销售区 5 的覆盖，编辑应还原 2 和 5，生效阈值为 5。继承时生效值即使也是 2，也不能在重新保存时擅自改成显式覆盖 2。

旧列 `sc_buyer_product.display_threshold_percent` 为整数，DDL COMMENT 标明“本批不消费”；新阈值允许两位小数。见 [021 DDL:200](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/021-admin-frontend-alignment.sql:200)、[旧 PATCH 接口说明:283](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/BuyerAdminController.java:283)。新旧字段在存储和业务效力上都不同。

## 3. 对原分析的复核判定与补充发现

### 3.1 原分析成立的部分

- 保存请求正确携带 2，写入新的通用定价版本。
- 后端列表仍读旧阈值，前端又把旧阈值作为新配置回填。
- 其他校验通过后，空值再次保存会切换为继承，存在规则被改写的风险。
- 利润隐藏按分销利润率严格小于阈值判断；两个阈值取较高者，税务及服务费不参与该利润率计算。
- 不能依据回填为空确定真实记录丢失，也不能仅依据后端规则宣称所有用户端页面已经隐藏。

### 3.2 需要补充的部分

**P1：未编辑配置可能被重置。** [ProductList.vue:352](/Users/mac/codespace/supplychainhubweb/src/views/supplier/distribution/distributor/detail/ProductList.vue:352) 每次固定发送 `salesInherited=true`、`serviceFeeInherited=true`。如果当前版本有这两组覆盖，改阈值或分销价也会把它们重置为继承。显式隐藏覆盖仅提交分销区阈值，原销售区阈值也会被置空。保存服务使用请求整体构造新版本，未保留旧组。

**P2：自定义公式回填信息不足。** 后端新定价列表仅返回“自定义公式”文案，见 [BuyerProductQueryService:398](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/BuyerProductQueryService.java:398)。前端却用正则解析该文案获得基数、百分比和运算数；对这四个字没有可解析参数，最终百分比/运算数为空。真实表单会要求补填；不能将此问题描述成所有情况下都能无修改再次保存。

**P2：继承标记无法完整保持。** 前端固定提交分销价覆盖、税务覆盖，并使用有效税务值回填。原本继承的配置即使数值回填成功，也可能变成冻结当前值的覆盖，失去未来跟随上游变化的语义。当前改价表单缺少这类原始标记。

**P2：不含税覆盖会意外降级为继承。** [ProductList.vue:294](/Users/mac/codespace/supplychainhubweb/src/views/supplier/distribution/distributor/detail/ProductList.vue:294) 不含税时清掉编码，提交仅在含税时发送 `taxRateCode`。于是 `taxInherited=false + taxIncluded=false` 的请求缺编码。[GenericPricingRequestMapper:98](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/pricing/GenericPricingRequestMapper.java:98) 将残缺税务组整体改为继承；若上游含税，用户选择的不含税覆盖不能保持。正确不含税覆盖须同时提交 `NON_TAXABLE`。

**接口能力缺口：目前没有可直接替换使用的分销商通用定价详情 GET。** 当前 `BuyerAdminController` 提供列表、保存和试算，平台 SKU 默认定价 GET 属于上游默认规则，不能用于读取分销商原始覆盖。仅要求前端“改用正确详情接口”不能形成可执行修复。

### 3.3 用户端隐藏边界

[GenericPricingCalculator:19](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/pricing/GenericPricingCalculator.java:19) 使用交叉相乘比较，展示用四位小数利润率不参与隐藏判定。供货价缺失或为零时先进入依赖缺失，不能按正常利润率解释。

[BuyerCatalogReadMapper.xml:42](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/mapper/catalog/BuyerCatalogReadMapper.xml:42) 的 `availableOnly=true` 条件要求定价状态 `READY`，排除 `MARGIN_HIDDEN`；AUTO 目录还验证来源版本及期限。[BuyerCatalogQueryService:43](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/BuyerCatalogQueryService.java:43) 中省略该参数按 false 处理。全量查询可能保留目录项，由返回可用性表达状态：AUTO 非 READY 按当前策略返回 UNKNOWN，MANUAL 非 READY 返回 UNAVAILABLE。最终是否从真实用户页面移除，仍取决于用户端查询参数、同步缓存和状态处理。

## 4. 本轮执行验证

### 4.1 真实前端组件复现：9 个场景通过

使用现有 SFC 编译与内存挂载工具执行真实 `ProductList.vue`、相关 TypeScript 模块及 API 包装，只替换 HTTP 和 UI 壳；没有向真实服务写入数据。

复现脚本见 [独立复现脚本](/Users/mac/.codex/visualizations/2026/10/13/01a13518-7020-7e52-8bde-d309b3e34983/distribution-hide-threshold-review.mjs)。执行命令：

```sh
node /Users/mac/.codex/visualizations/2026/10/13/01a13518-7020-7e52-8bde-d309b3e34983/distribution-hide-threshold-review.mjs
```

| 场景 | 实际结果 |
| --- | --- |
| 输入 2 后提交 | URL 为通用定价 PUT，`hideInherited=false`、分销阈值 2 |
| 旧列表列为 null，保存后重开再次确定 | 输入 undefined；下一次请求 `hideInherited=true`，不携带阈值 |
| 输入 0 | 保留显式覆盖 0，不误判为空 |
| 输入 2.25 | 原样提交 2.25 |
| 输入 100 | 原样提交 100 |
| 旧列为 7 | 回填 7，未从当前通用配置获取原值 |
| 文案为“自定义公式” | 百分比与运算数缺失 |
| 检查完整保存请求 | 销售价/服务费固定继承；销售区阈值未提交；分销价/税务固定声明覆盖 |
| 不含税编辑 | 缺少 `taxRateCode`，触发后端已确认的降级继承条件 |

HTTP 保存响应桩使用真实当前响应形状 `saveState=COMMITTED`；列表响应桩模拟源码中旧列为空的结果。上述证明组件构造及编辑行为，**不等于完成真实数据库事务集成验证**。Element UI 表单校验由壳替换，故没有用自定义公式场景证明空表单能够通过真实 UI 校验。

### 4.2 后端既有测试：54 项通过

```sh
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false \
  -Dtest=GenericPricingCalculatorTest,ProjectPricingConfigMergerTest,GenericPricingRequestMapperTest,GenericBatchPricingServiceTest,BuyerProductQueryServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：BUILD SUCCESS，54 项，失败/错误/跳过均为 0。分项为请求映射 7、商品列表 15、定价保存 23、计算器 6、继承合并 3。未运行其他模块单测。

既有列表测试验证了价格与规则文案，未验证隐藏阈值及五组配置无修改往返。因此通过不能证明本缺陷不存在。

### 4.3 前端既有相关测试：37 项通过

在当前前端仓库执行：

```sh
node --test tests/catalog-recalculate.test.mjs tests/catalog-save-result.test.mjs tests/product-pricing.test.mjs
```

37 项全部通过。这些测试覆盖目录提示、刷新和商品默认定价等相关能力，也未覆盖本次分销商品隐藏组的完整回显闭环。

本轮没有运行 MySQL/Liquibase IntegrationTest、完整 ContractTest/ArchitectureTest 或实机联调。业务实现没有修改，本轮测试用于复核现状，不构成修复发版门禁证据。

## 5. 修复方案比较

| 方案 | 做法 | 评估 |
| --- | --- | --- |
| A：列表返回当前版本完整编辑配置（推荐） | 复用列表已加载的当前版本，增加结构化原始覆盖、继承标记、当前版本及有效展示值；前端从结构化配置回填并保存完整五组 | 复用现有查询与保存路径，无新增表；覆盖阈值和同类配置丢失问题 |
| B：新增分销商品定价详情 GET | 打开弹窗时按 buyerCode + buyerProductCode 获取完整原始配置与有效快照，替换旧列回填 | 适合需要打开时重新读取最新版本的场景；新增控制器、归属/权限校验、加载/错误状态与契约测试。仍须修复完整请求构造 |
| C：只补一个阈值字段或镜像写旧列 | 让列表显示 2，再沿用旧表单 | 不能保持继承状态、销售区阈值与其他组；旧列还是整数。镜像旧列造成双事实来源，不建议采用 |

推荐 A，使用项目列表已经存在的 `ProjectPricingConfigView` 投影方式作为实现参考，见 [ProjectListingQueryService:333](/Users/mac/.codex/worktrees/bd7d/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/ProjectListingQueryService.java:333)。可复用结构模式，但分销商视图的继承来源须明确是商品默认，不能复用带项目语义的字段说明。

## 6. 推荐方案具体行为

### 6.1 后端契约

在分销商品列表追加当前定价视图（以下字段名为方案建议，实施前写入详细设计）：

- `currentBuyerPricingVersionId`、版本号、定价状态及不可售原因，来自定价身份及其当前版本。
- `buyerPricingConfig`：从当前版本 `override_json` 投影五组继承标记及原始覆盖对象，包括隐藏组两个原始阈值。
- `effectiveBuyerPricingConfig` 或明确命名的有效隐藏展示字段：来自同版本有效配置，用于只读说明继承后实际生效规则。
- `effectiveHideThresholdPercent`：只读计算结果。

不要把已有 `sourceBuyerPricingVersionId` 当作当前编辑版本编号：它表示自动目录商业快照引用的来源版本，目录未刷新时可能落后于定价身份。

保留旧 `displayThresholdPercent` 的兼容返回并明确旧语义；新编辑入口不再消费它，也不把它迁移成新的有效覆盖。完整原始配置不得被有效值替代。当前版本存在却配置缺失/损坏时，应明确报错或禁止编辑，不伪造全继承配置；历史无通用版本与真正未定价须显式标明，通过明确初始化操作进入通用定价。

本方案读取现有 `sc_*` 数据，不新增跨模块表访问、外部 RPC 或数据库 DDL。保存仍走现有定价事务及提交后目录刷新。

### 6.2 前端回填与请求构造

1. 表单从结构化公式回填，不解析 `pricingRule` 展示文案；完整保留基数、百分比、四种运算符和运算数。
2. 显式保存继承标记。继承的有效阈值可只读展示，但不能把有效数值填成覆盖并在下次保存时冻结。
3. 隐藏组覆盖保留两个原始阈值。页面只编辑分销区时，销售区原值仍随完整隐藏组提交；若销售区原值更高，应说明实际生效阈值，避免用户误以为只看分销区。
4. 空值、0、继承应分别表达。现有后端允许 `hideInherited=false + 两个阈值均 null`，代表该覆盖组无阈值；它与继承并非同一个状态。建议增加明确“继承商品默认隐藏规则”的开关，清空数字不自动切换继承方式。
5. 保存时以已读取的原始五组配置为底稿，只更新用户编辑的字段；未编辑的销售价、服务费、销售区阈值及继承标记原样保留。仍提交完整五组配置，不能依赖省略字段在当前 PUT 中自动保留旧值。
6. 不含税覆盖提交完整 `taxIncluded=false + taxRateCode=NON_TAXABLE`；继承税务则保持继承。其归一化不得依赖清空税率来表达。
7. 查询失败或配置不可还原时禁用确定；响应缺字段不能当成“默认继承”。保存响应仍区分已提交和目录刷新结果，网络不确定不自动重复保存。

“无修改再次保存”应保持业务配置等价；允许既有保存服务生成新的版本号，不要求版本主键相同。若跨管理员并发编辑需要防止陈旧覆盖，另行设计预期版本校验；仅携带版本编号并不能自动获得并发保护。本次最小修复不把新增并发契约列为前提。

### 6.3 文档、发布与历史数据

- 实施前更新 [通用定价详细设计 §6](/Users/mac/.codex/worktrees/bd7d/supply-hub/docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#6-管理端接口) 与管理端 API 设计 §6.3，明确原始配置/有效配置/旧字段边界及编辑提交语义，再修改代码。
- 将联调步骤与实际验收结果写入 `docs/集成测试.md`；源码、自动化和线上证据分别记录。
- 后端先以追加字段方式发布，再发布依赖新结构的前端；新前端连旧后端时须明确提示配置不可读取，不能退回旧列静默保存。
- 本次不需要批量改旧阈值或直接写历史版本 JSON。已被再次保存改成继承的记录须先核对版本历史；上一版存在 2 不能独立证明那次更改不是用户有意操作。确认要恢复后，使用正常定价保存生成新版本。

## 7. 修复验收门禁

| 验收类别 | 必须验证的场景 |
| --- | --- |
| 前端真实组件 | 保存 2 → 后端列表返回原始 2 → 重开显示 2 → 无修改保存仍为覆盖 2；旧列为 null 或 7 不影响新规则 |
| 原始隐藏组 | 0、2.25、100；分销 2/销售 5；分销 null/销售 5；覆盖双 null；继承上游非零。全部能区分原值、继承与有效最大阈值 |
| 配置无损往返 | 自定义公式四种运算与不同基数、继承分销价、覆盖销售价/服务费、不含税覆盖、继承税务。未编辑组业务等价 |
| 后端单测/契约 | 查询投影与真实 JSON 字段类型、权限及分销商归属；当前指针、损坏版本和无版本边界；保存映射完整性 |
| MySQL 集成 | 同事务发布/切换当前版本、失败回滚、历史版本不变、重新查询原始隐藏配置、继承上游变化后仍保持继承；原有锁与事务门禁 |
| 隐藏逻辑 | 供货价 10000 分、分销价 10199/10200/10201 分，阈值 2：分别隐藏/可售/可售；高精度边界仍按交叉相乘判断 |
| 目录与实机 | 分别查询 availableOnly=true/false，核对目录来源版本与刷新结果，验证实际用户端筛选/同步及阈值恢复后的重新可售 |
| 架构 | `SupplyChainModuleArchitectureTest`；新增接口若采用方案 B，必须具有正确 `@PreAuthorize` 和显式归属校验 |

## 8. 真实记录的只读核查方法

下面 SQL **本轮未执行**，仅用于取得指定商品当前规则的事实。参数必须由实际业务编号绑定；只连接供应链数据库。

```sql
SELECT b.buyer_code, bp.buyer_product_code,
       bp.display_threshold_percent AS legacy_display_threshold_percent,
       p.current_version_id, p.pricing_status,
       v.version_no, v.platform_pricing_version_id,
       JSON_EXTRACT(v.override_json, '$.hideInherited') AS hide_inherited,
       JSON_EXTRACT(v.override_json, '$.hideRule.distributionThresholdPercent') AS original_distribution_threshold,
       JSON_EXTRACT(v.override_json, '$.hideRule.salesThresholdPercent') AS original_sales_threshold,
       v.hide_rule_json, v.effective_hide_threshold_percent,
       v.distribution_price_cents, v.supply_price_cents,
       v.published_at, v.published_by
FROM sc_buyer b
JOIN sc_buyer_product bp ON bp.buyer_id = b.id
LEFT JOIN sc_buyer_sku_pricing p ON p.buyer_product_id = bp.id
LEFT JOIN sc_buyer_sku_pricing_version v ON v.id = p.current_version_id
WHERE b.buyer_code = :buyerCode
  AND bp.buyer_product_code = :buyerProductCode;
```

若当前版本已经继承，再按该定价身份的 `pricing_id` 查询历史版本的 `override_json`、发布时间及发布人，并与真实保存请求时间对应。由此才能判断“曾保存 2、后来被改成继承”是否发生于用户反馈的那条商品。

**本轮交付状态：独立复核、复现和方案已完成；仅增加报告及复现材料，未修改业务实现、未提交或推送、未修复线上记录。**
