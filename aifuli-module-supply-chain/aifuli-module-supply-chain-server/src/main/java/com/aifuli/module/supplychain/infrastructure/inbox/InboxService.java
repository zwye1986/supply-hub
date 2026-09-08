package com.aifuli.module.supplychain.infrastructure.inbox;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDateTime;

/**
 * 消费者侧幂等 Inbox：同一 {@code (consumerName, eventId)} 只允许首次进入处理中，
 * 已成功的事件直接跳过；处理中或失败可重试的事件允许重新处理。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#outbox-与-inbox}。</p>
 */
@Service
@RequiredArgsConstructor
public class InboxService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final short STATUS_PROCESSING = 10;
    private static final short STATUS_SUCCESS = 20;
    private static final short STATUS_RETRY = 30;
    private static final short STATUS_MANUAL = 90;

    private final InboxEventMapper inboxEventMapper;
    private final SupplyChainDatabaseClock databaseClock;

    /** 尝试开始处理；返回是否应当处理（首次或可重试）。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public InboxAttempt tryStart(String consumerName, String eventId, byte[] payload,
                                 String traceId) {
        LocalDateTime now = databaseClock.now();
        InboxEventDO record = InboxEventDO.builder()
                .consumerName(consumerName).eventId(eventId).payloadDigest(digest(payload))
                .status(STATUS_PROCESSING).traceId(traceId == null ? "" : traceId)
                .receivedAt(now).version(0L).updatedAt(now).build();
        try {
            inboxEventMapper.insert(record);
            return new InboxAttempt(record.getId(), true);
        } catch (DuplicateKeyException duplicate) {
            InboxEventDO existing = inboxEventMapper.selectForUpdate(consumerName, eventId);
            if (existing == null) {
                throw duplicate;
            }
            boolean shouldProcess = existing.getStatus() != STATUS_SUCCESS;
            return new InboxAttempt(existing.getId(), shouldProcess);
        }
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void succeed(Long inboxId, long expectedVersion) {
        LocalDateTime now = databaseClock.now();
        if (inboxEventMapper.markSucceeded(inboxId, expectedVersion, now, now) != 1) {
            throw new IllegalStateException("Inbox event state changed unexpectedly: " + inboxId);
        }
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void fail(Long inboxId, long expectedVersion, String errorCode, boolean retryable) {
        short status = retryable ? STATUS_RETRY : STATUS_MANUAL;
        if (inboxEventMapper.finishProcessing(inboxId, expectedVersion, status, errorCode,
                databaseClock.now()) != 1) {
            throw new IllegalStateException("Inbox event state changed unexpectedly: " + inboxId);
        }
    }

    private byte[] digest(byte[] payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(payload == null ? new byte[0] : payload);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record InboxAttempt(Long inboxId, boolean shouldProcess) {
    }
}
