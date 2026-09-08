# 项目业务身份与 SaaS 外部租户路由分离

采购商侧请求只能通过 `appId/appKey` 认证到 `BuyerProject`，中台业务归属、用户唯一性和数据隔离分别使用 `projectId` 和 `(projectId, buyerUserId)`。`sourceSystem/externalTenantId` 不再是采购商侧可提交的业务身份，也不参与中台业务唯一键。

`externalTenantId` 不删除。它与 `sourceSystem` 一起作为 SaaS 商城的外部路由坐标，保存在项目的不可变 `BuyerIntegrationProfileVersion` 能力配置中。会员或收银台外呼前，应用层用业务操作已固化的 `projectId/profileVersionId/capability` 解析路由，再把 `(projectId, profileVersionId, sourceSystem, externalTenantId, buyerUserId)` 交给出站 Port。Adapter 校验路由与项目档案版本一致，调用方不得临时覆盖。

这一分层允许同一 SaaS 租户服务多个采购项目，但不合并项目的订单、账户、用户或幂等事实。`buyerUserId` 始终是不透明字符串，禁止根据其字面值转换为 `Long` 会员主键，也禁止用 `projectId` 或 `buyerUserId` 拼接、推导 `externalTenantId`。
