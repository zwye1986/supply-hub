# 路由规则模式与状态系统字典设计

- 日期：2026-10-12
- 状态：方案已由用户确认，本地实现与自动化验证完成，未提交或部署
- 范围：路由规则管理页的 `routeMode`、`status`；只交付后端字典与接入契约，不修改前端代码。

## 1. 依据与边界

产品依据为用户提供的《供应链中台管理系统一期产品需求文档（PRD）》导出件（下载目录中同名文件夹）。§8.13.3.6 明确停用规则不参与路由、重新启用时校验适用范围重复；§8.13.3.2 仅有标题，未导出字段明细。具体编码与标签采用现有前后端一致契约，不将缺失的字段明细视为已验证的 PRD 定义。

后端基线：`docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md` §5.2、§5.5、§5.6。`RoutingRuleRouteMode` 为 AUTO/FIXED，`RoutingRuleStatus` 为 ENABLED/DISABLED；数据库 `sc_std_routing_rule.status` 的 10/20 是内部持久化码，不是本次字典值。

前端实际仓库为 `/Users/mac/codespace/supplychainhubweb`。规则列表与表单仍使用静态选项，启停开关也写死值与文案。本次增加字典不会使当前页面自动切换数据源；完整前端接入是后续独立任务。不开新接口、不改路由规则 HTTP 契约、不改默认规则保护、重复校验或路由执行算法。

## 2. 字典定义

### 2.1 路由模式

字典名称：路由模式；类型：`supply_chain_routing_mode`。

- sort=1，value=`AUTO`，label=自动路由，color_type 为空。
- sort=2，value=`FIXED`，label=固定供应商，color_type 为空。

自动路由与固定供应商的业务约束继续由供应链实现，不由字典数据驱动。固定模式主供应商必填等规则保持不变。

### 2.2 路由规则状态

字典名称：路由规则状态；类型：`supply_chain_routing_rule_status`。

- sort=1，value=`ENABLED`，label=启用，color_type=`success`。
- sort=2，value=`DISABLED`，label=停用，color_type=`info`。

所有字典类型和初始数据项的自身 status 均为 0（启用），css_class 均为空。不能因标签为“停用”把 DISABLED 数据项的自身 status 设为 1，否则该选项不会从精简字典列表返回。

## 3. 数据所有权与管理规则

字典由 system 服务全局管理，不按租户复制。只有 system-server 主数据源写 `system_dict_type`、`system_dict_data`，供应链不访问这些表，路由执行不新增 system RPC 依赖。

管理员可调整标签、排序、展示颜色；业务编码固定，不应新增、改名或删除已使用的编码。这是管理约定，当前通用字典 CRUD 没有编码锁定机制，本轮不新增该机制。供应链仍以封闭枚举拒绝未知编码。禁用或删除字典项只影响后续字典消费者的选项展示，不联动已有路由规则，不代表规则停用。

## 4. 迁移与存量兼容

正式入口为 system-server Liquibase，追加 `015-routing-rule-dictionaries`，不改已执行的 001–014。SQL 按二进制精确比较 type 和 dict_type+value，忽略逻辑删除行，只补缺失的未删除字典类型及四个标准数据项。

与部分旧字典脚本“更新为标准值”不同，本次不覆盖已有类型或数据的标签、排序、颜色、启用状态、备注与审计字段，避免热更重放抹掉管理员配置；不删除未知键或其它字典。逻辑删除的标准项可新增一条未删除行，但不复活旧行。已有标准项被禁用时保持禁用，部署验收需明确检查并由管理员决定是否恢复。已有重复活跃行或错误编码需人工处理，本次不做破坏性清理。部署 SQL 需串行执行；顺序幂等不承诺无唯一约束下的并发执行安全。

存量热更脚本位于 `script/deploy/aifuli-test/sql/system-supply-chain-routing-rule-dict.sql`，与正式 SQL 内容一致；只在获授权后在 system 库执行，不写 DATABASECHANGELOG、不伪标迁移已完成。之后正常 Liquibase 执行仍安全且记录变更集。本轮只交付文件，不操作共享环境数据库或部署。

## 5. 接入契约

复用 `GET /admin-api/system/dict-data/simple-list`，返回 `dictType/value/label/colorType/cssClass`；按上述两个 dictType 筛选，value 原样作为字符串提交路由规则保存、筛选或启停接口。不复用 common_status，不转换为 10/20 或 0/1。

后续前端任务覆盖：表单模式单选、表单状态单选、列表模式文案、状态筛选，以及启停开关的展示文案。AUTO/FIXED 的业务分支仍按稳定编码判断；ENABLED/DISABLED 开关的业务语义不能随字典排序倒置。字典更新后需要重新加载现有字典缓存；不能承诺已打开页面即时刷新。本轮不实施这些前端改动。

## 6. 验证与验收

扩展现有 system 真实 MySQL 字典迁移测试，验证注册到完整主清单的新增迁移产生两个启用类型与四个启用数据项，编码、标签、排序及颜色正确；验证 Liquibase 二次执行、SQL 热更重放不重复且保留人工元数据，缺失标准项能补齐，其他字典不变。通过真实 Mapper、Service 与管理端精简字典接口验证字符串值和标签返回，不以静态 SQL 字符串匹配替代数据库结果。

供应链运行现有路由保存、查询、启停、HTTP 契约及模块架构门禁，证明业务校验与接口不变。联调步骤与实际结果写入 `docs/集成测试.md`；本地自动化、部署后验收和前端未来接入分开记账。

验收不包含“改字典后当前路由页面自动变化”；未部署不得声称共享环境已存在新字典。

本地结果：字典真实 MySQL 与 MVC 接口集成测试1项通过，供应链回归2689项通过，均无失败、错误或跳过。system 标准 test 生命周期受既有旧单测编译错误阻断，采用目标测试单独编译后直接执行 Surefire，不宣称标准生命周期通过。详细命令与存量热更后正式迁移、人工配置保持验证见 `docs/集成测试.md` 的“2026-10-12 路由模式与路由规则状态系统字典（本地完成，未部署）”。
