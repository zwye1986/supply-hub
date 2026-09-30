package com.aifuli.module.supplychain.infrastructure.timer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 按 {@code taskType} 注册的持久化任务处理器；由独立有界工作池在事务外执行。
 *
 * <p>Handler 只能返回封闭结果，不得直接修改领域表；领域推进必须通过重新调用指定 Facade
 * 或 Lifecycle 的恢复 Interface 完成。</p>
 */
public interface PersistentTimerHandler {

    String taskType();

    /** 处理任务并返回稳定结果码；抛出异常视为可重试失败。 */
    String handle(TimerTaskContext context, JsonNode payload);

    /** 任务最大执行次数；超过后由引擎转死信。 */
    default int maxAttempts() {
        return 5;
    }
}
