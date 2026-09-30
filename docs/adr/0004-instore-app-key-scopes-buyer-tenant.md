# 到店购 app_key 唯一标识采购商租户作用域

> 项目化目标态的业务归属规则已由 [ADR-0006](./0006-project-identity-separates-external-tenant-route.md) 取代。本 ADR 仅保留为 `sourceSystem/externalTenantId` 过渡实现的历史依据。

旅通建单接口不再携带入口 token，也不包含 `sourceSystem` 或 `externalTenantId`，因此每个采购商租户必须配置独立 `app_key`。`aifuli-channel-rtmart-server` 验证 `app_key` 后，将它映射为 `(sourceSystem, externalTenantId)`，再把该租户作用域与协议中的 `user_code` 一起提交给供应链中台。`user_code` 继续严格等于不透明字符串 `buyer_user_id`，不得编码租户信息，也不得直接转换为 `Long` 会员主键；会员资料必须通过 `(sourceSystem, externalTenantId, buyer_user_id)` 端口解析。同一 `app_key` 禁止跨采购商租户共享。
