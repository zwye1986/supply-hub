# 标准共享受理、身份清理与项目永久键实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: subagent-driven-development，每任务独立规格→质量审查，禁止并发实施者。Task3完成后实施Task4；同一任务可按TDD步骤拆中文提交，不能留不编译中间状态。

**目标：** 复用既有标准受理完成可信项目下单核心，移除标准buyerUserId及外部版本/支付依赖，落实项目幂等、实际手机限购、内部备注与安全历史迁移。

**依据：** `docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md`全文，上位标准HTTP§3—7。实际环境操作仍暂缓，本任务实施代码/自动化。

## Task 4：标准受理核心及052迁移

### 文件职责与允许范围

API `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/`：

- `api/fulfillment/dto/AcceptFulfillmentReqDTO`删除buyerUserId/listingVersion/pricingQuoteToken/paidAt，保留真实内部source/tenant/projectCode及最小业务字段/CAS。
- 标准查询/资产RPC DTO删除用户，补真实projectCode，维护API及`BuyerFulfillmentContractTest`；不要改INSTORE/O2O DTO/scope。
- 标准下单封闭响应DTO提供稳定requestId和真实orderStatus，不把旧内部supplier字段暴露给HTTP。

Server `.../src/main/java/com/aifuli/module/supplychain/`：

- `application/facade/BuyerFulfillmentCommandFacade`保留内部身份适配，新增/拆`ProjectStandardOrderCommandFacade`可信项目适配；共用目标规范化/指纹/加密与受理编排，无DAL、无伪造DTO身份。
- `border/fulfillment/application/FulfillmentAcceptanceLifecycle` PreparedAcceptance无用户/支付/客户端版本；project key claim/CAS、Listing服务端版、项目商品/永久quote归属、真实snapshot，保留事务/资金/Outbox/频率原锁序。
- receipt Mapper/DO新project key/nullable server version/fingerprint version/冻结身份，shared orderDO/Mapper/XML实际手机号聚合与remark；`SkuPurchaseLimitService`不依赖用户、每单与phoneBind仍独立。
- `border/order/application/OrderViewQueryService`随资产DO删列移除asset.buyerUserId读取及HMAC用户摘要，标准资产审计NULL、真实ADMIN_USER/project/order，保留其他业务共享响应和现有管理端权限；相应既有查询测试回归。
- 标准`BuyerFulfillmentQueryFacade/Service`改真实RPC项目归属，与Task2项目读视图/解码/独立审计核心共享，不伪造StandardProductContext应用身份；RPC principal真实BUYER_SERVICE。
- `SupplierOrderLifecycle.persistAssets`去除user拷贝，assetDO无用户。所有STANDARD_REQUEST首次/重路由subjectKey=global orderNo，检索全部恢复调用点，不只改首次。
- 新052 SQL/主changelog HALT前置条件、生成列两类唯一键/用户清理/字段约束/hash格式0→2迁移门禁。
- 新`StandardOrderIdentityMigrationService`离线实际target/指纹/手机覆盖，既有RechargeFrequencyMigrationService/门禁协调增加覆盖；真实证据核验和原频率FIFO/SEND/资金保护保留，不新增force或自动开放。052将已存READY重新CLOSED；完整预检才事务恢复READY。
- 相关纯域确定性fingerprint policy/安全迁移原因/单元/真实MySQL/Contract；所有旧fixture因删除字段无法编译时机械修改但保留原独立业务断言，列额外文件，不删测试规避。

### TDD实施顺序

- [ ] RED：旧受理不带user/version/token/paidAt会拒绝；旧项目同号串单/异参竞争盲回放/requestId不稳定/真实orderStatus被PROCESSING覆盖/普通拒绝重放不当重试等，用可编译行为断言，missing类编译失败不是RED。
- [ ] API契约及可信两适配器、无用户PreparedAcceptance/target规范化；金额/项目档案快照真实，不伪造字段。
- [ ] fingerprint格式2固定顺序确定性UTF8、quote独立固化，已有回执先按冻结metadata比較；accepted配置变化重放，不从当前商品重新选择target/schema。nonce/CAS/version变动不改外部身份，原业务号大小写变体DB同键但字段变动仍可冲突。
- [ ] 052有真实HALT preconditions先于DROP/UPDATE，项目唯一＋legacy生成列保持共享场景唯一；CARD user NULL、asset/receipt删除列、remark/真实目标type、新fingerprint版本与nullable版本等符合设计/COMMENT。
- [ ] claim项目唯一键竞争重探测校验指纹，非业务唯一键数据库故障传播；PROCESSING恢复、普通终态重放、retryable仅当前CAS一次，不能要求新版Listing请求字段。
- [ ] accept同事务锁当前可售同身份Listing/Schema和首次报价，缺报价明确可重试拒绝不重定价；真实total/request/BO/fund/quote/Outbox/frequency全回滚。原MOBILE READY gate仍在，accepted replay gateclosed可读。
- [ ] 实际phone每人周期聚合＋SKU锁；nonmobile/card配置启用则skip内部remark一次，无responsehint。每单与绑定手机号分别执行；原失败/取消/fullrefund释放保留。
- [ ] legacy标准查询RPC移除user并可信项目绑定、audit principal不伪造app；asset生产不复制user；全repo检索标准consumer，保留共享INSTORE/O2O真实用户。
- [ ] 首次/恢复/重路由global orderNo subject，旧round按order关联迁移无新冲突，不影响其他subject。
- [ ] 离线覆盖全部CARD实际target及有效历史限购（包括成功/LIFETIME），既有crypto+Aad/schema验证、字节清零/SYSTEM_WORKER审计；可信accepted历史重建格式2，NULL项目/未建历史未知保持locked。完整覆盖及队列+READY同TX，failure全回滚且CLOSED独立已提交，真实证据缺失拒绝。实际执行仍待办。

### 必须运行的自动化范围

- [ ] API BuyerFulfillmentContractTest + Task2标准响应Contract + StandardPricingQuoteContract，核对删除字段及新项目绑定。
- [ ] 既有受理/查询/资产生产/限购/指纹/路由/频率逻辑单测和27架构；新的指纹/历史迁移/同项目幂等单测。
- [ ] 真MySQL FulfillmentAcceptanceIntegrationTest、RechargeFrequencyAcceptanceIntegrationTest及新增ProjectStandardAcceptanceIntegrationTest：同键只一单、同buyer两个project同号两单、异参竞争不误重放、CAS只一次、版变更重放/retry、stable receiptNo/orderNo/真实状态、缺quote不定价、rollback所有持久化副作用。补Task2在052之前不能正向存同号的项目查询/资产测试。
- [ ] 限购真实Mapper并发/上海周期/LIFETIME/释放/nonmobile备注/独立phoneBind；脚本不能只countMockito当数据库证据。
- [ ] 052实际Liquibase从051基线升级合法历史/非法唯一或routing关联HALT且旧数据没改；空库＋二次启动；CARD/非CARD/历史NULL生成键/CHECK/COMMENT；旧用户副本和format0安全标记、新rowformat2。
- [ ] StandardOrderIdentityMigrationIntegrationTest 与既有频率离线MigrationIntegrationTest全部回归：历史成功手机覆盖/未知目标拒绝/缺deploymentport拒绝/全批store或auditfault rollback/不能提早READY、幂等无重发与资金变动、旧frozen身份与key一致性。
- [ ] 共用sc_order的INSTORE/O2O受理/summary/refund/幂等真实tests保留；除本需求必要字段/SQL外不改其业务契约。

Java21、Maven串行。Test/Contract/Integration/Architecture显式选实际现有类，新类按真实类名加入；仅跳过SC之外模块测试，不-DskipTests。API Contract命名需独立-Dtest指定；真实测试用-Psupply-chain-integration和-it.test。最后再运行全供应链单元/Contract/真实Integration gate，不把重复定向测试累计当去重全仓数。

证据`/tmp/standard-acceptance-project-key`：命令、baseline/sourcecommits、真实RED/GREEN新鲜XML/hash/log、DDL旧SQL基线hash，记录所有阶段范围。不持久化测试随机32B身份key，不输出手机号/目标/摘要/密文/凭证。

- [ ] 自审重要中文注释/设计链接，DO与DDLCOMMENT一致，无魔法值/吞异常/上下文缺失TODO，仅owned源/测试/DDL中文提交，不暂存rootdocs。
- [ ] 独立规格通过后质量审查，原实施者fix并复评；root更新计划/集成记录。后续Task4前端说明与Task5HTTP保护/闭环，不提前宣称上线。

## Task 4前端配套：每人限购适用范围说明

在后端Task4两级审查通过后，使用实际前端worktree`/Users/mac/.codex/worktrees/project-app-access/supplychainhubweb`，只修改`src/views/supplier/product/list/form/index.vue`和`detail/index.vue`对应每人限购解释：只对实际手机号直充有效，卡券和非手机号直充忽略且订单内部备注。保留原开关/数量/周期和所有类型可配置；不自动清空或提交新字段。

这是可逆文案配套，不新增镜像单测；按两SFC实际运行ESLint/Prettier/构建或已有最小gate，检查改动没有类型新增诊断，不用全仓既有类型错误声称已全绿。中文commit，两级读代码审查；实际登录页面验收仍低优先级未执行。root更新记录后进入最终标准HTTP Controller/Gateway/日志/端到端自动化任务。
