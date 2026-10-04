package com.aifuli.framework.tracer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LoggingElkProperties} 属性单元测试
 *
 * 验证规则：
 * 1. 默认关闭 ELK TCP 发送；
 * 2. 默认地址为 127.0.0.1:50000；
 * 3. 默认缓冲队列大小为 8192，重连延迟为 5000ms。
 *
 * 设计见 {@code docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#32-配置属性定义-loggingelkproperties}
 */
class LoggingElkPropertiesTest {

    @Test
    @DisplayName("验证 LoggingElkProperties 默认值符合生产安全与防背压规范")
    void shouldHaveExpectedDefaults() {
        LoggingElkProperties properties = new LoggingElkProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getHost()).isEqualTo("127.0.0.1");
        assertThat(properties.getPort()).isEqualTo(50000);
        assertThat(properties.getRingBufferSize()).isEqualTo(8192);
        assertThat(properties.getReconnectionDelay()).isEqualTo(5000);
    }

}
