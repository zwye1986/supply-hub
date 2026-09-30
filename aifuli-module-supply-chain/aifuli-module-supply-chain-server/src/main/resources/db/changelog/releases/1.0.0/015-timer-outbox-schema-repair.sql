-- 表中文名：Outbox事件
-- 核心职责：补齐投递状态乐观锁，使数据库结构与 OutboxEventDO/Mapper 的 CAS 语义一致
ALTER TABLE sc_outbox_event
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0
        COMMENT '事件投递状态乐观锁版本号' AFTER published_at;
