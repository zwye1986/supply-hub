# 项目标准商品能力配置闭环实施计划

**目标：** 运营在项目详情独立授予/关闭标准商品能力；关闭后只保留查单，从未开通不可查单。

**架构：** tradecore.buyer 管理服务复用认证能力表，独立追加审计；Filter 按服务端操作读取能力。前端/API合同见对应设计 §3，无新增菜单、无业务授权数据迁移。

**技术：** Java21、Spring Security/MyBatis Plus/Liquibase/MySQL8.4、Vue3/Element Plus。

## 1. 后端（当前线程）

- [x] 扩展 StandardProjectHttpClosureIntegrationTest：现有能力停用，签名查询应返回订单结果/不存在而非认证拒绝；签名下单/资产拒绝；删除能力后查询也拒绝。现有实栈先复现预期差异再通过。
- [x] 修改 ProjectAppCredentialService/Filter：requireCapability 接受服务端 Operation；只有 STANDARD_ORDER_QUERY 允许已存在的 DISABLED 标准能力。同步受影响 auth 单测，回归签名/nonce/状态规则。
- [x] 新 ProjectCapabilityAdminIntegrationTest 导入实际数据源/Mapper/时钟/管理服务，以生产方法验证开启→重复→关闭→查询→再启用、版本竞争、审计回滚及项目暂停的关闭操作；真实权限代理与 HTTP/JSON/参数验证扩展既有 SupplyChainAdminMethodSecurityContractTest。管理服务的首次缺类编译红灯不作为运行时证明。
- [x] 实现 ProjectCapabilityAdminService、ProjectCapabilityAdminController、DO/Mapper，新增063审计DDL/注册与稳定错误179/180及 DO_NOT_RETRY 映射。管理路径严格标准商品，列表包含三类。
- [x] 执行供应链全量非 IntegrationTest（单测/契约/架构）及适用真实数据库门禁，补063涉及的时间迁移/模式基线；2726个不同用例通过。完整最终命令与日志见 docs/集成测试.md 末尾能力闭环记录。

## 2. 前端（独立并行写入范围）

- [x] types.ts 定义设计§3列表项，ProjectApi 添加 getCapabilities/enableStandardProduct/disableStandardProduct。
- [x] 现有 ProjectApplicationCredentials.vue 增加能力区，项目详情页签改为“应用接入”。查询/编辑沿 PROJECT_QUERY/UPDATE；不存在不能点关闭，O2O/INSTORE只读；关闭后持续说明访问边界。
- [x] 现有 SFC harness 7项行为验证版本提交、确认、状态限制、晚响应隔离，无源码文本断言；前端全套232项通过。
- [x] targeted eslint/stylelint/prettier通过，实际 Vue/Element Plus/Chrome 预览五态并检查截图；类型检查已运行但仍56条既有诊断，隔离HEAD基线亦56条，新增0、消失0。没有宣称全量类型检查通过。

## 3. 汇合与交付

- [x] 自审合并合同、关闭权限、异常与提交后日志，运行组合回归；时间序列化与错误码/重试注册差异据真实失败修复，不压制。
- [x] docs/集成测试.md 记录自动化、浏览器挡板与未发布边界；AMP_PROGRESS.md 记录进度。最终交付截图/关键鉴权diff和测试结果，不擅自提交/推送/部署。

## 4. 提交与后续交付

- 本次按用户“提交，用中文说明”的授权分别提交前后端本地改动；不包含推送或部署。AMP_PROGRESS.md 受用户忽略规则排除，保留为本地进度记录，不强行纳入提交。
- [ ] 推送、前后端发布；pro143迁移验收及正式项目授予，当前没有环境业务写入。
- [ ] 按集成文档完成部署环境页面→管理HTTP→能力/审计→项目签名查单/下单/卡密的人工闭环，不把浏览器挡板当真实联调。
- [ ] 第4～6项可售配置、路由与目录系统操作人，以及首笔真实 HUA_SHENG 交易，仍不在本轮完成范围。
