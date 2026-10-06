package com.aifuli.module.supplychain.infrastructure.ledger;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** §7.2.1：保留标识不能通过构造参数或访问输出被外部修改（R3-02O D3：Outbox 保留随公开API删除，由底座BindingProof契约测试覆盖）。 */
class LedgerReservationImmutabilityTest {
    @Test
    void timerNonceCopiesInputAndEveryOutput() {
        byte[] input = new byte[16];
        input[0] = 7;
        var result = new AppendScheduleIdentityLedgerService.TimerReservationResult(true, 1L, input, null, null);
        input[0] = 8;
        assertEquals(7, result.nonce()[0]);
        result.nonce()[0] = 9;
        assertEquals(7, result.nonce()[0]);
        assertArrayEquals(result.nonce(), result.nonce());
        assertNotSame(result.nonce(), result.nonce());
        assertNull(AppendScheduleIdentityLedgerService.TimerReservationResult.existing(1L, "t").nonce());
    }
}
