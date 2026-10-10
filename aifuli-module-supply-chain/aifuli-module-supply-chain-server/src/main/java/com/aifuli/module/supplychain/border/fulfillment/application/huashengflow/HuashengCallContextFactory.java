package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengAuthorizedCallContext;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCredentialIdentity;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCredentialIdentity.CredentialItemSnapshot;
import com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialItemIdentity;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengCallContext;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 华昇单次请求调用上下文工厂。
 *
 * <p>职责：
 * 从 {@link OperationTicket} 中提取出站参数与凭据材料，
 * 构造 {@link HuashengCallContext} 与 {@link HuashengCredentialIdentity} 并组装为 {@link HuashengAuthorizedCallContext}。
 * 严格验证 appId（来自 merchantAccount）为 16 字节；出站身份仅提取 appSecret 和 privateKey 两项，回调公钥绝不进入出站材料或缓存。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#33-冻结投影与首单-checkpoint}。</p>
 */
public class HuashengCallContextFactory {

    public HuashengAuthorizedCallContext createContext(OperationTicket ticket) {
        Objects.requireNonNull(ticket, "ticket cannot be null");
        if (ticket.credentialMaterial() == null) {
            throw new IllegalArgumentException("ticket.credentialMaterial cannot be null");
        }
        String appId = ticket.merchantAccount();
        if (appId == null || appId.getBytes(StandardCharsets.UTF_8).length != 16) {
            throw new IllegalArgumentException("appId (merchantAccount) must be strictly 16 UTF-8 bytes");
        }

        String supplierCode = ticket.supplierCode() == null || ticket.supplierCode().isBlank() ? "HUA_SHENG" : ticket.supplierCode();
        Long connectorVersionId = ticket.connectorVersionId() == null || ticket.connectorVersionId() <= 0 ? 1L : ticket.connectorVersionId();
        String baseUrl = ticket.baseUrl();
        int connectTimeout = ticket.connectTimeoutMs() <= 0 ? 3000 : ticket.connectTimeoutMs();
        int readTimeout = ticket.readTimeoutMs() <= 0 ? 5000 : ticket.readTimeoutMs();
        String operationNo = ticket.operationNo();
        String traceId = ticket.traceId() == null || ticket.traceId().isBlank() ? operationNo : ticket.traceId();

        HuashengCallContext callContext = new HuashengCallContext(
                supplierCode,
                connectorVersionId,
                baseUrl,
                appId,
                ticket.credentialMaterial(),
                connectTimeout,
                readTimeout,
                operationNo,
                traceId
        );

        long setId = ticket.credentialSetId() != null && ticket.credentialSetId() > 0 ? ticket.credentialSetId() : connectorVersionId;
        List<CredentialItemSnapshot> snapshots = new ArrayList<>();
        List<FrozenCredentialItemIdentity> frozenItems = ticket.frozenCredentialItems();
        if (frozenItems != null && !frozenItems.isEmpty()) {
            for (FrozenCredentialItemIdentity item : frozenItems) {
                if (HuashengOrderV1Metadata.OUTBOUND_KEYS.contains(item.itemKey())) {
                    snapshots.add(new CredentialItemSnapshot(item.itemId(), item.itemKey(), item.providerType(), item.activeSecretVersion()));
                }
            }
        }
        if (snapshots.isEmpty()) {
            snapshots.add(new CredentialItemSnapshot(1L, HuashengOrderV1Metadata.APP_SECRET, "ENV", 1));
            snapshots.add(new CredentialItemSnapshot(2L, HuashengOrderV1Metadata.PRIVATE_KEY, "ENV", 1));
        }

        HuashengCredentialIdentity credentialIdentity = new HuashengCredentialIdentity(setId, snapshots);
        return new HuashengAuthorizedCallContext(callContext, credentialIdentity);
    }
}
