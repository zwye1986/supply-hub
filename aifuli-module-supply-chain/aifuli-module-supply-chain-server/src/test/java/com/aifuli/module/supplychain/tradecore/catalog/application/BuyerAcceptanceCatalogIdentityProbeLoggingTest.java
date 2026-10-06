package com.aifuli.module.supplychain.tradecore.catalog.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 仅验证probe入口诊断先于查询、定位参数完整且不刷堆栈；mock不作为事务或RR证据。
 * 设计见 docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md
 * §3.4「批次事务边界、真实调用者与竞争重试」。
 */
class BuyerAcceptanceCatalogIdentityProbeLoggingTest {
    @Test
    void debugEntryRecordsAllLocatorsBeforeFirstQueryWithoutExceptionStack() {
        var listings = mock(BuyerListingMapper.class);
        var products = mock(BuyerProductMapper.class);
        var probe = new BuyerAcceptanceCatalogIdentityProbe(listings, products);
        var race = new BuyerAcceptanceCatalogIdentityRaceException(17L, 29L, "BPD_ROLLED_BACK", new RuntimeException("仅诊断测试原因"));
        Logger logger = (Logger) LoggerFactory.getLogger(BuyerAcceptanceCatalogIdentityProbe.class);
        Level original = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        var eventsAtFirstQuery = new AtomicReference<List<ILoggingEvent>>();
        when(listings.selectByBuyerAndSkuForUpdate(17L, 29L)).thenAnswer(invocation -> {
            eventsAtFirstQuery.set(List.copyOf(appender.list));
            return null;
        });
        try {
            assertThatThrownBy(() -> probe.requireWholeBatchRetry(race)).isSameAs(race);
            assertThat(eventsAtFirstQuery.get()).as("第一条数据库查询之前必须已有入口debug")
                    .singleElement().satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                        assertThat(event.getMessage()).contains("buyerId={}", "platformSkuId={}", "attemptedCode={}");
                        assertThat(event.getArgumentArray()).containsExactly(17L, 29L, "BPD_ROLLED_BACK");
                        assertThat(event.getFormattedMessage()).contains("buyerId=17", "platformSkuId=29", "attemptedCode=BPD_ROLLED_BACK");
                        assertThat(event.getThrowableProxy()).isNull();
                    });
            assertThat(appender.list).as("仅一条debug，不新增info或重复堆栈").hasSize(1);
            verifyNoInteractions(products);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(original);
        }
    }
}
