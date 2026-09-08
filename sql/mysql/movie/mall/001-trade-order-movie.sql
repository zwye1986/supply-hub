-- 仅在商城数据库执行；不得由 movie-server 加载。
-- 设计：docs/电影子系统与中台履约详细设计.md 第 2.1 节、第 5.1 节。
-- 使用对应 Liquibase 入口执行前向变更；已有表时应停止并核对结构，禁止覆盖或静默跳过。

CREATE TABLE `trade_order_movie` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '商城租户编号',
  `order_id` bigint NOT NULL COMMENT '商城主订单ID (关联 trade_order.id)',
  `order_no` varchar(64) NOT NULL COMMENT '商城全局唯一订单号',
  `user_id` bigint NOT NULL COMMENT '买家用户ID',
  `mobile` varchar(20) NOT NULL COMMENT '取票手机号 (接收短信/自助机取票)',

  -- 影片与影院快照 (不可变)
  `film_id` bigint NOT NULL COMMENT '标准影片ID',
  `film_code` varchar(32) NOT NULL COMMENT '影片专资编码',
  `film_name` varchar(128) NOT NULL COMMENT '影片名称',
  `poster_url` varchar(512) DEFAULT NULL COMMENT '海报封面',
  `film_type` varchar(64) DEFAULT NULL COMMENT '语言与制式版本 (如 国语 2D)',
  `cinema_id` bigint NOT NULL COMMENT '标准影院ID',
  `cinema_code` varchar(32) NOT NULL COMMENT '影院专资编码',
  `cinema_name` varchar(128) NOT NULL COMMENT '影院名称',
  `cinema_address` varchar(256) DEFAULT NULL COMMENT '影院详细地址',
  `hall_name` varchar(64) NOT NULL COMMENT '放映厅名称',

  -- 场次与座位详情
  `show_group_id` varchar(64) NOT NULL COMMENT '逻辑场次聚合键 (MD5)',
  `show_date` date NOT NULL COMMENT '放映日期',
  `show_time` datetime NOT NULL COMMENT '开场放映时间',
  `close_time` datetime DEFAULT NULL COMMENT '散场结束时间',
  `ticket_count` int NOT NULL DEFAULT '1' COMMENT '购票总张数',
  `seat_names` varchar(256) NOT NULL COMMENT '物理座位名称 (如 5排8座,5排9座)',
  `seat_details` text NOT NULL COMMENT '结构化座位坐标明细 JSON',

  -- 线下履约取票凭证
  `ticket_code` varchar(64) DEFAULT NULL COMMENT '线下自助取票机换票码/取票码',
  `ticket_qr_code` varchar(512) DEFAULT NULL COMMENT '取票二维码URL或内容',
  `ticket_sms_content` text DEFAULT NULL COMMENT '出票短信原始通知文案',
  `print_status` varchar(32) DEFAULT 'UNPRINTED' COMMENT '线下出纸质票状态 UNPRINTED/PRINTED',

  -- 供应链后台财务与对账 (对 C 端脱敏)
  `buyer_order_no` varchar(64) DEFAULT NULL COMMENT '供应链中台采购单号',
  `supplier_id` varchar(32) DEFAULT NULL COMMENT '中台当前或最终成功供应商编码投影 (如 JC, JY)',
  `external_order_no` varchar(128) DEFAULT NULL COMMENT '当前或最终成功供应商外部订单号投影',
  `cost_price` int NOT NULL DEFAULT '0' COMMENT '最终成功供应商总成本投影 (单位: 分，非中台权威事实)',
  `profit_price` int NOT NULL DEFAULT '0' COMMENT '商城内部毛利投影 (单位: 分，须按商城已发布口径计算)',

  `creator` varchar(64) NOT NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NOT NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_order_id` (`tenant_id`, `order_id`),
  UNIQUE KEY `uk_tenant_order_no` (`tenant_id`, `order_no`),
  KEY `idx_tenant_user_id` (`tenant_id`, `user_id`),
  KEY `idx_tenant_mobile` (`tenant_id`, `mobile`),
  KEY `idx_tenant_ticket_code` (`tenant_id`, `ticket_code`),
  KEY `idx_tenant_show_time` (`tenant_id`, `show_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城电影订单扩展详情表';
