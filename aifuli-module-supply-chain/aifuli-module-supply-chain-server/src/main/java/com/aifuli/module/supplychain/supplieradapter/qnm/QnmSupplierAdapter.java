package com.aifuli.module.supplychain.supplieradapter.qnm;

import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.CallDigestFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationExecutorPort;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.border.fulfillment.domain.RawObservationFact;
import com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 奇那梦（QNM）供应商协议适配器：实现 {@link OperationExecutorPort} 出站端口。
 *
 * <p>负责对接奇那梦 GameOrder V1 协议端点：
 * <ul>
 *   <li>直充：{@code /GameOrder/Send}</li>
 *   <li>卡密发券：{@code /GameOrder/SendKm}</li>
 *   <li>主动查单：{@code /GameOrder/Query}</li>
 * </ul>
 * 组装请求参数、计算 MD5 签名、发起网络调用、解析响应 JSON 与卡密，并组装标准 {@link OperationOutcome}。</p>
 *
 * <p>架构约束（ArchUnit）：本适配器不得依赖 {@code ..border..dal..}，不直接读写订单库或账本。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#101-奇那梦qnmgameorder-v1-协议实现规约}
 * 以及 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#731-operationexecutorport-契约模型}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QnmSupplierAdapter implements OperationExecutorPort {

    private final QnmHttpExecutor httpExecutor;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String adapterType) {
        return QnmGameOrderV1Metadata.TYPE.equals(adapterType);
    }

    @Override
    public OperationOutcome execute(OperationTicket ticket) {
        Objects.requireNonNull(ticket, "ticket must not be null");
        validateTicket(ticket);

        if (ticket.operationKind() == FulfillmentOperationKind.QUERY_ORDER) {
            return executeQuery(ticket);
        } else if (ticket.adapterStepCode() == AdapterStepCode.SEND_KM) {
            return executeSendKm(ticket);
        } else {
            return executeSend(ticket);
        }
    }

    /**
     * 执行直充端点 /GameOrder/Send。
     */
    private OperationOutcome executeSend(OperationTicket ticket) {
        String endpointCode = QnmEndpointConstants.ENDPOINT_CODE_SEND;
        String url = normalizeUrl(ticket.baseUrl(), QnmEndpointConstants.PATH_SEND);
        Map<String, String> params = buildSendParams(ticket);

        QnmHttpExecutor.HttpExecutionResult httpResult = httpExecutor.postForm(
                url, params, ticket.timeoutSeconds(), ticket.traceId());

        return handleHttpResponse(ticket, endpointCode, "CREATE_RESPONSE", httpResult, false);
    }

    /**
     * 执行卡密发券端点 /GameOrder/SendKm。
     */
    private OperationOutcome executeSendKm(OperationTicket ticket) {
        String endpointCode = QnmEndpointConstants.ENDPOINT_CODE_SEND_KM;
        String url = normalizeUrl(ticket.baseUrl(), QnmEndpointConstants.PATH_SEND_KM);
        Map<String, String> params = buildSendParams(ticket);

        QnmHttpExecutor.HttpExecutionResult httpResult = httpExecutor.postForm(
                url, params, ticket.timeoutSeconds(), ticket.traceId());

        return handleHttpResponse(ticket, endpointCode, "CREATE_RESPONSE", httpResult, true);
    }

    /**
     * 执行主动查单端点 /GameOrder/Query。
     */
    private OperationOutcome executeQuery(OperationTicket ticket) {
        String endpointCode = QnmEndpointConstants.ENDPOINT_CODE_QUERY;
        String url = normalizeUrl(ticket.baseUrl(), QnmEndpointConstants.PATH_QUERY);
        Map<String, String> params = buildQueryParams(ticket);

        QnmHttpExecutor.HttpExecutionResult httpResult = httpExecutor.postForm(
                url, params, ticket.timeoutSeconds(), ticket.traceId());

        return handleQueryResponse(ticket, endpointCode, httpResult);
    }

    /**
     * 组装直充与卡密创建参数并签名。
     */
    private Map<String, String> buildSendParams(OperationTicket ticket) {
        Map<String, String> params = new LinkedHashMap<>();
        if (ticket.extraParameters() != null && !ticket.extraParameters().isEmpty()) {
            for (Map.Entry<String, String> entry : ticket.extraParameters().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getKey().isBlank()) {
                    params.put(entry.getKey().trim(), entry.getValue().trim());
                }
            }
        }
        params.put(QnmEndpointConstants.PARAM_CUSTOMER_ID, ticket.merchantAccount());
        params.put(QnmEndpointConstants.PARAM_PRODUCT_ID, ticket.externalSkuCode());
        params.put(QnmEndpointConstants.PARAM_BILLNO, ticket.billno());
        params.put(QnmEndpointConstants.PARAM_ACCOUNT, ticket.rechargeAccount() != null ? ticket.rechargeAccount() : "");
        params.put(QnmEndpointConstants.PARAM_NUMBER, String.valueOf(ticket.quantity()));
        params.put(QnmEndpointConstants.PARAM_NOTIFY_URL, ticket.callbackUrl() != null ? ticket.callbackUrl() : "");
        params.put(QnmEndpointConstants.PARAM_TIMESTAMP, String.valueOf(System.currentTimeMillis() / 1000));

        String sign = QnmSignUtils.sign(params, ticket.secretKey());
        params.put(QnmEndpointConstants.PARAM_SIGN, sign);
        return params;
    }

    /**
     * 组装查单参数并签名。
     */
    private Map<String, String> buildQueryParams(OperationTicket ticket) {
        Map<String, String> params = new LinkedHashMap<>();
        if (ticket.extraParameters() != null && !ticket.extraParameters().isEmpty()) {
            for (Map.Entry<String, String> entry : ticket.extraParameters().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getKey().isBlank()) {
                    params.put(entry.getKey().trim(), entry.getValue().trim());
                }
            }
        }
        params.put(QnmEndpointConstants.PARAM_CUSTOMER_ID, ticket.merchantAccount());
        params.put(QnmEndpointConstants.PARAM_BILLNO, ticket.billno());
        if (ticket.externalOrderNo() != null && !ticket.externalOrderNo().isBlank()) {
            params.put(QnmEndpointConstants.PARAM_ORDER_SN, ticket.externalOrderNo().trim());
        }
        params.put(QnmEndpointConstants.PARAM_TIMESTAMP, String.valueOf(System.currentTimeMillis() / 1000));

        String sign = QnmSignUtils.sign(params, ticket.secretKey());
        params.put(QnmEndpointConstants.PARAM_SIGN, sign);
        return params;
    }

    /**
     * 处理创建类（Send / SendKm）HTTP 响应。
     */
    private OperationOutcome handleHttpResponse(OperationTicket ticket, String endpointCode,
                                                String sourceType,
                                                QnmHttpExecutor.HttpExecutionResult httpResult,
                                                boolean isCardIssuance) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 网络超时、网络中断或 HTTP 5xx / 408 / 429 瞬时网关服务端错误 -> 统一进入 RESULT_UNKNOWN
        if (httpResult.timedOut() || httpResult.networkError() || QnmProtocolParser.isTransientError(httpResult.statusCode())) {
            String errorMsg = httpResult.errorMessage();
            if (errorMsg == null && httpResult.statusCode() != null) {
                errorMsg = "HTTP error " + httpResult.statusCode();
            }
            log.warn("[QNM][CALL-TRANSIENT-ERROR] operationNo={}, billno={}, status={}, error={}",
                    ticket.operationNo(), ticket.billno(), httpResult.statusCode(), errorMsg);
            return OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.RESULT_UNKNOWN)
                    .externalErrorCode("NETWORK_ERROR")
                    .externalErrorMessage(errorMsg)
                    .callDigest(buildCallDigest(endpointCode, httpResult, OperationOutcomeClass.RESULT_UNKNOWN.name(), ticket.traceId()))
                    .rawObservation(RawObservationFact.builder()
                            .sourceType(sourceType)
                            .externalStatus(httpResult.statusCode() != null ? "HTTP_" + httpResult.statusCode() : "TIMEOUT")
                            .externalSubStatus(errorMsg)
                            .payloadDigest(httpResult.responseDigest() != null ? httpResult.responseDigest() : httpResult.requestDigest())
                            .rawPayload(httpResult.responseBody())
                            .observedAt(now)
                            .build())
                    .build();
        }

        // 2. HTTP 非 200 状态码（如 400/401/403 等配置/鉴权错误）-> 转入 MANUAL_REQUIRED
        if (httpResult.statusCode() != null && httpResult.statusCode() != 200) {
            log.warn("[QNM][CALL-CLIENT-ERROR] operationNo={}, billno={}, status={}, body={}",
                    ticket.operationNo(), ticket.billno(), httpResult.statusCode(),
                    QnmMaskingUtils.maskResponseBody(httpResult.responseBody()));
            return OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.MANUAL_REQUIRED)
                    .externalErrorCode("HTTP_" + httpResult.statusCode())
                    .externalErrorMessage("Upstream returned HTTP " + httpResult.statusCode())
                    .callDigest(buildCallDigest(endpointCode, httpResult, OperationOutcomeClass.MANUAL_REQUIRED.name(), ticket.traceId()))
                    .rawObservation(RawObservationFact.builder()
                            .sourceType(sourceType)
                            .externalStatus("HTTP_" + httpResult.statusCode())
                            .externalSubStatus(QnmMaskingUtils.maskResponseBody(httpResult.responseBody()))
                            .payloadDigest(httpResult.responseDigest())
                            .rawPayload(httpResult.responseBody())
                            .observedAt(now)
                            .build())
                    .build();
        }

        // 3. 解析响应 JSON
        String body = httpResult.responseBody();
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.error("[QNM][PARSE-ERROR] operationNo={}, billno={}, body={}",
                    ticket.operationNo(), ticket.billno(), QnmMaskingUtils.maskResponseBody(body), e);
            return OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.MANUAL_REQUIRED)
                    .externalErrorCode("INVALID_RESPONSE_JSON")
                    .externalErrorMessage("Failed to parse response: " + e.getMessage())
                    .callDigest(buildCallDigest(endpointCode, httpResult, OperationOutcomeClass.MANUAL_REQUIRED.name(), ticket.traceId()))
                    .rawObservation(RawObservationFact.builder()
                            .sourceType(sourceType)
                            .externalStatus("PARSE_ERROR")
                            .payloadDigest(httpResult.responseDigest())
                            .rawPayload(body)
                            .observedAt(now)
                            .build())
                    .build();
        }

        String resCode = QnmProtocolParser.extractResCode(root);
        String resMsg = QnmProtocolParser.extractResMsg(root);
        String orderSn = QnmProtocolParser.extractOrderSn(root);
        Long sumPrice = QnmProtocolParser.parseSumPrice(root.path(QnmEndpointConstants.FIELD_SUM_PRICE));

        boolean isSuccessResCode = QnmEndpointConstants.RES_CODE_SUCCESS.equalsIgnoreCase(resCode)
                || QnmEndpointConstants.RES_CODE_OK.equalsIgnoreCase(resCode);

        OperationOutcomeClass outcomeClass;
        List<OperationAssetFact> assets = List.of();

        if (isSuccessResCode) {
            if (isCardIssuance) {
                // 卡密发券：解析卡密数组
                JsonNode cardsNode = root.get(QnmEndpointConstants.FIELD_CARDS);
                if (cardsNode == null || cardsNode.isNull()) {
                    cardsNode = root.get("cardList");
                }
                if (cardsNode == null || cardsNode.isNull()) {
                    cardsNode = root.get("card_list");
                }
                assets = QnmProtocolParser.parseCards(cardsNode, objectMapper);
                // 权威成功，即使卡密为空也标记 SUCCEEDED，空卡密由生命周期触发 QNM-ASSET-RECOVERY-001
                outcomeClass = OperationOutcomeClass.SUCCEEDED;
            } else {
                // 直充受理成功，进入 ACCEPTED 状态等待回调或查单推进
                outcomeClass = OperationOutcomeClass.ACCEPTED;
            }
        } else {
            outcomeClass = OperationOutcomeClass.REJECTED;
        }

        return OperationOutcome.builder()
                .outcomeClass(outcomeClass)
                .externalOrderNo(orderSn)
                .reportedSettlementAmount(sumPrice)
                .externalErrorCode(isSuccessResCode ? null : (resCode.isEmpty() ? null : resCode))
                .externalErrorMessage(isSuccessResCode ? null : (resMsg.isEmpty() ? null : resMsg))
                .assets(assets)
                .callDigest(buildCallDigest(endpointCode, httpResult, outcomeClass.name(), ticket.traceId()))
                .rawObservation(RawObservationFact.builder()
                        .sourceType(sourceType)
                        .externalOrderNo(orderSn)
                        .externalStatus("ResCode=" + resCode)
                        .externalSubStatus(resMsg)
                        .payloadDigest(httpResult.responseDigest())
                        .rawPayload(body)
                        .observedAt(now)
                        .build())
                .build();
    }

    /**
     * 处理主动查单 HTTP 响应。
     */
    private OperationOutcome handleQueryResponse(OperationTicket ticket, String endpointCode,
                                                 QnmHttpExecutor.HttpExecutionResult httpResult) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 网络超时、网络中断或 HTTP 5xx / 408 / 429 -> RESULT_UNKNOWN
        if (httpResult.timedOut() || httpResult.networkError() || QnmProtocolParser.isTransientError(httpResult.statusCode())) {
            String errorMsg = httpResult.errorMessage();
            if (errorMsg == null && httpResult.statusCode() != null) {
                errorMsg = "HTTP error " + httpResult.statusCode();
            }
            log.warn("[QNM][QUERY-TRANSIENT-ERROR] operationNo={}, billno={}, status={}, error={}",
                    ticket.operationNo(), ticket.billno(), httpResult.statusCode(), errorMsg);
            return OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.RESULT_UNKNOWN)
                    .externalErrorCode("NETWORK_ERROR")
                    .externalErrorMessage(errorMsg)
                    .callDigest(buildCallDigest(endpointCode, httpResult, OperationOutcomeClass.RESULT_UNKNOWN.name(), ticket.traceId()))
                    .rawObservation(RawObservationFact.builder()
                            .sourceType("QUERY_RESPONSE")
                            .externalStatus(httpResult.statusCode() != null ? "HTTP_" + httpResult.statusCode() : "TIMEOUT")
                            .externalSubStatus(errorMsg)
                            .payloadDigest(httpResult.responseDigest() != null ? httpResult.responseDigest() : httpResult.requestDigest())
                            .rawPayload(httpResult.responseBody())
                            .observedAt(now)
                            .build())
                    .build();
        }

        // 2. HTTP 404 -> 订单在供应商侧不存在
        if (httpResult.statusCode() != null && httpResult.statusCode() == 404) {
            return OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.NOT_FOUND)
                    .externalErrorCode("404")
                    .externalErrorMessage("Order not found (HTTP 404)")
                    .callDigest(buildCallDigest(endpointCode, httpResult, OperationOutcomeClass.NOT_FOUND.name(), ticket.traceId()))
                    .rawObservation(RawObservationFact.builder()
                            .sourceType("QUERY_RESPONSE")
                            .externalStatus("HTTP_404")
                            .externalSubStatus("Order not found")
                            .payloadDigest(httpResult.responseDigest())
                            .rawPayload(httpResult.responseBody())
                            .observedAt(now)
                            .build())
                    .build();
        }

        // 3. HTTP 非 200 且非 404 -> MANUAL_REQUIRED
        if (httpResult.statusCode() != null && httpResult.statusCode() != 200) {
            return OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.MANUAL_REQUIRED)
                    .externalErrorCode("HTTP_" + httpResult.statusCode())
                    .externalErrorMessage("Upstream query returned HTTP " + httpResult.statusCode())
                    .callDigest(buildCallDigest(endpointCode, httpResult, OperationOutcomeClass.MANUAL_REQUIRED.name(), ticket.traceId()))
                    .rawObservation(RawObservationFact.builder()
                            .sourceType("QUERY_RESPONSE")
                            .externalStatus("HTTP_" + httpResult.statusCode())
                            .externalSubStatus(QnmMaskingUtils.maskResponseBody(httpResult.responseBody()))
                            .payloadDigest(httpResult.responseDigest())
                            .rawPayload(httpResult.responseBody())
                            .observedAt(now)
                            .build())
                    .build();
        }

        String body = httpResult.responseBody();
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.error("[QNM][QUERY-PARSE-ERROR] operationNo={}, billno={}, body={}",
                    ticket.operationNo(), ticket.billno(), QnmMaskingUtils.maskResponseBody(body), e);
            return OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.MANUAL_REQUIRED)
                    .externalErrorCode("INVALID_RESPONSE_JSON")
                    .externalErrorMessage("Failed to parse response: " + e.getMessage())
                    .callDigest(buildCallDigest(endpointCode, httpResult, OperationOutcomeClass.MANUAL_REQUIRED.name(), ticket.traceId()))
                    .rawObservation(RawObservationFact.builder()
                            .sourceType("QUERY_RESPONSE")
                            .externalStatus("PARSE_ERROR")
                            .payloadDigest(httpResult.responseDigest())
                            .rawPayload(body)
                            .observedAt(now)
                            .build())
                    .build();
        }

        String resCode = QnmProtocolParser.extractResCode(root);
        String resMsg = QnmProtocolParser.extractResMsg(root);
        String orderSn = QnmProtocolParser.extractOrderSn(root);
        Long sumPrice = QnmProtocolParser.parseSumPrice(root.path(QnmEndpointConstants.FIELD_SUM_PRICE));

        // 判断订单是否存在
        if (QnmProtocolParser.isOrderNotFound(httpResult.statusCode(), resCode, resMsg)) {
            return OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.NOT_FOUND)
                    .externalOrderNo(orderSn)
                    .externalErrorCode(resCode.isEmpty() ? null : resCode)
                    .externalErrorMessage(resMsg.isEmpty() ? null : resMsg)
                    .callDigest(buildCallDigest(endpointCode, httpResult, OperationOutcomeClass.NOT_FOUND.name(), ticket.traceId()))
                    .rawObservation(RawObservationFact.builder()
                            .sourceType("QUERY_RESPONSE")
                            .externalOrderNo(orderSn)
                            .externalStatus("orderStatus=NOT_FOUND")
                            .externalSubStatus(resMsg)
                            .payloadDigest(httpResult.responseDigest())
                            .rawPayload(body)
                            .observedAt(now)
                            .build())
                    .build();
        }

        Integer orderStatus = QnmProtocolParser.extractOrderStatus(root);

        OperationOutcomeClass outcomeClass;
        List<OperationAssetFact> assets = List.of();

        if (orderStatus != null && orderStatus == QnmEndpointConstants.QUERY_STATUS_SUCCESS) {
            outcomeClass = OperationOutcomeClass.SUCCEEDED;
            JsonNode cardsNode = root.get(QnmEndpointConstants.FIELD_CARDS);
            if (cardsNode == null || cardsNode.isNull()) {
                cardsNode = root.get("cardList");
            }
            if (cardsNode == null || cardsNode.isNull()) {
                cardsNode = root.get("card_list");
            }
            assets = QnmProtocolParser.parseCards(cardsNode, objectMapper);
        } else if (orderStatus != null && orderStatus == QnmEndpointConstants.QUERY_STATUS_PROCESSING) {
            outcomeClass = OperationOutcomeClass.ACCEPTED;
        } else if (orderStatus != null && orderStatus == QnmEndpointConstants.QUERY_STATUS_FAILED) {
            outcomeClass = OperationOutcomeClass.REJECTED;
        } else {
            // orderStatus 为空或未知状态码
            // 检查 ResCode：若 ResCode 明确是签名/商户等配置鉴权错误，映射为 MANUAL_REQUIRED
            if ("-100".equals(resCode) || "-101".equals(resCode) || "SIGN_ERROR".equalsIgnoreCase(resCode)) {
                outcomeClass = OperationOutcomeClass.MANUAL_REQUIRED;
            } else {
                outcomeClass = OperationOutcomeClass.RESULT_UNKNOWN;
            }
        }

        boolean isFinalSuccess = (outcomeClass == OperationOutcomeClass.SUCCEEDED || outcomeClass == OperationOutcomeClass.ACCEPTED);

        return OperationOutcome.builder()
                .outcomeClass(outcomeClass)
                .externalOrderNo(orderSn)
                .reportedSettlementAmount(sumPrice)
                .externalErrorCode(isFinalSuccess ? null : (resCode.isEmpty() ? null : resCode))
                .externalErrorMessage(isFinalSuccess ? null : (resMsg.isEmpty() ? null : resMsg))
                .assets(assets)
                .callDigest(buildCallDigest(endpointCode, httpResult, outcomeClass.name(), ticket.traceId()))
                .rawObservation(RawObservationFact.builder()
                        .sourceType("QUERY_RESPONSE")
                        .externalOrderNo(orderSn)
                        .externalStatus("orderStatus=" + (orderStatus != null ? orderStatus : "UNKNOWN"))
                        .externalSubStatus(resMsg)
                        .payloadDigest(httpResult.responseDigest())
                        .rawPayload(body)
                        .observedAt(now)
                        .build())
                .build();
    }

    private static int parseIntOrDefault(String str, int defaultVal) {
        try {
            return Integer.parseInt(str.trim());
        } catch (Exception e) {
            log.debug("[QNM] 整型解析失败 text={}, default={}", str, defaultVal, e);
            return defaultVal;
        }
    }

    private static String normalizeUrl(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("QNM baseUrl must not be blank");
        }
        String b = baseUrl.trim();
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + (path.startsWith("/") ? path : "/" + path);
    }

    private static CallDigestFact buildCallDigest(String endpointCode, QnmHttpExecutor.HttpExecutionResult httpResult,
                                                  String resultClass, String traceId) {
        return CallDigestFact.builder()
                .endpointCode(endpointCode)
                .durationMs(httpResult.durationMs())
                .httpStatus(httpResult.statusCode())
                .requestDigest(httpResult.requestDigest())
                .responseDigest(httpResult.responseDigest())
                .resultClass(resultClass)
                .traceId(traceId)
                .build();
    }

    private static void validateTicket(OperationTicket ticket) {
        if (ticket.baseUrl() == null || ticket.baseUrl().isBlank()) {
            throw new IllegalArgumentException("QNM ticket missing baseUrl");
        }
        if (ticket.merchantAccount() == null || ticket.merchantAccount().isBlank()) {
            throw new IllegalArgumentException("QNM ticket missing merchantAccount");
        }
        if (ticket.secretKey() == null || ticket.secretKey().isBlank()) {
            throw new IllegalArgumentException("QNM ticket missing secretKey");
        }
        if (ticket.operationKind() == null) {
            throw new IllegalArgumentException("QNM ticket missing operationKind");
        }
        if (ticket.operationKind() != FulfillmentOperationKind.FULFILL
                && ticket.operationKind() != FulfillmentOperationKind.QUERY_ORDER) {
            throw new UnsupportedOperationException("Unsupported operationKind for QNM: " + ticket.operationKind());
        }
        if (ticket.operationKind() == FulfillmentOperationKind.FULFILL) {
            if (ticket.externalSkuCode() == null || ticket.externalSkuCode().isBlank()) {
                throw new IllegalArgumentException("QNM ticket missing externalSkuCode");
            }
        }
    }
}
