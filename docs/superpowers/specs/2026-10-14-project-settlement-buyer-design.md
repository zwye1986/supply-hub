# 项目独立结算方设计

> 日期：2026-10-14；用户已确认业务规则并授权实施。前端仓库：`/Users/mac/codespace/supplychainhubweb`。

## 1. 业务不变量

项目归属 `buyerCode` 与结算方 `settlementBuyerCode` 分离。结算方必须是已存在且已启用的分销商，不校验组织范围、上下级或组织等级。项目商品目录、定价、路由、鉴权与订单归属仍使用项目关联分销商；预付资金冻结、扣款、释放与退款使用结算方账户。

结算方仅在 DRAFT 可修改；首次启动（包括创建为 ACTIVE、PUT 启动、PATCH 启动）后永久不可修改，SUSPENDED / CLOSED / ARCHIVED 同样锁定。DRAFT 的 PUT 可以同时修改结算方并启动，所有校验在项目行锁下完成。启动时再次确认所选分销商已启用，避免草稿期间停用后启动。启动后原结算方因停用不影响其他档案字段的原值保存；本次不新增在途交易状态策略。

## 2. 保存与兼容契约

POST / PUT 新增 `settlementBuyerCode`（采购商永久业务编码，最大 32 字符）。创建省略时默认为关联分销商；更新省略时保持原结算方，禁止把省略解释为重置。显式空串不作为默认值处理，按分销商不存在拒绝。非草稿显式提交相同结算方允许保存，提交不同结算方返回新错误 `PROJECT_SETTLEMENT_BUYER_IMMUTABLE`（1050001182）。不存在复用 `BUYER_PROFILE_NOT_FOUND`，未启用复用 `BUYER_STATUS_CONFLICT`。`settlementPartyType` 仍只接受 DISTRIBUTOR，PLATFORM 仍拒绝。

普通列表及详情返回 `settlementBuyerCode`、`settlementBuyerName`；字段注释与分销商 DDL 中的编码/名称语义保持一致。名称从当前分销商档案读取。结算方缺失记录不得伪造另一方名称。

## 3. 数据与资金

新增 Liquibase 067：`sc_buyer_project.settlement_buyer_id`（结算方分销商内部主键，逻辑引用sc_buyer.id），存 ID 不复制业务编码，不建物理外键。历史项目回填 buyer_id。允许 NULL 兼容历史内部写入，应用读取仅 NULL 回退到 buyer_id；管理端新建必须写入实际 ID。

`sc_fulfillment_request.settlement_buyer_id` 为结算方分销商内部主键，逻辑引用sc_buyer.id，历史请求回填 buyer_id。标准受理使用带 projectId 的 FreezeCommand，由资金应用层读取项目并核验所属 buyerId，冻结实际结算账户；把预留快照中的 buyerId 在同一事务内写入履约请求。后续扣款、释放与异常处理读取该冻结身份，不能用项目当前配置重新推断历史预留。

到店购冻结也通过 projectId 解析结算方，确认/释放沿用持久化 reservationNo；退款按原支付流水 reservationNo 读取预留的 buyerId，返还原扣款方。无项目的旧 O2O 流程不改变。border 不访问账户/账本 DAL，所有账户读取与写入经现有资金应用服务，沿用独立事务管理器。

## 4. 前端

关联分销商与结算方候选分开：结算方请求 `GET /buyers?view=simple&status=ACTIVE`，后端 simple 投影落实状态筛选。结算方仍为可搜索下拉，显示名称、提交业务编码；非草稿编辑禁用控件。已保存但后来停用的结算方仍以详情名称回显，作为禁用项补入列表，不能再次选择。创建默认跟随关联分销商，最终由后端校验。

## 5. 验证与交付

单测覆盖独立结算方、草稿改结算方、非草稿禁止变更、原值与省略保持、不存在/未启用、启动前复核、资金身份分离与历史回退。契约测试验证请求字段与列表/详情字段。真实 MySQL/Liquibase 测试覆盖历史回填、持久化、事务回滚、启动与改结算方并发；资金集成验证仅结算方账户动账、冻结/确认/释放/退款不串户。运行供应链适用单测、契约、架构门禁及前端真实组件测试/类型检查。联调步骤与实测范围维护在 `docs/集成测试.md`，本地测试不替代测试环境部署与浏览器联调。
