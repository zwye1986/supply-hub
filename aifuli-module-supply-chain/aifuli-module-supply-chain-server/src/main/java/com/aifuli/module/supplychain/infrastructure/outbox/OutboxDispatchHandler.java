package com.aifuli.module.supplychain.infrastructure.outbox;

/** LOCAL 投递目标的本地消费者；按 {@code targetName} 注册，幂等消费由 {@code InboxService} 保证。 */
public interface OutboxDispatchHandler {

    String targetName();

    /** 投递事件；抛出异常视为投递失败，由引擎按退避重试。 */
    void dispatch(OutboxEventDO event);
}
