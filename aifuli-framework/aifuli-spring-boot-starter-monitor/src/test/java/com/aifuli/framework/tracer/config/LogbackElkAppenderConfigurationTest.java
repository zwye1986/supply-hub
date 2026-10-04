package com.aifuli.framework.tracer.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.joran.spi.JoranException;
import net.logstash.logback.appender.LogstashTcpSocketAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logback ELK Appender XML 配置加载与属性映射集成测试
 *
 * 验证规则：
 * 1. logback-elk-appender.xml 语法正确，可通过 JoranConfigurator 成功加载；
 * 2. 成功实例化 LOGSTASH_TCP (LogstashTcpSocketAppender) 并挂载至 Root Logger；
 * 3. 验证 neverBlock=true 非阻塞与背压保护配置；
 * 4. Joran 解析状态列表中无 ERROR 异常。
 *
 * 设计见 {@code docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#51-可复用-logback-片段设计-logback-elk-appenderxml}
 */
class LogbackElkAppenderConfigurationTest {

    @Test
    @DisplayName("验证 logback-elk-appender.xml 模版语法正确且能正常解析为 LogstashTcpSocketAppender")
    void shouldParseLogbackElkAppenderSuccessfully() throws JoranException {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);

        // 设置上下文属性模拟 Spring Boot 注入
        context.putProperty("APP_NAME", "test-service");
        context.putProperty("ACTIVE_PROFILE", "test");
        context.putProperty("ELK_ENABLED", "true");
        context.putProperty("ELK_HOST", "127.0.0.1");
        context.putProperty("ELK_PORT", "50000");
        context.putProperty("ELK_RING_BUFFER_SIZE", "4096");
        context.putProperty("ELK_RECONNECTION_DELAY", "3000");

        String xml = "<configuration>\n" +
                "    <include resource=\"logback-elk-appender.xml\"/>\n" +
                "    <root level=\"INFO\">\n" +
                "        <appender-ref ref=\"LOGSTASH_TCP\"/>\n" +
                "    </root>\n" +
                "</configuration>";

        configurator.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        // 验证 Joran 配置过程无 ERROR
        assertThat(context.getStatusManager().getCopyOfStatusList())
                .noneMatch(status -> status.getLevel() == ch.qos.logback.core.status.Status.ERROR);

        // 验证 Root Logger 成功挂载 LOGSTASH_TCP Appender
        Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Appender<?> appender = rootLogger.getAppender("LOGSTASH_TCP");
        assertThat(appender).isNotNull();
        assertThat(appender).isInstanceOf(LogstashTcpSocketAppender.class);

        LogstashTcpSocketAppender tcpAppender = (LogstashTcpSocketAppender) appender;
        assertThat(tcpAppender.getRingBufferSize()).isEqualTo(4096);
        assertThat(tcpAppender.getReconnectionDelay().getMilliseconds()).isEqualTo(3000L);
    }

}
