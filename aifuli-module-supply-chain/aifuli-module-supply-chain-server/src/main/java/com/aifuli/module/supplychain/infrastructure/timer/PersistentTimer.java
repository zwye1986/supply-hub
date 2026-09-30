package com.aifuli.module.supplychain.infrastructure.timer;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 业务侧持久化定时器入口；只暴露调度与取消，{@code start/stop} 由容器生命周期管理。
 *
 * <p>数据库是任务真相源；内存队列只是提示，重启必须从 {@code sc_timer_task} 自动恢复。</p>
 */
public interface PersistentTimer {

    /** 调度一次性任务；{@code (taskType, idempotencyKey)} 唯一，重复调度幂等返回已存在任务。 */
    TimerHandle scheduleOnce(String taskType, String idempotencyKey, JsonNode payload, Instant fireAt);

    /** 按业务键取消任务；仅允许取消尚未完成或当前节点持有的任务。 */
    boolean cancel(String taskNo, String reason);
}
