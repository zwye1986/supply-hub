package com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 持久化定时任务数据对象。
 *
 * <p>必须保留无参构造，使 MyBatis 对 {@code SELECT *} 使用列名到属性名映射，
 * 禁止退化为依赖 DDL 列顺序的构造器映射。设计见
 * {@code docs/superpowers/specs/2026-08-20-timer-outbox-schema-repair-design.md#31-timer-映射}。</p>
 */
@TableName("sc_timer_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimerTaskDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private String taskType;
    private String idempotencyKey;
    private Integer payloadVersion;
    private String payloadJson;
    private String scheduleType;
    private LocalDateTime nextFireAt;
    private Integer priority;
    private Integer shard;
    private Short status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Long fencingToken;
    private String lastErrorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}
