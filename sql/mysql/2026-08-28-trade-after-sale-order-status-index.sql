-- 京东履约按主订单判断活动售后时使用的查询索引。
-- 设计依据：docs/superpowers/specs/2026-08-28-jd-order-active-after-sale-block-design.md#3-阻断范围与时机

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 'trade_after_sale'
             AND index_name = 'idx_trade_after_sale_order_status'),
    'SELECT 1',
    'ALTER TABLE `trade_after_sale` ADD KEY `idx_trade_after_sale_order_status` (`tenant_id`, `order_id`, `deleted`, `status`)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
