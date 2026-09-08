package com.aifuli.module.supplychain.infrastructure.timer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "aifuli.supply-chain.timer")
public class TimerEngineProperties {

    /** 是否启用持久化定时器引擎。 */
    private boolean enabled = true;

    /** 扫描周期。 */
    private Duration scanInterval = Duration.ofMillis(800);

    /** 单次扫描最多抢占任务数。 */
    private int batchSize = 32;

    /** 单任务执行租约时长；执行超过该时长未续租会被其它节点重新抢占。 */
    private Duration leaseDuration = Duration.ofSeconds(30);

    /** 执行租约续租周期，应显著小于 leaseDuration。 */
    private Duration renewInterval = Duration.ofSeconds(10);

    /** 执行工作池大小。 */
    private int workerPoolSize = 8;

    /** 失败退避基数。 */
    private Duration backoffBase = Duration.ofSeconds(5);

    /** 失败退避上限。 */
    private Duration backoffMax = Duration.ofMinutes(10);
}
