package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyEntryDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyEntryMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyExecutionMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyQueueMapper;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.application.RechargeFrequencyProductResolver;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RechargeFrequencyLifecycleTest {
    private final RechargeFrequencyQueueMapper queues = mock(RechargeFrequencyQueueMapper.class);
    private final RechargeFrequencyEntryMapper entries = mock(RechargeFrequencyEntryMapper.class);
    private final RechargeFrequencyProductResolver products = mock(RechargeFrequencyProductResolver.class);
    private final RechargeFrequencyIdentityService identities = new RechargeFrequencyIdentityService(() -> new byte[32]);
    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final RechargeFrequencyExecutionMapper executionLocks = mock(RechargeFrequencyExecutionMapper.class);
    private final FulfillmentOperationMapper operations = mock(FulfillmentOperationMapper.class);
    private final BOrderMapper bOrders = mock(BOrderMapper.class);
    private final SupplyChainOrderMapper orders = mock(SupplyChainOrderMapper.class);
    private final RechargeFrequencyLifecycle lifecycle = new RechargeFrequencyLifecycle(
            queues, entries, identities, products, clock, executionLocks, operations, bOrders, orders);

    @Test
    void nonMobileDirectRechargeDoesNotEnqueue() {
        when(products.resolve(11L)).thenReturn(new RechargeFrequencyProductResolver.ProductIdentity(2L, true));
        lifecycle.enqueue(command("EXTERNAL_ACCOUNT", null));
        verifyNoInteractions(queues, clock);
        verify(entries, never()).insert(any(RechargeFrequencyEntryDO.class));
    }

    @Test
    void voucherAndOtherFulfillmentTypesDoNotEnqueueEvenWhenCommandContainsMobile() {
        when(products.resolve(11L)).thenReturn(new RechargeFrequencyProductResolver.ProductIdentity(2L, false));
        lifecycle.enqueue(command("MOBILE", "13800138000"));
        verifyNoInteractions(queues, clock);
        verify(entries, never()).insert(any(RechargeFrequencyEntryDO.class));
    }

    @Test
    void illegalMobileFailsClosedAndDoesNotLeakRawValue() {
        when(products.resolve(11L)).thenReturn(new RechargeFrequencyProductResolver.ProductIdentity(2L, true));
        assertThatThrownBy(() -> lifecycle.enqueue(command("MOBILE", "private-invalid-mobile")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-invalid-mobile");
        verifyNoInteractions(queues, clock);
    }

    @Test
    void sameOrderRetainsFrozenQueueWithoutReReadingMutableSku() {
        when(entries.selectByOrderId(5L)).thenReturn(RechargeFrequencyEntryDO.builder().queueId(9L).build());
        lifecycle.enqueue(command("MOBILE", "13800138000"));
        verifyNoInteractions(products, queues, clock);
        verify(entries, never()).insert(any(RechargeFrequencyEntryDO.class));
    }

    @Test
    void internalCommandToStringContainsOnlyNonSensitiveReferences() {
        assertThat(command("MOBILE", "13800138000").toString()).doesNotContain("13800138000");
    }

    private RechargeFrequencyLifecycle.EnqueueCommand command(String type, String mobile) {
        return new RechargeFrequencyLifecycle.EnqueueCommand(5L, "SC_TEST", 7L, "OP_TEST", 3L, 4L, 11L, type, mobile);
    }
}
