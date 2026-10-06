package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BuyerProductSelectionRetryTest {
    private final BuyerProductSelectionBatchTransactionalWorker worker = mock(BuyerProductSelectionBatchTransactionalWorker.class);
    private final BuyerAcceptanceCatalogIdentityProbe probe = mock(BuyerAcceptanceCatalogIdentityProbe.class);
    private final BuyerProductCommandService service = new BuyerProductCommandService(null, null, null, null, null,
            null, null, null, null, null, null, null, null, worker, probe);
    private final List<BuyerProductCommandService.ProductLine> lines = List.of(
            new BuyerProductCommandService.ProductLine("SKU_A", null, null, null, null, null),
            new BuyerProductCommandService.ProductLine("SKU_B", null, null, null, null, null));

    @Test
    void dedicatedRaceReprobesThenRerunsWholeOriginalBatchAndOnlyReturnsFinalCodes() {
        var race = new BuyerAcceptanceCatalogIdentityRaceException(1L, 2L, "BPD_ROLLED_BACK", new RuntimeException("原唯一原因"));
        when(worker.executeBatch("buyer", lines, 7L)).thenThrow(race).thenReturn(List.of("BPD_COMMITTED_A", "BPD_COMMITTED_B"));
        assertThat(service.addProducts("buyer", lines, 7L)).containsExactly("BPD_COMMITTED_A", "BPD_COMMITTED_B");
        var order = inOrder(worker, probe);
        order.verify(worker).executeBatch("buyer", lines, 7L);
        order.verify(probe).requireWholeBatchRetry(race);
        order.verify(worker).executeBatch("buyer", lines, 7L);
    }

    @Test
    void atMostTwoWholeBatchRetriesPreserveCause() {
        var race = new BuyerAcceptanceCatalogIdentityRaceException(1L, 2L, "BPD_A", new RuntimeException("原唯一原因"));
        when(worker.executeBatch("buyer", lines, 7L)).thenThrow(race);
        assertThatThrownBy(() -> service.addProducts("buyer", lines, 7L))
                .isInstanceOf(ServiceException.class).hasCause(race);
        verify(worker, times(3)).executeBatch("buyer", lines, 7L);
        verify(probe, times(2)).requireWholeBatchRetry(race);
    }

    @Test
    void arbitrarySqlOrJsonFailureNeverReprobesOrRetries() {
        var failure = new IllegalStateException("损坏JSON");
        when(worker.executeBatch("buyer", lines, 7L)).thenThrow(failure);
        assertThatThrownBy(() -> service.addProducts("buyer", lines, 7L)).isSameAs(failure);
        verifyNoInteractions(probe);
        verify(worker).executeBatch("buyer", lines, 7L);
    }
}
