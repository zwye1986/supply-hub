package com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql;

import com.aifuli.module.supplychain.infrastructure.ledger.LocalLedgerIntentCollector;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据库 Collation 确定性分组与排序 Mapper（R3-03 §7.2.2(4) 冻结 SQL）。
 * <p>
 * 两条无实体表派生表 SQL：逐键返回行＋{@code DENSE_RANK() OVER (ORDER BY 已带 collation 派生列)} 组号＋
 * 原始精确列 {@code k*_orig} 回传定位＋最外层 ORDER BY 派生列；字符串列
 * {@code CAST(... AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci}，数值列 {@code CAST(... AS SIGNED)}；
 * 参数化 foreach 绑定（禁止拼接外部输入）；零 InnoDB 访问 → 零行/间隙锁、不锚定 RR 读视图。
 * 每 flush 每类恰好执行 1 条（N≥1；N=0 不执行），执行时点在首个账本锁之前。
 * <p>
 * 设计：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#722-完整技术-worker-意图收集数据库-collation-确定性排序与单次-flush-协议r3-03
 */
@Mapper
public interface CollationOrderingMapper {

    /** Outbox 四列键：逐键返回行＋DENSE_RANK 组号＋原始精确列定位。 */
    List<OutboxKeyRow> orderOutboxKeys(@Param("keys") List<LocalLedgerIntentCollector.OutboxKey> keys);

    /** Timer 两列键：逐键返回行＋DENSE_RANK 组号＋原始精确列定位。 */
    List<TimerKeyRow> orderTimerKeys(@Param("keys") List<LocalLedgerIntentCollector.TimerKey> keys);

    /**
     * Outbox 分组排序行：派生 collation 列（数据库排序/等价的唯一权威）＋原始精确列（仅回传定位）＋组号。
     * 行序即数据库 Collation 序；DENSE_RANK 保证同组行相邻。
     */
    final class OutboxKeyRow {
        private String aggregateType;
        private String aggregateId;
        private Long aggregateVersion;
        private String eventType;
        private String aggregateTypeOrig;
        private String aggregateIdOrig;
        private Long aggregateVersionOrig;
        private String eventTypeOrig;
        private Long grp;

        public String getAggregateType() {
            return aggregateType;
        }

        public void setAggregateType(String aggregateType) {
            this.aggregateType = aggregateType;
        }

        public String getAggregateId() {
            return aggregateId;
        }

        public void setAggregateId(String aggregateId) {
            this.aggregateId = aggregateId;
        }

        public Long getAggregateVersion() {
            return aggregateVersion;
        }

        public void setAggregateVersion(Long aggregateVersion) {
            this.aggregateVersion = aggregateVersion;
        }

        public String getEventType() {
            return eventType;
        }

        public void setEventType(String eventType) {
            this.eventType = eventType;
        }

        public String getAggregateTypeOrig() {
            return aggregateTypeOrig;
        }

        public void setAggregateTypeOrig(String aggregateTypeOrig) {
            this.aggregateTypeOrig = aggregateTypeOrig;
        }

        public String getAggregateIdOrig() {
            return aggregateIdOrig;
        }

        public void setAggregateIdOrig(String aggregateIdOrig) {
            this.aggregateIdOrig = aggregateIdOrig;
        }

        public Long getAggregateVersionOrig() {
            return aggregateVersionOrig;
        }

        public void setAggregateVersionOrig(Long aggregateVersionOrig) {
            this.aggregateVersionOrig = aggregateVersionOrig;
        }

        public String getEventTypeOrig() {
            return eventTypeOrig;
        }

        public void setEventTypeOrig(String eventTypeOrig) {
            this.eventTypeOrig = eventTypeOrig;
        }

        public Long getGrp() {
            return grp;
        }

        public void setGrp(Long grp) {
            this.grp = grp;
        }
    }

    /** Timer 分组排序行：结构同 Outbox（两列）。 */
    final class TimerKeyRow {
        private String taskType;
        private String idempotencyKey;
        private String taskTypeOrig;
        private String idempotencyKeyOrig;
        private Long grp;

        public String getTaskType() {
            return taskType;
        }

        public void setTaskType(String taskType) {
            this.taskType = taskType;
        }

        public String getIdempotencyKey() {
            return idempotencyKey;
        }

        public void setIdempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
        }

        public String getTaskTypeOrig() {
            return taskTypeOrig;
        }

        public void setTaskTypeOrig(String taskTypeOrig) {
            this.taskTypeOrig = taskTypeOrig;
        }

        public String getIdempotencyKeyOrig() {
            return idempotencyKeyOrig;
        }

        public void setIdempotencyKeyOrig(String idempotencyKeyOrig) {
            this.idempotencyKeyOrig = idempotencyKeyOrig;
        }

        public Long getGrp() {
            return grp;
        }

        public void setGrp(Long grp) {
            this.grp = grp;
        }
    }
}
