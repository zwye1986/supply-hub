package com.aifuli.module.supplychain.infrastructure.identity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编码格式失败仍保留解析原因，并记录操作人及失败耗时，便于按错误码排障。
 * 设计见 docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#5-存储迁移与并发。
 */
class ScopedBusinessCodeServiceLogTest {

    @Test
    void invalidManualCodeRetainsCauseAndLogsFailureWithOperator() {
        Logger logger = (Logger) LoggerFactory.getLogger(ScopedBusinessCodeService.class);
        Level priorLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            var service = new ScopedBusinessCodeService(null, null, null, null);
            assertThatThrownBy(() -> service.allocateRoot(ScopedBusinessCodeService.Kind.BRD, "bad", 42L))
                    .isInstanceOf(ServiceException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage))
                    .anyMatch(message -> message.contains("分配入口") && message.contains("operatorId=42"))
                    .anyMatch(message -> message.contains("分配失败") && message.contains("operatorId=42")
                            && message.contains("elapsedMicros="));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(priorLevel);
            appender.stop();
        }
    }
}
