package com.aifuli.module.supplychain.tradecore.catalog.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 扫描诊断四桶上界、60秒计数窗口及业务异常无堆栈保护，见可售目录设计 §3.1。 */
class BuyerCatalogSweepFailureLogTest {
    @Test
    void boundedBucketsSummarizeRepeatedFailuresAndResetAfterRecovery() {
        Logger logger = (Logger) LoggerFactory.getLogger(getClass());
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            AtomicLong now = new AtomicLong();
            var limiter = new BuyerCatalogSweepFailureLog(now::get);
            var business = new ServiceException(123, "fixture refusal");
            var technical = new IllegalStateException("fixture failure");
            for (var stage : BuyerCatalogSweepFailureLog.Stage.values()) {
                for (long id = 1; id <= 100; id++) {
                    limiter.failure(logger, stage, id, business);
                    limiter.failure(logger, stage, id, technical);
                }
            }
            assertThat(appender.list).hasSize(4);
            assertThat(appender.list.stream().filter(event -> event.getFormattedMessage().contains("业务拒绝")))
                    .allMatch(event -> event.getThrowableProxy() == null && event.getLevel() == Level.WARN);
            now.set(TimeUnit.SECONDS.toNanos(60));
            limiter.failure(logger, BuyerCatalogSweepFailureLog.Stage.REFRESH, 101L, technical);
            assertThat(appender.list.getLast().getFormattedMessage()).contains("totalCount=101", "suppressedCount=100", "windowMs=60000");
            limiter.recovered(logger, BuyerCatalogSweepFailureLog.Stage.REFRESH);
            assertThat(appender.list.getLast().getLevel()).isEqualTo(Level.INFO);
            limiter.failure(logger, BuyerCatalogSweepFailureLog.Stage.REFRESH, 102L, technical);
            assertThat(appender.list.getLast().getThrowableProxy()).isNotNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
