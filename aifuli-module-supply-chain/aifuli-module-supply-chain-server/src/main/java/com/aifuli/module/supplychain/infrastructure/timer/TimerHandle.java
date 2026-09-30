package com.aifuli.module.supplychain.infrastructure.timer;

/** 调度结果；持有任务业务号与内部主键，不持有数据库连接。 */
public record TimerHandle(String taskNo, Long taskId, long fencingToken) {
}
