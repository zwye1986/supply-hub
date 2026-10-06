package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainTransactionObservationManager;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.dataobject.OutboxAppendIdentityDO;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.dataobject.TimerScheduleIdentityDO;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.OutboxAppendIdentityMapper;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.TimerScheduleIdentityMapper;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实 reservation Java 入口契约；mock DAL 不证明真实 SQL 映射或事务。
 * R3-02O（rev3，D3/D4）：Outbox 断言按同模块 privateLookupIn 反射桥驱动
 * OutboxEventRepository 内 main 私有嵌套可信追加底座；Timer 公开 API 段不动。
 */
class LedgerComparisonEntryContractTest {
    private final OutboxAppendIdentityMapper outbox = mock(OutboxAppendIdentityMapper.class);
    private final TimerScheduleIdentityMapper timer = mock(TimerScheduleIdentityMapper.class);
    private final DefaultAppendScheduleIdentityLedgerService service = new DefaultAppendScheduleIdentityLedgerService(timer);
    private final OutboxEventMapper source = mock(OutboxEventMapper.class);
    private final BusinessNoGenerator businessNoGenerator = mock(BusinessNoGenerator.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final Object core = TrustedOutboxAppendCoreBridge.newCore(source, businessNoGenerator, outbox, dataSource);
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 5, 1, 2, 3);

    @BeforeEach
    void begin() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        ConnectionHolder holder = new ConnectionHolder(mock(Connection.class));
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(dataSource, holder);
        SupplyChainTransactionObservationManager.registerScope(mock(SupplyChainTransactionObservationManager.class), holder);
    }

    @AfterEach
    void end() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void overBudgetFirstPayloadStopsBothDalBeforeInteraction() {
        for (String payload : new String[]{"1e4096", "1e1000000000", "1e-1000000000", "\"" + "a".repeat(1048575) + "\"",
                "[".repeat(65) + "0" + "]".repeat(65)}) {
            assertNotNull(assertThrows(SystemIntegrityException.class, () -> service.reserveTimerSlot("T", "k", 1, "LEGACY_JSON_V1", payload, now)).getCause());
            assertNotNull(assertThrows(SystemIntegrityException.class, () -> TrustedOutboxAppendCoreBridge.newCommand(
                    "A", "i", 1L, "E", "LOCAL", "t", 1, "LEGACY_JSON_V1", payload, "trace", null, null, now)).getCause());
        }
        verifyNoInteractions(outbox, timer, source);
    }

    @Test
    void boundOldPayloadBudgetCannotBeShortCircuitedByMetadataMismatch() {
        when(timer.selectForUpdate("T", "k")).thenReturn(TimerScheduleIdentityDO.builder()
                .bindingStatus((short)20).payloadVersion(2).comparisonProtocol("LEGACY_JSON_V1")
                .payloadJson("1e4096").taskId(2L).taskNo("task").build());
        when(outbox.selectForUpdate("A", "i", 1L, "E")).thenReturn(OutboxAppendIdentityDO.builder()
                .bindingStatus((short)20).payloadVersion(2).comparisonProtocol("LEGACY_JSON_V1")
                .targetType("LOCAL").targetName("other").payloadJson("1e4096").eventId("event").build());
        var t = assertThrows(SystemIntegrityException.class, () -> service.reserveTimerSlot("T", "k", 1, "LEGACY_JSON_V1", "0", now));
        var o = assertThrows(SystemIntegrityException.class, () -> TrustedOutboxAppendCoreBridge.append(core,
                TrustedOutboxAppendCoreBridge.newCommand("A", "i", 1L, "E", "LOCAL", "t", 1, "LEGACY_JSON_V1", "0", "trace", null, null, now)));
        assertNotNull(t.getCause());
        assertNotNull(o.getCause());
        assertEquals("JSON预算超限: NUMBER_OUTPUT", t.getCause().getMessage());
        assertEquals("JSON预算超限: NUMBER_OUTPUT", o.getCause().getMessage());
    }

    @Test
    void malformedFirstPayloadIsRejectedBeforeEitherSlotWrite() {
        assertThrows(SystemIntegrityException.class, () -> service.reserveTimerSlot("T", "k", 1, "LEGACY_JSON_V1", "{", now));
        assertThrows(SystemIntegrityException.class, () -> TrustedOutboxAppendCoreBridge.newCommand(
                "A", "i", 1L, "E", "LOCAL", "t", 1, "LEGACY_JSON_V1", "{", "trace", null, null, now));
        verifyNoInteractions(outbox, timer, source);
    }

    @Test
    void timerBoundComparisonUsesExactLegacyEncoding() {
        when(timer.selectForUpdate("T", "k")).thenReturn(TimerScheduleIdentityDO.builder()
                .bindingStatus((short)20).payloadVersion(1).comparisonProtocol("LEGACY_JSON_V1")
                .payloadJson("{\"n\":1.0,\"traceId\":\"x\"}").taskId(2L).taskNo("task").build());
        var result = service.reserveTimerSlot("T", "k", 1, "LEGACY_JSON_V1", "{\"traceId\":\"x\",\"n\":1e0}", now);
        assertFalse(result.firstReserved());
        assertEquals(2L, result.existingTaskId());
        assertThrows(SystemIntegrityException.class, () -> service.reserveTimerSlot("T", "k", 1, "LEGACY_JSON_V1", "{\"n\":1,\"traceId\":\"y\"}", now));
        assertThrows(SystemIntegrityException.class, () -> service.reserveTimerSlot("T", "k", 2, "LEGACY_JSON_V1", "{\"n\":1,\"traceId\":\"x\"}", now));
    }

    @Test
    void outboxBoundComparisonPreservesTargetsAndTypes() {
        when(outbox.selectForUpdate("A", "i", 1L, "E")).thenReturn(OutboxAppendIdentityDO.builder()
                .bindingStatus((short)20).payloadVersion(1).comparisonProtocol("LEGACY_JSON_V1")
                .targetType("LOCAL").targetName("t").payloadJson("{\"n\":1.0}").eventId("event").build());
        assertEquals("event", TrustedOutboxAppendCoreBridge.append(core,
                TrustedOutboxAppendCoreBridge.newCommand("A", "i", 1L, "E", "LOCAL", "t", 1, "LEGACY_JSON_V1", "{\"n\":1}", "trace", null, null, now)));
        assertThrows(SystemIntegrityException.class, () -> TrustedOutboxAppendCoreBridge.append(core,
                TrustedOutboxAppendCoreBridge.newCommand("A", "i", 1L, "E", "LOCAL", "other", 1, "LEGACY_JSON_V1", "{\"n\":1}", "trace", null, null, now)));
        assertThrows(SystemIntegrityException.class, () -> TrustedOutboxAppendCoreBridge.append(core,
                TrustedOutboxAppendCoreBridge.newCommand("A", "i", 1L, "E", "LOCAL", "t", 1, "LEGACY_JSON_V1", "{\"n\":\"1\"}", "trace", null, null, now)));
        verifyNoInteractions(source);
    }

    @Test
    void firstReservationsKeepNonceStableDespiteOutputMutation() {
        var timerRow = new java.util.concurrent.atomic.AtomicReference<TimerScheduleIdentityDO>();
        doAnswer(call -> { var row = (TimerScheduleIdentityDO)call.getArgument(0); row.setId(1L); timerRow.set(row); return 1; })
                .when(timer).insertSlotOnDuplicateKeyUpdate(any());
        when(timer.selectForUpdate("T", "k")).thenAnswer(call -> timerRow.get());
        var result = service.reserveTimerSlot("T", "k", 1, "LEGACY_JSON_V1", "{}", now);
        byte[] saved = result.nonce();
        result.nonce()[0] ^= 1;
        assertArrayEquals(saved, result.nonce());
        assertArrayEquals(saved, timerRow.get().getReservationNonce());
    }

    @Test
    void bindingProofNonceCopiesInputAndEveryOutput() {
        byte[] input = new byte[16];
        input[0] = 7;
        Object proof = TrustedOutboxAppendCoreBridge.newProof(1L, input, "event");
        input[0] = 8;
        assertEquals(7, TrustedOutboxAppendCoreBridge.proofNonceCopy(proof)[0]);
        byte[] copy = TrustedOutboxAppendCoreBridge.proofNonceCopy(proof);
        copy[0] = 9;
        assertEquals(7, TrustedOutboxAppendCoreBridge.proofNonceCopy(proof)[0]);
        assertNotSame(TrustedOutboxAppendCoreBridge.proofNonceCopy(proof), TrustedOutboxAppendCoreBridge.proofNonceCopy(proof));
    }

    @Test
    void unapprovedBcsBusinessSchemaRemainsFailClosed() {
        assertThrows(SystemIntegrityException.class, () -> service.reserveTimerSlot("T", "k", 1, "BCS_JSON_V1", "{}", now));
        assertThrows(SystemIntegrityException.class, () -> TrustedOutboxAppendCoreBridge.newCommand(
                "A", "i", 1L, "E", "LOCAL", "t", 1, "BCS_JSON_V1", "{}", "trace", null, null, now));
        verifyNoInteractions(outbox, timer, source);
    }
}
