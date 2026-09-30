-- 渠道服务自有库表：tbsg_channel_refund
-- 缓存 ele.me /refund 退款请求的协议字段（notifyUrl 等）与中台 refundCaseNo，供中台 CHANNEL_NOTIFY 回调 ele.me refundCallback 时查询。
-- 属渠道服务独立 DB，不进中台 Liquibase，不与中台 DB 共用。详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7。
CREATE TABLE IF NOT EXISTS `tbsg_channel_refund` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `channel_refund_no` VARCHAR(128) NOT NULL COMMENT 'ele.me 渠道退款号',
    `transaction_id`     VARCHAR(64)  NOT NULL COMMENT 'ele.me 渠道交易号',
    `out_trade_no`       VARCHAR(64)  DEFAULT NULL COMMENT '原支付中台 outTradeNo',
    `refund_case_no`     VARCHAR(64)  DEFAULT NULL COMMENT '中台退款 Case 号（outRefundNo）',
    `refund_amount`      INT          DEFAULT NULL COMMENT '退款金额（分）',
    `notify_url`         VARCHAR(512) DEFAULT NULL COMMENT 'ele.me 退款回调地址',
    `refund_status`      VARCHAR(16)  DEFAULT 'PROCESSING' COMMENT '本地退款状态：PROCESSING/SUCCESS/FAIL',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_channel_refund_no` (`channel_refund_no`),
    KEY `idx_transaction_id` (`transaction_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道退款记录表';
