# HUA_SHENG 卡券业务闭环实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: 使用 subagent-driven-development 逐任务实施；每项先规格审查再质量审查。只并行只读分析，实施者每次一个。用户明确要求在当前仓库分支开发。

**目标：** 从商品、供应商、分销商、项目与路由配置出发，使 HUA_SHENG 一张卡券订单完成受理、履约、资产交付和资金收敛。

**架构：** 保持供应链独立进程、独立 sc_* 数据所有权、显式 RPC 注册。协议适配器只产生类型化事实，多步骤推进、checkpoint、资产与资金由应用层事务收敛。原 QNM 实现和契约保留。

**技术栈：** Java/JCA、Spring Boot、MyBatis Plus、Liquibase、JUnit 5、MockWebServer、Testcontainers MySQL 8.4、Vue 管理端。

**设计来源：** `docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#12-闭环推进与实施前核验2026-10-03`。第一、二切片已完成；2026-10-03用户同意项目直接下单、中台按配置首冻，增量规格与实施计划见同日 `standard-project-direct-order-price-freeze` 文档。供应商协议保证仍待取得正式证据，不能由实施者猜测。

**2026-10-03前后端综合核验后的优先级：** 见 `docs/superpowers/specs/2026-10-03-frontend-backend-business-closure-audit.md#6-调整后的推进顺序与完成判据`。完成已批准首冻后，优先设计接通管理选品与订单受理 BuyerListing，不能继续用预置目录宣称 Task5 从零配置通过。先以已实现的 QNM/FIXED 建立全配置自动化基准，再按正式协议实施华昇。实际菜单组件、空环境QNM槽入口、券码管理详情等纳入基准链前置核验；主动通知与售后分别验收。新增缺口仅记录，目录与非固定价业务语义尚未确认，不在首冻切片中猜测实现。

## Task 1：通信密码协议（可独立实施）

**文件：**
- 新增 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/supplieradapter/huasheng/HuashengCrypto.java`。
- 新增同模块 `src/test/java/com/aifuli/module/supplychain/supplieradapter/huasheng/HuashengCryptoTest.java`。

- [x] 先写协议测试：JCA 独立生成 AES 密文与 RSA 签名，验证 UTF-8、原密文签名、篡改/空白/非法 hex/非法 AES 长度、PKCS#8 私钥、X.509/PKCS#1 公钥与固定安全异常。
- [x] 测试调用的接口固定为 `encryptBodyHex(String,String,String)`、`decryptBodyHex(String,String,String)`、`signBodyHex(String,PrivateKey)`、`verifyBodyHex(String,String,PublicKey)`、`loadPkcs8PrivateKey(String)`、`loadPublicKey(String)`。测试密钥由本地 JCA 生成，禁止使用环境或真实秘密。
- [x] 记录 RED：允许先有只抛 UnsupportedOperationException 的编译壳，测试应因行为缺失失败，而不是把无法编译当作 RED。命令 `mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -Dtest=HuashengCryptoTest test`，输出保存 `/tmp/huasheng-crypto-red.log`。
- [x] 按设计 §12.1 实现纯 JCA 工具类，写中文职责/不变量/文档链接；不加 Spring Bean、不改启动/RPC/事务/缓存/安全配置。
- [x] 记录 GREEN：同命令输出保存 `/tmp/huasheng-crypto-green.log`；再运行 `-Dtest=HuashengCryptoTest,SupplyChainModuleArchitectureTest` 验证边界。
- [x] 规格审查通过后质量审查；发现问题由原实施者修复并复审。只提交本项文件，中文提交标题。

实施记录：`aecef14` 首次实现、`b8ae128` 规格修复。首轮 RED 69 项实际执行失败，首轮 GREEN 69 项通过；JDK21 协议 69 + 架构 28 项通过。复审增加完整异常输出敏感标记检查并统一 HUA_SHENG 名称，RED 73 项/50 断言失败，最终 GREEN 73 项全部通过，规格及质量独立复审均通过。日志 `/tmp/huasheng-crypto-review-{red,green}.log` 和 `/tmp/huasheng-crypto-architecture-jdk21.log`。本切片为纯 JCA，不声称已经完成适配器外呼或真实供应商联调。

## Task 2：连接器显式凭据集与配置传递

完整契约为 HUA_SHENG 设计 §12.3。实际文件范围：后端 `supplieradapter/metadata/{AdapterMetadata,CredentialBindingMode,PublishedConnectorConfigurationResolver}`，`tradecore/supply/application/{SupplierConfigurationCommandService,ConnectorValidationService,ConnectorPublicationService,SupplierConfigurationQueryService,ConnectorCredentialBindingValidator,SupplierCredentialCommandService}`，连接器版本 DO，`interfaceapi/admin/SupplierConfigurationAdminController`，`infrastructure/security/DefaultCredentialSetResolver`，`border/fulfillment/{domain/OperationTicket,application/FulfillmentOperationTicketFactory}`，changelog 058 与登记 YAML；前端 `src/api/supplier/config/types.ts`、`src/views/supplier/config/components/{ConnectorPanel,ConnectorFormDialog,ConnectorDetailDrawer,CredentialItemDialog}.vue` 及对应真实组件测试。修改旧构造参数时保持单槽兼容委派，新增 Spring 依赖显式补齐集成测试 @Import，不绕开被测生产装配。

RED/GREEN 重点用实际连接器服务及本地 Fake SET metadata 验证；HS 可执行元数据此时不注册。新 `ConnectorCredentialSetIntegrationTest` 通过真实 MySQL 8.4 + Liquibase 覆盖长 PEM 加密、集引用发布和停用回滚。运行命令 `mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test-compile failsafe:integration-test failsafe:verify -Psupply-chain-integration -DskipTests=false -DskipITs=false '-Dit.test=ConnectorCredentialSetIntegrationTest,ConnectorPublicationIntegrationTest,SupplierConfigurationSchemaIntegrationTest,SupplyChainLiquibaseIntegrationTest,SupplierConfigurationAdminRestContractTest'`；计数基线跟随本次新增 058（总变更集 60、业务表 123、DATETIME 列 295）。前端 `node --test tests/*.test.mjs`、改动文件 ESLint、`pnpm build:local`，不把既有全仓类型错误归为本次修复。

- [x] 先更新适配器设计 AD-3/AD-5、共享地基文 §5 和管理端契约：新增 credential_set_id，不复用旧 slot ID；校验项键、供应商归属与凭据模式；运行时只读取冻结连接器版本。
- [x] 以真实 MySQL 测试锁定 QNM slot 兼容、HUA_SHENG 所需凭据集完整性（隔离 Fake SET metadata）、错供应商/缺项拒绝、ENV/ENVELOPE 解密与发布回滚。
- [x] 增量 Liquibase、DO/DTO/发布配置/运行时 Ticket、连接器前端表单同步实现；秘密只写不回显且不入日志。运行受影响的 *Test、*ContractTest、*IntegrationTest 和 ArchitectureTest。

实施记录：后端 `23400fa`、前端 `69f6903`，规格及质量独立复审均通过。最终供应链 Surefire 1658 项全部通过（包含架构28、Crypto73与日志保护15）；相关10类 Failsafe 51 项及共享夹具 Acceptance 3 项全部通过，合计54项，不声称全量集成测试已执行。common全量18项及安装、Gateway全量6项通过；前端真实组件等29项、改动文件 ESLint、build:local通过。058迁移基线为60变更集/123业务表/295 DATETIME列。首次集成失败及日志泄漏RED均保留，修复过程和最终日志见 `docs/集成测试.md#779-连接器显式凭据集验收入口2026-10-03自动化已通过`。本切片未注册不可执行的 HUA_SHENG 元数据、未执行真实供应商或部署验收；Task3–6仍未完成。

## Task 3：协议解析、受控多步骤与查单恢复

- [ ] 先修订设计 AD-1/AD-2/AD-4：明确 PLACE 返回 WAIT 后确认、SUCCESS 跳过确认并取券的推进及恢复事实，ACCEPTED 不伪造成 REJECTED；checkpoint AAD 绑定操作身份，传递下一步时解密后重新绑定加密。
- [ ] 先核实金额单位、重发幂等与资产额外字段契约，再测试原外部订单号、num=1、金额合法性、7014/61、超时/未知结果、步骤重复和资产数量错误，随后实现 HTTP、token、parser、adapter 与 metadata。
- [ ] 真实 MySQL/Timer 验证步骤并发恰一次、节点重启恢复、资金仅预占一次且最终仅扣一次/释放一次；未知结果禁止重下与切供应商。QNM/手机号频控全回归。

## Task 4：可信供应商回调

- [ ] 先对齐回调凭据冻结版本定位、Appid/X-Sign/raw body、时间戳/nonce、防重和响应契约设计。
- [ ] 通过 HTTP/真实 MySQL 测试验签前无副作用、跨连接器/双号拒绝、重复回调、迟到冲突、取券并发；再实现入口、解析器、收敛服务与安全路径。
- [ ] 评估 Gateway 与独立供应链进程的路由、安全、敏感日志影响，架构约束不放宽。

## Task 5：业务入口与自动化全配置闭环

- [ ] 用户已确认直接下单；先依 `2026-10-03-standard-project-direct-order-price-freeze-design.md` 完成可信自动首冻，再接全配置闭环；不能让调用方提交分销金额覆盖服务端定价。
- [ ] 先设计页面 sc_buyer_product/sc_project_listing 与订单 sc_buyer_listing 的关系、buyerSkuCode 来源和发布/停用责任，再实施目录衔接；全新配置测试不得通过直接SQL或隐藏API种子补目录掩盖页面断点。
- [ ] 核验实际订单菜单与前端组件、QNM单槽准备入口、适配器白名单/外呼有效配置，以及券码管理详情兼容性；条件风险先核验，不宣称现网故障。
- [ ] 测试配置接口建立真实商品、供应商、连接器、合作商品上架、分销定价、项目选品/凭证和路由，再经真实 Controller 下单，Timer 走本地供应商 HTTP 挡板完成资产交付。
- [ ] 验证成功、明确失败、结果未知、同号幂等、跨项目隔离、改价后原单冻结金额与资产审计；自动化不直接改订单状态制造成功。
- [ ] 当前源码运行供应链全量单测/契约/架构及 supply-chain-integration，前端改动文件检查与构建；记录准确执行范围。

## Task 6：真实环境验收与后续运营

- [ ] 核实测试环境、迁移窗口、供应商测试凭据与对外回调域名，再恢复先前暂缓的实际环境事项。
- [ ] 管理端完整配置后，用真实测试供应商核对订单/路由/B 单/资产/资金，结果写入 `docs/集成测试.md`。未经实际执行保持未验收。
- [ ] 第一条真实链路通过后，再按既有通知设计实施项目签名事件/重投；异常处置、流水对账、直充和售后各自细化设计，不混入通信协议切片。
