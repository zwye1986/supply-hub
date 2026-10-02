-- 标准商品项目订单首次永久冻结报价：删除报价用户绑定、失效时间改为可空历史字段、
-- 新增「project_id + buyer_order_no」唯一键；到期索引已无用途一并删除。
-- 破坏性 DDL 前的 HALT 预条件检查见 db.changelog-master.yaml 051 变更集。
-- 设计见 docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#4-增量DDL与历史阻断检查

ALTER TABLE sc_standard_pricing_quote
  DROP KEY idx_sc_standard_quote_expiry,
  DROP COLUMN buyer_user_id,
  MODIFY COLUMN quote_token VARCHAR(64) NOT NULL COMMENT '随机内部追踪标识；不作为HTTP请求字段，报价永久键为项目+采购商订单号',
  MODIFY COLUMN expires_at DATETIME(3) NULL COMMENT '历史报价失效时间，Asia/Shanghai；永久冻结报价为空且不作为本流程下单条件',
  ADD UNIQUE KEY uk_sc_standard_quote_project_order (project_id, buyer_order_no),
  COMMENT = '标准商品项目订单首次永久冻结报价';
