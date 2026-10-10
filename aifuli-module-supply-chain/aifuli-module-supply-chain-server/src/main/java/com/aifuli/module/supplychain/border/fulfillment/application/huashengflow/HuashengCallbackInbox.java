package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.infrastructure.inbox.InboxEventDO;
import com.aifuli.module.supplychain.infrastructure.inbox.InboxEventMapper;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengCallbackFact;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Arrays;

/**
 * 华昇回调幂等收敛专用 Inbox 窄接口。
 *
 * <p>职责：
 * <ul>
 *   <li>固定 consumer 名称为 {@code HUASHENG_CALLBACK}；</li>
 *   <li>eventId 采用固定顺序 UTF-8 JSON (supplierId, appid, nonce) 之 SHA-256 十六进制；</li>
 *   <li>payloadDigest 采用 HMAC-SHA256 对规范化完整解密事实及 connectorVersionId 计算；</li>
 *   <li>同 nonce 异内容或改投其它版本抛出 409 冲突；</li>
 *   <li>已成功同内容直接幂等返回，不重新执行领域流转；</li>
 *   <li>首单与重投在同供应链事务内原子提交/回滚。</li>
 * </ul>
 *
 * <p>设计见 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#82-持久防重与收敛。
 */
@Component
public class HuashengCallbackInbox {

    public static final String CONSUMER_NAME = "HUASHENG_CALLBACK";
    public static final String HMAC_PURPOSE = "HUASHENG_CALLBACK_PAYLOAD";
    public static final int TIME_WINDOW_SECONDS = 300;

    private final InboxEventMapper inboxEventMapper;
    private final SupplyChainDatabaseClock databaseClock;
    private final HmacFingerprintPort hmacFingerprintPort;

    public HuashengCallbackInbox(
            InboxEventMapper inboxEventMapper,
            SupplyChainDatabaseClock databaseClock,
            HmacFingerprintPort hmacFingerprintPort) {
        this.inboxEventMapper = Objects.requireNonNull(inboxEventMapper, "inboxEventMapper must not be null");
        this.databaseClock = Objects.requireNonNull(databaseClock, "databaseClock must not be null");
        this.hmacFingerprintPort = Objects.requireNonNull(hmacFingerprintPort, "hmacFingerprintPort must not be null");
    }

    public enum InboxStatus {
        NEW, ALREADY_PROCESSED
    }

    public record InboxClaim(
            Long inboxEventId,
            Long version,
            InboxStatus status
    ) {}

    public InboxClaim claim(Long supplierId, Long connectorVersionId, HuashengCallbackFact fact, String traceId) {
        Objects.requireNonNull(supplierId, "supplierId must not be null");
        Objects.requireNonNull(fact, "fact must not be null");

        String eventId = HuashengCallbackFact.computeEventId(supplierId, fact.appId(), fact.nonce());
        byte[] canonical = fact.canonicalFactBytes(connectorVersionId);
        byte[] payloadDigest;
        try { payloadDigest = hmacFingerprintPort.fingerprint(HMAC_PURPOSE, canonical); }
        finally { Arrays.fill(canonical, (byte) 0); }

        InboxEventDO existing = inboxEventMapper.selectForUpdate(CONSUMER_NAME, eventId);
        if (existing != null) {
            if (!MessageDigest.isEqual(existing.getPayloadDigest(), payloadDigest)) {
                throw new HuashengCallbackConflictException(
                        "Callback payload or connectorVersion conflict for nonce: " + fact.nonce());
            }
            if (existing.getStatus() != null && existing.getStatus() == InboxEventMapper.STATUS_SUCCESS) {
                return new InboxClaim(existing.getId(), existing.getVersion(), InboxStatus.ALREADY_PROCESSED);
            }
            return new InboxClaim(existing.getId(), existing.getVersion(), InboxStatus.NEW);
        }

        long nowEpochSec = databaseClock.toInstant(databaseClock.now()).getEpochSecond();
        if (Math.abs(nowEpochSec - fact.timestamp()) > TIME_WINDOW_SECONDS) {
            throw new HuashengCallbackValidationException(
                    "Callback timestamp out of window: " + fact.timestamp());
        }

        LocalDateTime now = databaseClock.now();
        InboxEventDO event = InboxEventDO.builder()
                .consumerName(CONSUMER_NAME)
                .eventId(eventId)
                .payloadDigest(payloadDigest)
                .status(InboxEventMapper.STATUS_PROCESSING)
                .traceId(traceId)
                .receivedAt(now)
                .version(0L)
                .updatedAt(now)
                .build();
        inboxEventMapper.insert(event);
        return new InboxClaim(event.getId(), event.getVersion(), InboxStatus.NEW);
    }

    public void markSucceeded(Long inboxId, Long expectedVersion) {
        Objects.requireNonNull(inboxId, "inboxId must not be null");
        Objects.requireNonNull(expectedVersion, "expectedVersion must not be null");

        LocalDateTime now = databaseClock.now();
        int updated = inboxEventMapper.markSucceeded(inboxId, expectedVersion, now, now);
        if (updated == 0) {
            throw new IllegalStateException("Failed to mark inbox succeeded for id: " + inboxId + ", version: " + expectedVersion);
        }
    }
}
