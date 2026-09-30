package com.aifuli.module.supplychain.infrastructure.timer;

/** 一次执行的身份与防旧令牌；完成、续租和受保护的业务写入都校验 fencing token。 */
public record TimerTaskContext(String taskNo, Long taskId, int attemptNo, long fencingToken,
                               String workerId) {
}
