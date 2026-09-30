-- 商品搜索 ES 同步事件表
-- 说明：该表用于记录 MySQL 商品池变化后的 ES 待同步事件，XXL-Job 会定时消费该表并写入 ES。

CREATE TABLE IF NOT EXISTS `mall_product_search_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL COMMENT '租户编号',
  `pool_id` bigint NOT NULL COMMENT '租户商品池 SPU 编号',
  `spu_id` bigint DEFAULT NULL COMMENT '平台商品 SPU 编号',
  `event_type` varchar(32) NOT NULL COMMENT '事件类型：POOL_ADD、POOL_UPDATE、POOL_STATUS、POOL_SKU_STATUS、POOL_DELETE、JD_PRICE_CHANGE',
  `sync_status` tinyint NOT NULL DEFAULT 0 COMMENT '同步状态：0 待同步，1 同步中，2 成功，3 失败',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '重试次数',
  `last_error` varchar(1024) DEFAULT NULL COMMENT '最近一次失败原因',
  `next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可重试时间',
  `first_sync_time` datetime DEFAULT NULL COMMENT '首次同步时间',
  `last_sync_time` datetime DEFAULT NULL COMMENT '最近同步时间',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_pool` (`tenant_id`, `pool_id`),
  KEY `idx_sync_status_retry` (`sync_status`, `next_retry_time`, `id`),
  KEY `idx_spu_id` (`spu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品搜索 ES 同步事件表';
