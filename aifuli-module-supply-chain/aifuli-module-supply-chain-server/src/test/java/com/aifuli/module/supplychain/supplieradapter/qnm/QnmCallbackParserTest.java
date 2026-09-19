package com.aifuli.module.supplychain.supplieradapter.qnm;

import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 奇那梦回调报文解析单元测试。
 */
class QnmCallbackParserTest {

    private static final String SECRET_KEY = "callback_secret_test";
    private QnmCallbackParser callbackParser;

    @BeforeEach
    void setUp() {
        callbackParser = new QnmCallbackParser(new ObjectMapper());
    }

    @Test
    @DisplayName("成功回调：status=1，包含订单号、结算金额及卡密")
    void shouldParseSuccessfulCallbackWithCards() {
        Map<String, String> params = new HashMap<>();
        params.put("billno", "BILL202609180001");
        params.put("orderSn", "QNM202609189999");
        params.put("status", "1");
        params.put("sumPrice", "9500");
        params.put("cards", "[{\"cardNo\":\"CARD001\",\"cardPwd\":\"PWD001\",\"expireTime\":\"2027-12-31 23:59:59\"}]");
        params.put("timestamp", "1726639200");
        String sign = QnmSignUtils.sign(params, SECRET_KEY);
        params.put("sign", sign);

        QnmCallbackParser.CallbackResult result = callbackParser.parse(params, SECRET_KEY);

        assertThat(result.valid()).isTrue();
        assertThat(result.billno()).isEqualTo("BILL202609180001");
        assertThat(result.orderSn()).isEqualTo("QNM202609189999");
        assertThat(result.outcomeClass()).isEqualTo(OperationOutcomeClass.SUCCEEDED);
        assertThat(result.reportedSettlementAmount()).isEqualTo(9500L);
        assertThat(result.assets()).hasSize(1);
        OperationAssetFact asset = result.assets().get(0);
        assertThat(asset.cardNo()).isEqualTo("CARD001");
        assertThat(asset.cardSecret()).isEqualTo("PWD001");
        assertThat(asset.validUntil()).isEqualTo("2027-12-31T23:59:59");
    }

    @Test
    @DisplayName("失败回调：status=2，映射为 REJECTED")
    void shouldParseFailedCallback() {
        Map<String, String> params = new HashMap<>();
        params.put("billno", "BILL202609180002");
        params.put("orderSn", "QNM202609188888");
        params.put("status", "2");
        params.put("timestamp", "1726639200");
        String sign = QnmSignUtils.sign(params, SECRET_KEY);
        params.put("sign", sign);

        QnmCallbackParser.CallbackResult result = callbackParser.parse(params, SECRET_KEY);

        assertThat(result.valid()).isTrue();
        assertThat(result.billno()).isEqualTo("BILL202609180002");
        assertThat(result.orderSn()).isEqualTo("QNM202609188888");
        assertThat(result.outcomeClass()).isEqualTo(OperationOutcomeClass.REJECTED);
        assertThat(result.assets()).isEmpty();
    }

    @Test
    @DisplayName("验签失败时拒绝解析")
    void shouldRejectWhenSignInvalid() {
        Map<String, String> params = new HashMap<>();
        params.put("billno", "BILL202609180003");
        params.put("status", "1");
        params.put("sign", "invalid_signature");

        QnmCallbackParser.CallbackResult result = callbackParser.parse(params, SECRET_KEY);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("Sign verification failed");
    }

    @Test
    @DisplayName("回调解析：支持小数元转分、别名字段、ISO时间与脱敏rawPayload")
    void shouldParseCallbackWithDecimalsAndAliasesAndMaskedRawPayload() {
        Map<String, String> params = new HashMap<>();
        params.put("billno", "BILL_DECIMAL_01");
        params.put("orderSn", "QNM_DECIMAL_01");
        params.put("status", "1");
        params.put("sumPrice", "98.50");
        params.put("cards", "[{\"card_no\":\"CARD_A\",\"card_pwd\":\"PWD_A\",\"expire_time\":\"2028-12-31T23:59:59\"}]");
        params.put("timestamp", "1726639200");
        String sign = QnmSignUtils.sign(params, SECRET_KEY);
        params.put("sign", sign);

        QnmCallbackParser.CallbackResult result = callbackParser.parse(params, SECRET_KEY);

        assertThat(result.valid()).isTrue();
        assertThat(result.reportedSettlementAmount()).isEqualTo(9850L);
        assertThat(result.assets()).hasSize(1);
        assertThat(result.assets().get(0).cardNo()).isEqualTo("CARD_A");
        assertThat(result.assets().get(0).cardSecret()).isEqualTo("PWD_A");
        assertThat(result.assets().get(0).validUntil()).isEqualTo("2028-12-31T23:59:59");
        assertThat(result.rawPayload()).contains("cards=****");
    }

    @Test
    @DisplayName("处理中回调：status=0，映射为 ACCEPTED")
    void shouldParseProcessingCallback() {
        Map<String, String> params = new HashMap<>();
        params.put("billno", "BILL_PROCESSING_01");
        params.put("orderSn", "QNM_PROC_01");
        params.put("status", "0");
        params.put("timestamp", "1726639200");
        String sign = QnmSignUtils.sign(params, SECRET_KEY);
        params.put("sign", sign);

        QnmCallbackParser.CallbackResult result = callbackParser.parse(params, SECRET_KEY);

        assertThat(result.valid()).isTrue();
        assertThat(result.outcomeClass()).isEqualTo(OperationOutcomeClass.ACCEPTED);
        assertThat(result.orderSn()).isEqualTo("QNM_PROC_01");
    }

    @Test
    @DisplayName("空回调或仅含 sign 字段时必须拒绝解析")
    void shouldRejectEmptyCallback() {
        String emptySign = QnmSignUtils.md5Hex("key=" + SECRET_KEY);
        Map<String, String> params = Map.of("sign", emptySign);

        QnmCallbackParser.CallbackResult result = callbackParser.parse(params, SECRET_KEY);
        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("Sign verification failed");

        assertThat(callbackParser.parse(null, SECRET_KEY).valid()).isFalse();
        assertThat(callbackParser.parse(Map.of(), SECRET_KEY).valid()).isFalse();
    }

    @Test
    @DisplayName("回调解析：支持 order_sn 与 order_status 别名字段")
    void shouldParseCallbackWithOrderSnAndOrderStatusAliases() {
        Map<String, String> params = new HashMap<>();
        params.put("billno", "BILL_ALIAS_01");
        params.put("order_sn", "QNM_ALIAS_999");
        params.put("order_status", "1");
        params.put("sumPrice", "6600");
        params.put("timestamp", "1726639200");
        String sign = QnmSignUtils.sign(params, SECRET_KEY);
        params.put("sign", sign);

        QnmCallbackParser.CallbackResult result = callbackParser.parse(params, SECRET_KEY);

        assertThat(result.valid()).isTrue();
        assertThat(result.orderSn()).isEqualTo("QNM_ALIAS_999");
        assertThat(result.outcomeClass()).isEqualTo(OperationOutcomeClass.SUCCEEDED);
        assertThat(result.reportedSettlementAmount()).isEqualTo(6600L);
    }

    @Test
    @DisplayName("回调解析：支持 bill_no、sum_price 与 cardList 别名字段")
    void shouldParseCallbackWithBillNoAndSumPriceAndCardListAliases() {
        Map<String, String> params = new HashMap<>();
        params.put("bill_no", "BILL_SNAKE_01");
        params.put("order_sn", "QNM_SNAKE_999");
        params.put("status", "1");
        params.put("sum_price", "7700");
        params.put("cardList", "[{\"card_no\":\"CARD_SNAKE\",\"card_pwd\":\"PWD_SNAKE\"}]");
        params.put("timestamp", "1726639200");
        String sign = QnmSignUtils.sign(params, SECRET_KEY);
        params.put("sign", sign);

        QnmCallbackParser.CallbackResult result = callbackParser.parse(params, SECRET_KEY);

        assertThat(result.valid()).isTrue();
        assertThat(result.billno()).isEqualTo("BILL_SNAKE_01");
        assertThat(result.orderSn()).isEqualTo("QNM_SNAKE_999");
        assertThat(result.outcomeClass()).isEqualTo(OperationOutcomeClass.SUCCEEDED);
        assertThat(result.reportedSettlementAmount()).isEqualTo(7700L);
        assertThat(result.assets()).hasSize(1);
        assertThat(result.assets().get(0).cardNo()).isEqualTo("CARD_SNAKE");
        assertThat(result.assets().get(0).cardSecret()).isEqualTo("PWD_SNAKE");
    }
}
