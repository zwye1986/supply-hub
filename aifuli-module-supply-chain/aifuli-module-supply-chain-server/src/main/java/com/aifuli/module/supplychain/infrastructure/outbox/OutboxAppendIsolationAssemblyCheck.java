package com.aifuli.module.supplychain.infrastructure.outbox;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.aop.support.AopUtils;

/**
 * 生产装配断言（D1）：outboxEventRepository 必须已被 appendLocal 隔离 advisor 代理，
 * 装配缺失或目标非代理即启动失败关闭。
 */
public class OutboxAppendIsolationAssemblyCheck implements SmartInitializingSingleton {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxAppendIsolationAssemblyCheck(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!AopUtils.isAopProxy(outboxEventRepository)) {
            throw new IllegalStateException(
                    "outboxEventRepository 未装配 appendLocal 保留命名空间隔离 advisor，启动失败关闭");
        }
    }
}
