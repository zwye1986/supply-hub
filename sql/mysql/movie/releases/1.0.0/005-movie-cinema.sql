-- 仅在独立电影数据库执行；平台主数据不包含商城 tenant_id。
-- 设计：docs/电影子系统与中台履约详细设计.md 第 2.1 节、第 5.1 节。
-- 使用对应 Liquibase 入口执行前向变更；已有表时应停止并核对结构，禁止覆盖或静默跳过。

CREATE TABLE `movie_cinema` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '影院主键ID',
  `cinema_code` varchar(32) NOT NULL COMMENT '国家专资办影院编码 (8位唯一业务键)',
  `cinema_name` varchar(128) NOT NULL COMMENT '影院标准名称',
  `standard_city_id` bigint NOT NULL COMMENT '所属标准城市ID',
  `city_name` varchar(64) DEFAULT NULL COMMENT '城市名称冗余',
  `region_name` varchar(64) DEFAULT NULL COMMENT '行政区名称 (如 朝阳区)',
  `address` varchar(256) NOT NULL COMMENT '影院详细物理地址',
  `longitude` decimal(10,6) DEFAULT NULL COMMENT '百度/高德经度',
  `latitude` decimal(10,6) DEFAULT NULL COMMENT '百度/高德纬度',
  `phone` varchar(64) DEFAULT NULL COMMENT '影院联系电话',
  `status` tinyint DEFAULT '1' COMMENT '状态 1-正常营业 0-停业下架',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cinema_code` (`cinema_code`),
  KEY `idx_city_status` (`standard_city_id`, `status`),
  KEY `idx_location` (`latitude`, `longitude`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电影平台标准影院库';
