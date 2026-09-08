-- 仅在独立电影数据库执行；平台主数据不包含商城 tenant_id。
-- 设计：docs/电影子系统与中台履约详细设计.md 第 2.1 节、第 5.1 节。
-- 使用对应 Liquibase 入口执行前向变更；已有表时应停止并核对结构，禁止覆盖或静默跳过。

CREATE TABLE `movie_city` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '城市主键ID',
  `city_code` varchar(32) NOT NULL COMMENT '行政区划统一代码 (如 110100)',
  `city_name` varchar(64) NOT NULL COMMENT '城市标准名称 (如 北京市)',
  `pin_yin` varchar(64) DEFAULT NULL COMMENT '拼音全拼 (beijing)',
  `first_letter` char(1) DEFAULT NULL COMMENT '首字母索引 (B)',
  `is_hot` tinyint(1) DEFAULT '0' COMMENT '是否热门城市 0-否 1-是',
  `status` tinyint DEFAULT '1' COMMENT '状态 1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_city_code` (`city_code`),
  KEY `idx_letter_hot` (`first_letter`, `is_hot`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电影平台标准城市表';
