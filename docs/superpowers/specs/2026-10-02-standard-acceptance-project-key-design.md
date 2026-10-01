# 标准受理的项目永久键、身份清理与手机号限购实施设计

日期：2026-10-02。状态：承接已确认业务契约的实现细化，待报价Task3完成后实施。

依据`2026-09-30-project-signed-standard-order-http-design.md` §3—§7，以及`2026-10-02-standard-internal-quote-freeze-design.md`。沿既有FulfillmentAcceptanceLifecycle、RechargeFrequencyAcceptanceCoordinator、资金应用服务、路由/履约/Outbox推进，不复制状态机。本文件只讨论代码和自动化门禁；实际环境迁移与离线核实仍暂缓。

## 1. 双入口与共享命令

外部项目HTTP入口取认证projectId/buyerId，不接受source/tenant/projectCode等身份参数；项目/分销商只读档案服务提供真实名称/编码快照。内部RPC仍以真实sourceSystem/externalTenantId解析buyer，再按projectCode解析项目。两个适配器分别构造同一种可信标准受理命令，不能用假source/tenant/appId/user/paidAt模拟另一入口。

标准内部AcceptFulfillmentReqDTO删除buyerUserId、listingVersion、pricingQuoteToken、paidAt，保留真实内部身份字段、buyerOrderNo/buyerSkuCode/quantity/target/previousReceiptVersion；本期quantity=1。外部DTO只保留主设计§3四必填与可选CAS。PreparedAcceptance删除上述用户/支付/调用方商品版本字段；本次服务端版本、商品身份、冻结报价id、目标Schema/摘要/密文是独立服务端快照，禁止以版本1或假时间占位。

标准内部查单/回执DTO补真实projectCode绑定；资产DTO删除buyerUserId，项目在RPC服务端由真实来源与projectCode校验。内部资产访问主体保持BUYER_SERVICE及真实source:tenant，不能构造假的PROJECT_APPLICATION/keyVersion；共用项目归属快照和解码/独立审计核心，audit appKeyVersion=null。内部原scope如保留，仍真实校验，HTTP不增加该scope。INSTORE/O2O身份DTO、HMACscope及共享订单真实用户字段继续保留。

## 2. 首次身份与重放优先

Facade先通过项目永久键读取已有回执的不可变快照。已有格式已验证回执使用其冻结商品/目标Schema构造规范化输入，不因为当前Listing下架/版本变动而重建用户请求身份；先比较请求身份，再决定只回放或CAS重试。旧回执格式未验证时失败关闭，不能按新格式直接比较旧hash或重新建单。

无已有回执时按该项目商品关系解析buyerSkuCode→平台SKU/目标Schema，目标规范化、封闭验证、加密在数据库受理事务外完成。项目商品必须属于认证buyer/project并与首次报价listingId/platformSkuId一致。当前项目Listing及分销Listing发布快照在实际受理事务中锁定校验，业务版本由服务端取得；没有可解析版本时回执version字段nullable，不人为补1。

手机号规范化始终只处理target.accountType=MOBILE的实际值；卡券和非手机号账号不补出用户身份。保持既有目标AAD `buyerId|buyerOrderNo|schemaCode|schemaVersion`，以引用链隔离不同项目；新目标摘要仍用既有专用HMAC用途，旧密文解密不改AAD。

外部不变量采用格式2：项目、分销商、原始buyerOrderNo、buyerSkuCode、服务端冻结商品身份（可解析时）、quantity、冻结目标Schema/版本与规范化目标摘要。nonce/当前Listing业务版本/CAS/app密钥版本/内部token/paidAt不参与。实现用固定字段顺序和UTF8确定性编码，字段须长度分隔或准确JSON转义，不用不分隔的字符串拼接；同一算法供新请求及可信历史重建调用。quoteId独立固化，不能让HTTP请求自选报价；同永久项目键只能使用首次报价。

回执request_fingerprint/invariant_fingerprint明确改为无用户的规范化请求格式，服务端尝试版本等保存在单独列。总订单requestFingerprint、buyerOrderDigest也移除用户成分，后者仅对项目＋订单业务键形成不可逆摘要。原始规范化JSON只包含真实target与上述字段，不留购买人身份副本。

## 3. 回执竞争、重试和真实返回

claim按projectId+buyerOrderNo非锁定首次探测＋唯一键裁决创建，已有回执再行锁。只有实际永久键竞争才重探测，不能捕获任意DataIntegrityViolation后盲回放；重探测仍比较不变量，异参并发必须幂等冲突。非该唯一键的数据库故障保留cause传播。

- PROCESSING相同身份：允许继续原尝试，实际accept行锁保证只建一单；不同身份冲突。
- ACCEPTED/REJECTED_FINAL相同身份：只回放，不重路由/预占/追加备注。accepted传CAS也不能开启新下单。
- REJECTED_RETRYABLE普通重发：只回放；携带当前positive previousReceiptVersion才CAS重开，不能要求调用方更换Listing版本。CAS失败返回原状态或稳定版本冲突；同一CAS并发只一新尝试。

条件重试固定原target和已冻结quoteId；读当前可售同商品兼容Schema，可以采用新的服务端Listing版本。缺首次报价明确业务拒绝且可在内部形成首次报价后按CAS重新尝试，不能下单时定价；首次已有报价的拒绝/重试不改价格或绑定。商品身份变更、目标变化及最终拒绝需新订单号。

受理保留原锁顺序及频率门禁：MOBILE写回执/入队/SEND都核验既有READY密钥覆盖，临时门禁关闭不固化为终拒；ACCEPTED相同身份重放在门禁关闭仍可读回执。资金金额取永久报价快照，消费/订单/请求/路由/资金/队列/Outbox在同一供应链事务。业务拒绝按原独立reject收敛，系统故障不能虚构已受理。

AcceptanceSnapshot包含实际receiptNo和真实orderStatus；首次与重放的requestId都返回实际receiptNo，不能每次生成新的requestId冒充稳定回执号。重放返回当前实际订单状态，不能永远写PROCESSING。外部响应不传supplier/内部数字id/fulfillmentRequestNo/备注；orderNo使用稳定字符串。未建字段显式null。

## 4. 手机号限购与内部备注

SkuPurchaseLimitService移除buyerUserId参数；以已验证的targetAccountType和normalizedMobile作为输入。每订单规则始终独立；每人周期规则只有实际MOBILE值可用时统计`CARD,projectId,platformSkuId,fulfillment_account_value,period`，不从buyer_user_id回退。现有SQL金额/数量聚合用真实Mapper，状态排除FAILED/CANCELLED/FULLY_REFUNDED沿原规则，周期按上海自然时间含LIFETIME。

卡券/EXTERNAL_ACCOUNT在开启每人限购时跳过该规则，成功建单内部remark追加一次“该商品无可用手机号，本单未执行每人每周期限购”，未开启不写；同TX、重放/重试不重复，不覆盖别的备注。不能把此规则skip扩散到每单或绑定手机号限购；绑定手机号规则仍缺手机明确拒绝。响应不说明skip。

新订单冻结实际targetAccountType及仅MOBILE的fulfillmentAccountValue，独立字段代表履约目标，不是购买人身份。平台SKU锁串行限购校验＋订单插入，真实MySQL并发不能超额；不得只把查询换手机号而放开原锁。

管理端前端实际仓库`/Users/mac/.codex/worktrees/project-app-access/supplychainhubweb`，`src/views/supplier/product/list/form/index.vue`、`detail/index.vue`增加“每人周期限购仅对实际手机号直充有效，卡券及非手机号直充忽略并记录订单备注”的配置说明。保留配置可编辑，不擅自禁用/清空卡券或非手机号商品的规则。

## 5. 052增量、历史清理与安全覆盖

新`052-standard-order-project-key.sql`，不改004/032/033/035。执行前HALT校验重复项目键、旧唯一键的保留场景、路由轮次映射冲突及孤立关联；破坏性DDL前完成检查。

- receipt删除buyer_user_id；project永久唯一键，NULL项目生成legacy_buyer_key保留历史buyer+buyerOrderNo唯一性。listing_version_no可空、COMMENT“服务端解析的本次尝试商品业务版本号”，CHECK允许NULL或>=1。新增fingerprint_version（0历史未验证、2新规范化格式）、target_schema_version nullable、platform_sku_id nullable、pricing_quote_id nullable等不可变绑定；没有真实解析值时不补假数据。
- order的buyer_user_id可空，CHECK要求CARD为NULL，INSTORE/O2O继续真实写入；清NULL所有历史CARD。项目CARD生成standard_project_key唯一(project,buyerOrderNo)，其余订单/历史NULL项目生成legacy_buyer_key唯一(buyer,buyerOrderNo)，保留旧场景唯一性。新增remark VARCHAR(1024) NULL COMMENT“中台订单内部业务备注，不向项目接口返回”，实际fulfillment_account_type nullable并COMMENT说明真实目标类型。
- asset标准专用buyer_user_id及其依赖索引删除；SupplierOrderLifecycle生产资产不再从共享总单复制购买人。审计标准旧用户摘要清NULL，不改INSTORE/O2O其他真实身份持久化。报价用户列由051删除。
- 每人限购索引替换为实际目标手机号；两个独立规则可以共享准确列索引，不保留user索引。旧手机列只经已验证加密target确认，不把旧购买人手机号当充值账号。
- STANDARD_REQUEST路由subjectKey统一全局orderNo，历史按round.order_id严格关联CARD总单转换；先检测新唯一键冲突再改，不改O2O/INSTORE其他subjectType。所有首次/重路由/恢复代码读取同一全局键。

历史hash含用户输入，不能把它标记成格式2。052将其置为不可用的格式0历史标记（不含用户字段的不可逆迁移标识），同时清理共享CARD订单的旧请求hash/业务digest。历史可核验记录通过下节离线应用流程重新生成格式2；无法验证的NULL项目/未建单旧回执继续只读，禁止条件重试或新建同键订单。新写入直接格式2。

### 5.1 离线应用迁移与实际手机号覆盖

提供无HTTP/Timer入口的`StandardOrderIdentityMigrationService`，复用受控离线部署的真实quiescence证据与供应链事务。它由现有RechargeFrequencyMigrationService在closeBeforePreflight后、publishReady前协调调用；052升级将既有频率覆盖门禁重新置CLOSED，不能继承旧READY而绕过新历史手机号覆盖。实际运行继续列待办，自动化实现安全验证。

按CARD总单完整关联核验receipt/request/quote，解密并校验真实target的AAD、Schema/版本及封闭字段，恢复实际accountType及规范化MOBILE。任何有效限购历史（含已成功与LIFETIME）不能因不是限频活跃候选而遗漏；不可确认的目标/关联阻断覆盖，不能仅扫描当前占用。验证完整批后才更新手机快照/新指纹；历史非手机号和卡券明确排除每人限购，而非以NULL误认已验证。

已接受且project/商品/目标/报价可核验的回执，从真实请求与报价重建格式2及冻结绑定，关联总单hash同步；首次拒绝/未建单若没有可信目标Schema/报价关联，不开放条件重试，不靠当前SKU补历史语义。未建单孤立旧回执仅保留查询进度/拒绝。旧NULL项目不从source映射推测归属。

目标解密记录真实SYSTEM_WORKER敏感审计，无原始明文/用户占位；字节清零，安全原因与cause保留。离线记录更新、已完成的所有覆盖与READY在同TX提交，任意存储/解密/审计/证据故障全回滚，门禁保持独立已提交CLOSED。幂等再次执行不改已冻结目标/quote/原频率序号/资金，也不新建SEND操作。新HTTP不会绕开这一门禁。

## 6. 门禁与边界

自动化必须覆盖新RPC字段闭包及HTTP共享命令无用户/支付/外部版本；真实MySQL同键并发一单、不同项目同号两单、异参竞争冲突、条件CAS一次/普通拒绝重放、Listing变动accepted重放和retry、首次报价消费/资金/Outbox/频率队列故障全回滚；回执requestId稳定和真实orderStatus。

限购覆盖上海周期边界/LIFETIME、MOBILE规范化/项目隔离/并发超额、失败取消全退释放、卡券与外部账号跳过且备注一次、独立phoneBind规则、实际front表单说明。迁移真实MySQL/Liquibase验证双唯一键、共享非CARD原唯一性、CHECK/COMMENT、旧用户列删除与无身份副本、新hash版本、路由subject迁移、未知历史锁闭、非活跃成功限购历史覆盖及整批失败回滚/READY不可提前打开。

SupplyChainModuleArchitectureTest与既有INSTORE/O2O受理/查询回归必须保留。代码自审→独立规格→质量审查；执行证据和前置条件记录`docs/集成测试.md`。本阶段不开放Controller/Gateway，之后还须严格HTTP解析、原始字节透传、全链敏感日志/no-store及完整自动化HTTP闭环。
