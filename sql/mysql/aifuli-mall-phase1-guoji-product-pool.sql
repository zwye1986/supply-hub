/*
 Navicat / MySQL migration

 项目：吾盛一期 - 郭际分工部分
 范围：商品分类、供应商、商品 SPU/SKU 扩展、来源扩展表、商品池表
 说明：
 1. 本脚本为增量 SQL，基于现有 yudao mall 表结构执行。
 2. MySQL 原生 ALTER TABLE ADD COLUMN 不支持所有版本的 IF NOT EXISTS；
    如线上库已存在同名字段/索引，请先手工跳过对应语句。
 3. 一期只做京东实物商品，但 SPU 预留 product_type / fulfill_type / need_delivery_address，
    便于后续接点餐、电影票、卡券、虚拟权益等非快递商品。
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =========================================================
-- 1. 商品分类：支持最多四级，任意层级可挂商品
-- =========================================================

ALTER TABLE `product_category`
    ADD COLUMN `level` tinyint NOT NULL DEFAULT 1 COMMENT '分类层级：1-4' AFTER `parent_id`,
    ADD COLUMN `tree_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '分类路径，例如 /1/12/35/' AFTER `level`;

-- 初始化已有分类路径。执行前建议备份；如果已有更复杂历史数据，可按实际数据重算。
UPDATE `product_category`
SET `level` = 1,
    `tree_path` = CONCAT('/', `id`, '/')
WHERE `parent_id` = 0;

UPDATE `product_category` c
    JOIN `product_category` p ON c.`parent_id` = p.`id`
SET c.`level` = p.`level` + 1,
    c.`tree_path` = CONCAT(p.`tree_path`, c.`id`, '/')
WHERE c.`parent_id` <> 0;

-- 多执行几轮，兼容已有三、四级分类按父级递推路径。
UPDATE `product_category` c
    JOIN `product_category` p ON c.`parent_id` = p.`id`
SET c.`level` = p.`level` + 1,
    c.`tree_path` = CONCAT(p.`tree_path`, c.`id`, '/')
WHERE c.`parent_id` <> 0;

UPDATE `product_category` c
    JOIN `product_category` p ON c.`parent_id` = p.`id`
SET c.`level` = p.`level` + 1,
    c.`tree_path` = CONCAT(p.`tree_path`, c.`id`, '/')
WHERE c.`parent_id` <> 0;

CREATE INDEX `idx_product_category_parent` ON `product_category` (`parent_id`);
CREATE INDEX `idx_product_category_level_status` ON `product_category` (`level`, `status`);
CREATE INDEX `idx_product_category_tree_path` ON `product_category` (`tree_path`);

-- =========================================================
-- 2. 商城供应商：mall 边界内维护，不直接依赖 erp_supplier
-- =========================================================

CREATE TABLE IF NOT EXISTS `mall_supplier` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `supplier_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '供应商编码，例如 JD',
    `supplier_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '供应商名称',
    `supplier_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'OTHER' COMMENT '供应商类型：JD、SELF、OTHER',
    `contact_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '联系人',
    `contact_mobile` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '联系电话',
    `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态：0 启用，1 禁用',
    `default_delivery_template_id` bigint NULL DEFAULT NULL COMMENT '默认运费模板 ID，商品未配置运费模板时使用',
    `config_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '供应商接口配置 JSON，不建议存储敏感明文',
    `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备注',
    `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号，平台供应商建议为 0',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_mall_supplier_code` (`supplier_code`) USING BTREE,
    KEY `idx_mall_supplier_status` (`status`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商城供应商';

INSERT INTO `mall_supplier` (`supplier_code`, `supplier_name`, `supplier_type`, `status`, `remark`, `tenant_id`)
VALUES ('JD', '京东', 'JD', 0, '吾盛一期京东商品供应商', 0)
ON DUPLICATE KEY UPDATE
    `supplier_name` = VALUES(`supplier_name`),
    `supplier_type` = VALUES(`supplier_type`),
    `status` = VALUES(`status`),
    `remark` = VALUES(`remark`),
    `update_time` = CURRENT_TIMESTAMP;

-- =========================================================
-- 3. 商品 SPU 扩展：平台商品、来源、价格、起购量、热销、兼容虚拟商品
-- =========================================================

ALTER TABLE `product_spu`
    ADD COLUMN `owner_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PLATFORM' COMMENT '商品归属：PLATFORM 平台商品，TENANT 租户自营预留' AFTER `id`,
    ADD COLUMN `owner_tenant_id` bigint NULL DEFAULT NULL COMMENT '自营商品所属租户，一期不用' AFTER `owner_type`,
    ADD COLUMN `supplier_id` bigint NULL DEFAULT NULL COMMENT '供应商 ID' AFTER `owner_tenant_id`,
    ADD COLUMN `source_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'SELF' COMMENT '来源类型：JD、SELF、OTHER' AFTER `supplier_id`,
    ADD COLUMN `source_platform` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '来源平台编码，例如 JD' AFTER `source_type`,
    ADD COLUMN `source_spu_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '外部 SPU ID' AFTER `source_platform`,
    ADD COLUMN `source_status` tinyint NULL DEFAULT NULL COMMENT '外部商品状态' AFTER `source_spu_id`,
    ADD COLUMN `supply_price` int NOT NULL DEFAULT 0 COMMENT '平台供应价，单位：分' AFTER `cost_price`,
    ADD COLUMN `hot_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '热销标识，例如 HOME_HOT、JD_HOT' AFTER `status`,
    ADD COLUMN `min_buy_count` int NOT NULL DEFAULT 1 COMMENT 'SPU 起购量，最终以下单 SKU 起购量为准' AFTER `stock`,
    ADD COLUMN `product_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PHYSICAL' COMMENT '商品形态：PHYSICAL 实物，VIRTUAL 虚拟，SERVICE 服务，CARD 卡券，OTHER 其他' AFTER `min_buy_count`,
    ADD COLUMN `fulfill_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'EXPRESS' COMMENT '履约方式：EXPRESS 快递，PICK_UP 自提，VIRTUAL_CODE 虚拟码，API_REDEEM 接口兑换，OFFLINE_VERIFY 线下核销，NONE 无需履约' AFTER `product_type`,
    ADD COLUMN `need_delivery_address` bit(1) NOT NULL DEFAULT b'1' COMMENT '是否需要收货地址' AFTER `fulfill_type`,
    ADD COLUMN `last_sync_time` datetime NULL DEFAULT NULL COMMENT '最近一次来源同步时间' AFTER `need_delivery_address`;

CREATE INDEX `idx_product_spu_source` ON `product_spu` (`source_platform`, `source_spu_id`);
CREATE INDEX `idx_product_spu_owner_tenant` ON `product_spu` (`owner_type`, `owner_tenant_id`);
CREATE INDEX `idx_product_spu_supplier` ON `product_spu` (`supplier_id`);
CREATE INDEX `idx_product_spu_hot_type` ON `product_spu` (`hot_type`);
CREATE INDEX `idx_product_spu_category_status` ON `product_spu` (`category_id`, `status`);
CREATE INDEX `idx_product_spu_product_fulfill` ON `product_spu` (`product_type`, `fulfill_type`);

-- 现有历史商品默认按平台自营实物商品处理；京东同步代码后续会覆盖 source_type/source_platform/supplier_id。
UPDATE `product_spu`
SET `owner_type` = IFNULL(NULLIF(`owner_type`, ''), 'PLATFORM'),
    `source_type` = IFNULL(NULLIF(`source_type`, ''), 'SELF'),
    `product_type` = IFNULL(NULLIF(`product_type`, ''), 'PHYSICAL'),
    `fulfill_type` = IFNULL(NULLIF(`fulfill_type`, ''), 'EXPRESS'),
    `need_delivery_address` = b'1',
    `min_buy_count` = IF(`min_buy_count` IS NULL OR `min_buy_count` <= 0, 1, `min_buy_count`),
    `supply_price` = IF(`supply_price` IS NULL OR `supply_price` <= 0, `price`, `supply_price`);

-- =========================================================
-- 4. 商品 SKU 扩展：来源 SKU、供应价、起购量、来源库存状态
-- =========================================================

ALTER TABLE `product_sku`
    ADD COLUMN `supplier_id` bigint NULL DEFAULT NULL COMMENT '供应商 ID，冗余便于同步排查' AFTER `spu_id`,
    ADD COLUMN `source_sku_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '外部 SKU ID' AFTER `supplier_id`,
    ADD COLUMN `supply_price` int NOT NULL DEFAULT 0 COMMENT 'SKU 供应价，单位：分' AFTER `cost_price`,
    ADD COLUMN `source_stock` int NULL DEFAULT NULL COMMENT '来源库存' AFTER `stock`,
    ADD COLUMN `source_status` tinyint NULL DEFAULT NULL COMMENT '来源 SKU 状态' AFTER `source_stock`,
    ADD COLUMN `min_buy_count` int NOT NULL DEFAULT 1 COMMENT 'SKU 起购量' AFTER `source_status`,
    ADD COLUMN `last_sync_time` datetime NULL DEFAULT NULL COMMENT '最近一次来源同步时间' AFTER `min_buy_count`;

CREATE INDEX `idx_product_sku_supplier_source` ON `product_sku` (`supplier_id`, `source_sku_id`);
CREATE INDEX `idx_product_sku_spu` ON `product_sku` (`spu_id`);

UPDATE `product_sku`
SET `supply_price` = IF(`supply_price` IS NULL OR `supply_price` <= 0, `price`, `supply_price`),
    `min_buy_count` = IF(`min_buy_count` IS NULL OR `min_buy_count` <= 0, 1, `min_buy_count`);

-- =========================================================
-- 5. 商品来源扩展表：保存京东等外部来源原始数据
-- =========================================================

CREATE TABLE IF NOT EXISTS `mall_product_source_data` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `spu_id` bigint NOT NULL COMMENT '本地 SPU ID',
    `sku_id` bigint NULL DEFAULT NULL COMMENT '本地 SKU ID，SPU 级数据为空',
    `supplier_id` bigint NOT NULL COMMENT '供应商 ID',
    `source_platform` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源平台，例如 JD',
    `source_spu_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '外部 SPU ID',
    `source_sku_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '外部 SKU ID',
    `data_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '数据类型：SPU_DETAIL、SKU_DETAIL、PRICE、STOCK、IMAGE、OTHER',
    `raw_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源原始 JSON',
    `data_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '来源数据版本或同步批次',
    `sync_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '同步时间',
    `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号，平台来源数据建议为 0',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_mall_source_spu_type` (`spu_id`, `data_type`) USING BTREE,
    KEY `idx_mall_source_sku_type` (`sku_id`, `data_type`) USING BTREE,
    KEY `idx_mall_source_external_spu` (`source_platform`, `source_spu_id`) USING BTREE,
    KEY `idx_mall_source_external_sku` (`source_platform`, `source_sku_id`) USING BTREE,
    KEY `idx_mall_source_sync_time` (`sync_time`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品来源原始数据扩展表';

-- =========================================================
-- 6. 商品池 SPU 表：租户选择平台商品、控制上下架和售价
-- =========================================================

CREATE TABLE IF NOT EXISTS `mall_tenant_product_pool` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tenant_id` bigint NOT NULL COMMENT '租户 ID',
    `spu_id` bigint NOT NULL COMMENT '平台商品 SPU ID',
    `pool_status` tinyint NOT NULL DEFAULT 0 COMMENT '商品池状态：0 下架，1 上架',
    `sort` int NOT NULL DEFAULT 0 COMMENT '商户侧排序',
    `hot_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '租户侧热销标识，优先级高于平台商品 hot_type',
    `supply_price` int NOT NULL DEFAULT 0 COMMENT '租户侧供应价，单位：分',
    `sale_price` int NOT NULL DEFAULT 0 COMMENT '租户侧销售价，单位：分，H5 积分价按规则换算',
    `display_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '租户侧展示名称，为空使用平台商品名称',
    `display_pic_url` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '租户侧展示主图，为空使用平台主图',
    `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备注',
    `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_mall_pool_tenant_spu` (`tenant_id`, `spu_id`) USING BTREE,
    KEY `idx_mall_pool_tenant_status_sort` (`tenant_id`, `pool_status`, `sort`) USING BTREE,
    KEY `idx_mall_pool_tenant_hot` (`tenant_id`, `hot_type`, `pool_status`) USING BTREE,
    KEY `idx_mall_pool_spu` (`spu_id`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '租户商品池 SPU 表';

-- =========================================================
-- 7. 商品池 SKU 表：租户侧 SKU 价格、上下架、限售
-- =========================================================

CREATE TABLE IF NOT EXISTS `mall_tenant_product_sku` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tenant_id` bigint NOT NULL COMMENT '租户 ID',
    `pool_id` bigint NOT NULL COMMENT '租户商品池 SPU ID',
    `spu_id` bigint NOT NULL COMMENT '平台商品 SPU ID',
    `sku_id` bigint NOT NULL COMMENT '平台商品 SKU ID',
    `pool_status` tinyint NOT NULL DEFAULT 0 COMMENT 'SKU 池状态：0 下架，1 上架',
    `supply_price` int NOT NULL DEFAULT 0 COMMENT '租户侧 SKU 供应价，单位：分',
    `supply_price_coefficient` decimal(12,6) NOT NULL DEFAULT 1.000000 COMMENT '加入商品池时使用的供货价系数：租户SKU供货价 = 平台SKU供货价 x 该系数',
    `sale_price` int NOT NULL DEFAULT 0 COMMENT '租户侧 SKU 销售价，单位：分',
    `min_buy_count` int NOT NULL DEFAULT 1 COMMENT '租户侧起购量，默认取平台 SKU 起购量',
    `limit_buy_count` int NULL DEFAULT NULL COMMENT '租户侧限购量，为空不限购',
    `stock` int NULL DEFAULT NULL COMMENT '租户侧可售库存，为空跟随平台/来源库存',
    `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备注',
    `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_mall_pool_sku` (`pool_id`, `sku_id`) USING BTREE,
    KEY `idx_mall_pool_sku_tenant_sku` (`tenant_id`, `sku_id`) USING BTREE,
    KEY `idx_mall_pool_sku_tenant_status` (`tenant_id`, `pool_status`) USING BTREE,
    KEY `idx_mall_pool_sku_spu` (`spu_id`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '租户商品池 SKU 表';

SET FOREIGN_KEY_CHECKS = 1;

DROP TABLE IF EXISTS `mall_product_source_category`;

CREATE TABLE `mall_product_source_category` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键 ID',

    `supplier_id` bigint NOT NULL COMMENT '供应商 ID，关联 mall_supplier.id',
    `source_platform` varchar(32) NOT NULL COMMENT '来源平台编码，例如 JD',
    `source_category_id` varchar(64) NOT NULL COMMENT '来源分类 ID，例如京东 catId',
    `source_parent_id` varchar(64) NOT NULL DEFAULT '0' COMMENT '来源父分类 ID，根分类为 0',
    `source_category_name` varchar(255) NOT NULL COMMENT '来源分类名称',
    `source_level` tinyint NOT NULL DEFAULT 1 COMMENT '来源分类层级，从 1 开始',
    `source_tree_path` varchar(512) DEFAULT NULL COMMENT '来源分类路径，例如 /1/12/35/',
    `source_sort` int NOT NULL DEFAULT 0 COMMENT '来源分类排序',
    `source_status` tinyint NOT NULL DEFAULT 0 COMMENT '来源分类状态，0 正常，1 禁用',

    `local_category_id` bigint DEFAULT NULL COMMENT '本地商品分类 ID，关联 product_category.id',
    `sync_enabled` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否纳入商品同步白名单',
    `is_leaf` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否叶子分类',

    `last_sync_time` datetime DEFAULT NULL COMMENT '最近一次同步时间',
    `remark` varchar(500) DEFAULT NULL COMMENT '备注',

    `creator` varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号，平台级数据默认为 0',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_supplier_platform_category` (`supplier_id`, `source_platform`, `source_category_id`, `deleted`),
    KEY `idx_source_parent` (`supplier_id`, `source_platform`, `source_parent_id`),
    KEY `idx_local_category_id` (`local_category_id`),
    KEY `idx_sync_enabled` (`sync_enabled`),
    KEY `idx_last_sync_time` (`last_sync_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商城商品来源分类映射表';
