package com.aifuli.module.supplychain.infrastructure.outbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "aifuli.supply-chain.outbox")
public class OutboxDeliveryProperties {

    private boolean enabled = true;

    private Duration scanInterval = Duration.ofMillis(800);

    private int batchSize = 32;

    private Duration leaseDuration = Duration.ofSeconds(30);

    private Duration renewInterval = Duration.ofSeconds(10);

    private int workerPoolSize = 8;

    private Duration backoffBase = Duration.ofSeconds(5);

    private Duration backoffMax = Duration.ofMinutes(10);
}
