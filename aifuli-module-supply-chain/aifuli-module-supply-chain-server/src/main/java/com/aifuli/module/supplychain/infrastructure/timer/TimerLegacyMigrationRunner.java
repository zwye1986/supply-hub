package com.aifuli.module.supplychain.infrastructure.timer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 旧 Timer 存量迁移与启动切换核验执行器。
 * <p>
 * 在供应链服务启动前置阶段优先执行：
 * 1. 回填存量 sc_timer_task 记录至 sc_timer_schedule_identity；
 * 2. 全量核验源表与账本双向一致性，任何破坏或超限阻断启动。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "aifuli.supply-chain", name = "enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class TimerLegacyMigrationRunner implements ApplicationRunner {

    private final TimerLegacyMigrationService migrationService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("启动执行：旧 Timer 账本迁移与切换一致性核验");
        int count = migrationService.backfillLegacyTasks();
        log.info("旧 Timer 账本回填完成，处理记录数={}", count);
        migrationService.verifyMigrationAndSwitchIntegrity();
        log.info("旧 Timer 启动切换一致性核验成功通过");
    }
}
