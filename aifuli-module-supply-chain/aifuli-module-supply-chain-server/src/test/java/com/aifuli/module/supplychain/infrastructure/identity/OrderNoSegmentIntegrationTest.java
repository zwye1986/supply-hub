package com.aifuli.module.supplychain.infrastructure.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.dal.mysql.*;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

class OrderNoSegmentIntegrationTest {
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("supply_chain")
                    .withUsername("supply_chain")
                    .withPassword("supply_chain");
    static OrderNoIntegrationSupport db;
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final LocalDate DATE = LocalDate.of(2026, 10, 13);

    static String owner() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @BeforeAll
    static void start() {
        MYSQL.start();
        db = new OrderNoIntegrationSupport(MYSQL);
    }

    @AfterAll
    static void stop() {
        if (db != null) db.close();
        MYSQL.stop();
    }

    @BeforeEach
    void clear() {
        db.clear();
    }

    @Test
    void firstDateRaceReservesDisjointPermanentIntervals() throws Exception {
        try (var workers = Executors.newFixedThreadPool(12)) {
            var gate = new CountDownLatch(1);
            List<Future<OrderNoSegment>> futures =
                    IntStream.range(0, 12)
                            .mapToObj(
                                    i ->
                                            workers.submit(
                                                    () -> {
                                                        gate.await();
                                                        return db.store.reserve(
                                                                DATE, owner(), 1000, 2, TIMEOUT);
                                                    }))
                            .toList();
            gate.countDown();
            var segments = new ArrayList<OrderNoSegment>();
            for (var f : futures) segments.add(f.get(15, TimeUnit.SECONDS));
            segments.sort(Comparator.comparingInt(OrderNoSegment::startValue));
            for (int i = 0; i < 12; i++) {
                assertThat(segments.get(i).startValue()).isEqualTo(i * 1000 + 1);
                assertThat(segments.get(i).endValue()).isEqualTo((i + 1) * 1000);
            }
            assertThat(
                            db.jdbc.queryForObject(
                                    "SELECT last_reserved_value FROM sc_order_no_sequence",
                                    Integer.class))
                    .isEqualTo(12000);
            assertThat(
                            db.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sc_order_no_segment", Integer.class))
                    .isEqualTo(12);
        }
    }

    @Test
    void partialFinalRangeMustFitWholeBatch() {
        var first = db.store.reserve(DATE, owner(), 1000, 2, TIMEOUT);
        db.jdbc.update("UPDATE sc_order_no_sequence SET last_reserved_value=99999997");
        var last = db.store.reserve(DATE, owner(), 1000, 2, TIMEOUT);
        assertThat(last.startValue()).isEqualTo(99999998);
        assertThat(last.endValue()).isEqualTo(99999999);
        assertThatThrownBy(() -> db.store.reserve(DATE, owner(), 1000, 1, TIMEOUT))
                .isInstanceOf(ServiceException.class);
        assertThat(
                        db.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sc_order_no_segment", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void insufficientPairCapacityDoesNotAdvanceHighwater() {
        db.store.reserve(DATE, owner(), 1000, 1, TIMEOUT);
        db.jdbc.update("UPDATE sc_order_no_sequence SET last_reserved_value=99999998");
        assertThatThrownBy(() -> db.store.reserve(DATE, owner(), 1000, 2, TIMEOUT))
                .isInstanceOf(ServiceException.class);
        assertThat(
                        db.jdbc.queryForObject(
                                "SELECT last_reserved_value FROM sc_order_no_sequence",
                                Integer.class))
                .isEqualTo(99999998);
    }

    @Test
    void segmentInsertFailureRollsBackBothTables() {
        OrderNoSegmentMapper actual = db.context.getBean(OrderNoSegmentMapper.class);
        var failing = mock(OrderNoSegmentMapper.class);
        doAnswer(
                        inv -> {
                            actual.insert(
                                    (com.aifuli.module.supplychain.infrastructure.identity.dal
                                                    .dataobject.OrderNoSegmentDO)
                                            inv.getArgument(0));
                            throw new IllegalStateException("after insert rollback");
                        })
                .when(failing)
                .insert(
                        any(
                                com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject
                                        .OrderNoSegmentDO.class));
        assertThatThrownBy(() -> db.store(failing, db.tm).reserve(DATE, owner(), 1000, 2, TIMEOUT))
                .hasMessageContaining("rollback");
        assertThat(
                        db.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sc_order_no_sequence", Integer.class))
                .isZero();
        assertThat(
                        db.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sc_order_no_segment", Integer.class))
                .isZero();
        assertThat(db.store.reserve(DATE, owner(), 1000, 2, TIMEOUT).startValue()).isEqualTo(1);
    }

    @Test
    void committedReservationNeverReclaimsAndReleasesBoundConnection() {
        String o = owner();
        assertThat(db.store.reserve(DATE, o, 1000, 2, TIMEOUT).startValue()).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.hasResource(db.ds)).isFalse();
        assertThat(db.store.reserve(DATE, o, 1000, 2, TIMEOUT).startValue()).isEqualTo(1001);
    }

    @Test
    void nestedTransactionRejectedBeforeBorrowingSecondConnection() {
        new TransactionTemplate(db.tm)
                .executeWithoutResult(
                        s ->
                                assertThatThrownBy(
                                                () ->
                                                        db.store.reserve(
                                                                DATE, owner(), 1000, 2, TIMEOUT))
                                        .isInstanceOf(IllegalStateException.class));
        assertThat(
                        db.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sc_order_no_sequence", Integer.class))
                .isZero();
    }

    @Test
    void returningToHistoricalDateKeepsPermanentHighwater() {
        assertThat(db.store.reserve(DATE, owner(), 1000, 2, TIMEOUT).startValue()).isEqualTo(1);
        assertThat(db.store.reserve(DATE.plusDays(1), owner(), 1000, 2, TIMEOUT).startValue()).isEqualTo(1);
        assertThat(db.store.reserve(DATE, owner(), 1000, 2, TIMEOUT).startValue()).isEqualTo(1001);
    }

    @Test
    void commitAcknowledgementFailureNeverHandsOutCommittedRange() {
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var uncertain =
                new org.springframework.transaction.PlatformTransactionManager() {
                    public org.springframework.transaction.TransactionStatus getTransaction(
                            org.springframework.transaction.TransactionDefinition definition) {
                        return db.tm.getTransaction(definition);
                    }

                    public void commit(org.springframework.transaction.TransactionStatus status) {
                        db.tm.commit(status);
                        if (first.getAndSet(false))
                            throw new IllegalStateException("commit acknowledgement lost");
                    }

                    public void rollback(org.springframework.transaction.TransactionStatus status) {
                        db.tm.rollback(status);
                    }
                };
        var store = db.store(db.context.getBean(OrderNoSegmentMapper.class), uncertain);
        assertThatThrownBy(() -> store.reserve(DATE, owner(), 1000, 2, TIMEOUT))
                .hasMessageContaining("acknowledgement");
        assertThat(
                        db.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sc_order_no_segment", Integer.class))
                .isEqualTo(1);
        assertThat(store.reserve(DATE, owner(), 1000, 2, TIMEOUT).startValue()).isEqualTo(1001);
    }

    @Test
    void ddlCommentsAndChecksMatchApprovedContract() {
        assertThat(
                        db.jdbc.queryForObject(
                                "SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE"
                                    + " TABLE_SCHEMA=DATABASE() AND"
                                    + " TABLE_NAME='sc_order_no_sequence' AND"
                                    + " COLUMN_NAME='last_reserved_value'",
                                String.class))
                .isEqualTo("已提交预留号段的当日最大流水，初始为0");
        assertThatThrownBy(
                        () ->
                                db.jdbc.update(
                                        "INSERT INTO sc_order_no_segment VALUES (?,0,1,?,NOW(3))",
                                        DATE,
                                        owner()))
                .isInstanceOf(RuntimeException.class);
    }
}
