-- 仅在独立电影数据库执行；平台主数据不包含商城 tenant_id。
-- 设计：docs/business/电影子系统与中台履约详细设计.md 第 2.1 节、第 5.1 节。
-- 使用对应 Liquibase 入口执行前向变更；已有表时应停止并核对结构，禁止覆盖或静默跳过。

CREATE TABLE `movie_film_mapping` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `channel` varchar(32) NOT NULL COMMENT '供应商渠道 (jy, jc)',
  `supplier_film_id` varchar(64) NOT NULL COMMENT '供应商原始影片ID',
  `standard_film_id` bigint NOT NULL COMMENT '关联的标准影片ID',
  `film_code` varchar(32) NOT NULL COMMENT '专资编码快照',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_supplier_film` (`channel`, `supplier_film_id`),
  KEY `idx_standard_film` (`standard_film_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商影片映射表';
