package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.module.supplychain.infrastructure.configuration.BuyerSelectionCatalogProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 后台扫描生命周期：供应链专用单线程 fixed-delay 调度器，启动即扫描，前一批结束后等待默认30秒。
 * 原生调度线程主动包装上下文：每轮生成新 traceId/executionId，保存并恢复完整原 MDC，不依赖Spring装饰器；
 * 退出/中断不伪造成功。汇总日志按 TASK 口径：无变化 DEBUG、有失败 WARN、恢复 INFO，耗时 elapsedMs。
 * 不改共享调度器、nonce清理、Timer/Outbox 线程池或八进程装配。
 * 设计见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#6-后台自动恢复有界周期扫描、
 * §9 日志与配置约束及 docs/superpowers/specs/2026-10-04-logging-standardization-spec.md §2.4/§3.3。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BuyerSelectionCatalogSweepLifecycle {

    private static final String MDC_KEY_EXECUTION_ID = "executionId";

    private final BuyerSelectionCatalogSweepService sweepService;
    private final BuyerSelectionCatalogProperties properties;

    private ScheduledExecutorService executor;
    private volatile boolean lastRoundHadFailure;
    private final BuyerCatalogSweepFailureLog failureLog = new BuyerCatalogSweepFailureLog();

    @PostConstruct
    public void start() {
        requirePositiveConfiguration();
        if (!properties.isSweepEnabled()) {
            log.info("[catalogSweepLifecycle][后台扫描关闭] 扫描未启用，保存后自动重试收敛不作承诺");
            return;
        }
        int delaySeconds = properties.getSweepDelaySeconds() == null
                ? 30 : properties.getSweepDelaySeconds();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "supply-chain-catalog-sweep");
            thread.setDaemon(true);
            return thread;
        });
        // fixed-delay：前一批结束后等待；启动即执行第一轮。
        executor.scheduleWithFixedDelay(this::runOneRound, 0, delaySeconds, TimeUnit.SECONDS);
        log.info("[catalogSweepLifecycle][后台扫描启动] delaySeconds={}, batchSize={}, maxEvaluationAgeSeconds={}",
                delaySeconds, effectiveBatchSize(), properties.effectiveMaxEvaluationAgeSeconds());
    }

    @PreDestroy
    public void stop() {
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) {
            // 中断停止：不启动新条目；当前条目依既有事务协议结束，不伪造成功。
            current.shutdownNow();
        }
    }

    /** 单轮执行：安装本轮独立编号，结束后恢复执行线程原上下文。包私有供单元测试直接驱动。 */
    void runOneRound() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.setContextMap(Map.of(TracerUtils.MDC_KEY_TRACE_ID, newTraceId(), MDC_KEY_EXECUTION_ID, newTraceId()));
        long startedNanos = System.nanoTime();
        try {
            BuyerSelectionCatalogSweepService.SweepBatch batch = sweepService.sweepOneBatch();
            failureLog.recovered(log, BuyerCatalogSweepFailureLog.Stage.QUERY);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            if (batch.failed() > 0) {
                lastRoundHadFailure = true;
                log.debug("[catalogSweep][扫描完成存在失败] attempted={}, published={}, failed={}, skipped={}, elapsedMs={}",
                        batch.attempted(), batch.published(), batch.failed(), batch.skipped(), elapsedMs);
            } else if (lastRoundHadFailure) {
                lastRoundHadFailure = false;
                log.debug("[catalogSweep][本批无失败] attempted={}, published={}, failed={}, skipped={}, elapsedMs={}",
                        batch.attempted(), batch.published(), batch.failed(), batch.skipped(), elapsedMs);
            } else {
                log.debug("[catalogSweep][扫描完成] attempted={}, published={}, failed={}, skipped={}, elapsedMs={}",
                        batch.attempted(), batch.published(), batch.failed(), batch.skipped(), elapsedMs);
            }
        } catch (RuntimeException failure) {
            // 查询失败本批不推进位置，下一次重试；本轮系统失败记录一次因果，不打印重复堆栈。
            lastRoundHadFailure = true;
            failureLog.failure(log, BuyerCatalogSweepFailureLog.Stage.QUERY, null, failure);
        } finally {
            restoreContext(previous);
        }
    }

    /** 显式配置非正数启动拒绝：不静默回退掩盖部署错误。未配置(null)使用默认值。 */
    private void requirePositiveConfiguration() {
        if (properties.getSweepBatchSize() != null && properties.getSweepBatchSize() <= 0) {
            throw new IllegalStateException("catalog sweep batch size must be positive");
        }
        if (properties.getSweepDelaySeconds() != null && properties.getSweepDelaySeconds() <= 0) {
            throw new IllegalStateException("catalog sweep delay seconds must be positive");
        }
        if (properties.getMaxEvaluationAgeSeconds() != null && properties.getMaxEvaluationAgeSeconds() <= 0) {
            throw new IllegalStateException("catalog max evaluation age seconds must be positive");
        }
    }

    private int effectiveBatchSize() {
        Integer configured = properties.getSweepBatchSize();
        return configured == null || configured <= 0 ? 100 : configured;
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 恢复执行线程原上下文：原为空才清空，不无条件清掉外层上下文。 */
    private static void restoreContext(Map<String, String> previous) {
        if (previous == null || previous.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
