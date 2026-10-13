package com.aifuli.module.supplychain.infrastructure.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.aifuli.module.supplychain.infrastructure.configuration.BudgetContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.util.*;

import javax.sql.DataSource;

class OrderNoAllocationServiceTest {
    final FakeStore store = new FakeStore();
    final FakeRedis redis = new FakeRedis();
    final DataSource ds = mock(DataSource.class);

    OrderNoAllocationService allocator(int size) {
        var p = OrderNoPropertiesTest.valid();
        p.setSegmentSize(size);
        return new OrderNoAllocationService(store, redis, p, new OrderNoFormatter(() -> 0), ds);
    }

    @AfterEach
    void cleanup() {
        BudgetContext.clear();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        if (TransactionSynchronizationManager.hasResource(ds))
            TransactionSynchronizationManager.unbindResource(ds);
    }

    @Test
    void fiveHundredPairsReuseSingleConfirmedSegmentAndNeverReadDatabaseOnHotPath() {
        var a = allocator(1000);
        var numbers = new HashSet<String>();
        for (int i = 0; i < 500; i++) {
            var n = a.prepare(OrderNoPurpose.ORDER_AND_BILL);
            numbers.add(n.orderNo());
            numbers.add(n.billno());
        }
        assertThat(numbers).hasSize(1000);
        assertThat(store.reserves).isEqualTo(1);
        assertThat(redis.timeCalls).isEqualTo(1);
        store.fail = true;
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_ONLY))
                .hasMessageContaining("mysql");
    }

    @Test
    void timeFailureDuringPairRolloverDoesNotRestoreSingleOldRemainder() {
        var a = allocator(3);
        a.prepare(OrderNoPurpose.ORDER_AND_BILL);
        redis.timeFail = true;
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_AND_BILL))
                .hasMessageContaining("TIME failure");
        redis.timeFail = false;
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000004");
    }

    @Test
    void rollbackCursorAndDeletionUseLocalFloor() {
        var a = allocator(10);
        String first = a.prepare(OrderNoPurpose.ORDER_AND_BILL).billno();
        redis.cursors.clear();
        var next = a.prepare(OrderNoPurpose.ORDER_ONLY);
        assertThat(next.orderNo().substring(16)).isEqualTo("00000003");
        redis.cursors.replaceAll((k, v) -> 0);
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo())
                .isNotEqualTo(first)
                .endsWith("00000004");
    }

    @Test
    void uncertaintyRetiresWholeSegmentAndRestartUsesNewOwner() {
        var a = allocator(10);
        a.prepare(OrderNoPurpose.ORDER_ONLY);
        redis.loseResponse = true;
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_AND_BILL))
                .hasMessageContaining("lost");
        redis.loseResponse = false;
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000011");
        var b = allocator(10);
        assertThat(b.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000021");
        assertThat(store.owners).hasSize(2);
    }

    @Test
    void pairAbandonsSingleRemainderAndConfirmedRangeSurvivesMysqlOutage() {
        var a = allocator(3);
        a.prepare(OrderNoPurpose.ORDER_AND_BILL);
        assertThat(a.prepare(OrderNoPurpose.ORDER_AND_BILL).orderNo()).endsWith("00000004");
        store.fail = true;
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000006");
    }

    @Test
    void formatterFailureStillAdvancesLocalFloor() {
        var p = OrderNoPropertiesTest.valid();
        p.setSegmentSize(10);
        int[] calls = {0};
        var a =
                new OrderNoAllocationService(
                        store, redis, p, new OrderNoFormatter(() -> calls[0]++ == 0 ? 100 : 0), ds);
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_ONLY))
                .isInstanceOf(IllegalArgumentException.class);
        redis.cursors.clear();
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000002");
    }

    @Test
    void transactionAndBoundConnectionGuardAppliesEvenToHotPath() {
        var a = allocator(10);
        a.prepare(OrderNoPurpose.ORDER_ONLY);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_ONLY))
                .isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.bindResource(ds, new Object());
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_ONLY))
                .isInstanceOf(IllegalStateException.class);
        assertThat(redis.consumes).isEqualTo(1);
    }

    @Test
    void expiredOrCancelledBudgetDoesNotAcquireOrConsume() {
        var a = allocator(10);
        var b = BudgetContext.create(System.nanoTime(), System.nanoTime() - 1);
        BudgetContext.set(b);
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_ONLY))
                .isInstanceOf(RuntimeException.class);
        b = BudgetContext.create(System.nanoTime(), System.nanoTime() + 1_000_000_000);
        b.cancel();
        BudgetContext.set(b);
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_ONLY))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.reserves).isZero();
    }

    @Test
    void repeatedDayChangeStopsAfterOneCorrection() {
        var a = allocator(10);
        redis.alwaysCrossDay = true;
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_ONLY))
                .isInstanceOf(IllegalStateException.class);
        assertThat(redis.consumes).isEqualTo(2);
        assertThat(store.reserves).isEqualTo(2);
    }

    @Test
    void illegalResponseRetiresRange() {
        var a = allocator(10);
        redis.invalid = true;
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_ONLY))
                .isInstanceOf(IllegalStateException.class);
        redis.invalid = false;
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000011");
    }

    static class FakeStore implements OrderNoSegmentStore {
        int reserves, last;
        boolean fail;
        Set<String> owners = new HashSet<>();

        public OrderNoSegment reserve(
                LocalDate d, String o, int size, int count, Duration timeout) {
            if (fail) throw new IllegalStateException("mysql offline");
            reserves++;
            owners.add(o);
            int start = last + 1;
            last += size;
            return new OrderNoSegment(d, start, last, o);
        }
    }

    static class FakeRedis implements OrderNoRedisOperations {
        int timeCalls, consumes;
        boolean loseResponse, alwaysCrossDay, invalid, timeFail;
        Map<String, Integer> cursors = new HashMap<>();
        Instant now = Instant.parse("2026-10-13T07:30:27Z");

        public Instant time(Duration timeout) {
            timeCalls++;
            if (timeFail) throw new IllegalStateException("Redis TIME failure");
            return now;
        }

        public Result consume(
                String key, OrderNoSegment s, int floor, int count, Duration timeout) {
            consumes++;
            if (alwaysCrossDay) {
                now = now.plusSeconds(86400);
                return new Result(Status.DATE_CHANGED, now, 0);
            }
            int value = Math.max(floor, cursors.getOrDefault(key, floor));
            if (value + count > s.endValue()) return new Result(Status.EXHAUSTED, now, value);
            value += count;
            cursors.put(key, value);
            if (loseResponse) throw new IllegalStateException("lost response");
            return new Result(Status.SUCCESS, now, invalid ? s.endValue() + 1 : value);
        }
    }
}
