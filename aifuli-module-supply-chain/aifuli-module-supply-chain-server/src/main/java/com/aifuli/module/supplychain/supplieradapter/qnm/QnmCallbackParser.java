package com.aifuli.module.supplychain.supplieradapter.qnm;

import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 奇那梦异步回调报文验签与事实解析器。
 *
 * <p>负责校验奇那梦入站回调的 MD5 签名，并提取业务事实（单号、状态、报告结算金额与卡密等）。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#101-奇那梦qnmgameorder-v1-协议实现规约}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QnmCallbackParser {

    private final ObjectMapper objectMapper;

    /**
     * 校验并解析 QNM 回调表单参数。
     *
     * @param params    回调表单参数 Map
     * @param secretKey 供应商通信密钥
     * @return 解析结果事实
     */
    public CallbackResult parse(Map<String, String> params, String secretKey) {
        if (params == null || params.isEmpty()) {
            return CallbackResult.invalid("Empty callback parameters");
        }
        String requestSign = params.get(QnmEndpointConstants.PARAM_SIGN);
        if (!QnmSignUtils.verify(params, secretKey, requestSign)) {
            log.warn("[QNM][CALLBACK-VERIFY-FAILED] params={}", QnmMaskingUtils.maskParams(params));
            return CallbackResult.invalid("Sign verification failed");
        }

        String billno = params.get(QnmEndpointConstants.PARAM_BILLNO);
        if (billno == null || billno.isBlank()) {
            billno = params.get("billNo");
        }
        if (billno == null || billno.isBlank()) {
            billno = params.get("bill_no");
        }
        String orderSn = QnmProtocolParser.extractOrderSn(params);
        String statusStr = params.get("status");
        if (statusStr == null || statusStr.isBlank()) {
            statusStr = params.get(QnmEndpointConstants.FIELD_ORDER_STATUS);
        }
        if (statusStr == null || statusStr.isBlank()) {
            statusStr = params.get("order_status");
        }
        if (statusStr == null || statusStr.isBlank()) {
            statusStr = params.get("orderStatus");
        }
        int status = parseIntOrDefault(statusStr, -1);

        OperationOutcomeClass outcomeClass;
        if (status == QnmEndpointConstants.CALLBACK_STATUS_SUCCESS) {
            outcomeClass = OperationOutcomeClass.SUCCEEDED;
        } else if (status == QnmEndpointConstants.CALLBACK_STATUS_FAILED) {
            outcomeClass = OperationOutcomeClass.REJECTED;
        } else if (status == QnmEndpointConstants.QUERY_STATUS_PROCESSING) {
            outcomeClass = OperationOutcomeClass.ACCEPTED;
        } else {
            outcomeClass = OperationOutcomeClass.RESULT_UNKNOWN;
        }

        String sumPriceStr = params.get(QnmEndpointConstants.FIELD_SUM_PRICE);
        if (sumPriceStr == null || sumPriceStr.isBlank()) {
            sumPriceStr = params.get("sum_price");
        }
        if (sumPriceStr == null || sumPriceStr.isBlank()) {
            sumPriceStr = params.get("sumprice");
        }
        Long sumPrice = QnmProtocolParser.parseSumPrice(sumPriceStr);

        String cardsStr = params.get(QnmEndpointConstants.FIELD_CARDS);
        if (cardsStr == null || cardsStr.isBlank()) {
            cardsStr = params.get("cardList");
        }
        if (cardsStr == null || cardsStr.isBlank()) {
            cardsStr = params.get("card_list");
        }
        List<OperationAssetFact> assets = QnmProtocolParser.parseCards(cardsStr, objectMapper);

        return new CallbackResult(
                true,
                null,
                billno,
                orderSn,
                outcomeClass,
                sumPrice,
                assets,
                QnmMaskingUtils.maskParams(params).toString()
        );
    }

    private static int parseIntOrDefault(String str, int defaultVal) {
        if (str == null || str.isBlank()) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(str.trim());
        } catch (Exception e) {
            log.debug("Failed to parse callback integer '{}', using default {}", str, defaultVal, e);
            return defaultVal;
        }
    }

    /**
     * 回调结果。
     */
    public record CallbackResult(
            boolean valid,
            String errorMessage,
            String billno,
            String orderSn,
            OperationOutcomeClass outcomeClass,
            Long reportedSettlementAmount,
            List<OperationAssetFact> assets,
            String rawPayload
    ) {
        public static CallbackResult invalid(String errorMessage) {
            return new CallbackResult(false, errorMessage, null, null, null, null, List.of(), null);
        }

        public CallbackResult {
            assets = assets == null ? List.of() : List.copyOf(assets);
        }
    }
}
