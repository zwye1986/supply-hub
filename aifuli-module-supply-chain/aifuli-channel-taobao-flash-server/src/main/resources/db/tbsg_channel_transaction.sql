-- 渠道服务自有库表：tbsg_channel_transaction
-- 缓存 ele.me /pay 下单时的协议字段（notifyUrl 等），供中台 CHANNEL_NOTIFY 回调 ele.me payCallback 时查询。
-- 属渠道服务独立 DB，不进中台 Liquibase，不与中台 DB 共用。详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7。
CREATE TABLE IF NOT EXISTS `tbsg_channel_transaction` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `transaction_id`  VARCHAR(64)  NOT NULL COMMENT 'ele.me 渠道交易号',
    `out_trade_no`    VARCHAR(64)  DEFAULT NULL COMMENT '中台返回的稳定外部交易号',
    `order_no`        VARCHAR(64)  DEFAULT NULL COMMENT '中台总订单号',
    `pay_amount`      INT          DEFAULT NULL COMMENT '支付金额（分）',
    `channel_user_id` VARCHAR(128) DEFAULT NULL COMMENT 'ele.me 用户标识（uid）',
    `notify_url`      VARCHAR(512) DEFAULT NULL COMMENT 'ele.me 平台回调地址',
    `subject`         VARCHAR(128) DEFAULT NULL COMMENT '商品标题',
    `pay_status`      VARCHAR(16)  DEFAULT 'NOTPAY' COMMENT '本地支付状态：NOTPAY/PAID/CLOSED',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_transaction_id` (`transaction_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道交易注册表';
