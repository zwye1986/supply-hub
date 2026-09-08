package com.aifuli.module.supplychain.infrastructure.outbox;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 仅负责在调用方现有中台事务中追加 Outbox，不负责投递。
 */
@Repository
@RequiredArgsConstructor
public class OutboxEventRepository {

    private final OutboxEventMapper mapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final ObjectMapper objectMapper;

    public String appendLocal(String aggregateType, String aggregateId, long aggregateVersion,
                              String eventType, String targetName, Object payload,
                              String traceId, String correlationId, String causationId,
                              LocalDateTime occurredAt) {
        String eventId = businessNoGenerator.next("EVT_");
        OutboxEventDO event = OutboxEventDO.builder()
                .eventId(eventId)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .aggregateVersion(aggregateVersion)
                .eventType(eventType)
                .targetType("LOCAL")
                .targetName(targetName)
                .payloadVersion(1)
                .payloadJson(writePayload(payload))
                .availableAt(occurredAt)
                .status((short) 0)
                .attemptCount(0)
                .maxAttempts(20)
                .fencingToken(0L)
                .traceId(traceId)
                .correlationId(correlationId)
                .causationId(causationId)
                .createdAt(occurredAt)
                .updatedAt(occurredAt)
                .build();
        mapper.insert(event);
        return eventId;
    }

    private String writePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Outbox payload cannot be serialized", exception);
        }
    }
}
