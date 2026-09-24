-- 变更集：路由规则 PRD 四级范围模型重构
-- 设计见 docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#52-新表-ddl
-- 1) 策略表增加全局综合权重（三权和恒为100，任一规则保存携带 COMPOSITE 时同步全规则生效）
-- 2) 新建四级范围规则表 sc_std_routing_rule（分销商/项目/商品类型三级匹配 + 供应商范围限定候选）
-- 3) 新建范围明细表 sc_std_routing_rule_scope（仅 DISTRIBUTOR/PROJECT/PRODUCT_TYPE；供应商落在候选表并保序）
-- 4) 新建候选表 sc_std_routing_candidate（保留 offer 级动态检查策略；candidate_priority 降级为同分裁决键）
-- 5) 预置受保护全局默认规则 GLOBAL_DEFAULT；旧强类型规则表废弃并删除（测试环境无存量保留价值）
-- 6) 总订单表增加项目编码/名称快照列（订单视图按前端契约回显项目）

-- 表中文名：供应商路由策略（全局综合权重列）
ALTER TABLE sc_supplier_routing_policy
    ADD COLUMN stability_weight SMALLINT NOT NULL DEFAULT 40 COMMENT '综合排序稳定性评分权重%，与价格/时效权重之和恒为100',
    ADD COLUMN price_weight SMALLINT NOT NULL DEFAULT 30 COMMENT '综合排序价格权重%',
    ADD COLUMN timeliness_weight SMALLINT NOT NULL DEFAULT 30 COMMENT '综合排序时效评分权重%',
    ADD CONSTRAINT ck_sc_routing_policy_weight
        CHECK (stability_weight + price_weight + timeliness_weight = 100);

-- 表中文名：标准商品路由规则（PRD 四级范围模型）
-- 核心职责：维护分销商/项目/商品类型三级范围匹配与 AUTO/FIXED 路由模式、三优先级策略、最高可接受供货价
CREATE TABLE sc_std_routing_rule (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '规则内部主键',
    policy_version_id BIGINT NOT NULL COMMENT '路由策略版本主键，逻辑引用sc_supplier_routing_policy_version.id',
    rule_code VARCHAR(64) NOT NULL COMMENT '规则稳定编码，RR+yyyyMMdd+3位序号；受保护规则为GLOBAL_DEFAULT',
    rule_name VARCHAR(128) NOT NULL COMMENT '规则中文名称，启用集内全局唯一',
    route_mode VARCHAR(16) NOT NULL COMMENT '路由模式：AUTO自动路由多候选失败降级，FIXED固定供应商',
    primary_supplier_id BIGINT NULL COMMENT 'FIXED模式主供应商内部主键；AUTO为空',
    distributor_mode VARCHAR(16) NOT NULL COMMENT '适用分销商范围：ALL全部，SPECIFIED指定',
    project_mode VARCHAR(16) NOT NULL COMMENT '适用项目范围：ALL全部，SPECIFIED指定',
    product_type_mode VARCHAR(16) NOT NULL COMMENT '适用商品类型范围：ALL全部，SPECIFIED指定',
    supplier_mode VARCHAR(16) NOT NULL COMMENT '适用供应商范围：ALL全部（候选动态解析），SPECIFIED指定（候选表保序）',
    max_supply_price_cents BIGINT NULL COMMENT '最高可接受供货价，单位为分；空表示不检查',
    strategy_priority_1 VARCHAR(16) NOT NULL COMMENT '第1优先级策略：STABLE稳定优先，PRICE价格优先，COMPOSITE综合排序',
    strategy_priority_2 VARCHAR(16) NOT NULL COMMENT '第2优先级策略，与1、3不可重复',
    strategy_priority_3 VARCHAR(16) NOT NULL COMMENT '第3优先级策略，与1、2不可重复',
    rule_priority INT NOT NULL DEFAULT 100 COMMENT '范围特异性相同时的裁决优先级，数值越小越优先',
    status SMALLINT NOT NULL COMMENT '规则状态：10启用，20停用',
    protected_rule TINYINT NOT NULL DEFAULT 0 COMMENT '受保护规则标记：1系统预置不可删除，范围与路由模式不可改',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '草稿规则乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '规则创建时间，Asia/Shanghai',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '规则最后修改时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_std_rule_code (policy_version_id, rule_code),
    KEY idx_sc_std_rule_match (policy_version_id, status, rule_priority),
    CONSTRAINT ck_sc_std_rule_mode CHECK (route_mode IN ('AUTO', 'FIXED')),
    CONSTRAINT ck_sc_std_rule_scopes CHECK (distributor_mode IN ('ALL', 'SPECIFIED')
        AND project_mode IN ('ALL', 'SPECIFIED') AND product_type_mode IN ('ALL', 'SPECIFIED')
        AND supplier_mode IN ('ALL', 'SPECIFIED')),
    CONSTRAINT ck_sc_std_rule_strategy CHECK (strategy_priority_1 IN ('STABLE', 'PRICE', 'COMPOSITE')
        AND strategy_priority_2 IN ('STABLE', 'PRICE', 'COMPOSITE')
        AND strategy_priority_3 IN ('STABLE', 'PRICE', 'COMPOSITE')),
    CONSTRAINT ck_sc_std_rule_strategy_distinct CHECK (strategy_priority_1 <> strategy_priority_2
        AND strategy_priority_1 <> strategy_priority_3 AND strategy_priority_2 <> strategy_priority_3),
    CONSTRAINT ck_sc_std_rule_fixed CHECK ((route_mode = 'FIXED' AND primary_supplier_id IS NOT NULL)
        OR (route_mode = 'AUTO' AND primary_supplier_id IS NULL)),
    CONSTRAINT ck_sc_std_rule_status CHECK (status IN (10, 20)),
    CONSTRAINT ck_sc_std_rule_protected CHECK (protected_rule IN (0, 1)),
    CONSTRAINT ck_sc_std_rule_priority CHECK (rule_priority >= 0),
    CONSTRAINT ck_sc_std_rule_price CHECK (max_supply_price_cents IS NULL OR max_supply_price_cents > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准商品路由的四级范围规则（PRD模型）';

-- 表中文名：标准商品路由规则范围明细
-- 核心职责：维护规则在分销商/项目/商品类型三个匹配维度上的指定范围编码
CREATE TABLE sc_std_routing_rule_scope (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '范围明细内部主键',
    rule_id BIGINT NOT NULL COMMENT '规则主键，逻辑引用sc_std_routing_rule.id',
    scope_kind VARCHAR(16) NOT NULL COMMENT '范围维度：DISTRIBUTOR分销商，PROJECT项目，PRODUCT_TYPE商品类型',
    scope_code VARCHAR(64) NOT NULL COMMENT '范围编码：buyer_code/project_code/sc_product_type.type_code',
    created_at DATETIME(3) NOT NULL COMMENT '范围明细创建时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_std_rule_scope (rule_id, scope_kind, scope_code),
    KEY idx_sc_std_rule_scope_code (scope_kind, scope_code),
    CONSTRAINT ck_sc_std_rule_scope_kind CHECK (scope_kind IN ('DISTRIBUTOR', 'PROJECT', 'PRODUCT_TYPE')),
    CONSTRAINT ck_sc_std_rule_scope_code CHECK (scope_code <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='路由规则三级匹配维度的指定范围明细';

-- 表中文名：标准商品路由候选（PRD模型）
-- 核心职责：维护 AUTO+SPECIFIED 规则下保序的候选供应商与 offer 级动态事实检查策略
CREATE TABLE sc_std_routing_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '候选内部主键',
    rule_id BIGINT NOT NULL COMMENT '规则主键，逻辑引用sc_std_routing_rule.id',
    supplier_id BIGINT NOT NULL COMMENT '候选供应商内部主键，运行时解析有效SupplierOfferVersion',
    candidate_priority INT NOT NULL COMMENT '同分裁决优先级，数值越小越优先；AUTO+SPECIFIED时同时表达前端候选顺序',
    inventory_check_mode VARCHAR(16) NOT NULL COMMENT '库存检查模式：REQUIRED必需，OPTIONAL可选，UNSUPPORTED不支持',
    inventory_max_age_seconds INT NULL COMMENT '库存事实最大允许年龄秒数，不支持时为空',
    balance_check_mode VARCHAR(16) NOT NULL COMMENT '供应商余额检查模式：REQUIRED必需，OPTIONAL可选，UNSUPPORTED不支持',
    balance_max_age_seconds INT NULL COMMENT '供应商余额事实最大允许年龄秒数，不支持时为空',
    health_check_mode VARCHAR(16) NOT NULL COMMENT '供应商健康检查模式：REQUIRED必需，OPTIONAL可选，UNSUPPORTED不支持',
    health_max_age_seconds INT NULL COMMENT '供应商健康事实最大允许年龄秒数，不支持时为空',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '候选配置乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '候选配置创建时间，Asia/Shanghai',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '候选配置最后修改时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_std_candidate_supplier (rule_id, supplier_id),
    KEY idx_sc_std_candidate_order (rule_id, candidate_priority, supplier_id),
    KEY idx_sc_std_candidate_supplier (supplier_id),
    CONSTRAINT ck_sc_std_candidate_priority CHECK (candidate_priority >= 0),
    CONSTRAINT ck_sc_std_inventory_mode CHECK ((inventory_check_mode = 'UNSUPPORTED' AND inventory_max_age_seconds IS NULL) OR (inventory_check_mode IN ('REQUIRED', 'OPTIONAL') AND inventory_max_age_seconds > 0)),
    CONSTRAINT ck_sc_std_balance_mode CHECK ((balance_check_mode = 'UNSUPPORTED' AND balance_max_age_seconds IS NULL) OR (balance_check_mode IN ('REQUIRED', 'OPTIONAL') AND balance_max_age_seconds > 0)),
    CONSTRAINT ck_sc_std_health_mode CHECK ((health_check_mode = 'UNSUPPORTED' AND health_max_age_seconds IS NULL) OR (health_check_mode IN ('REQUIRED', 'OPTIONAL') AND health_max_age_seconds > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='规则下保序候选供应商与offer级动态检查策略';

-- 旧强类型规则模型废弃删除（测试环境无存量保留价值，语义已被四级范围模型取代）
DROP TABLE IF EXISTS sc_standard_product_routing_candidate;
DROP TABLE IF EXISTS sc_standard_product_routing_rule;

-- 总订单表增加项目快照（订单视图按前端契约回显所属项目）
ALTER TABLE sc_supply_chain_order
    ADD COLUMN project_code_snapshot VARCHAR(64) NULL COMMENT '受理时项目业务编码快照；CARD场景可空' AFTER buyer_order_no,
    ADD COLUMN project_name_snapshot VARCHAR(128) NULL COMMENT '受理时项目名称快照；CARD场景可空' AFTER project_code_snapshot;

-- 预置受保护全局默认规则：仅当尚无启用的 STANDARD_PRODUCT 策略时引导（幂等，避免破坏现网唯一启用约束）
INSERT INTO sc_supplier_routing_policy
    (policy_code, policy_name, routing_type, status, active_flag, version,
     stability_weight, price_weight, timeliness_weight, created_at, updated_at)
SELECT 'STANDARD_PRODUCT_PRIMARY', '标准商品供给路由', 'STANDARD_PRODUCT', 10, 1, 0,
       40, 30, 30, NOW(3), NOW(3)
WHERE NOT EXISTS (
    SELECT 1 FROM sc_supplier_routing_policy
    WHERE routing_type = 'STANDARD_PRODUCT' AND active_flag = 1
);

-- 默认策略的第一版：受保护全局默认规则（四级全 ALL、AUTO、候选动态解析、稳定优先）。
-- ck_sc_routing_policy_publish 要求已发布版本 published_by 非空，系统预置记为 1（系统管理员）。
-- valid_from 回拨 5 秒：避免部署后极短窗口内与调用方「取库时钟并截断到秒」的同秒比较竞态。
INSERT INTO sc_supplier_routing_policy_version
    (policy_id, version_no, version_name, valid_from, valid_to, config_digest, status,
     publish_reason, published_by, published_at, version, created_at, updated_at)
SELECT p.id, 1, '全局默认规则预置', NOW(3) - INTERVAL 5 SECOND, NULL, UNHEX(REPEAT('0', 64)), 10,
       '系统预置全局默认规则', 1, NOW(3), 0, NOW(3), NOW(3)
FROM sc_supplier_routing_policy p
WHERE p.policy_code = 'STANDARD_PRODUCT_PRIMARY'
  AND NOT EXISTS (
      SELECT 1 FROM sc_supplier_routing_policy_version v WHERE v.policy_id = p.id
  );

INSERT INTO sc_std_routing_rule
    (policy_version_id, rule_code, rule_name, route_mode, primary_supplier_id,
     distributor_mode, project_mode, product_type_mode, supplier_mode,
     max_supply_price_cents, strategy_priority_1, strategy_priority_2, strategy_priority_3,
     rule_priority, status, protected_rule, version, created_at, updated_at)
SELECT v.id, 'GLOBAL_DEFAULT', '全局默认规则', 'AUTO', NULL,
       'ALL', 'ALL', 'ALL', 'ALL',
       NULL, 'STABLE', 'PRICE', 'COMPOSITE',
       9999, 10, 1, 0, NOW(3), NOW(3)
FROM sc_supplier_routing_policy_version v
JOIN sc_supplier_routing_policy p ON p.id = v.policy_id
WHERE p.policy_code = 'STANDARD_PRODUCT_PRIMARY' AND v.version_no = 1
  AND NOT EXISTS (
      SELECT 1 FROM sc_std_routing_rule r WHERE r.policy_version_id = v.id AND r.rule_code = 'GLOBAL_DEFAULT'
  );
