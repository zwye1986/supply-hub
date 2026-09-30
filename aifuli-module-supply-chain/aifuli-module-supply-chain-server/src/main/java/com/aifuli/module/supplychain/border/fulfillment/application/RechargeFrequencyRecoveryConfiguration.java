package com.aifuli.module.supplychain.border.fulfillment.application;

import lombok.extern.slf4j.Slf4j;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyRecoveryCodes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyConstants.*;

/** 本队列独立、有界补偿调度，不调整TimerEngine全局参数或租约语义。
 * 持续故障按批次聚合与30分钟采样，失败保留安全类别及因果，数据库意图在下一轮重试。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#6-timer-与补偿、#9-告警与观测。 */
@Slf4j
@Configuration(proxyBeanMethods=false)
public class RechargeFrequencyRecoveryConfiguration {
    @Bean(initMethod="start", destroyMethod="close")
    RechargeFrequencyCompensationLoop rechargeFrequencyCompensationLoop(RechargeFrequencyRecoveryService recovery) {
        return new RechargeFrequencyCompensationLoop(recovery);
    }

    /** 仅此恢复循环拥有执行器，不注册通用Executor/TaskScheduler以免改变认证等其他进程内调度。 */
    public static final class RechargeFrequencyCompensationLoop implements AutoCloseable {
        private final RechargeFrequencyRecoveryService recovery;
        private ScheduledExecutorService executor;
        RechargeFrequencyCompensationLoop(RechargeFrequencyRecoveryService recovery) { this.recovery = recovery; }
        public void start() {
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                var thread = new Thread(runnable, "sc-recharge-frequency-recovery"); thread.setDaemon(true); return thread;
            });
            var failures = new AtomicLong(); var lastLog = new AtomicLong(Long.MIN_VALUE);
            executor.scheduleWithFixedDelay(() -> {
                try {
                    recovery.compensate();
                    long recovered = failures.getAndSet(0);
                    if (recovered > 0) { log.warn("[直充限频补偿] event={}, failedBatches={}", RechargeFrequencyRecoveryCodes.STORAGE_RECOVERED, recovered); }
                } catch (RuntimeException exception) {
                    long count = failures.incrementAndGet(); long now = System.nanoTime(); long previous = lastLog.get();
                    if (count == 1 || now-previous >= TimeUnit.SECONDS.toNanos(ALERT_REPEAT_SECONDS)) {
                        lastLog.set(now);
                        log.error("[直充限频补偿] event={}, failedBatches={}, failureType={}, causeType={}, failedQueues={}",
                                RechargeFrequencyRecoveryCodes.STORAGE_FAILED, count, exception.getClass().getSimpleName(),
                                exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName(), exception.getSuppressed().length+1);
                    }
                }
            }, RECOVERY_CHECK_SECONDS, RECOVERY_CHECK_SECONDS, TimeUnit.SECONDS);
        }
        @Override public void close() { executor.shutdown(); }
    }
}
