-- 吾盛一期：外部平台价格换算规则表
-- 编码：UTF-8 without BOM
-- 说明：
-- 1. 商品池 sale_price 存人民币分（CNY_CENT），对外展示/扣减按本表换算。
-- 2. 吾盛场景 target_price_unit=POINT；接口返回 priceUnit 取规则目标单位。
-- 3. 换算公式：targetAmount = round(sourceAmount * rate_numerator / rate_denominator, rounding_mode)
-- 4. 规则选取：商品池 price_rule_id > 平台默认规则(is_default=1 且启用)。

CREATE TABLE IF NOT EXISTS `mall_platform_price_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `platform_code` varchar(32) NOT NULL COMMENT '平台编码，例如 WUSHENG',
  `rule_name` varchar(64) NOT NULL COMMENT '规则名称',
  `source_price_unit` varchar(16) NOT NULL DEFAULT 'CNY_CENT' COMMENT '来源单位：CNY_CENT',
  `target_price_unit` varchar(16) NOT NULL COMMENT '目标单位：POINT / CNY_CENT',
  `rate_numerator` int NOT NULL COMMENT '换算分子，必须 > 0',
  `rate_denominator` int NOT NULL COMMENT '换算分母，必须 > 0',
  `rounding_mode` varchar(16) NOT NULL DEFAULT 'HALF_UP' COMMENT '取整方式：HALF_UP / UP / DOWN',
  `is_default` bit(1) NOT NULL DEFAULT b'1' COMMENT '是否平台默认规则：同平台启用中仅允许一条默认',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：0 禁用，1 启用',
  `start_time` datetime DEFAULT NULL COMMENT '生效开始时间，空表示立即生效',
  `end_time` datetime DEFAULT NULL COMMENT '生效结束时间，空表示长期有效',
  `remark` varchar(512) DEFAULT NULL COMMENT '备注',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_price_rule_tenant_platform` (`tenant_id`, `platform_code`, `status`),
  KEY `idx_price_rule_default` (`tenant_id`, `platform_code`, `is_default`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部平台价格换算规则表';

-- 吾盛默认规则：1 元 = 100 积分 => 人民币分与积分 1:1
-- 执行前请将 {WUSHENG_TENANT_ID} 替换为实际吾盛租户 ID
-- INSERT INTO `mall_platform_price_rule` (
--   `tenant_id`, `platform_code`, `rule_name`,
--   `source_price_unit`, `target_price_unit`,
--   `rate_numerator`, `rate_denominator`, `rounding_mode`,
--   `is_default`, `status`, `remark`
-- ) VALUES (
--   {WUSHENG_TENANT_ID}, 'WUSHENG', '吾盛默认积分比例',
--   'CNY_CENT', 'POINT',
--   1, 1, 'HALF_UP',
--   b'1', 1, '1元=100积分，即1分=1积分'
-- );
