package com.aifuli.module.supplychain.border.fulfillment.domain;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Builder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可变履约操作执行凭证（Ticket）：包含执行单次供应商外部副作用所需的完整业务与技术入参。
 *
 * <p>调用者（如定时任务处理器或重试引擎）抢占 Operation 成功后构造本 Ticket 并传递给适配器，
 * 身份字段构造后不可变；新协议材料由Ticket接管并在调用完成/失败时关闭擦除，旧String材料路径保持原语义，严禁日志明文记录。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#731-operationexecutorport-契约模型}
 * 以及 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#33-冻结投影与首单-checkpoint}。
 * 新材料生命周期见 {@code docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#32-ticket扩展}。</p>
 *
 * @param settlementUnitPriceMinorUnits 冻结的供应商履约结算单价，单位为分；旧路径为空
 * @param currency 供应商结算币种，一期固定CNY；旧路径为空
 * @param resolvedCredentialMaterial 新协议按声明键接管的受保护材料，关闭Ticket时擦除；旧路径为空
 */
@Builder
@JsonSerialize(using = FulfillmentInternalOnlySerializer.class)
public record OperationTicket(
        String operationNo,
        String bOrderNo,
        String billno,
        String externalOrderNo,
        String supplierCode,
        String adapterType,
        Long connectorVersionId,
        Long offerVersionId,
        FulfillmentOperationKind operationKind,
        AdapterStepCode adapterStepCode,
        int sequenceNo,
        String idempotencyKey,
        String externalSkuCode,
        long quantity,
        String rechargeAccount,
        String targetSchemaCode,
        String callbackUrl,
        String baseUrl,
        String merchantAccount,
        String secretKey,
        int timeoutSeconds,
        String traceId,
        byte[] checkpointPayload,
        Map<String, String> extraParameters,
        CredentialMaterial credentialMaterial,
        int connectTimeoutMs,
        int readTimeoutMs,
        Long credentialSetId,
        List<FrozenCredentialItemIdentity> frozenCredentialItems,
        long executionGeneration,
        Long settlementUnitPriceMinorUnits,
        String currency,
        ResolvedCredentialMaterial resolvedCredentialMaterial
) implements AutoCloseable {
    /** 旧30字段构造兼容入口；新协议材料与供价不从旧字段猜测。 */
    public OperationTicket(String operationNo, String bOrderNo, String billno, String externalOrderNo,
            String supplierCode, String adapterType, Long connectorVersionId, Long offerVersionId,
            FulfillmentOperationKind operationKind, AdapterStepCode adapterStepCode, int sequenceNo,
            String idempotencyKey, String externalSkuCode, long quantity, String rechargeAccount, String targetSchemaCode,
            String callbackUrl, String baseUrl, String merchantAccount, String secretKey, int timeoutSeconds,
            String traceId, byte[] checkpointPayload, Map<String, String> extraParameters,
            CredentialMaterial credentialMaterial, int connectTimeoutMs, int readTimeoutMs, Long credentialSetId,
            List<FrozenCredentialItemIdentity> frozenCredentialItems, long executionGeneration) {
        this(operationNo, bOrderNo, billno, externalOrderNo, supplierCode, adapterType, connectorVersionId, offerVersionId,
                operationKind, adapterStepCode, sequenceNo, idempotencyKey, externalSkuCode, quantity, rechargeAccount,
                targetSchemaCode, callbackUrl, baseUrl, merchantAccount, secretKey, timeoutSeconds, traceId,
                checkpointPayload, extraParameters, credentialMaterial, connectTimeoutMs, readTimeoutMs,
                credentialSetId, frozenCredentialItems, executionGeneration, null, null, null);
    }

    /** 旧24字段构造兼容入口；毫秒值缺省由秒值补齐，不推断集材料。 */
    public OperationTicket(String operationNo, String bOrderNo, String billno, String externalOrderNo,
            String supplierCode, String adapterType, Long connectorVersionId, Long offerVersionId,
            FulfillmentOperationKind operationKind, AdapterStepCode adapterStepCode, int sequenceNo,
            String idempotencyKey, String externalSkuCode, long quantity, String rechargeAccount, String targetSchemaCode,
            String callbackUrl, String baseUrl, String merchantAccount, String secretKey, int timeoutSeconds,
            String traceId, byte[] checkpointPayload, Map<String, String> extraParameters) {
        this(operationNo, bOrderNo, billno, externalOrderNo, supplierCode, adapterType, connectorVersionId, offerVersionId,
                operationKind, adapterStepCode, sequenceNo, idempotencyKey, externalSkuCode, quantity, rechargeAccount,
                targetSchemaCode, callbackUrl, baseUrl, merchantAccount, secretKey, timeoutSeconds, traceId,
                checkpointPayload, extraParameters, null, 0, 0, null, List.of(), 0L);
    }

    /** 27字段构造兼容入口。 */
    public OperationTicket(String operationNo, String bOrderNo, String billno, String externalOrderNo,
            String supplierCode, String adapterType, Long connectorVersionId, Long offerVersionId,
            FulfillmentOperationKind operationKind, AdapterStepCode adapterStepCode, int sequenceNo,
            String idempotencyKey, String externalSkuCode, long quantity, String rechargeAccount, String targetSchemaCode,
            String callbackUrl, String baseUrl, String merchantAccount, String secretKey, int timeoutSeconds,
            String traceId, byte[] checkpointPayload, Map<String, String> extraParameters,
            CredentialMaterial credentialMaterial, int connectTimeoutMs, int readTimeoutMs) {
        this(operationNo, bOrderNo, billno, externalOrderNo, supplierCode, adapterType, connectorVersionId, offerVersionId,
                operationKind, adapterStepCode, sequenceNo, idempotencyKey, externalSkuCode, quantity, rechargeAccount,
                targetSchemaCode, callbackUrl, baseUrl, merchantAccount, secretKey, timeoutSeconds, traceId,
                checkpointPayload, extraParameters, credentialMaterial, connectTimeoutMs, readTimeoutMs,
                null, List.of(), 0L);
    }

    public OperationTicket {
        if (operationNo == null || operationNo.isBlank()) {
            throw new IllegalArgumentException("operationNo must not be blank");
        }
        if (billno == null || billno.isBlank()) {
            throw new IllegalArgumentException("billno must not be blank");
        }
        if (adapterType == null || adapterType.isBlank()) {
            throw new IllegalArgumentException("adapterType must not be blank");
        }
        if (extraParameters == null || extraParameters.isEmpty()) {
            extraParameters = Map.of();
        } else {
            Map<String, String> safeMap = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : extraParameters.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    safeMap.put(entry.getKey(), entry.getValue());
                }
            }
            extraParameters = Collections.unmodifiableMap(safeMap);
        }
        checkpointPayload = checkpointPayload == null ? null : checkpointPayload.clone();
        if (quantity <= 0) {
            quantity = 1L;
        }
        if (sequenceNo < 1) {
            sequenceNo = 1;
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 10;
        }
        if (readTimeoutMs <= 0) readTimeoutMs = Math.multiplyExact(timeoutSeconds, 1000);
        if (connectTimeoutMs <= 0) connectTimeoutMs = Math.multiplyExact(timeoutSeconds, 1000);
        timeoutSeconds = (int) Math.max(1L, (readTimeoutMs + 999L) / 1000L);
        if (traceId == null || traceId.isBlank()) {
            traceId = operationNo;
        }
        frozenCredentialItems = frozenCredentialItems == null ? List.of() : List.copyOf(frozenCredentialItems);
        if (executionGeneration < 0L) {
            executionGeneration = 0L;
        }
    }

    /** 敏感字段不参与日志表达。 */
    @Override
    public String toString() {
        return "OperationTicket[operationNo=" + operationNo + ", billno=" + billno
                + ", adapterType=" + adapterType + ", connectorVersionId=" + connectorVersionId + "]";
    }

    /** 出站材料不包含回调公钥；不可变材料字符串省略所有秘密。 */
    @JsonSerialize(using = FulfillmentInternalOnlySerializer.class)
    public record CredentialMaterial(String appSecret, String privateKeyPem) {
        public CredentialMaterial {
            if (appSecret == null || appSecret.isBlank() || privateKeyPem == null || privateKeyPem.isBlank()) {
                throw new IllegalArgumentException("Outbound credential material is incomplete");
            }
        }
        @Override public String toString() { return "CredentialMaterial[redacted]"; }
    }

    @Override
    public byte[] checkpointPayload() {
        return checkpointPayload == null ? null : checkpointPayload.clone();
    }

    /**
     * 仅关闭新协议接管的材料；旧不可变String路径不改变语义，也不宣称可擦除。
     * 设计见 docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#32-ticket扩展。
     */
    @Override
    public void close() {
        if (resolvedCredentialMaterial != null) resolvedCredentialMaterial.close();
    }

    @JsonSerialize(using = FulfillmentInternalOnlySerializer.class)
    public static class OperationTicketBuilder {
        @Override
        public String toString() {
            return "OperationTicketBuilder[redacted]";
        }
    }
}
