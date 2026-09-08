package com.aifuli.module.supplychain.infrastructure.timer;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 按 {@code taskType} 唯一解析处理器；启动时发现重复或缺失即失败关闭。 */
@Component
public class PersistentTimerHandlerRegistry {

    private final Map<String, PersistentTimerHandler> handlers;

    public PersistentTimerHandlerRegistry(List<PersistentTimerHandler> handlers) {
        Map<String, PersistentTimerHandler> map = new HashMap<>();
        for (PersistentTimerHandler handler : handlers) {
            PersistentTimerHandler existing = map.put(handler.taskType(), handler);
            if (existing != null) {
                throw new IllegalStateException("Duplicate PersistentTimerHandler for taskType: "
                        + handler.taskType());
            }
        }
        this.handlers = map;
    }

    public PersistentTimerHandler require(String taskType) {
        PersistentTimerHandler handler = handlers.get(taskType);
        if (handler == null) {
            throw new IllegalStateException("No PersistentTimerHandler registered for taskType: "
                    + taskType);
        }
        return handler;
    }

    public int maxAttempts(String taskType) {
        return require(taskType).maxAttempts();
    }
}
