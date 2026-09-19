package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Builder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 不可变履约操作执行凭证（Ticket）：包含执行单次供应商外部副作用所需的完整业务与技术入参。
 *
 * <p>调用者（如定时任务处理器或重试引擎）抢占 Operation 成功后构造本 Ticket 并传递给适配器，
 * 凭证构造后完全不可变，通信密钥在调用完成后由适配器丢弃，严禁日志明文记录。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#731-operationexecutorport-契约模型}。</p>
 */
@Builder
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
        Map<String, String> extraParameters
) {
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
        if (traceId == null || traceId.isBlank()) {
            traceId = operationNo;
        }
    }

    @Override
    public byte[] checkpointPayload() {
        return checkpointPayload == null ? null : checkpointPayload.clone();
    }
}
