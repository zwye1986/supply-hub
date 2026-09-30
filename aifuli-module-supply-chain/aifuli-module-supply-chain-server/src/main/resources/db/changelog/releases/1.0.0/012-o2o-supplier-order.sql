-- 表中文名：O2O固定商户供应商订单
-- 核心职责：保存O2O场景采购方支付成功且资金处理后为固定外部商户（例如盒马）创建的供应商履约订单与结算事实
-- 设计依据：docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#9.2
--   采购方支付成功且采购资金处理完成后，为SupplierChannelBinding固定映射出的外部商户创建至多一张SupplierOrder；
--   不参与候选排序、不自动切换商户；CHANNEL_NOTIFY操作通过supplier_order_id关联本表。
CREATE TABLE sc_o2o_supplier_order (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'O2O供应商订单内部主键',
    supplier_order_no VARCHAR(32) NOT NULL COMMENT 'O2O供应商订单稳定业务编号',
    order_id BIGINT NOT NULL COMMENT '供应链总订单主键，逻辑引用sc_supply_chain_order.id；O2O一笔采购至多一张供应商订单',
    o2o_detail_id BIGINT NOT NULL COMMENT 'O2O渠道详情主键，逻辑引用sc_o2o_channel_detail.id',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键',
    supplier_id BIGINT NOT NULL COMMENT '固定外部商户供应商主键，逻辑引用sc_supplier.id',
    supplier_channel_binding_id BIGINT NOT NULL COMMENT '受理时冻结的渠道商户映射版本主键，逻辑引用sc_supplier_channel_binding.id',
    supplier_code_snapshot VARCHAR(32) NOT NULL COMMENT '固定外部商户供应商编码快照',
    supplier_name_snapshot VARCHAR(128) NOT NULL COMMENT '固定外部商户供应商名称快照',
    channel_code VARCHAR(32) NOT NULL COMMENT 'O2O渠道编码，一期为ELEME',
    transaction_id VARCHAR(128) NOT NULL COMMENT '渠道交易唯一标识快照，便于对账与运维检索',
    settlement_mode VARCHAR(32) NOT NULL COMMENT '结算模式：CHANNEL_AMOUNT按渠道金额结算，一期固定',
    channel_pay_amount BIGINT NOT NULL COMMENT '渠道支付金额快照，单位为分',
    supplier_settlement_amount BIGINT NOT NULL COMMENT '供应商应结金额，单位为分；一期等于channel_pay_amount',
    currency CHAR(3) NOT NULL COMMENT '结算币种，一期固定CNY',
    fulfillment_status SMALLINT NOT NULL COMMENT '履约状态：0待通知，10通知中，20结果未知，30成功，40失败，90人工处理',
    settlement_status SMALLINT NOT NULL COMMENT '结算核对状态：0待对账，10已匹配，20有差异，90人工处理',
    external_fulfillment_no VARCHAR(128) NULL COMMENT '渠道侧履约单号，由225推送或权威查单返回',
    active_flag TINYINT NULL COMMENT '活动尝试标记：1非终态，NULL终态，用于限制同一采购订单只有一个活动供应商订单',
    effective_success_flag TINYINT NULL COMMENT '有效成功标记：1唯一有效成功，NULL其他，用于限制同一采购订单至多一张有效成功',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '供应商订单乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '供应商订单创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '供应商订单最后更新时间，UTC',
    completed_at DATETIME(3) NULL COMMENT '供应商订单进入业务终态时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_o2o_supplier_order_no (supplier_order_no),
    UNIQUE KEY uk_sc_o2o_supplier_order_order (order_id),
    UNIQUE KEY uk_sc_o2o_supplier_order_detail (o2o_detail_id),
    UNIQUE KEY uk_sc_o2o_supplier_order_active (order_id, active_flag),
    UNIQUE KEY uk_sc_o2o_supplier_order_success (order_id, effective_success_flag),
    UNIQUE KEY uk_sc_o2o_supplier_order_external (supplier_id, external_fulfillment_no),
    KEY idx_sc_o2o_supplier_order_supplier (supplier_id, created_at),
    KEY idx_sc_o2o_supplier_order_status (fulfillment_status, updated_at),
    KEY idx_sc_o2o_supplier_order_settlement (settlement_status, updated_at),
    CONSTRAINT ck_sc_o2o_supplier_order_mode CHECK (settlement_mode = 'CHANNEL_AMOUNT'),
    CONSTRAINT ck_sc_o2o_supplier_order_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_o2o_supplier_order_amount CHECK (channel_pay_amount >= 0 AND supplier_settlement_amount = channel_pay_amount),
    CONSTRAINT ck_sc_o2o_supplier_order_flags CHECK (active_flag IS NULL OR active_flag = 1),
    CONSTRAINT ck_sc_o2o_supplier_order_success_flag CHECK (effective_success_flag IS NULL OR effective_success_flag = 1),
    CONSTRAINT ck_sc_o2o_supplier_order_external CHECK (external_fulfillment_no IS NULL OR external_fulfillment_no <> ''),
    CONSTRAINT ck_sc_o2o_supplier_order_fulfillment CHECK (fulfillment_status IN (0, 10, 20, 30, 40, 90)),
    CONSTRAINT ck_sc_o2o_supplier_order_settlement_status CHECK (settlement_status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='O2O场景固定外部商户供应商履约订单与结算事实';

-- 给O2O外部操作表补充渠道通知操作关联的供应商订单主键
-- CHANNEL_NOTIFY操作通过该字段关联sc_o2o_supplier_order；PREORDER/PAYMENT_QUERY/BUYER_REFUND/REFUND_QUERY为空
ALTER TABLE sc_o2o_operation
    ADD COLUMN supplier_order_id BIGINT NULL COMMENT '渠道通知操作关联的O2O供应商订单主键，逻辑引用sc_o2o_supplier_order.id' AFTER refund_case_id;

-- 渠道通知操作按供应商订单检索的辅助索引
CREATE INDEX idx_sc_o2o_operation_supplier_order ON sc_o2o_operation (supplier_order_id, created_at);
