-- 仅在独立电影数据库执行；平台主数据不包含商城 tenant_id。
-- 设计：docs/电影子系统与中台履约详细设计.md 第 2.1 节、第 5.1 节。
-- 使用对应 Liquibase 入口执行前向变更；已有表时应停止并核对结构，禁止覆盖或静默跳过。

CREATE TABLE `movie_film` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '影片主键ID',
  `film_code` varchar(32) NOT NULL COMMENT '国家专资影片编码 (12位唯一业务键)',
  `film_name` varchar(128) NOT NULL COMMENT '影片标准中文名',
  `poster_url` varchar(512) DEFAULT NULL COMMENT '海报封面图URL',
  `duration` int DEFAULT NULL COMMENT '影片时长 (分钟)',
  `publish_date` date DEFAULT NULL COMMENT '公映日期',
  `director` varchar(128) DEFAULT NULL COMMENT '导演姓名',
  `cast_actors` varchar(512) DEFAULT NULL COMMENT '主要演职员表',
  `intro` text COMMENT '影片剧情简介',
  `film_type` varchar(64) DEFAULT NULL COMMENT '类型标签 (科幻/动作/剧情)',
  `score` decimal(3,1) DEFAULT '0.0' COMMENT '大众评分 (如 9.3)',
  `is_presell` tinyint(1) DEFAULT '0' COMMENT '是否预售 0-热映中 1-预售待映',
  `status` tinyint DEFAULT '1' COMMENT '状态 1-上架 0-下架',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_film_code` (`film_code`),
  KEY `idx_publish_presell` (`publish_date`, `is_presell`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电影平台标准影片库';
