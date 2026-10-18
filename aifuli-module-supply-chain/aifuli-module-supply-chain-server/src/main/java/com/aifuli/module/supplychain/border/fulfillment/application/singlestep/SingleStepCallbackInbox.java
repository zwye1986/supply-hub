package com.aifuli.module.supplychain.border.fulfillment.application.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.domain.SingleStepSupplierFact;
import com.aifuli.module.supplychain.infrastructure.inbox.InboxEventDO;
import com.aifuli.module.supplychain.infrastructure.inbox.InboxEventMapper;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 单步回调稳定身份与消费围栏，不拥有订单或资金；认领和完成必须在原单结果事务内。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#77-t04回调公共核心切片2026-10-18。
 */
@Component
@RequiredArgsConstructor
public class SingleStepCallbackInbox {
    private static final String CONSUMER = "SINGLE_STEP_SUPPLIER_CALLBACK";
    private static final short STATUS_MANUAL = 90;
    private static final String CONFLICT = "SINGLE_STEP_SECURITY_CONFLICT";
    private final InboxEventMapper events;
    private final ObjectMapper json;
    private final HmacFingerprintPort fingerprint;

    /** 传输时间/签名不进入业务摘要；同一认证事实重投仍对应同一事件与观察。设计同§7.7。 */
    public Prepared prepare(SingleStepSupplierFact fact, String merchantAccount) {
        var binding = fact.binding();
        var identity = json.createObjectNode().put("adapterType", binding.adapterType())
                .put("supplierId", binding.supplierId()).put("merchantAccount", merchantAccount)
                .put("billno", fact.billno()).put("externalOrderNo", fact.externalOrderNo())
                .put("status", fact.observation().name());
        String eventId;
        try {
            eventId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM不支持SHA-256", impossible);
        }
        var business = identity.deepCopy().put("operationNo", binding.operationNo()).put("bOrderId", binding.bOrderId())
                .put("stepCode", binding.adapterStepCode().name()).put("connectorVersionId", binding.connectorVersionId())
                .put("offerVersionId", binding.offerVersionId()).put("configDigest", binding.configDigest())
                .put("targetDigest", fact.targetDigest()).put("quantity", fact.quantity())
                .put("reportedSettlementAmount", fact.reportedSettlementAmount()).put("zeroDeliveryConfirmed", fact.zeroDeliveryConfirmed());
        byte[] digest = fingerprint.fingerprint(CONSUMER, business.toString().getBytes(StandardCharsets.UTF_8));
        return new Prepared(eventId, new SingleStepSupplierFact(binding, fact.source(), fact.billno(), fact.targetDigest(), fact.quantity(),
                fact.verification(), fact.observation(), fact.externalOrderNo(), fact.receivedAt(), digest,
                fact.reportedSettlementAmount(), fact.zeroDeliveryConfirmed()));
    }

    /** 必须先于原单聚合锁；仅本次INSERT唯一争用要求整笔事务回滚重试。设计同§7.7。 */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY)
    public Claim claim(Prepared prepared, String traceId, LocalDateTime now) {
        var row = events.selectForUpdate(CONSUMER, prepared.eventId());
        if (row == null) {
            row = InboxEventDO.builder().consumerName(CONSUMER).eventId(prepared.eventId())
                    .payloadDigest(prepared.fact().payloadDigest()).status(InboxEventMapper.STATUS_PROCESSING)
                    .traceId(traceId == null ? "" : traceId).receivedAt(now).version(0L).updatedAt(now).build();
            try {
                events.insert(row);
            } catch (DuplicateKeyException race) {
                throw new ClaimRace(race);
            }
        }
        boolean conflict = row.getStatus() == STATUS_MANUAL
                || !MessageDigest.isEqual(row.getPayloadDigest(), prepared.fact().payloadDigest());
        return new Claim(row, conflict, !conflict && row.getStatus() == InboxEventMapper.STATUS_SUCCESS);
    }

    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY)
    public void succeed(Claim claim, LocalDateTime now) {
        if (events.markSucceeded(claim.row().getId(), claim.row().getVersion(), now, now) != 1) {
            throw new IllegalStateException("单步回调Inbox完成版本冲突");
        }
    }

    /** 保留首份摘要与已消费时间，冲突不可被原内容重投恢复为成功。设计同§7.7。 */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY)
    public void conflict(Claim claim, LocalDateTime now) {
        var row = claim.row();
        if (row.getStatus() == STATUS_MANUAL) return;
        if (events.update(null, new LambdaUpdateWrapper<InboxEventDO>()
                .eq(InboxEventDO::getId, row.getId()).eq(InboxEventDO::getVersion, row.getVersion())
                .set(InboxEventDO::getStatus, STATUS_MANUAL).set(InboxEventDO::getLastErrorCode, CONFLICT)
                .set(InboxEventDO::getVersion, row.getVersion() + 1).set(InboxEventDO::getUpdatedAt, now)) != 1) {
            throw new IllegalStateException("单步回调Inbox冲突版本变化");
        }
    }

    public record Prepared(String eventId, SingleStepSupplierFact fact) { }
    public record Claim(InboxEventDO row, boolean conflict, boolean completed) { }
    public static final class ClaimRace extends RuntimeException {
        private ClaimRace(DuplicateKeyException cause) { super("单步回调Inbox首次认领争用", cause); }
    }
}
