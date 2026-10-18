package com.aifuli.module.supplychain.border.fulfillment.application;

/** 只用于验证建单原子性的故障注入端口；生产环境无实现时不执行任何动作。 */
@FunctionalInterface
public interface AcceptanceFailureInjector {
    void after(Stage stage);

    enum Stage {
        ORDER_INSERTED,
        REQUEST_ATTACHED,
        ROUTING_RECORDED,
        B_ORDER_CREATED,
        CHECKPOINT_FROZEN,
        PREPARATION_WATCHDOG_SCHEDULED,
        FUNDS_FROZEN,
        OUTBOX_APPENDED
    }
}
