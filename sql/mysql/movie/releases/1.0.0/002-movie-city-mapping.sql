-- 仅在独立电影数据库执行；平台主数据不包含商城 tenant_id。
-- 设计：docs/电影子系统与中台履约详细设计.md 第 2.1 节、第 5.1 节。
-- 使用对应 Liquibase 入口执行前向变更；已有表时应停止并核对结构，禁止覆盖或静默跳过。

CREATE TABLE `movie_city_mapping` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `channel` varchar(32) NOT NULL COMMENT '供应商渠道编码 (jy, jc)',
  `supplier_city_id` varchar(64) NOT NULL COMMENT '供应商原始城市ID',
  `supplier_city_name` varchar(64) DEFAULT NULL COMMENT '供应商原始城市名',
  `standard_city_id` bigint NOT NULL COMMENT '绑定的标准城市ID (关联 movie_city.id)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_supplier_city` (`channel`, `supplier_city_id`),
  KEY `idx_standard_city` (`standard_city_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商城市映射表';
