-- 商品中心管理能力增强。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#2-领域数据模型与表结构设计028-changeset

ALTER TABLE sc_platform_spu
    MODIFY COLUMN product_type VARCHAR(32) NULL COMMENT '商品类型编码，由sc_product_type动态维护，供货命中用',
    ADD COLUMN secondary_category_ids JSON NULL COMMENT '辅助/扩展分类ID列表(JSON数组)' AFTER product_classification,
    ADD COLUMN slider_image_urls JSON NULL COMMENT '商品轮播图地址列表(JSON数组)' AFTER cover_url,
    ADD COLUMN description LONGTEXT NULL COMMENT '商品富文本详情与图文介绍' AFTER use_instruction,
    ADD COLUMN delivery_type VARCHAR(32) NOT NULL DEFAULT 'VIRTUAL' COMMENT '配送履约模式：VIRTUAL虚拟发货, EXPRESS物流快递, PICKUP自提核销' AFTER status,
    ADD COLUMN min_quantity BIGINT NOT NULL DEFAULT 1 COMMENT 'SPU默认单次最低起购量' AFTER delivery_type,
    ADD COLUMN max_quantity BIGINT NOT NULL DEFAULT 9999999 COMMENT 'SPU默认单次最高限购量' AFTER min_quantity,
    DROP CHECK ck_sc_spu_product_type,
    ADD CONSTRAINT ck_sc_spu_quantity CHECK (min_quantity > 0 AND max_quantity >= min_quantity);

ALTER TABLE sc_platform_sku
    ADD COLUMN market_price BIGINT NOT NULL DEFAULT 0 COMMENT '市场划线价/吊牌价，单位为分' AFTER face_value,
    ADD COLUMN suggested_sale_price BIGINT NOT NULL DEFAULT 0 COMMENT '建议零售价/销售建议价，单位为分' AFTER market_price,
    ADD COLUMN min_quantity BIGINT NOT NULL DEFAULT 1 COMMENT 'SKU单次最小起购数量' AFTER suggested_sale_price,
    ADD COLUMN max_quantity BIGINT NOT NULL DEFAULT 9999999 COMMENT 'SKU单次最大限购数量' AFTER min_quantity,
    ADD CONSTRAINT ck_sc_sku_market_price CHECK (market_price >= 0),
    ADD CONSTRAINT ck_sc_sku_suggested_price CHECK (suggested_sale_price >= 0),
    ADD CONSTRAINT ck_sc_sku_quantity CHECK (min_quantity > 0 AND max_quantity >= min_quantity);

-- 先显式终止存在冲突数据的迁移，禁止静默修改业务数据。
SET @category_duplicate_count = (
    SELECT COUNT(*) FROM (
        SELECT COALESCE(parent_id, 0), category_name
        FROM sc_product_category
        WHERE status <> 90
        GROUP BY COALESCE(parent_id, 0), category_name
        HAVING COUNT(*) > 1
    ) duplicated_categories
);
SET @category_duplicate_guard = IF(
    @category_duplicate_count = 0,
    'SELECT 1',
    'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''存在同父级未归档同名类目，请先治理后执行028'''
);
PREPARE category_duplicate_statement FROM @category_duplicate_guard;
EXECUTE category_duplicate_statement;
DEALLOCATE PREPARE category_duplicate_statement;

ALTER TABLE sc_product_category
    ADD COLUMN visible TINYINT NOT NULL DEFAULT 1 COMMENT '前端展示可见性: 1-显示, 0-隐藏' AFTER sort,
    ADD COLUMN live_parent_key BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 90 THEN NULL ELSE COALESCE(parent_id, 0) END
    ) STORED COMMENT '未归档类目的父级唯一键，根类目使用0',
    ADD COLUMN live_category_name VARCHAR(128) GENERATED ALWAYS AS (
        CASE WHEN status = 90 THEN NULL ELSE category_name END
    ) STORED COMMENT '未归档类目名称唯一键',
    ADD KEY idx_sc_category_visible_status (visible, status),
    ADD UNIQUE KEY uk_sc_category_live_parent_name (live_parent_key, live_category_name);

ALTER TABLE sc_brand
    ADD UNIQUE KEY uk_sc_brand_name (brand_name);

CREATE TABLE sc_product_type (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '商品类型内部主键',
    type_code VARCHAR(32) NOT NULL COMMENT '商品类型业务编码',
    type_name VARCHAR(128) NOT NULL COMMENT '商品类型名称',
    sort INT NOT NULL DEFAULT 0 COMMENT '展示排序值，越小越靠前',
    status SMALLINT NOT NULL DEFAULT 10 COMMENT '管理状态：10启用，20停用，90已归档',
    remark VARCHAR(512) NULL COMMENT '类型备注与说明',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_product_type_code (type_code),
    UNIQUE KEY uk_sc_product_type_name (type_name),
    KEY idx_sc_product_type_status_sort (status, sort),
    CONSTRAINT ck_sc_product_type_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台商品类型配置表';

INSERT INTO sc_product_type (type_code, type_name, sort, status, remark, created_at, updated_at)
VALUES
    ('EQUITY_PRODUCT', '虚拟权益商品', 10, 10, '虚拟卡密与直充类权益商品', NOW(3), NOW(3)),
    ('MOVIE', '电影票', 20, 10, '本期仅保留普通类型数据', NOW(3), NOW(3)),
    ('DINING', '生活点餐', 30, 10, '本期仅保留普通类型数据', NOW(3), NOW(3)),
    ('JD', '京东实物', 40, 10, '京东物流履约实物商品', NOW(3), NOW(3)),
    ('VIP', '大客户权益', 50, 10, '企业专享福利定制权益', NOW(3), NOW(3));

ALTER TABLE sc_supplier_supply_scope
    MODIFY COLUMN product_type VARCHAR(32) NOT NULL COMMENT '商品类型编码，由sc_product_type动态维护',
    DROP CHECK ck_sc_supplier_scope_product_type;

ALTER TABLE sc_buyer_category_scope
    MODIFY COLUMN scope_code VARCHAR(32) NOT NULL COMMENT '范围编码：可售品类枚举名或动态商品类型编码';
