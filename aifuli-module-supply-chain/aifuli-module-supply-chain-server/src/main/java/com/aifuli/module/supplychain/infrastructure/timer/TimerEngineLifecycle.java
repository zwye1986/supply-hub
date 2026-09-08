package com.aifuli.module.supplychain.infrastructure.timer;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 持久化定时器引擎：以 {@link SmartLifecycle} 替代 {@code @Scheduled}，
 * 扫描抢占由短事务完成，执行在独立有界工作池事务外进行，租约周期续租。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#持久化定时器} 与
 * {@code docs/superpowers/plans/2026-08-11-supply-chain-m1-qnm-vertical-slice.md#task-9}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(TimerEngineProperties.class)
public class TimerEngineLifecycle implements SmartLifecycle {

    private final TimerClaimService claimService;
    private final PersistentTimerHandlerRegistry handlerRegistry;
    private final ObjectMapper objectMapper;
    private final TimerEngineProperties properties;
    private final SupplyChainDatabaseClock databaseClock;

    private final String workerId = "timer-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong phase = new AtomicLong(-1);
    private volatile long lastScanWarnNanos = 0L;
    private static final long SCAN_WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private ScheduledExecutorService scanner;
    private ScheduledThreadPoolExecutor workers;
    private final Map<Long, Renewer> activeLeases = new ConcurrentHashMap<>();

    @PostConstruct
    void validate() {
        if (properties.getLeaseDuration().toMillis() <= properties.getRenewInterval().toMillis()) {
            throw new IllegalStateException("timer renewInterval must be smaller than leaseDuration");
        }
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        workers = new ScheduledThreadPoolExecutor(properties.getWorkerPoolSize());
        workers.setRemoveOnCancelPolicy(true);
        workers.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        scanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "supply-chain-timer-scanner");
            t.setDaemon(true);
            return t;
        });
        long intervalMs = properties.getScanInterval().toMillis();
        scanner.scheduleWithFixedDelay(this::scan, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("TimerEngine started: workerId={}, workerPoolSize={}, scanInterval={}ms",
                workerId, properties.getWorkerPoolSize(), intervalMs);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (scanner != null) {
            scanner.shutdownNow();
        }
        if (workers != null) {
            workers.shutdown();
            try {
                workers.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        activeLeases.clear();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return (int) phase.get();
    }

    public void setPhase(int phase) {
        this.phase.set(phase);
    }

    private void scan() {
        if (!running.get()) {
            return;
        }
        try {
            List<TimerClaimService.ClaimedTask> claimed =
                    claimService.claimBatch(workerId, properties.getBatchSize(), properties.getLeaseDuration());
            for (TimerClaimService.ClaimedTask task : claimed) {
                workers.submit(() -> execute(task));
            }
        } catch (Exception e) {
            // 高频扫描路径：DB 等系统性故障时按 30s 限频，避免每次扫描刷屏。
            long now = System.nanoTime();
            if (now - lastScanWarnNanos >= SCAN_WARN_INTERVAL_NANOS) {
                lastScanWarnNanos = now;
                log.warn("Timer scan failed (throttled): {}", e.toString());
            } else {
                log.debug("Timer scan failed: {}", e.toString());
            }
        }
    }

    private void execute(TimerClaimService.ClaimedTask task) {
        Renewer renewer = new Renewer(task);
        activeLeases.put(task.taskId(), renewer);
        renewer.start();
        LocalDateTime startedAt = databaseClock.now();
        String resultCode = null;
        String errorCode = null;
        boolean failed = false;
        try {
            PersistentTimerHandler handler = handlerRegistry.require(task.taskType());
            JsonNode payload = objectMapper.readTree(task.payloadJson());
            TimerTaskContext context = new TimerTaskContext(task.taskNo(), task.taskId(),
                    task.attemptNo(), task.fencingToken(), workerId);
            claimService.startExecution(task, workerId);
            resultCode = handler.handle(context, payload);
        } catch (Exception e) {
            failed = true;
            errorCode = e.getClass().getSimpleName();
            // 重试由 DB 审计（last_error_code + sc_timer_execution）记录，此处按错误码降级日志：
            // 可重试失败用 debug 防止重试风暴刷屏，死信在下方以 warn 单独记录。
            log.debug("Timer task {} execution failed (retryable): {}", task.taskNo(), e.toString());
        } finally {
            renewer.stop();
            activeLeases.remove(task.taskId());
            if (!failed) {
                claimService.complete(task, workerId, startedAt, resultCode, null);
            } else {
                boolean deadLetter = task.attemptNo() >= handlerRegistry.maxAttempts(task.taskType());
                Duration backoff = nextBackoff(task.attemptNo());
                claimService.fail(task, workerId, startedAt, errorCode, null, backoff, deadLetter);
                if (deadLetter) {
                    // 终态日志必须晚于状态与执行审计事务提交，设计见
                    // docs/superpowers/specs/2026-08-20-timer-outbox-schema-repair-design.md#31-timer-映射。
                    log.warn("Timer task {} dead-lettered after {} attempts: {}",
                            task.taskNo(), task.attemptNo(), errorCode);
                }
            }
        }
    }

    private Duration nextBackoff(int attemptNo) {
        long base = properties.getBackoffBase().toMillis();
        long max = properties.getBackoffMax().toMillis();
        long value = base * (1L << Math.min(attemptNo, 10));
        return Duration.ofMillis(Math.min(value, max));
    }

    /** 单任务租约续租器。 */
    private final class Renewer {
        private final TimerClaimService.ClaimedTask task;
        private java.util.concurrent.ScheduledFuture<?> future;

        Renewer(TimerClaimService.ClaimedTask task) {
            this.task = task;
        }

        void start() {
            long periodMs = properties.getRenewInterval().toMillis();
            future = workers.scheduleWithFixedDelay(this::renew, periodMs, periodMs, TimeUnit.MILLISECONDS);
        }

        void renew() {
            try {
                claimService.renewLease(task.taskId(), task.fencingToken(), workerId,
                        properties.getLeaseDuration());
            } catch (Exception e) {
                log.debug("Timer lease renew failed for task {}: {}", task.taskNo(), e.toString());
            }
        }

        void stop() {
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
