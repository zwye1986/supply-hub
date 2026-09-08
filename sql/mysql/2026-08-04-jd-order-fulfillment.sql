-- 京东 VOP 订单履约账本。

-- 一、供应商履约状态账本。执行前先运行 2026-07-29 来源列迁移和 2026-07-30 数据回填。
CREATE TABLE IF NOT EXISTS `trade_order_fulfillment` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '履约记录编号',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
    `trade_order_id` bigint NOT NULL COMMENT '商城订单编号',
    `trade_order_no` varchar(64) NOT NULL COMMENT '商城订单号',
    `supplier_code` varchar(64) NOT NULL COMMENT '供应商编码，例如 JD',
    `out_order_no` varchar(64) NOT NULL COMMENT '供应商侧稳定幂等订单号',
    `order_item_ids` json NOT NULL COMMENT '本次履约覆盖的商城订单项编号',
    `request_hash` char(64) NOT NULL COMMENT '商品及收件快照 SHA-256',
    `status` tinyint NOT NULL COMMENT '状态：0 INIT、10 SUBMITTING、20 SUBMITTED、30 UNKNOWN、40 FAILURE、50 CANCEL_PENDING、60 CANCELING、70 CANCELED、80 CANCEL_UNKNOWN、90 CANCEL_FAILURE、100 CLOSED',
    `jd_parent_order_id` bigint NULL COMMENT '京东父订单号',
    `jd_child_order_ids` json NULL COMMENT '京东子订单号',
    `jd_parent_type` int NULL COMMENT '京东父子单类型',
    `jd_order_price` decimal(20,2) NULL COMMENT '京东采购总金额',
    `jd_freight_price` decimal(20,2) NULL COMMENT '京东运费',
    `submit_retry_count` int NOT NULL DEFAULT 0 COMMENT '下单前准备次数',
    `next_submit_time` datetime NULL COMMENT '下次准备或提交时间',
    `query_retry_count` int NOT NULL DEFAULT 0 COMMENT '主动查单次数',
    `next_query_time` datetime NULL COMMENT '下次主动查单时间',
    `submit_time` datetime NULL COMMENT '首次提交时间',
    `success_time` datetime NULL COMMENT '京东下单确认成功时间',
    `cancel_retry_count` int NOT NULL DEFAULT 0 COMMENT '京东取消尝试次数',
    `next_cancel_time` datetime NULL COMMENT '下次京东取消时间',
    `cancel_time` datetime NULL COMMENT '京东取消确认时间',
    `error_code` varchar(64) NULL COMMENT '安全错误码',
    `error_summary` varchar(256) NULL COMMENT '安全错误摘要',
    `creator` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trade_fulfillment_order_supplier` (`tenant_id`, `trade_order_id`, `supplier_code`),
    UNIQUE KEY `uk_trade_fulfillment_out_order` (`out_order_no`),
    KEY `idx_trade_fulfillment_submit` (`tenant_id`, `status`, `next_submit_time`, `id`),
    KEY `idx_trade_fulfillment_query` (`tenant_id`, `status`, `next_query_time`, `id`),
    KEY `idx_trade_fulfillment_cancel` (`tenant_id`, `status`, `next_cancel_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易订单供应商履约账本';

-- 二、候选订单扫描索引。
SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'trade_order'
             AND index_name = 'idx_trade_order_jd_fulfillment'),
    'SELECT 1',
    'ALTER TABLE `trade_order` ADD KEY `idx_trade_order_jd_fulfillment` (`tenant_id`, `status`, `pay_status`, `delivery_type`, `source_type`, `id`)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'trade_order_item'
             AND index_name = 'idx_trade_order_item_supplier_order'),
    'SELECT 1',
    'ALTER TABLE `trade_order_item` ADD KEY `idx_trade_order_item_supplier_order` (`tenant_id`, `supplier_code`, `order_id`, `after_sale_status`)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- XXL-Job 管理台建议配置：
-- JobHandler：jdOrderFulfillmentJob
-- Cron：0/30 * * * * ?
-- 阻塞处理策略：单机串行；数据库唯一键和状态机仍负责跨节点幂等。
