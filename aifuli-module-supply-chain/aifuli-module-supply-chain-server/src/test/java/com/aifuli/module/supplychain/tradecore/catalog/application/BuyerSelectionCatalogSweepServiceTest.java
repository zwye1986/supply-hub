package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.configuration.BuyerSelectionCatalogProperties;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerCatalogRefreshOutcome;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 有界公平扫描纯逻辑：上界冻结、逐批推进、失败仍推进、查询失败不推进、空页重置、中断不启动新条目。
 * 设计 §6.1～§6.3。
 */
class BuyerSelectionCatalogSweepServiceTest {

    private final BuyerListingMapper listings = mock(BuyerListingMapper.class);
    private final BuyerSelectionCatalogRefreshService refresh = mock(BuyerSelectionCatalogRefreshService.class);
    private final BuyerSelectionCatalogProperties properties = new BuyerSelectionCatalogProperties();
    private BuyerSelectionCatalogSweepService service;

    @BeforeEach
    void setUp() {
        properties.setSweepBatchSize(2);
        service = new BuyerSelectionCatalogSweepService(listings, refresh, properties);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void freezesUpperIdAtTraversalStartAndAdvancesCursorAcrossBatches() {
        when(listings.selectMaxAutoSweepableId()).thenReturn(10L);
        when(listings.selectAutoSweepableIds(isNull(), eq(10L), eq(2))).thenReturn(List.of(1L, 2L));
        when(listings.selectAutoSweepableIds(eq(2L), eq(10L), eq(2))).thenReturn(List.of(3L, 4L));
        when(listings.selectAutoSweepableIds(eq(4L), eq(10L), eq(2))).thenReturn(List.of());
        when(refresh.refresh(anyLong(), eq(BuyerSelectionCatalogRefreshService.Trigger.SWEEP), isNull()))
                .thenReturn(unchanged());

        service.sweepOneBatch();
        service.sweepOneBatch();
        service.sweepOneBatch();

        // 高ID持续新增不会饿死低ID：本遍历上界冻结为10，直到到达上界才重新读上界。
        verify(listings, times(1)).selectMaxAutoSweepableId();
        verify(refresh, times(4)).refresh(anyLong(), eq(BuyerSelectionCatalogRefreshService.Trigger.SWEEP), isNull());
        // 空页清空位置，下一次遍历重读上界。
        service.sweepOneBatch();
        verify(listings, times(2)).selectMaxAutoSweepableId();
    }

    @Test
    void refreshFailureAdvancesCursorWithoutBlockingRest() {
        when(listings.selectMaxAutoSweepableId()).thenReturn(10L);
        when(listings.selectAutoSweepableIds(isNull(), eq(10L), eq(2))).thenReturn(List.of(1L, 2L));
        when(refresh.refresh(eq(1L), eq(BuyerSelectionCatalogRefreshService.Trigger.SWEEP), isNull()))
                .thenThrow(new IllegalStateException("broken reference"));
        when(refresh.refresh(eq(2L), eq(BuyerSelectionCatalogRefreshService.Trigger.SWEEP), isNull()))
                .thenReturn(updated());

        BuyerSelectionCatalogSweepService.SweepBatch batch = service.sweepOneBatch();

        assertThat(batch.attempted()).isEqualTo(2);
        assertThat(batch.published()).isEqualTo(1);
        assertThat(batch.failed()).isEqualTo(1);
        assertThat(batch.skipped()).isZero();
        // 失败条已推进：下一批从2之后开始，不重试本批。
        when(listings.selectAutoSweepableIds(eq(2L), eq(10L), eq(2))).thenReturn(List.of(3L));
        when(refresh.refresh(eq(3L), eq(BuyerSelectionCatalogRefreshService.Trigger.SWEEP), isNull()))
                .thenReturn(unchanged());
        service.sweepOneBatch();
        verify(refresh, times(1)).refresh(eq(1L), eq(BuyerSelectionCatalogRefreshService.Trigger.SWEEP), isNull());
    }

    @Test
    void queryFailureDoesNotAdvanceCursor() {
        when(listings.selectMaxAutoSweepableId()).thenReturn(10L);
        when(listings.selectAutoSweepableIds(isNull(), eq(10L), eq(2)))
                .thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(service::sweepOneBatch).isInstanceOf(IllegalStateException.class);
        // 位置未推进：下次查询同一范围。
        when(listings.selectAutoSweepableIds(isNull(), eq(10L), eq(2))).thenReturn(List.of(1L));
        when(refresh.refresh(eq(1L), eq(BuyerSelectionCatalogRefreshService.Trigger.SWEEP), isNull()))
                .thenReturn(unchanged());
        service.sweepOneBatch();
        verify(refresh).refresh(eq(1L), eq(BuyerSelectionCatalogRefreshService.Trigger.SWEEP), isNull());
    }

    @Test
    void noCandidatesReturnsEmptyAndSchedulerKeepsRunning() {
        when(listings.selectMaxAutoSweepableId()).thenReturn(null);
        assertThat(service.sweepOneBatch()).isEqualTo(new BuyerSelectionCatalogSweepService.SweepBatch(0, 0, 0, 0));
        verify(listings, never()).selectAutoSweepableIds(any(), any(), anyInt());
        verify(refresh, never()).refresh(anyLong(), any(), any());
        // 没有候选不结束调度：下一次仍重新读上界。
        service.sweepOneBatch();
        verify(listings, times(2)).selectMaxAutoSweepableId();
    }

    @Test
    void repeatedTechnicalFailuresKeepFirstCauseWithoutPerListingWarnStorm() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BuyerSelectionCatalogSweepService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(listings.selectMaxAutoSweepableId()).thenReturn(2L);
            when(listings.selectAutoSweepableIds(isNull(), eq(2L), eq(2))).thenReturn(List.of(1L, 2L));
            var failure = new IllegalStateException("broken reference", new java.sql.SQLException("fixture SQL failure"));
            when(refresh.refresh(anyLong(), any(), isNull())).thenThrow(failure);
            assertThat(service.sweepOneBatch().failed()).isEqualTo(2);
            assertThat(service.sweepOneBatch().failed()).isEqualTo(2);
            var warnings = appender.list.stream().filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN).toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.getFirst().getThrowableProxy()).isNotNull();
            assertThat(warnings.getFirst().getThrowableProxy().getCause().getClassName()).isEqualTo(java.sql.SQLException.class.getName());
            org.mockito.Mockito.doReturn(unchanged()).when(refresh).refresh(anyLong(), any(), isNull());
            service.sweepOneBatch();
            assertThat(appender.list).anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.INFO
                    && event.getFormattedMessage().contains("恢复"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void interruptDuringFirstEntryDoesNotStartRemainingEntries() {
        when(listings.selectMaxAutoSweepableId()).thenReturn(10L);
        when(listings.selectAutoSweepableIds(isNull(), eq(10L), eq(2))).thenReturn(List.of(1L, 2L));
        when(refresh.refresh(eq(1L), any(), isNull())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return unchanged();
        });
        var batch = service.sweepOneBatch();
        assertThat(batch.attempted()).isEqualTo(1);
        verify(refresh, never()).refresh(eq(2L), any(), any());
        Thread.interrupted();
        when(listings.selectAutoSweepableIds(eq(1L), eq(10L), eq(2))).thenReturn(List.of());
        service.sweepOneBatch();
        verify(listings).selectAutoSweepableIds(eq(1L), eq(10L), eq(2));
    }

    @Test
    void interruptedBatchStartsNoNewEntry() {
        when(listings.selectMaxAutoSweepableId()).thenReturn(10L);
        Thread.currentThread().interrupt();
        assertThat(service.sweepOneBatch()).isEqualTo(new BuyerSelectionCatalogSweepService.SweepBatch(0, 0, 0, 0));
        verify(refresh, never()).refresh(anyLong(), any(), any());
    }

    @Test
    void countsPublishedUpdatedAndOthersSkipped() {
        when(listings.selectMaxAutoSweepableId()).thenReturn(10L);
        when(listings.selectAutoSweepableIds(isNull(), eq(10L), eq(2))).thenReturn(List.of(1L, 2L));
        when(refresh.refresh(eq(1L), eq(BuyerSelectionCatalogRefreshService.Trigger.SWEEP), isNull()))
                .thenReturn(updated());
        when(refresh.refresh(eq(2L), eq(BuyerSelectionCatalogRefreshService.Trigger.SWEEP), isNull()))
                .thenReturn(paused());

        BuyerSelectionCatalogSweepService.SweepBatch batch = service.sweepOneBatch();
        assertThat(batch).isEqualTo(new BuyerSelectionCatalogSweepService.SweepBatch(2, 1, 0, 1));
    }

    private static BuyerSelectionCatalogRefreshResult updated() {
        return result(BuyerCatalogRefreshOutcome.UPDATED);
    }

    private static BuyerSelectionCatalogRefreshResult unchanged() {
        return result(BuyerCatalogRefreshOutcome.UNCHANGED);
    }

    private static BuyerSelectionCatalogRefreshResult paused() {
        return result(BuyerCatalogRefreshOutcome.PAUSED);
    }

    private static BuyerSelectionCatalogRefreshResult result(BuyerCatalogRefreshOutcome outcome) {
        return new BuyerSelectionCatalogRefreshResult(outcome, BuyerListingStatus.PUBLISHED,
                ListingAvailability.AVAILABLE, null, LocalDateTime.of(2026, 10, 9, 12, 0));
    }
}
