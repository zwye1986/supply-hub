package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyQueueDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyQueueMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyRecoveryCodes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实compensate与实际循环回调，验证最后日志边界的安全因果及采样。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#9-告警与观测。 */
class RechargeFrequencyRecoveryConfigurationTest {
    @Test void actualLoopPreservesSafeQueueAndScanCausesWhileThrottlingRepeatedFailures() {
        var queues=mock(RechargeFrequencyQueueMapper.class);
        var clock=mock(SupplyChainDatabaseClock.class);
        when(clock.now()).thenReturn(LocalDateTime.of(2026,10,1,8,0));
        when(queues.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                RechargeFrequencyQueueDO.builder().id(1L).build(), RechargeFrequencyQueueDO.builder().id(2L).build()));
        var worker=mock(RechargeFrequencyRecoveryService.class);
        when(worker.repair(1L)).thenThrow(new DataAccessResourceFailureException("SQLrawtext phone=13800138000 secret=queue1",
                new SQLTransientConnectionException("SQLrawtext password=hidden")));
        when(worker.repair(2L)).thenThrow(new IllegalArgumentException("secret=queue2 phone=13800138000"));
        ObjectProvider<RechargeFrequencyRecoveryService> self=mock(ObjectProvider.class); when(self.getObject()).thenReturn(worker);
        var recovery=new RechargeFrequencyRecoveryService(queues,null,null,null,null,clock,self);
        var executor=mock(ScheduledExecutorService.class);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(RechargeFrequencyRecoveryConfiguration.class);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var previous=logger.getLevel(); logger.setLevel(ch.qos.logback.classic.Level.INFO); events.start(); logger.addAppender(events);
        try(var factory=mockStatic(Executors.class)) {
            factory.when(()->Executors.newSingleThreadScheduledExecutor(any(ThreadFactory.class))).thenReturn(executor);
            var loop=new RechargeFrequencyRecoveryConfiguration.RechargeFrequencyCompensationLoop(recovery); loop.start();
            var callback=ArgumentCaptor.forClass(Runnable.class);
            verify(executor).scheduleWithFixedDelay(callback.capture(),eq(30L),eq(30L),eq(TimeUnit.SECONDS));
            callback.getValue().run();
            assertThat(events.list).hasSize(1);
            var event=events.list.getFirst();
            assertThat(event.getFormattedMessage()).contains(RechargeFrequencyRecoveryCodes.STORAGE_FAILED,"failedQueues=2","queueIds=[1, 2]");
            assertThat(event.getThrowableProxy()).isNotNull();
            String causal=ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy());
            assertThat(causal).contains(DataAccessResourceFailureException.class.getName(),SQLTransientConnectionException.class.getName(),
                    IllegalArgumentException.class.getName(),"Suppressed:","RechargeFrequencyRecoveryService.compensate","RechargeFrequencyRecoveryConfigurationTest");
            assertThat(causal+event.getFormattedMessage()).doesNotContain("13800138000","SQLrawtext","secret=","password=hidden");
            callback.getValue().run(); assertThat(events.list).hasSize(1);
            doReturn(false).when(worker).repair(1L); doReturn(false).when(worker).repair(2L);
            callback.getValue().run(); assertThat(events.list).hasSize(2);
            assertThat(events.list.getLast().getFormattedMessage()).contains(RechargeFrequencyRecoveryCodes.STORAGE_RECOVERED,"failedBatches=2");
            when(queues.selectList(any(LambdaQueryWrapper.class))).thenThrow(new DataAccessResourceFailureException("SQLrawtext secret=scan phone=13800138000",
                    new SQLTransientConnectionException("SQLrawtext password=hidden")));
            callback.getValue().run(); assertThat(events.list).hasSize(3);
            var scan=events.list.getLast(); assertThat(scan.getThrowableProxy()).isNotNull();
            String scanCause=ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(scan.getThrowableProxy());
            assertThat(scanCause).contains(DataAccessResourceFailureException.class.getName(),SQLTransientConnectionException.class.getName(),"RechargeFrequencyRecoveryService.compensate");
            assertThat(scanCause+scan.getFormattedMessage()).doesNotContain("13800138000","SQLrawtext","secret=","password=hidden");
            loop.close(); verify(executor).shutdown();
        } finally { logger.detachAppender(events); events.stop(); logger.setLevel(previous); }
    }
}
