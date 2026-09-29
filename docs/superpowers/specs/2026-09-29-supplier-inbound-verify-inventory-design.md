# 供应商入站验签与库存查询配置设计（2026-09-29）

## 1. 目标与边界

本文覆盖供应商订单状态回调的身份校验、事实去重与订单收敛，以及供应商库存查询接口的定时刷新和路由使用。库存通常由中台查询，**不把供应商主动推送库存设为通用接入要求**。项目应用与平台公钥见《2026-09-29-project-app-issuance-platform-signing-callback-design.md》，后端落点见《2026-09-29-access-config-backend-impl-design.md》。cardshop/cardshop-trade-engine 只作旧业务参考；不接入、不继承 V1 协议。供应链只访问自身 sc_* 表。

已有 QnmCallbackParser 可验签解析奇那梦报文，但还没有回调 Controller 和订单收敛接线。sc_inventory_snapshot 已建表，当前只可创建 REMOTE_ON_DEMAND 来源，订单受理向路由传入空事实，路由供给解析也只接受 REMOTE_ON_DEMAND。这些是本设计明确要补的缺口。

## 2. 接入原则

1. **入站协议由订单冻结的连接器版本决定**。奇那梦继续使用其原生共享密钥 MD5 验签；新标准协议采用供应商私钥签名、中台用供应商公钥验签。不得强迫既有供应商改协议。
2. 出站调用秘密仍由 sc_credential_slot.secret_ref 引用；入站公钥可放连接器版本的封闭 adapter_config_json。轮换入站公钥或共享密钥需发布新连接器版本；旧订单使用冻结版本和可用的旧材料。旧 env 引用不得原地覆盖后立即删除，须保留到版本回调验收截止。声明 RECEIVE_CALLBACK 的连接器必须按供应商合同配置 callback_retention_seconds，不在设计中猜测统一天数。
3. 供应商回调是外部事实，不直接修改订单表。验签通过后先去重并冻结状态映射，再在同一短事务内运行状态机。重复投递成功确认；状态冲突和迟到成功留异常审计，不覆盖有效终态。
4. QUERY_INVENTORY 只对真正实现查询适配器的连接器版本开放。奇那梦一期能力集不含 QUERY_INVENTORY，仍用 REMOTE_ON_DEMAND。
5. 远程库存只是一份带时效的观察事实，不预占、不扣减。刷新定时执行，路由只读快照；未知或过期不等于零库存。

## 3. 供应商订单回调

### 3.1 入口与冻结版本解析

HTTP 入口为 POST /supply-chain/callback/{connectorNo}/**。对外通知 URL 由受控公网基础域名、该固定前缀、connectorNo 和连接器版本 callback_path 组成；创建供应商订单时冻结该版本，不能拼接管理端任意绝对 URL。接口在供应链服务 aifuli.security.permit-all-urls 和 Gateway 路由中精确放行登录态要求，随后执行协议验签；限流、请求体大小限制和敏感字段日志遮蔽为入口必备条件。

部署切换前检查历史版本实际发给供应商的 notifyurl。若已有在途订单使用旧路径，应为该旧路径注册受限的兼容入口，仍按 billno 定位冻结版本验签，直至其回调验收截止；不得仅改变新版本 URL 后使旧路径返回 404。历史订单若从未配置 notifyurl，则继续靠原查单恢复，不能假定可收到回调。

处理顺序：

1. 按 connectorNo 定位连接器及供应商。对未认证报文只做限长的结构预解析，取中台发给供应商的 billno；不得据此修改业务。
2. 以 (supplier_id, billno) 定位 BOrder，读取其 connector_version_id、供应商及原始履约操作。billno 不存在时返回协议约定失败响应并做限频安全审计；没有关联订单，不能伪造 sc_fulfillment_exception_case。
3. 使用 **BOrder 冻结的版本**核对 connector_id、adapter_type 和 callback_path，并取得该版本的验签材料。该版本即使不再是 current，仍允许在回调保留窗口内用于已创建订单；状态为 RETIRED 时还须早于 callback_accept_until。已退役版本不能用于新下单。材料不可用时失败关闭并告警，不回退到当前版本。
4. 由对应协议验签器验证原始报文，再解析外部状态、外部订单号、金额和资产。核对回调外部订单号与已知订单信息；冲突进入异常处理，不改订单。
5. 从冻结版本的状态映射表解析标准事实并取得真实 mapping_definition_id。验签解析器不能自行绕过封闭映射直接决定最终状态。

若协议本身无法从报文取得 billno，则该适配器必须定义另一种可唯一定位冻结订单的索引；没有此能力不得发布 RECEIVE_CALLBACK。回调响应格式和状态码由适配器协议测试固定，奇那梦 success/fail 文本需以供应商协议样例验证，不凭推测写死。

连接器发布新版本不会自动退役旧版本。显式退役旧版本时，若仍有非终态 BOrder 引用则拒绝；否则将 callback_accept_until 设为所有引用该版本的 BOrder 的 completed_at + callback_retention_seconds 的最大值。此时仅允许处理这些已存在订单的迟到事实；截止后才能删除旧密钥引用。既有已发布版本若缺少合同窗口值，应先由受审计迁移补齐，再允许退役，不能用隐含默认值代替。

### 3.2 标准协议与原生协议

奇那梦复用 QnmSignUtils 和 QnmCallbackParser，但解析结果须额外暴露原始状态码，使其经过冻结的状态映射。共享密钥从冻结版本引用的 CredentialSlot 解析。标准协议连接器的 inboundVerify 封闭 Schema 包含 algorithm=SHA256withRSA、keyVersion、publicKeyPem 和 allowedMessageTypes；发布时校验密钥格式、允许类型和一份带已知签名的契约样例。仅当注册了实际 AdapterMetadata、解析器和所声明的能力实现后，才可发布 STANDARD_V1；不得配置一个只有验签器而无业务适配器的占位连接器。

标准协议头为 X-Aifui-Key-Version、X-Aifui-Timestamp、X-Aifui-Signature、X-Supplier-Message-Type。eventId 和 billNo 为正文必填。时间戳为 Unix 毫秒，容差 ±5 分钟。签名串按以下八行以 LF 连接，无末尾换行，对原始 bodyBytes 求摘要并用 SHA256withRSA 验签：

~~~text
SC-SUPPLIER-CALLBACK-V1
connectorNo
normalizedCallbackPath
keyVersion
messageType
timestamp
eventId
lowercaseHex(SHA-256(bodyBytes))
~~~

eventId 从正文读取，验签前仅作限长结构预解析；验签后严格校验头、正文和配置的版本及消息类型。绑定 connectorNo、路径和消息类型，防止同一密钥跨连接器或路径复用。重复 eventId 由持久化事实唯一键处理，不额外要求 nonce。原生协议若无时间戳，仍按其协议验签和事实去重，不能假装满足标准协议时效校验。

### 3.3 去重、状态机与迟到事实

定义由已验签事实生成的 observation_key，写库格式固定为 CB: + lowercaseHex(SHA-256(规范化键材料))，长度 67，小于既有 VARCHAR(128)。标准协议键材料为 connector_version_id + eventId（eventId 限长 64）；奇那梦为 connector_version_id + billno + 原始状态 + 金额/卡密的稳定摘要，不含签名、时间戳和可变重试字段。标准协议同一 eventId 重投必须保持业务正文语义一致；若键相同而事实摘要不同，按协议冲突记录异常并拒绝，不能当正常重复吞掉。先按 BOrder 找到**原始供应商创建/发码操作**，不能简单选择 BOrder 下最新的 EXECUTING/RESULT_UNKNOWN 操作，因为那可能是查单操作。必要时新增稳定的回调目标操作引用。

一个 supplyChainTransactionManager 短事务完成：插入 sc_raw_status_observation（含真实 mapping_definition_id）→ 唯一冲突时读取既有摘要，仅相同事实返回 DUPLICATE → 锁定相关操作/BOrder → 运行收敛决策。观察事实的插入去重与状态机必须在同一事务；不得先提交事实后因状态事务失败而永久跳过重试。回调入口不要再调用会重复插入观察事实的原 SupplierOrderLifecycle.transition 路径，应抽出接收已持久化事实的收敛入口。唯一键冲突必须被识别为幂等或协议冲突，不能变成 HTTP 500。

终态同事实重投只确认；终态收到相矛盾的新事实时追加观察并开 sc_fulfillment_exception_case。迟到成功若携带卡密，卡密进入隔离/人工核对，不能自动二次发码、扣款或释放资金；遵循现有订单核心设计的资金终态矩阵。无法匹配订单的报文只做限频安全审计与指标，不创建需要订单主键的异常单。完成数据库提交后才向供应商返回协议成功应答；事务失败让对方重试。

## 4. 远程观察库存来源

连接器版本还需新增两个供应链字段，供 §3.1 的退役判定使用：

~~~sql
ALTER TABLE sc_supplier_connector_version
    ADD COLUMN callback_retention_seconds INT NULL COMMENT '供应商合同约定的最大回调延迟，秒；声明RECEIVE_CALLBACK时必填',
    ADD COLUMN callback_accept_until DATETIME(3) NULL COMMENT '已退役版本对既有订单的回调验收截止时间，Asia/Shanghai',
    ADD CONSTRAINT ck_sc_connector_callback_retention CHECK (
        callback_retention_seconds IS NULL OR callback_retention_seconds > 0);
~~~

新草稿声明 RECEIVE_CALLBACK 时由管理端必填 callback_retention_seconds，并在验证、发布时复核；历史已发布版本通过带审计的迁移或管理命令补齐，不直接修改其他已发布协议配置。

管理端新增 POST /supply-chain/supplier-config/inventory-sources/remote-observed。入参为 supplierCode、platformSkuCode、**queryConnectorNo**、externalSourceCode、refreshIntervalSeconds、snapshotTtlSeconds。创建时校验连接器属于该供应商、当前发布版本已实现 QUERY_INVENTORY，且该 SKU 能通过适配器映射到供应商库存来源。来源显式绑定稳定 query_connector_id；定时刷新解析其当前发布版本。连接器发布新版本时，若存在启用的观察来源，新版本仍须声明并实现 QUERY_INVENTORY，否则拒绝发布；来源如需换连接器，通过受审计的来源配置版本或新来源迁移。现有启用接口继续适用。

SupplierOfferPublicationService.publish 目前只接受 REMOTE_ON_DEMAND，须扩展为接受启用的 REMOTE_OBSERVED；同时校验 Offer 的供应商/SKU 与来源一致，查询连接器属于同一供应商且仍有 QUERY_INVENTORY。库存查询连接器可与履约连接器不同，两者都必须在发布时明确验证，不从供应商“当前任意连接器”推断。

新增 Liquibase 列如下；DATE/DATETIME 均遵循数据库 Asia/Shanghai 本地时间：

~~~sql
ALTER TABLE sc_inventory_source
    ADD COLUMN query_connector_id BIGINT NULL COMMENT '远程观察库存查询连接器内部主键，逻辑引用sc_supplier_connector.id',
    ADD COLUMN refresh_interval_seconds INT NULL COMMENT '远程观察来源刷新间隔，秒',
    ADD COLUMN snapshot_ttl_seconds INT NULL COMMENT '远程观察快照有效期，秒',
    ADD COLUMN next_refresh_at DATETIME(3) NULL COMMENT '下次计划刷新时间，Asia/Shanghai',
    ADD COLUMN refresh_lease_owner VARCHAR(128) NULL COMMENT '当前刷新任务租约持有者',
    ADD COLUMN refresh_lease_until DATETIME(3) NULL COMMENT '当前刷新任务租约截止时间，Asia/Shanghai',
    ADD COLUMN refresh_fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '刷新认领的防旧执行者令牌',
    ADD KEY idx_sc_inventory_source_refresh (source_type, status, next_refresh_at, refresh_lease_until),
    ADD CONSTRAINT ck_sc_inventory_source_remote_observed CHECK (
        source_type <> 'REMOTE_OBSERVED'
        OR (query_connector_id IS NOT NULL
            AND refresh_interval_seconds IS NOT NULL AND refresh_interval_seconds >= 60
            AND snapshot_ttl_seconds IS NOT NULL AND snapshot_ttl_seconds >= 60
            AND next_refresh_at IS NOT NULL));
~~~

迁移执行前检查现有 REMOTE_OBSERVED 行：若已有人工写入而缺少查询连接器、周期或 TTL，先完成受审计回填再加 CHECK 约束。CHECK 显式校验非 NULL，防止 MySQL 将 UNKNOWN 当作通过；启用来源时应用层还要复核这些字段。

多个供应链进程实例可同时运行刷新扫描器，但必须由短事务按来源抢占租约并递增 fencing_token。对供应商的 HTTP 查询在事务外执行；成功或失败写快照和推进 next_refresh_at 时校验租约持有者及 fencing_token。失联任务由租约到期重新认领，旧执行者的迟到结果不得覆盖更新鲜的调度状态。连接器限流和并发上限同时约束刷新任务，不允许每个实例各自无协调调用供应商。连续失败不自动禁用来源；按来源和错误码聚合告警。

observed_at 采用中台收到查询结果的数据库时间，供应商时间可另作诊断，不能让供应商未来/旧时间改变 TTL。valid_until = observed_at + snapshot_ttl_seconds。查询失败追加 ERROR 快照，其 raw_digest 为脱敏错误码/响应摘要的 SHA-256，不能为 NULL；没有数量时 observed_quantity 为 NULL。供应商明确返回数量 0 规范化为 UNAVAILABLE，正数为 AVAILABLE；若供应商只返回可用/不可用状态则数量可为 NULL。矛盾状态与数量拒绝或规范化后记审计。奇那梦不配置此类来源。

## 5. 路由读取与业务含义

路由先解析 OfferVersion 和它引用的 InventorySource，再按 **inventory_source_id** 读取最新快照，不能只按 supplierCode 缓存库存事实；同一供应商的不同 SKU/来源可能库存不同。REMOTE_ON_DEMAND 无快照信号，也不产生虚假本地预占。REMOTE_OBSERVED 将最新快照转为既有 RoutingInput.Fact，复用 REQUIRED/OPTIONAL/UNSUPPORTED、inventoryMaxAgeSeconds 及 valid_until 的判定，不能另写一套绕过路由规则的 InventorySignal。

| 最新快照 | 路由事实 |
| --- | --- |
| 未过期 AVAILABLE，数量为空或足够本单需求 | AVAILABLE |
| 未过期 AVAILABLE，但已知数量少于本单需求 | UNAVAILABLE/INSUFFICIENT |
| 未过期 UNAVAILABLE | UNAVAILABLE |
| UNKNOWN、ERROR、无快照或过期 | UNKNOWN；REQUIRED 拒绝，OPTIONAL 保留并标记未知，UNSUPPORTED 不读取 |

过期的 UNAVAILABLE 同样是 UNKNOWN。订单事务内只读数据库，不发起库存 HTTP 查询。路由规则发布时对 REMOTE_ON_DEMAND 候选使用 REQUIRED 库存检查应给出配置错误或明确拒绝；不能暗中当成 AVAILABLE。库存快照是资格信号，与 Offer 单笔数量限制、每日累计配额互不替代。

## 6. 管理端与安全配置

supplychainhubweb 供应商详情增加出站凭证引用、入站验签模式/公钥指纹、连接器版本及回调路径、库存查询能力；观察来源显示刷新周期、TTL、最近快照/错误与手动触发入口。共享密钥只展示引用与脱敏提示；公钥可回显。管理端保存配置、验证草稿、发布版本有审计且受 @PreAuthorize 保护。

回调端点必须在供应链服务 aifuli.security.permit-all-urls 精确放行，并核对 Gateway 路由。访问日志不得记录完整请求体、卡密、签名或共享秘密。未通过验签的高频请求按 connectorNo 和错误码聚合日志/指标。

## 7. 测试与联调门禁

- *Test：冻结版本选择、原生与标准协议验签、签名目标绑定、事实去重键、迟到事实决策、快照数量和 TTL、路由策略。
- *IntegrationTest：真实 MySQL/Liquibase 下轮换后旧订单回调、重复/乱序/迟到成功、事实与状态机同事务、异常 Case、多个实例竞争库存刷新租约、错误快照、路由过期边界。
- *ContractTest：QNM 回调路径及成功/失败应答、标准协议逐字节签名、供应商库存查询请求/响应映射；没有真实协议契约的 Adapter 不发布能力。
- *ArchitectureTest：interfaceapi/callback 只委托应用服务、不直连 DAL；管理端 @PreAuthorize；供应链不跨库；公开端点确实可匿名到达验签器。
- 联调步骤、前置条件、签名样例及排障记录同步写入 docs/集成测试.md。
