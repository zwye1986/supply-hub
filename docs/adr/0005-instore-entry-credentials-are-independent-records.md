# 到店购稳定用户绑定与一次性入口凭证分离

`sc_instore_channel_user_binding` 只保存采购商租户范围内的稳定用户绑定，不再承载可复用 token 或匿名渠道会员号。每次首页或订单详情入口都新建一条独立的短期凭证；同一用户允许同时存在多枚未消费凭证，每枚凭证只能成功消费一次，默认十分钟过期且有效期可配置。数据库只保存 token 的 SHA-256 摘要，明文只在签发响应中出现且禁止写日志。详情凭证必须额外绑定目标到店购订单，首页凭证与详情凭证不得互换。

消费状态为 `ACTIVE/PROCESSING/USED/EXPIRED`。会员接口先以版本 CAS 把一枚凭证从 `ACTIVE` 抢占为 `PROCESSING`，采购商会员资料查询成功后才推进为 `USED`；明确失败时，未过期凭证释放回 `ACTIVE`。这保证并发请求只有一个能转发会员查询，也避免外部服务瞬时失败提前烧毁凭证；进程崩溃遗留的 `PROCESSING` 凭证不再被重新抢占，到期后失效。

旧绑定表上的 `channel_user_id/token/token_status/token_expires_at` 以及订单上的匿名 `channel_user_id` 仅作为 `013` 兼容列保留，由 `014` 前向迁移改为可空，生产路径不再读写。`014` 同时以 `(source_system, external_tenant_id, buyer_user_id, channel_code)` 唯一键替换旧 `(buyer_id, buyer_user_id, channel_code)` 唯一键。
