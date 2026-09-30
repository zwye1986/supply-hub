-- 交易订单来源快照列，必须早于 2026-07-30 历史数据回填脚本执行。

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'trade_order'
             AND column_name = 'source_type'),
    'SELECT 1',
    'ALTER TABLE `trade_order` ADD COLUMN `source_type` varchar(32) NOT NULL DEFAULT ''SELF'' COMMENT ''商品来源类型快照：JD、SELF、MIXED'' AFTER `terminal`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'trade_order_item'
             AND column_name = 'supplier_code'),
    'SELECT 1',
    'ALTER TABLE `trade_order_item` ADD COLUMN `supplier_code` varchar(64) NULL COMMENT ''供应商编码快照，例如 JD'' AFTER `sku_id`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'trade_order_item'
             AND column_name = 'source_spu_id'),
    'SELECT 1',
    'ALTER TABLE `trade_order_item` ADD COLUMN `source_spu_id` varchar(128) NULL COMMENT ''外部 SPU 编号快照'' AFTER `supplier_code`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'trade_order_item'
             AND column_name = 'source_sku_id'),
    'SELECT 1',
    'ALTER TABLE `trade_order_item` ADD COLUMN `source_sku_id` varchar(128) NULL COMMENT ''外部 SKU 编号快照'' AFTER `source_spu_id`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
