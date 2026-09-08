package com.aifuli.module.supplychain.infrastructure.outbox;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbox 至少一次投递引擎：扫描抢占由短事务完成，LOCAL 目标投递给注册处理器，
 * HTTP 目标在 M1 仅记录日志（外部投递由后续任务接入）；投递在事务外执行。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#outbox-与-inbox}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(OutboxDeliveryProperties.class)
public class OutboxDeliveryEngine implements SmartLifecycle {

    private static final String TARGET_LOCAL = "LOCAL";
    private static final String TARGET_HTTP = "HTTP";

    private final OutboxDeliveryClaimService claimService;
    private final OutboxDispatchHandlerRegistry handlerRegistry;
    private final OutboxDeliveryProperties properties;
    private final String workerId = "outbox-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastScanWarnNanos = 0L;
    private static final long SCAN_WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private ScheduledExecutorService scanner;
    private ScheduledThreadPoolExecutor workers;
    private final ConcurrentHashMap<Long, java.util.concurrent.ScheduledFuture<?>> leases = new ConcurrentHashMap<>();

    @PostConstruct
    void validate() {
        if (properties.getLeaseDuration().toMillis() <= properties.getRenewInterval().toMillis()) {
            throw new IllegalStateException("outbox renewInterval must be smaller than leaseDuration");
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
            Thread t = new Thread(r, "supply-chain-outbox-scanner");
            t.setDaemon(true);
            return t;
        });
        long intervalMs = properties.getScanInterval().toMillis();
        scanner.scheduleWithFixedDelay(this::scan, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("OutboxDeliveryEngine started: workerId={}, workerPoolSize={}", workerId,
                properties.getWorkerPoolSize());
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
        leases.clear();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void scan() {
        if (!running.get()) {
            return;
        }
        try {
            List<OutboxEventDO> claimed = claimService.claimBatch(workerId,
                    properties.getBatchSize(), properties.getLeaseDuration());
            for (OutboxEventDO event : claimed) {
                workers.submit(() -> deliver(event));
            }
        } catch (Exception e) {
            // 高频扫描路径：系统性故障时按 30s 限频，避免刷屏。
            long now = System.nanoTime();
            if (now - lastScanWarnNanos >= SCAN_WARN_INTERVAL_NANOS) {
                lastScanWarnNanos = now;
                log.warn("Outbox scan failed (throttled): {}", e.toString());
            } else {
                log.debug("Outbox scan failed: {}", e.toString());
            }
        }
    }

    private void deliver(OutboxEventDO event) {
        long fencing = event.getFencingToken();
        scheduleRenewal(event.getId(), fencing);
        try {
            claimService.markDelivering(event.getId(), fencing, workerId);
            if (TARGET_LOCAL.equals(event.getTargetType())) {
                OutboxDispatchHandler handler = handlerRegistry.find(event.getTargetName());
                if (handler == null) {
                    throw new IllegalStateException("No OutboxDispatchHandler for targetName: "
                            + event.getTargetName());
                }
                handler.dispatch(event);
            } else if (TARGET_HTTP.equals(event.getTargetType())) {
                log.info("Outbox HTTP delivery not implemented in M1: eventId={}, target={}",
                        event.getEventId(), event.getTargetName());
            } else {
                throw new IllegalStateException("Unknown outbox target_type: " + event.getTargetType());
            }
            claimService.publish(event.getId(), fencing, workerId);
        } catch (Exception e) {
            String errorCode = e.getClass().getSimpleName();
            boolean deadLetter = event.getAttemptCount() >= event.getMaxAttempts();
            // 重试由 DB 审计记录；可重试失败用 debug 防风暴，死信用 warn 单独记录。
            if (deadLetter) {
                log.warn("Outbox delivery dead-lettered for eventId={} after {} attempts: {}",
                        event.getEventId(), event.getAttemptCount(), errorCode);
            } else {
                log.debug("Outbox delivery failed for eventId={} (retryable): {}",
                        event.getEventId(), e.toString());
            }
            Duration backoff = nextBackoff(event.getAttemptCount());
            claimService.fail(event.getId(), fencing, workerId, errorCode, backoff, deadLetter);
        } finally {
            cancelRenewal(event.getId());
        }
    }

    private Duration nextBackoff(int attemptCount) {
        long base = properties.getBackoffBase().toMillis();
        long max = properties.getBackoffMax().toMillis();
        long value = base * (1L << Math.min(attemptCount, 10));
        return Duration.ofMillis(Math.min(value, max));
    }

    private void scheduleRenewal(Long id, long fencing) {
        long periodMs = properties.getRenewInterval().toMillis();
        java.util.concurrent.ScheduledFuture<?> future = workers.scheduleWithFixedDelay(
                () -> claimService.renewLease(id, fencing, workerId, properties.getLeaseDuration()),
                periodMs, periodMs, TimeUnit.MILLISECONDS);
        leases.put(id, future);
    }

    private void cancelRenewal(Long id) {
        java.util.concurrent.ScheduledFuture<?> future = leases.remove(id);
        if (future != null) {
            future.cancel(false);
        }
    }
}
