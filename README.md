# supply-hub（企业级供应链中台）

`supply-hub` 是一个企业级全场景供应链中台服务平台，旨在为多组织主体、多采购商、多采购项目提供高度标准化、可插拔、可扩展的供应链整合与履约能力。

平台支持多种核心供给场景：
- **标准虚拟卡券与数字资产**：卡号卡密、兑换码、直冲权益、账户绑定权益等标准供给。
- **话费 / 流量直充**：高频直冲路由、黑名单与限频防护、多家上游供应商竞价与兜底路由。
- **电影供给与在线选座**：独立排期同步、在线验座、实时报价、锁座与出票履约。
- **大润发到店购（INSTORE）**：线下门店扫码购 POS 聚合、会员身份置换与双向交易退款闭环。
- **淘宝闪购（Taobao Flash）**：渠道端轻量接入与选品投放。

---

## 一、微服务架构与独立进程矩阵

系统采用微服务分布式架构，支持以下 **8 个独立启动的微服务进程**：

| 微服务模块 | 启动类 | 默认端口 | 职责定位与核心特性 |
| :--- | :--- | :--- | :--- |
| **aifuli-gateway** | `GatewayServerApplication` | 48080 | 统一 API 网关，路由转发、全局鉴权、限流与 MDC 链路追踪上下文建立。 |
| **aifuli-module-supply-chain-server** | `SupplyChainServerApplication` | 48083 | **供应链中台核心进程**。拥有独立数据源 `supplyChainDataSource`、独立库（`sc_*` 前缀）、独立事务管理器。严格禁止跨库访问其他业务表。 |
| **aifuli-module-system-server** | `SystemServerApplication` | 48081 | 平台管理后台服务，提供 RBAC 用户、角色、系统动态菜单、数据字典与参数管理。 |
| **aifuli-module-infra-server** | `InfraServerApplication` | 48082 | 基础设施服务，提供代码生成、定时任务、配置管理与系统监控。 |
| **aifuli-module-member-server** | `MemberServerApplication` | 48084 | 会员服务，提供会员主数据管理与凭证置换。 |
| **aifuli-channel-taobao-flash-server** | `TaobaoFlashChannelApplication` | 48085 | 淘宝闪购独立渠道服务，承接淘系 O2O 选品与交易。 |
| **aifuli-module-movie-server** | `MovieServerApplication` | 48086 | **电影子系统独立微服务**。拥有独立 MySQL 与 MongoDB 存储，提供影院排期同步、验座报价与出票履约。 |
| **aifuli-server** | `AifuliServerApplication` | 48080 | 本地开发聚合进程（仅聚合 system + infra，用于轻量级本地快速调试）。 |

> [!IMPORTANT]
> **关于服务形态的重要提示**：
> 1. 本仓库由历史工程拆分演进而来，已剔除旧商城（`product`、`promotion`、`trade`、`pay`、`erp`）模块，仓库中**不存在** `MallServerApplication`。不要沿用旧聚合单体思维排查问题。
> 2. `supply-chain-server` 与 `movie-server` 各自拥有独立的数据库与事务上下文，不可合并容器。
> 3. 供应链出站外部调用必须走显式注册的 Feign 客户端（如 `MemberProfileResolveApi`），严禁依赖隐式包扫描。

---

## 二、架构防腐与设计约束

为保证中台核心领域模型纯粹与高内聚低耦合，项目受到以下门禁与规范的严格保护：

1. **统一语言（Ubiquitous Language）**：
   - 严格区分 **采购商（Buyer）**、**分销商（Distributor）**、**采购项目（Buyer Project）**、**供应商（Supplier）** 与 **平台商品（Platform Product）**。
   - 详见 [CONTEXT.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/CONTEXT.md)。
2. **ArchUnit 架构防腐门禁**：
   - 由 `SupplyChainModuleArchitectureTest` 强制执行架构依赖规则：
     - `Facade` 层不得直接依赖 `..dal..`。
     - `routing.domain` 领域层不得依赖 Spring、MyBatis 等外部框架。
     - `border` 边界层不得越权访问核心账户与账本 DAL。
     - 管理端每个 HTTP 映射必须配置 `@PreAuthorize` 权限注解。
3. **全系统日志与追踪规范**：
   - 遵循单行结构化语义、规范化 MDC 追踪及 ES 格式，详见 [全系统日志规范](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/superpowers/specs/2026-10-04-logging-standardization-spec.md)。
4. **设计先行原则**：
   - 方案与业务逻辑变更前，必须优先更新 `docs/superpowers/specs/` 详细设计并对齐测试门禁。

---

## 三、前端配套工程

- 供应链管理端 Web 前端项目位于独立的配套仓库：
  - 本地路径：`/Users/mac/Documents/codeSpace/supplychainhubweb`
  - 前端基于 Vue3 / Vite / Element Plus 构建，包含分销商管理、采购项目接入、商品选品与发布批次、供应商管理、路由规则编排、订单与售后监控等管理页面。
  - 在涉及页面展示排查、前端入参契约与动态菜单配置时，应与该前端仓库对齐。

---

## 四、文档导航与知识体系

项目文档均经过系统化归类，详情请查阅 [docs/README.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/README.md)：

- 📘 **规范与规则**：
  - [AGENTS.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/AGENTS.md) — 团队协作与 AI 辅助开发硬性规范
  - [CONTEXT.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/CONTEXT.md) — 供应链中台统一语言与领域名词词典
- 🧭 **开发与测试工作台**：
  - [docs/集成测试.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/集成测试.md) — 全流程联调步骤、前置数据与实机验证记录
  - [docs/待办列表.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/待办列表.md) — 任务跟进与未闭环项
- 🏛️ **架构与业务**：
  - [docs/architecture/](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/architecture) — 中台架构总览、多场景方案、采购商项目化设计
  - [docs/business/](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/business) — 虚拟卡券商品模型、电影履约设计
  - [docs/integration/](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/integration) — 大润发到店购（`rtmart/`）、影乐星、收银台等对接契约
  - [docs/deploy/](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/deploy) — 部署清单与环境报告
  - [docs/adr/](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/adr) — 架构决策记录 (ADR)
- 🚀 **工程设计与计划**：
  - [docs/superpowers/specs/](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/superpowers/specs) — 详细设计方案库
  - [docs/superpowers/plans/](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/superpowers/plans) — 迭代执行切片计划库

---

## 五、快速开始

### 1. 环境依赖
- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Redis 6.0+
- Nacos 2.2+

### 2. 编译构建
```bash
# 全工程代码编译与依赖检查
mvn clean compile -DskipTests

# 运行供应链中台单测与架构测试门禁
mvn test -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server
```
