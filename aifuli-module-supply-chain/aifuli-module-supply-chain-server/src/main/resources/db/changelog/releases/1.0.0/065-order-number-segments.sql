-- 订单编号永久预留；不修改旧迁移，已提交区间不删除、不重写、不回收。
-- 设计：docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#41-mysql永久预留与所有权
CREATE TABLE sc_order_no_sequence (
    sequence_date DATE NOT NULL COMMENT '订单编号流水所属日期，Asia/Shanghai',
    last_reserved_value INT UNSIGNED NOT NULL COMMENT '已提交预留号段的当日最大流水，初始为0',
    created_at DATETIME(3) NOT NULL COMMENT '订单编号流水行创建时间，Asia/Shanghai',
    updated_at DATETIME(3) NOT NULL COMMENT '订单编号流水行最后更新时间，Asia/Shanghai',
    PRIMARY KEY (sequence_date),
    CONSTRAINT chk_sc_order_no_sequence_capacity CHECK (last_reserved_value <= 99999999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单编号每日预留高水位';

CREATE TABLE sc_order_no_segment (
    sequence_date DATE NOT NULL COMMENT '订单编号号段所属日期，Asia/Shanghai',
    start_value INT UNSIGNED NOT NULL COMMENT '已提交预留号段的起始流水，含边界',
    end_value INT UNSIGNED NOT NULL COMMENT '已提交预留号段的结束流水，含边界',
    owner_id VARCHAR(32) NOT NULL COMMENT '号段独占分配器本次启动标识',
    created_at DATETIME(3) NOT NULL COMMENT '订单编号号段预留记录创建时间，Asia/Shanghai',
    PRIMARY KEY (sequence_date, start_value),
    CONSTRAINT chk_sc_order_no_segment_range CHECK (start_value >= 1 AND start_value <= end_value AND end_value <= 99999999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单编号永久预留号段';
