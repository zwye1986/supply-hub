# 仓库服务形态约束

- 本仓由 mall-pro 拆分而来，`mall`（含 `product`、`promotion`、`trade`）、`pay`、`erp` 模块已删除，仓库内不存在 `MallServerApplication`。不要沿用「商城三模块聚合为同一进程」的旧假设排查问题。拆仓范围见 `docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md`。
- 可独立启动的进程共 8 个：`GatewayServerApplication`、`AifuliServerApplication`（仅聚合 system + infra，member 依赖已注释）、`SystemServerApplication`、`InfraServerApplication`、`MemberServerApplication`、`SupplyChainServerApplication`、`TaobaoFlashChannelApplication`、`MovieServerApplication`。
- `aifuli-module-supply-chain-server` 是独立进程：独立数据源 `supplyChainDataSource`、独立事务管理器 `supplyChainTransactionManager`、独立库（表名统一 `sc_*` 前缀，表间无物理外键）。供应链代码不得访问 `system_*`、`member_*` 等其它模块的表；管理端菜单与数据字典由 `script/deploy` 下的部署 SQL 写入 system 库。
- 供应链出站调用走显式注册的 Feign：当前只注册会员的 `MemberProfileResolveApi`、`MemberSsoTokenApi`（`framework/rpc/config/RpcConfiguration`）。新增外部服务依赖必须显式注册，不得依赖包扫描隐式引入。
- 淘宝闪购渠道 `aifuli-channel-taobao-flash-server` 与电影 `aifuli-module-movie-server` 各自是独立进程与独立库，不得并入供应链容器。
- 供应链模块边界由 `SupplyChainModuleArchitectureTest` 以 ArchUnit 强制（Facade 不得依赖 `..dal..`、`routing.domain` 不得依赖 Spring/MyBatis、`border` 不得访问账户与账本 `dal`、管理端每个 HTTP 映射必须有 `@PreAuthorize` 等）。调整分层或依赖方向前先读该测试。
- 修改启动、RPC、事务、缓存或安全配置时，必须评估对应进程的整体影响，并确认是否触发上述架构约束测试。

# 开发规范（Cursor / Codex / ChatGPT 共用）

以下规范适用于本仓库所有 AI 辅助开发（含 Cursor、Codex、ChatGPT）。冲突时以用户当次明确指示为准，否则必须遵守。

## 1. 设计先行

- 改实现方案或调整已有设计决策前，先更新对应详细设计文档（如 `docs/superpowers/specs/` 下相关文档），再改代码。
- 禁止「先写代码、事后补文档」；文档与实现应同一次变更内对齐。

## 2. 语义不清先确认

- 需求、边界条件、领域语义或接口契约不清时，先停下来向用户确认，再写代码。
- 不得用猜测填补关键业务语义。

## 3. 联调 / 集成测试文档

- 联调步骤、集成测试场景、前置条件、验证点与排障记录，写入并持续维护 `docs/集成测试.md`。
- 新增或变更可联调流程时，同步更新该文档。

## 4. 重要注释与设计链接

- 重要类、方法、流程须写详细中文注释，说明职责、不变量与关键决策。
- 注释中用文档路径 + 章节标题/锚点链接到详细设计对应章节（例如：`docs/superpowers/specs/xxx.md#章节名`）。

## 5. DTO 与 DDL、TODO

- DTO 字段注释必须与 DDL `COMMENT` 语义一致（同一业务含义用同一表述）。
- 未完成实现处使用 `TODO`，并附带指向设计/任务文档的链接；禁止无上下文的裸 TODO。

## 6. 异常与日志

- 禁止吞异常（空 `catch`、只打日志不处理也不上抛、捕获后丢失因果关系等）。
- 高频路径（循环、定时、消息消费、回调）须防日志风暴：限频、采样、或按错误码聚合，避免每次失败都刷满日志。
- 重要方法入口须打详细日志：对外入口、跨模块/跨服务调用、核心业务编排、状态变更等方法，入口处记录关键入参（业务单号、金额、状态、渠道标识等），便于问题排查；高频路径的入口日志可降级为 `debug` 或采样，避免与防风暴冲突。
- 第三方调用须打详细日志：所有与第三方（HTTP/RPC/SDK/中间件/外部服务）的调用，记录请求参数、响应结果（含错误码/报文摘要）、耗时；敏感字段（密钥、完整凭证、手机号等）脱敏；高频第三方调用按错误码聚合或采样防风暴。

## 7. 禁止业务魔法值

- 业务状态、类型、错误码、渠道标识等不得散落字面量；使用枚举、常量或已有字典/配置。
- 仅对无业务语义的技术字面量（如 `0`、`1` 作下标）可例外，且应显而易见。

## 8. MyBatis Plus 查询 / 更新写法

- 不推荐在 Mapper 上新增 `@Select`、`@Update`、`@Insert`、`@Delete` 等注解 SQL。
- 优先使用 MyBatis Plus 的 `LambdaQueryWrapper` / `LambdaUpdateWrapper`（或等价 `lambdaQuery()` / `lambdaUpdate()`），以方法引用绑定列，避免字符串列名漂移。
- 仅当 Lambda 无法表达（复杂多表、数据库方言特性、已存在且稳定的 XML）时才使用 XML Mapper；新增注解 SQL 须有明确理由。

## 9. 单元测试范围

- 仅跳过 `aifuli-module-supply-chain` **以外**模块的单元测试；不得将「跳过其它模块单测」理解为跳过全仓库单测。
- `aifuli-module-supply-chain` 须按 `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁` 编写与运行测试：
  - `*Test`：状态机、金额不变量、路由策略、错误映射等纯逻辑单测。
  - `*IntegrationTest`：真实 MySQL、Liquibase、Mapper、事务、锁与并发（Maven profile `supply-chain-integration`）。
  - `*ContractTest`：RPC/HTTP 契约一致性。
  - `*ArchitectureTest`：模块依赖与数据所有权边界。
- 第 3 条的 `docs/集成测试.md` 记录联调与人工验收步骤，不替代上述供应链自动化测试要求。

## 10. Git 提交信息

- Git 提交标题与正文必须使用中文描述，准确说明本次变更的业务目的或技术结果。
- 必要的代码标识、模块名、协议名与命令可保留英文；禁止只使用无语义的英文模板或编号作为提交说明。
