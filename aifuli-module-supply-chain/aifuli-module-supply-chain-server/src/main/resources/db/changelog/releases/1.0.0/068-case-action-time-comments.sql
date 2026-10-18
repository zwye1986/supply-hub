-- 补齐066异常命令审计时间列的Asia/Shanghai语义，只改注释，不偏移存量时间。
-- 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#104-t00基线收口修订2026-10-16
-- 保留原DATETIME(3)、可空性及历史变更集校验和；本变更不改变状态、资金或执行预算。
ALTER TABLE sc_fulfillment_case_action
    MODIFY COLUMN lease_until DATETIME(3) NULL COMMENT '命令执行租约截止时间；到期无完整结果则终结为UNKNOWN，Asia/Shanghai',
    MODIFY COLUMN fresh_until DATETIME(3) NULL COMMENT '核实结果可用于失败确认的截止时间，Asia/Shanghai',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '命令受理时间，Asia/Shanghai',
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '命令最后更新时间，Asia/Shanghai';
