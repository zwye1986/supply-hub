package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.infrastructure.security.CredentialProvider;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialSlotStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 冻结供应商配置与凭证的统一Ticket装配；查询复用原履约身份且不解密充值目标。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#6-timer-与补偿。 */
@Component
@RequiredArgsConstructor
public class FulfillmentOperationTicketFactory {
    private final CredentialSlotMapper credentialSlotMapper;
    private final List<CredentialProvider> credentialProviders;
    private final FulfillmentCallbackProperties callbackProperties;
    static final int DEFAULT_READ_TIMEOUT_SECONDS = 10;
    public String resolveCredential(PublishedConnectorConfiguration config) {
        return resolveSecretKey(config.credentialSlotId());
    }
    public OperationTicket create(FulfillmentOperationDO operation, BOrderDO bOrder, FulfillmentRequestDO request,
                                  SupplierDO supplier, SupplierOfferVersionDO offerVersion,
                                  PublishedConnectorConfiguration connectorConfig, FulfillmentOperationKind kind,
                                  String rechargeAccount, String secretKey, String traceId) {
        return OperationTicket.builder()
                    .operationNo(operation.getOperationNo())
                    .bOrderNo(bOrder.getBOrderNo())
                    .billno(bOrder.getBillno() != null && !bOrder.getBillno().isBlank() ? bOrder.getBillno() : bOrder.getBOrderNo())
                    .externalOrderNo(bOrder.getExternalOrderNo())
                    .supplierCode(supplier.getSupplierCode())
                    .adapterType(connectorConfig.adapterType())
                    .connectorVersionId(bOrder.getConnectorVersionId())
                    .offerVersionId(bOrder.getOfferVersionId())
                    .operationKind(kind)
                    .adapterStepCode(AdapterStepCode.valueOf(operation.getAdapterStepCode()))
                    .sequenceNo(operation.getSequenceNo() != null ? operation.getSequenceNo() : 1)
                    .idempotencyKey(operation.getIdempotencyKey())
                    .externalSkuCode(offerVersion != null ? offerVersion.getExternalSkuCode() : null)
                    .quantity(bOrder.getRequestedQuantity() != null ? bOrder.getRequestedQuantity() : 1L)
                    .rechargeAccount(rechargeAccount)
                    .targetSchemaCode(request.getTargetSchemaCode())
                    .callbackUrl(resolveCallbackUrl(connectorConfig.callbackPath()))
                    .baseUrl(connectorConfig.baseUrl())
                    .merchantAccount(connectorConfig.merchantAccount())
                    .secretKey(secretKey)
                    .timeoutSeconds(DEFAULT_READ_TIMEOUT_SECONDS)
                    .traceId(traceId)
                    .checkpointPayload(operation.getCheckpointCiphertext())
                    .extraParameters(Map.of())
                    .build();
    }
    private String resolveCallbackUrl(String callbackPath) {
        String base = callbackProperties != null ? callbackProperties.getPublicCallbackBaseUrl() : null;
        if (base == null || base.isBlank() || callbackPath == null || callbackPath.isBlank()) {
            return "";
        }
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedPath = callbackPath.startsWith("/") ? callbackPath : "/" + callbackPath;
        return normalizedBase + normalizedPath;
    }

    private String resolveSecretKey(Long credentialSlotId) {
        if (credentialSlotId == null) {
            return null;
        }
        CredentialSlotDO slot = credentialSlotMapper.selectById(credentialSlotId);
        if (slot == null || slot.getStatus() != CredentialSlotStatus.ACTIVE.getCode()) {
            throw new IllegalStateException("CredentialSlot 不可用 slotId=" + credentialSlotId);
        }
        CredentialProvider provider = credentialProviders.stream()
                .filter(p -> p.supports(slot.getProviderType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到支持的 CredentialProvider: " + slot.getProviderType()));
        char[] secretChars = provider.resolve(slot);
        return secretChars != null ? new String(secretChars) : null;
    }

}
