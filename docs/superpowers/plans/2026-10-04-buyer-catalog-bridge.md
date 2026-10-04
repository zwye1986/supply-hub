# 页面选品与受理目录桥接实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: 使用 subagent-driven-development。实施者每次一名，先规格审查再质量审查；只读分析可并行。用户明确在当前仓库dev分支开发，不新建工作树。

目标：页面BPD编号成为订单buyerSkuCode，真实管理选品建立唯一受理身份，条件满足时自动发布真实商业快照，最终按实际项目当前供给受理；之后用全新数据完成HUA_SHENG固定价卡密订单及资产、资金验收。不得用QNM或SQL补目录替代目标。

详细设计：`docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md`。用户已确认BPD编码、新选品自动发布、旧手工目录保留原商业管理；旧RPC移除联动仍待回答，该项单独隔离。设计须先修复独立审查问题，才开始对应实现。

## Task A：唯一受理身份与双编码解析

文件范围（实现时按实际调用链核对）：catalog应用层新增Resolver/身份确保服务与封闭管理来源，BuyerProductCommandService、ProductCatalogCommandService、BuyerListingPublicationService；BuyerProductMapper/BuyerListingMapper及相关DO；FulfillmentAcceptanceLifecycle三处身份读取和NULL尝试版本；供应链错误码；下一独立Liquibase变更集和YAML；必要管理接口的补关联契约及测试。Facade仍不接DAL，不改金额/资金/履约，不注册HUA_SHENG。

- [ ] 设计审查问题先修订详细规格，明确本片MANUAL-only发布保护、AUTO人工恢复失败关闭、唯一冲突结束事务后重探测，以及刷新Outbox仅在具有真实消费者的后续片启用。
- [ ] RED：真实双路径Resolver测试，新BPD/旧手工编码命中唯一LST，跨买家/不同SKU碰撞拒绝，不规范化原请求编码，REMOVED不删除身份；真实选品Controller创建DRAFT/UNKNOWN，无商业版本；兼容发布入口拒绝AUTO外部价格；DRAFT NULL尝试版本不伪造1、不抛缺版本500，非空损坏引用仍抛系统异常。
- [ ] 按详细设计§3/§4落身份迁移、DO/DTO COMMENT、唯一创建与旧manual复用；不锁已有LST改状态，不把PLT主键写履约LST列。不改历史行编码、价格、状态或已成功回执。
- [ ] Lifecycle的Schema、claim和最终锁定都委派应用Resolver；最终按LST ID当前读锁定并重验真实归属，保持receipt→LST→SKU前缀。不新增BPD→已有LST反向锁。
- [ ] 真实MySQL/Liquibase/Mapper/事务验证：新旧数据升级、CHECK和unique、页面与旧创建竞争、多个页面竞争、任一关联/定价写失败原事务回滚、唯一竞争仅专用异常及明确新事务重探测，不吞任意SQL异常。
- [ ] 供应链相关纯测试/HTTP契约、架构及全量Surefire，相关真实MySQL Failsafe通过；更新迁移基线计数应以实际模式为准，不机械加1。JDK21，供应链测试不跳过。
- [ ] 自审、中文提交DONE；随后根安排独立规格和独立质量审查。docs/集成测试.md由根记录原始命令、计数、RED/失败及准确范围。

本片只能宣告身份衔接，不能宣告目录已发布/AVAILABLE或全配置已闭环。首冻前目录准备、当前供给和冻结Schema回放属于后续片；尚无这些行为时不得扩写验收结论。

## Task B：真实商业镜像与可用性

- [ ] 修订并审查MANUAL与AUTO职责：自动商业仅AUTO，MANUAL价格/状态不接管；新HTTP复用manual目录的可用性不能永远卡在没有更新器的UNKNOWN，也不能暗改旧RPC待确认停用语义。
- [ ] 实施真实READY分销价来源、摘要/版本幂等、publisher/CAS与新独立只读资格Reader；损坏引用系统失败，缺条件DEFERRED，不填假价格/AVAILABLE。
- [ ] 全部当前生产者同事务写可靠Outbox；注册封闭Handler、Inbox与目录写同事务、分页展开、乱序读当前、重试/死信/Timer补偿，不能裸afterCommit或先成功Inbox后写业务。
- [ ] 真实MySQL并发/宕机/迟到/重启/到期/手工暂停退役回归，当前管理HTTP与真实前端payload一致；新增管理读字段及重算入口须权限并按实际需要同步前端。
- [ ] 独立规格、质量审查与完整文档取证。本片不宣告最终供给门禁或HUA_SHENG一单已经完成。

## Task C：当前准入、冻结回放与从零链路

- [ ] 首冻前准备目录，DRAFT/缺价格的拒绝及CAS沿详细设计真实返回，不制造空版本/报价。
- [ ] 最终新只读真实项目供给快照在订单/资金写前建立并复用，AVAILABLE滞后不能授权；不为加强停用线性化临时反锁供给/定价。
- [ ] 先冻结已有回执SKU/Schema再准备指纹，保持格式2原编码不变量；已接受同参回放不依赖当前目录和Schema，不重路由/扣款，历史治理不伪造来源。
- [ ] 当前RR旧视图、真实停用并发、改价原报价、跨项目隔离及真实Controller从零配置测试。
- [ ] 依赖HUA_SHENG协议、资产、受控多步骤/恢复与可信回调各自已通过规格及质量审查后，执行HUA_SHENG真实协议HTTP挡板全链及资产/资金恰一次断言；挡板结果不算真实供应商验收。
- [ ] 核验真实环境条件和单次测试范围，使用全新管理配置完成实际HUA_SHENG订单/资产/资金核对，结果写docs/集成测试.md；未经执行仍标未验收。

## Task D：旧RPC移除联动（待用户回答）

- [ ] 用户选择后先修订详细设计§12，明确BPD/PLT移除与旧编码新尝试范围、锁序和兼容；保持已成功同参回放。
- [ ] 按确认范围实施并独立审查、真实MySQL验证；回答前不猜测该项，A/B/C的新页面链路继续独立推进。
