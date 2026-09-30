-- 京东最终子订单级取消任务。

CREATE TABLE IF NOT EXISTS `trade_jd_sub_order_cancel_task` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务编号',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
    `after_sale_id` bigint NOT NULL COMMENT '商城售后单编号',
    `trade_order_id` bigint NOT NULL COMMENT '商城订单编号',
    `order_item_id` bigint NOT NULL COMMENT '商城订单项编号',
    `fulfillment_id` bigint NOT NULL COMMENT '京东履约账本编号',
    `fulfillment_order_id` bigint NOT NULL COMMENT '京东最终子订单本地编号',
    `supplier_order_no` varchar(64) NOT NULL COMMENT '京东最终子订单号',
    `status` tinyint NOT NULL COMMENT '状态：10待处理、20处理中、30取消成功、40重试、50失败、60关闭',
    `retry_count` int NOT NULL DEFAULT 0 COMMENT '处理尝试次数',
    `next_process_time` datetime NULL COMMENT '下次处理时间',
    `process_time` datetime NULL COMMENT '取消成功时间',
    `error_code` varchar(64) NULL COMMENT '安全错误码',
    `error_summary` varchar(256) NULL COMMENT '安全错误摘要',
    `creator` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trade_jd_cancel_after_sale_order` (`tenant_id`, `after_sale_id`, `fulfillment_order_id`),
    KEY `idx_trade_jd_cancel_process` (`tenant_id`, `status`, `next_process_time`, `id`),
    KEY `idx_trade_jd_cancel_fulfillment` (`tenant_id`, `fulfillment_id`, `fulfillment_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='京东最终子订单取消任务';

-- 升级前尚未开始执行的整笔取消任务恢复为已提交，避免误取消未命中退款商品的其他子订单。
UPDATE `trade_order_fulfillment`
SET `status` = 20,
    `cancel_retry_count` = 0,
    `next_cancel_time` = NULL,
    `cancel_time` = NULL,
    `next_query_time` = NOW(),
    `next_delivery_query_time` = NOW(),
    `error_code` = NULL,
    `error_summary` = NULL
WHERE `deleted` = b'0'
  AND `supplier_code` = 'JD'
  AND `status` = 50;

-- 升级前已有退款申请的已提交履约单，触发一次父子单重同步并生成子订单取消任务。
UPDATE `trade_order_fulfillment` f
SET f.`next_query_time` = NOW()
WHERE f.`deleted` = b'0'
  AND f.`supplier_code` = 'JD'
  AND f.`status` = 20
  AND EXISTS (
      SELECT 1
      FROM `trade_order_item` i
      WHERE i.`tenant_id` = f.`tenant_id`
        AND i.`order_id` = f.`trade_order_id`
        AND i.`supplier_code` = 'JD'
        AND i.`after_sale_id` IS NOT NULL
        AND i.`after_sale_status` <> 0
        AND i.`deleted` = b'0'
  );

-- XXL-Job 管理台新增 jdSubOrderCancelJob，建议每 30 秒执行一次并使用单机串行阻塞策略。
