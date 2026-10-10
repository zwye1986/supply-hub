package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventDO;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Outbox→首执行 Timer 保留来源，历史事件未知来源不伪造（生产闭环设计 §5.2）。 */
class FulfillmentOperationDispatchHandlerTest {
    @Test
    void timerPayloadPreservesFrozenSourceAndOmitsUnknownSource() {
        var timer = mock(PersistentTimer.class);
        var clock = mock(SupplyChainDatabaseClock.class);
        when(clock.now()).thenReturn(LocalDateTime.of(2026, 10, 10, 0, 0));
        var handler = new FulfillmentOperationDispatchHandler(timer, new ObjectMapper(), clock);
        for (String trace : new String[]{"original-request", null}) {
            handler.dispatch(OutboxEventDO.builder().aggregateId("BO_SOURCE").payloadJson("{\"operationNo\":\"OP_SOURCE\"}")
                    .traceId(trace).build());
        }
        var payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(timer, times(2)).scheduleOnce(anyString(), anyString(), payload.capture(), any());
        assertThat(payload.getAllValues().getFirst().path("originTraceId").asText()).isEqualTo("original-request");
        assertThat(payload.getAllValues().getLast().has("originTraceId")).isFalse();
    }
}
