-- Trade 订单同步持久化租户商品池与平台展示价格快照
-- 编码：UTF-8 without BOM

ALTER TABLE `trade_order`
  ADD COLUMN `platform_total_price` decimal(20, 2) NULL
    COMMENT '下单时平台展示价格合计快照'
    AFTER `total_price`,
  ADD COLUMN `platform_price_unit` varchar(16) NULL
    COMMENT '平台展示价格单位技术码快照'
    AFTER `platform_total_price`,
  ADD COLUMN `platform_price_unit_name` varchar(64) NULL
    COMMENT '平台展示价格单位名称快照'
    AFTER `platform_price_unit`;

ALTER TABLE `trade_order_item`
  ADD COLUMN `pool_id` bigint NULL
    COMMENT '租户商品池 SPU 记录编号快照'
    AFTER `sku_id`,
  ADD COLUMN `pool_sku_id` bigint NULL
    COMMENT '租户商品池 SKU 记录编号快照'
    AFTER `pool_id`,
  ADD COLUMN `min_buy_count` int NULL
    COMMENT '商品池 SKU 起购数量快照'
    AFTER `count`,
  ADD COLUMN `supply_price` int NULL
    COMMENT '商品池供货价快照，单位：人民币分'
    AFTER `price`,
  ADD COLUMN `platform_price` decimal(20, 2) NULL
    COMMENT '平台展示单价快照'
    AFTER `supply_price`,
  ADD COLUMN `platform_total_price` decimal(20, 2) NULL
    COMMENT '平台展示价格小计快照'
    AFTER `platform_price`,
  ADD COLUMN `platform_price_unit` varchar(16) NULL
    COMMENT '平台展示价格单位技术码快照'
    AFTER `platform_total_price`,
  ADD COLUMN `platform_price_unit_name` varchar(64) NULL
    COMMENT '平台展示价格单位名称快照'
    AFTER `platform_price_unit`,
  ADD INDEX `idx_trade_order_item_pool_sku_id` (`pool_sku_id`);
