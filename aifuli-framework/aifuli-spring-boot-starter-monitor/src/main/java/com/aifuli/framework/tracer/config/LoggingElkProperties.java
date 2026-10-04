package com.aifuli.framework.tracer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ELK Logstash TCP 日志推送配置属性
 *
 * 设计见 {@code docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#32-配置属性定义-loggingelkproperties}
 *
 * @author 芋道源码
 */
@Data
@ConfigurationProperties(prefix = "aifuli.logging.elk")
public class LoggingElkProperties {

    /**
     * 是否开启 ELK Logstash TCP 日志推送，默认关闭 (在 dev/test/prod 环境按需开启)
     */
    private boolean enabled = false;

    /**
     * Logstash TCP 服务主机地址
     */
    private String host = "127.0.0.1";

    /**
     * Logstash TCP 端口号 (默认 50000)
     */
    private int port = 50000;

    /**
     * 异步环形队列 RingBuffer 大小 (默认 8192)
     */
    private int ringBufferSize = 8192;

    /**
     * 网络断开重连延迟时间 (毫秒，默认 5000ms)
     */
    private int reconnectionDelay = 5000;

}
