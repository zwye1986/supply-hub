# 供应链中台文档索引与知识地图

本目录汇集了 **supply-hub（供应链中台）** 的所有架构设计、业务规范、渠道集成契约、联调门禁及工程规范文档。

---

## 目录导航速览

```text
docs/
├── README.md                      # [当前文档] 知识库全景导航地图
├── 集成测试.md                     # 核心门禁：联调验证、实库测试与排障记录
├── 待办列表.md                     # 阶段任务、菜单部署与验收待办清单
├── architecture/                  # 架构设计：系统架构、多场景演进、采购商项目化
├── business/                      # 核心业务：虚拟卡券商品、电影子系统履约
├── integration/                   # 渠道与外部集成契约
│   ├── rtmart/                    # 大润发到店购（INSTORE）全套对接规范与契约
│   ├── 影乐星开放平台接口文档.md     # 影乐星电影供应商接入契约
│   └── 商城收银台对接API.md         # 商城收银台与中台支付对接契约
├── deploy/                        # 部署上线：部署检查清单、环境验证报告
├── adr/                           # 架构决策记录 (ADR 0001~0007)
├── images/                        # 架构图、时序图与静态设计图解
└── superpowers/                   # 工程规格与实施计划
    ├── specs/                     # 详细技术设计规范 (Specs)
    └── plans/                     # 任务开发计划 (Plans)
```

---

## 1. 核心工作台与开发门禁

在日常开发、需求对齐与发版自测前，优先查阅并遵守以下核心工作台文档：

| 文档 | 作用与定位 | 关键约束与指引 |
| :--- | :--- | :--- |
| [集成测试.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/集成测试.md) | **全仓联调与验收基石** | 记录联调步骤、前置条件、自动化门禁执行结果、真实环境排障与回滚记录。每次重大发版前必须对照更新。 |
| [待办列表.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/待办列表.md) | **阶段待办与风险看板** | 记录延期验收、Liquibase 菜单发布、待人工验证的测试环境项。 |
| [CONTEXT.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/CONTEXT.md) | **全系统统一语言词汇表** | 明确 Buyer（采购商）、Project（采购项目）、Supplier（供应商）、L1/L2/L3 结算价等领域术语，严禁业务魔法值与术语混淆。 |
| [AGENTS.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/AGENTS.md) | **开发规范与架构硬约束** | 8 独立微服务进程规范、独立库 `sc_*` 无外键约束、显式 Feign、日志规范、ArchUnit 门禁。 |

---

## 2. 架构设计与系统演进 (`architecture/`)

| 文档 | 说明 | 适用场景 |
| :--- | :--- | :--- |
| [供应链中台系统架构总览.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/architecture/供应链中台系统架构总览.md) | 中台核心拓扑、服务边界、DDD 分层架构与核心数据流图 | 了解中台整体架构设计与服务划分 |
| [供应链中台多场景概要设计.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/architecture/供应链中台多场景概要设计.md) | 标准商品、话费直充、到店购、电影票等多业务形态的统一支撑体系 | 评估多业务场景接入与中台扩展能力 |
| [2026-08-21-供应链中台采购商项目化设计方案.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/architecture/2026-08-21-供应链中台采购商项目化设计方案.md) | 采购商与采购项目多对多解耦、凭证体系设计方案 | 了解多租户解耦、独立 AppId/AppKey 鉴权与目录发布机制 |
| [adr/ 架构决策记录目录](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/adr) | 收录 ADR 0001 ~ 0007（到店购独立于 O2O、单一支付身份、项目身份路由等） | 追溯重大架构决策的历史背景与推导过程 |

---

## 3. 核心业务与领域模型 (`business/`)

| 文档 | 说明 | 核心内容 |
| :--- | :--- | :--- |
| [供应链中台虚拟卡券商品设计说明.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/business/供应链中台虚拟卡券商品设计说明.md) | 虚拟卡券、直充类商品模型设计与流转状态机 | 平台 SPU/SKU、批次调价、卡密池管理、直充状态机 |
| [电影子系统与中台履约详细设计.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/business/电影子系统与中台履约详细设计.md) | 独立电影微服务 (`movie-server`) 与中台的对接履约设计 | 影院排期同步、验座报价、锁座与出票履约、MongoDB 存储设计 |

---

## 4. 渠道集成与外部对接契约 (`integration/`)

### 4.1 大润发到店购 (`integration/rtmart/`)
包含中台接入「大润发到店扫码购」（INSTORE 模式）的完整设计与双向通信协议：

- [供应链中台INSTORE到店购渠道接入实现总结.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/integration/rtmart/供应链中台INSTORE到店购渠道接入实现总结.md)：到店购接入整体架构沉淀与技术实现总结。
- [大润发到店购-旅通调用商户接口说明.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/integration/rtmart/大润发到店购-旅通调用商户接口说明.md)：旅通 POS 系统反向调用中台的交易、状态与退款商户接口契约。
- [大润发到店购入口-前端对接说明.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/integration/rtmart/大润发到店购入口-前端对接说明.md)：前端扫码入口、Token 置换与 H5 页面交互说明。
- [instore-channel-command-api.html](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/integration/rtmart/instore-channel-command-api.html)：到店购指令 API 契约与接口状态定义。
- [instore-channel-member-resolve-api.html](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/integration/rtmart/instore-channel-member-resolve-api.html)：会员身份回调与解析契约。
- [大润发到店购渠道分销接口.docx](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/integration/rtmart/大润发到店购渠道分销接口.docx)：外部旅通渠道方提供的原始分销接口规格。

### 4.2 供应商与收银台对接
- [影乐星开放平台接口文档.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/integration/影乐星开放平台接口文档.md)：影乐星（LX）电影供给侧开放平台协议。
- [商城收银台对接API.md](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/integration/商城收银台对接API.md)：商城收银台与中台结算账户/支付通道的对接规范。

---

## 5. 部署、环境与验证 (`deploy/`)

| 文档 | 说明 |
| :--- | :--- |
| [大润发部署检查清单.html](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/deploy/大润发部署检查清单.html) | 到店购生产/测试上线前必须逐项核对的配置、网络、密钥与数据库检查单 |
| [pro143部署验证报告.html](file:///Users/mac/.gemini/antigravity/worktrees/supply-hub/organize_supply_hub_docs/docs/deploy/pro143部署验证报告.html) | pro143 测试环境联调验收与部署实测报告 |

---

## 6. 详细技术规范与执行计划 (`superpowers/`)

工程开发严格遵守 **设计先行** 规范，所有功能特性与缺陷修复均有对应的设计方案与执行切片：

- **详细设计规范 (`docs/superpowers/specs/`)**：
  - **核心基石**：`2026-08-04-supply-chain-core-design.md`、`2026-08-07-supply-chain-phase1-detailed-design.md`、`2026-08-07-supply-chain-phase1-database-ddl.md`
  - **全系统日志规范**：`2026-10-04-logging-standardization-spec.md`（全仓 8 独立进程统一日志标准）
  - **订单与履约路由**：`2026-09-24-standard-order-routing-execution-design.md`、`2026-09-30-project-signed-standard-order-http-design.md` 等
  - **商品与目录**：`2026-09-11-supply-chain-product-center-design.md`、`2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md` 等
  - **供应商适配**：`2026-10-02-huasheng-fulfillment-adapter-design.md` 等
- **任务开发计划 (`docs/superpowers/plans/`)**：
  - 包含按 TDD 拆分的细粒度研发切片与自动化验证计划。
