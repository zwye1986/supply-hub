package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** 所有单例构造完成后验证必需Timer；与事务调度器分离，避免默认JDK代理改变其可注入类型。
 * 延迟构造解决Registry依赖环，但不允许生产缺少Timer而降级发送。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#6-timer-与补偿。 */
@Component
@RequiredArgsConstructor
public class RechargeFrequencyTimerStartupCheck implements SmartInitializingSingleton {
    private final ObjectProvider<PersistentTimer> timer;
    @Override public void afterSingletonsInstantiated() { timer.getObject(); }
}
