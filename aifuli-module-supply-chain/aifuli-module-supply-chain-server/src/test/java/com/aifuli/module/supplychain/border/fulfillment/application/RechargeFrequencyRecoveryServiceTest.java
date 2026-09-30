package com.aifuli.module.supplychain.border.fulfillment.application;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class RechargeFrequencyRecoveryServiceTest {
    private final LocalDateTime now=LocalDateTime.of(2026,10,1,8,0);
    @Test void alarmEventIsEmittedOnlyAfterCommittedStateCanEnforceRateLimit() {
        var queues=org.mockito.Mockito.mock(com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyQueueMapper.class);
        var entries=org.mockito.Mockito.mock(com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyEntryMapper.class);
        var tasks=org.mockito.Mockito.mock(com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper.class);
        var clock=org.mockito.Mockito.mock(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class);
        org.mockito.Mockito.when(clock.now()).thenReturn(now);
        var queue=com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyQueueDO.builder()
                .id(1L).platformSpuId(9L).wakeGeneration(1L).nextCheckAt(now.minusSeconds(1)).backlogAlertActive(false).occupiedAlertActive(false).build();
        org.mockito.Mockito.when(queues.selectByIdForUpdate(1L)).thenReturn(queue);
        org.mockito.Mockito.when(queues.updateById(org.mockito.ArgumentMatchers.any(com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyQueueDO.class))).thenReturn(1);
        org.mockito.Mockito.when(entries.selectCount(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(6L);
        org.mockito.Mockito.when(tasks.selectByTypeAndKey("RECHARGE_FREQUENCY_WAKE","1:1")).thenReturn(
                com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO.builder().status((short)0).nextFireAt(now.plusHours(1)).build());
        var service=new RechargeFrequencyRecoveryService(queues,entries,null,null,tasks,clock,null);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(RechargeFrequencyRecoveryService.class);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); events.start(); logger.addAppender(events);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.repair(1L);
            assertThat(events.list).isEmpty();
            var synchronizations=org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            assertThat(events.list).hasSize(1);
            assertThat(events.list.getFirst().getFormattedMessage()).contains("BACKLOG_ACTIVE","queueId=1","platformSpuId=9");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); logger.detachAppender(events); events.stop();
        }
    }
    @Test void batchFailureKeepsEverySafeCauseAndDoesNotPretendSuccess() {
        var queues=org.mockito.Mockito.mock(com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyQueueMapper.class);
        var clock=org.mockito.Mockito.mock(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class);
        org.mockito.Mockito.when(clock.now()).thenReturn(now);
        org.mockito.Mockito.when(queues.selectList(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class)))
                .thenReturn(java.util.List.of(com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyQueueDO.builder().id(1L).build(),
                        com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyQueueDO.builder().id(2L).build()));
        var worker=org.mockito.Mockito.mock(RechargeFrequencyRecoveryService.class);
        org.mockito.Mockito.when(worker.repair(1L)).thenThrow(new IllegalStateException("secret one"));
        org.mockito.Mockito.when(worker.repair(2L)).thenThrow(new IllegalArgumentException("secret two"));
        org.springframework.beans.factory.ObjectProvider<RechargeFrequencyRecoveryService> provider=org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getObject()).thenReturn(worker);
        var service=new RechargeFrequencyRecoveryService(queues,null,null,null,null,clock,provider);
        assertThatThrownBy(service::compensate).hasMessage("FREQUENCY_RECOVERY_FAILED").satisfies(error -> {
            assertThat(error.getSuppressed()).hasSize(1);
            assertThat(error.getSuppressed()[0].getCause().getMessage()).isEqualTo(IllegalArgumentException.class.getName());
        });
        org.mockito.Mockito.verify(worker).repair(1L); org.mockito.Mockito.verify(worker).repair(2L);
    }
    @Test void missingRequiredTimerFailsAtStartupAfterLazyConstructorResolution() {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(com.fasterxml.jackson.databind.ObjectMapper.class,()->new com.fasterxml.jackson.databind.ObjectMapper());
            context.registerBean(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class,
                    ()->org.mockito.Mockito.mock(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class));
            context.register(RechargeFrequencyWakeScheduler.class, RechargeFrequencyTimerStartupCheck.class);
            assertThatThrownBy(context::refresh).isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
        }
    }
    @Test void inactiveAlarmDoesNotEmit() { assertThat(RechargeFrequencyRecoveryService.alarmDue(false,false,null,now)).isFalse(); }
    @Test void firstCrossingEmits() { assertThat(RechargeFrequencyRecoveryService.alarmDue(true,false,null,now)).isTrue(); }
    @Test void recoveryEmitsOnce() { assertThat(RechargeFrequencyRecoveryService.alarmDue(false,true,now.minusSeconds(1),now)).isTrue(); }
    @Test void repeatedAlarmWaitsThirtyMinutes() {
        assertThat(RechargeFrequencyRecoveryService.alarmDue(true,true,now.minusSeconds(1799),now)).isFalse();
        assertThat(RechargeFrequencyRecoveryService.alarmDue(true,true,now.minusSeconds(1800),now)).isTrue();
    }
    @Test void propagationRetainsSafeNestedTypesAndCodeLocationsWithoutSupplierText() {
        var original=new IllegalStateException("secret mobile 13800138000", new java.io.IOException("provider secret"));
        var failure=RechargeFrequencyRecoveryService.safeFailure("RECOVERY_FAILED",original);
        assertThat(failure).hasMessage("RECOVERY_FAILED");
        assertThat(failure.getCause().getMessage()).isEqualTo(IllegalStateException.class.getName());
        assertThat(failure.getCause().getCause()).isNotNull();
        assertThat(failure.getCause().getCause().getMessage()).isEqualTo(java.io.IOException.class.getName());
        assertThat(failure.getCause().getStackTrace()).isEqualTo(original.getStackTrace());
    }
}
