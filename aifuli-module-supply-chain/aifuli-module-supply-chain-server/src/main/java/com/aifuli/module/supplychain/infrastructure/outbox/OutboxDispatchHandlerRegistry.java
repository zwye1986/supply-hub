package com.aifuli.module.supplychain.infrastructure.outbox;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 按 {@code targetName} 唯一解析 LOCAL 投递处理器；启动时发现重复注册即失败关闭。 */
@Component
public class OutboxDispatchHandlerRegistry {

    private final Map<String, OutboxDispatchHandler> handlers;

    public OutboxDispatchHandlerRegistry(List<OutboxDispatchHandler> handlers) {
        Map<String, OutboxDispatchHandler> map = new HashMap<>();
        for (OutboxDispatchHandler handler : handlers) {
            OutboxDispatchHandler existing = map.put(handler.targetName(), handler);
            if (existing != null) {
                throw new IllegalStateException("Duplicate OutboxDispatchHandler for targetName: "
                        + handler.targetName());
            }
        }
        this.handlers = map;
    }

    public OutboxDispatchHandler find(String targetName) {
        return handlers.get(targetName);
    }
}
