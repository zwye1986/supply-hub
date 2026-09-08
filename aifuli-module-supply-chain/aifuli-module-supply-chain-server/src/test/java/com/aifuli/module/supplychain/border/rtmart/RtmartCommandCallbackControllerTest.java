package com.aifuli.module.supplychain.border.rtmart;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.AcceptRefundCommand;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.CancelPaymentCommand;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.CancelPaymentTicket;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.PaymentTicket;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.PaymentStatusView;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.QueryPaymentCommand;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.QueryRefundCommand;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.RefundAcceptance;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.RefundStatusView;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.WireCreatePaymentCommand;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旅通商户接口②③④⑤⑥入站控制器测试：协议校验编排、SHA1withRSA 验签、
 * 内部状态码→旅通状态码映射。密钥均在测试运行时生成。
 */
@ExtendWith(MockitoExtension.class)
class RtmartCommandCallbackControllerTest {

    private static final String APP_KEY = "1G23d45P6";
    private static final long PROJECT_ID = 201L;
    private static final String CHANNEL_CODE = "RT_MART";

    @Mock
    private InstoreChannelCommandFacade commandFacade;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RtmartInboundProperties properties;
    private RtmartCommandCallbackController controller;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        properties = new RtmartInboundProperties();
        properties.setEnabled(true);
        properties.setAppKey(APP_KEY);
        properties.setPublicKey(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        properties.setProjectId(PROJECT_ID);
        properties.setChannelCode(CHANNEL_CODE);
        properties.setTimestampToleranceSeconds(180);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        controller = new RtmartCommandCallbackController(properties, commandFacade, redisTemplate);
        controller.initializeVerifyKey();
    }

    @Test
    void disabledAdapterFailsClosed() {
        properties.setEnabled(false);
        controller = new RtmartCommandCallbackController(properties, commandFacade, null);

        assertThatCode(controller::initializeVerifyKey).doesNotThrowAnyException();

        RtmartResponse response = controller.createOrder(validCreateOrderRequest());

        assertThat(response.getCode()).isEqualTo("E5000");
        verify(commandFacade, never()).createPaymentOrder(any());
    }

    @Test
    void enabledAdapterRequiresRedisDuringInitialization() {
        controller = new RtmartCommandCallbackController(properties, commandFacade, null);

        assertThatThrownBy(controller::initializeVerifyKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rtmart inbound requires StringRedisTemplate when enabled");
    }

    @Test
    void missingRequiredFieldsRejected() {
        RtmartCreateOrderRequest request = validCreateOrderRequest();
        request.setNonceStr(null);

        RtmartResponse response = controller.createOrder(request);

        assertThat(response.getCode()).isEqualTo("E4001");
        verify(commandFacade, never()).createPaymentOrder(any());
    }

    @Test
    void appKeyMismatchRejected() {
        RtmartCreateOrderRequest request = validCreateOrderRequest();
        request.setAppKey("wrong-key");
        request.setSign(signCreateOrder(request));

        RtmartResponse response = controller.createOrder(request);

        assertThat(response.getCode()).isEqualTo("E4003");
        verify(commandFacade, never()).createPaymentOrder(any());
    }

    @Test
    void invalidSignatureRejected() {
        RtmartCreateOrderRequest request = validCreateOrderRequest();
        request.setSign("INVALID-SIGNATURE==");

        RtmartResponse response = controller.createOrder(request);

        assertThat(response.getCode()).isEqualTo("E4004");
        assertThat(response.getMsg()).isEqualTo("签名验证失败");
        verify(commandFacade, never()).createPaymentOrder(any());
    }

    @Test
    void createOrderSuccess() {
        RtmartCreateOrderRequest request = validCreateOrderRequest();
        when(commandFacade.createPaymentOrder(any())).thenReturn(
                new PaymentTicket("SC_1", "IPF_OTN_1", "https://cashier.example.com", (short) 10));

        RtmartResponse response = controller.createOrder(request);

        assertThat(response.getResult()).isEqualTo("success");
        assertThat(response.getCode()).isEqualTo("E0000");
        RtmartCreateOrderData data = (RtmartCreateOrderData) response.getData();
        assertThat(data.getOutTradeNo()).isEqualTo("IPF_OTN_1");
        assertThat(data.getCashierUrl()).isEqualTo("https://cashier.example.com");

        ArgumentCaptor<WireCreatePaymentCommand> captor = ArgumentCaptor.forClass(WireCreatePaymentCommand.class);
        verify(commandFacade).createPaymentOrder(captor.capture());
        assertThat(captor.getValue().channelOrderId()).isEqualTo("RT-ORDER-001");
        assertThat(captor.getValue().tradeId()).isEqualTo("RT-TRADE-001");
        assertThat(captor.getValue().userCode()).isEqualTo("247");
        assertThat(captor.getValue().totalAmount()).isEqualTo(1650L);
        assertThat(captor.getValue().companyCode()).isEqualTo("X0000A");
    }

    @Test
    void orderTypeTwoRejectedEvenWhenDynamicCodeProvided() {
        RtmartCreateOrderRequest request = validCreateOrderRequest();
        request.setOrderType(2);
        request.setDynamicCode("DYNAMIC-CODE-001");
        request.setSign(signCreateOrder(request));

        RtmartResponse response = controller.createOrder(request);

        assertThat(response.getCode()).isEqualTo("E4001");
        assertThat(response.getMsg()).isEqualTo("order_type 当前仅支持 1");
        verify(commandFacade, never()).createPaymentOrder(any());
    }

    @Test
    void queryPaymentSuccessMapsPaidStatusToOne() {
        RtmartQueryPaymentRequest request = validQueryPaymentRequest();
        when(commandFacade.queryAndConvergePayment(any())).thenReturn(
                new PaymentStatusView("IPF_1", "IPF_OTN_1", "RT-TRADE-001",
                        (short) 20, 1650L, 1650L, "CASHIER_1", "TXN_1", 1724760000L));

        RtmartResponse response = controller.queryPayment(request);

        assertThat(response.getCode()).isEqualTo("E0000");
        RtmartQueryPaymentData data = (RtmartQueryPaymentData) response.getData();
        assertThat(data.getOutTradeNo()).isEqualTo("IPF_OTN_1");
        assertThat(data.getPayStatus()).isEqualTo(1);
        assertThat(data.getPayFee()).isEqualTo(1650);
        assertThat(data.getPayTime()).isEqualTo(1724760000);
        assertThat(data.getTransactionId()).isEqualTo("TXN_1");
    }

    @Test
    void queryPaymentPendingFillsPayFeeFromPayAmountAndEmptyTransactionId() throws Exception {
        when(commandFacade.queryAndConvergePayment(any())).thenReturn(
                new PaymentStatusView("IPF_1", "IPF_OTN_1", "RT-TRADE-001",
                        (short) 0, 1650L, null, null, null, null));

        RtmartResponse response = controller.queryPayment(validQueryPaymentRequest());

        assertThat(response.getCode()).isEqualTo("E0000");
        RtmartQueryPaymentData data = (RtmartQueryPaymentData) response.getData();
        assertThat(data.getPayStatus()).isEqualTo(0);
        assertThat(data.getPayFee()).isEqualTo(1650);
        assertThat(data.getPayTime()).isEqualTo(0);
        assertThat(data.getTransactionId()).isEqualTo("");

        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"out_trade_no\":\"IPF_OTN_1\"");
        assertThat(json).contains("\"pay_status\":0");
        assertThat(json).contains("\"pay_fee\":1650");
        assertThat(json).contains("\"pay_time\":0");
        assertThat(json).contains("\"transaction_id\":\"\"");
        assertThat(json).doesNotContain("\"pay_fee\":null");
        assertThat(json).doesNotContain("\"pay_time\":null");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void queryPaymentPaidWithoutTransactionIdFailsClosed(String transactionId) {
        when(commandFacade.queryAndConvergePayment(any())).thenReturn(
                new PaymentStatusView("IPF_1", "IPF_OTN_1", "RT-TRADE-001",
                        (short) 20, 1650L, 1650L, "CASHIER_1", transactionId, 1724760000L));

        RtmartResponse response = controller.queryPayment(validQueryPaymentRequest());

        assertThat(response.getCode()).isEqualTo("E5000");
        assertThat(response.getResult()).isEqualTo("fail");
    }

    @Test
    void queryPaymentMapsCancelledStatusToTwo() {
        RtmartQueryPaymentRequest request = validQueryPaymentRequest();
        when(commandFacade.queryAndConvergePayment(any())).thenReturn(
                new PaymentStatusView("IPF_1", "IPF_OTN_1", "RT-TRADE-001",
                        (short) 40, 1650L, null, null, null, null));

        RtmartResponse response = controller.queryPayment(request);

        RtmartQueryPaymentData data = (RtmartQueryPaymentData) response.getData();
        assertThat(data.getPayStatus()).isEqualTo(2);
        assertThat(data.getPayFee()).isEqualTo(1650);
        assertThat(data.getPayTime()).isEqualTo(0);
        assertThat(data.getTransactionId()).isEqualTo("");
    }

    @Test
    void cancelPaymentSuccess() {
        RtmartCancelPaymentRequest request = validCancelPaymentRequest();
        when(commandFacade.cancelPaymentFlow(any())).thenReturn(
                new CancelPaymentTicket("IPF_OTN_1", null, false, false));

        RtmartResponse response = controller.cancelPayment(request);

        assertThat(response.getCode()).isEqualTo("E0000");
        RtmartCancelPaymentData data = (RtmartCancelPaymentData) response.getData();
        assertThat(data.getOutTradeNo()).isEqualTo("IPF_OTN_1");

        ArgumentCaptor<CancelPaymentCommand> captor = ArgumentCaptor.forClass(CancelPaymentCommand.class);
        verify(commandFacade).cancelPaymentFlow(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(PROJECT_ID);
        assertThat(captor.getValue().tradeId()).isEqualTo("RT-TRADE-001");
        assertThat(captor.getValue().channelCode()).isEqualTo(CHANNEL_CODE);
    }

    @Test
    void acceptRefundSuccess() {
        RtmartAcceptRefundRequest request = validAcceptRefundRequest();
        when(commandFacade.acceptRefund(any())).thenReturn(
                new RefundAcceptance("IRC_1", "IPF_OTN_1", (short) 20, true));

        RtmartResponse response = controller.acceptRefund(request);

        assertThat(response.getCode()).isEqualTo("E0000");
        RtmartAcceptRefundData data = (RtmartAcceptRefundData) response.getData();
        assertThat(data.getOutTradeNo()).isEqualTo("IPF_OTN_1");
        assertThat(data.getOutRefundNo()).isEqualTo("IRC_1");
        assertThat(data.getRefundStatus()).isEqualTo(0);
        assertThat(data.getRefundFee()).isEqualTo(5634L);
        assertThat(data.getRefundTime()).isBetween(
                (int) (System.currentTimeMillis() / 1000) - 2,
                (int) (System.currentTimeMillis() / 1000) + 2);
        assertThat(data.getRefuseReason()).isEqualTo("");

        ArgumentCaptor<AcceptRefundCommand> captor = ArgumentCaptor.forClass(AcceptRefundCommand.class);
        verify(commandFacade).acceptRefund(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(PROJECT_ID);
        assertThat(captor.getValue().channelRefundNo()).isEqualTo("RT-REFUND-001");
        assertThat(captor.getValue().channelRefundAmount()).isEqualTo(5634L);
        assertThat(captor.getValue().refundType()).isEqualTo("CHANNEL_REFUND");
    }

    @Test
    void acceptRefundSuccessJsonAlwaysIncludesRefundTimeAndEmptyRefuseReason() throws Exception {
        when(commandFacade.acceptRefund(any())).thenReturn(
                new RefundAcceptance("IRC_1", "IPF_OTN_1", (short) 20, true));

        RtmartResponse response = controller.acceptRefund(validAcceptRefundRequest());

        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String json = mapper.writeValueAsString(response.getData());
        assertThat(json).contains("\"out_trade_no\":\"IPF_OTN_1\"");
        assertThat(json).contains("\"out_refund_no\":\"IRC_1\"");
        assertThat(json).contains("\"refund_status\":0");
        assertThat(json).contains("\"refund_fee\":5634");
        assertThat(json).contains("\"refund_time\":");
        assertThat(json).contains("\"refuse_reason\":\"\"");
        assertThat(json).doesNotContain("\"refund_time\":null");
        assertThat(json).doesNotContain("\"refuse_reason\":null");
    }

    @Test
    void acceptRefundWithZeroFeeRejected() {
        RtmartAcceptRefundRequest request = validAcceptRefundRequest();
        request.setRefundFee(0L);
        request.setSign(signAcceptRefund(request));

        RtmartResponse response = controller.acceptRefund(request);

        assertThat(response.getCode()).isEqualTo("E4001");
        assertThat(response.getMsg()).isEqualTo("退款金额必须大于0");
        verify(commandFacade, never()).acceptRefund(any());
    }

    @Test
    void acceptRefundWithNegativeFeeRejected() {
        RtmartAcceptRefundRequest request = validAcceptRefundRequest();
        request.setRefundFee(-100L);
        request.setSign(signAcceptRefund(request));

        RtmartResponse response = controller.acceptRefund(request);

        assertThat(response.getCode()).isEqualTo("E4001");
        assertThat(response.getMsg()).isEqualTo("退款金额必须大于0");
        verify(commandFacade, never()).acceptRefund(any());
    }

    @Test
    void acceptRefundWithNullFeeRejected() {
        RtmartAcceptRefundRequest request = validAcceptRefundRequest();
        request.setRefundFee(null);
        request.setSign(signAcceptRefundExcludingNullFee(request));

        RtmartResponse response = controller.acceptRefund(request);

        assertThat(response.getCode()).isEqualTo("E4001");
        assertThat(response.getMsg()).isEqualTo("退款金额必须大于0");
        verify(commandFacade, never()).acceptRefund(any());
    }

    @Test
    void queryRefundSuccessMapsRefundedToOne() {
        RtmartQueryRefundRequest request = validQueryRefundRequest();
        when(commandFacade.queryAndConvergeRefund(any())).thenReturn(
                new RefundStatusView("IRC_1", "RT-REFUND-001", "CHANNEL_REFUND",
                        (short) 30, 580L, 580L, "IPF_OTN_1", 1724760000L, null));

        RtmartResponse response = controller.queryRefund(request);

        assertThat(response.getCode()).isEqualTo("E0000");
        RtmartQueryRefundData data = (RtmartQueryRefundData) response.getData();
        assertThat(data.getOutTradeNo()).isEqualTo("IPF_OTN_1");
        assertThat(data.getOutRefundNo()).isEqualTo("IRC_1");
        assertThat(data.getRefundStatus()).isEqualTo(1);
        assertThat(data.getRefundFee()).isEqualTo(580L);
        assertThat(data.getRefundTime()).isEqualTo(1724760000);
        assertThat(data.getRefuseReason()).isEqualTo("");
    }

    @Test
    void queryRefundMapsFailedToTwo() {
        RtmartQueryRefundRequest request = validQueryRefundRequest();
        when(commandFacade.queryAndConvergeRefund(any())).thenReturn(
                new RefundStatusView("IRC_1", "RT-REFUND-001", "CHANNEL_REFUND",
                        (short) 40, 580L, null, null, null, "余额不足"));

        RtmartResponse response = controller.queryRefund(request);

        RtmartQueryRefundData data = (RtmartQueryRefundData) response.getData();
        assertThat(data.getRefundStatus()).isEqualTo(2);
        assertThat(data.getRefuseReason()).isEqualTo("余额不足");
        int nowSeconds = (int) (System.currentTimeMillis() / 1000);
        assertThat(data.getRefundTime()).isBetween(nowSeconds - 2, nowSeconds + 2);
    }

    @Test
    void queryRefundProcessingJsonAlwaysIncludesRefundTimeAndEmptyRefuseReason() throws Exception {
        when(commandFacade.queryAndConvergeRefund(any())).thenReturn(
                new RefundStatusView("IRC_1", "RT-REFUND-001", "CHANNEL_REFUND",
                        (short) 20, 280L, null, "IPF_OTN_1", null, null));

        RtmartResponse response = controller.queryRefund(validQueryRefundRequest());

        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String json = mapper.writeValueAsString(response.getData());
        assertThat(json).contains("\"out_trade_no\":\"IPF_OTN_1\"");
        assertThat(json).contains("\"out_refund_no\":\"IRC_1\"");
        assertThat(json).contains("\"refund_status\":0");
        assertThat(json).contains("\"refund_fee\":280");
        assertThat(json).contains("\"refund_time\":");
        assertThat(json).contains("\"refuse_reason\":\"\"");
        assertThat(json).doesNotContain("\"refund_time\":null");
        assertThat(json).doesNotContain("\"refuse_reason\":null");
    }

    @Test
    void callerCorrectableServiceExceptionMapsToInvalidArgument() {
        RtmartCreateOrderRequest request = validCreateOrderRequest();
        when(commandFacade.createPaymentOrder(any()))
                .thenThrow(new ServiceException(1_050_001_030, "退款金额超过可退金额"));

        RtmartResponse response = controller.createOrder(request);

        assertThat(response.getResult()).isEqualTo("fail");
        assertThat(response.getCode()).isEqualTo("E4001");
        assertThat(response.getMsg()).isEqualTo("请求参数或业务状态无效");
    }

    @Test
    void nonceReplayDetected() {
        RtmartCreateOrderRequest request = validCreateOrderRequest();
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);

        RtmartResponse response = controller.createOrder(request);

        assertThat(response.getCode()).isEqualTo("E4001");
        assertThat(response.getMsg()).isEqualTo("请求已处理，请勿重复提交");
        verify(commandFacade, never()).createPaymentOrder(any());
    }

    @Test
    void nonceStoreReturnsNullFailsClosed() {
        RtmartCreateOrderRequest request = validCreateOrderRequest();
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(null);

        RtmartResponse response = controller.createOrder(request);

        assertThat(response.getCode()).isEqualTo("E5000");
        assertThat(response.getMsg()).isEqualTo("服务暂时不可用");
        verify(commandFacade, never()).createPaymentOrder(any());
    }

    @Test
    void nonceStoreUnavailableFailsClosed() {
        RtmartCreateOrderRequest request = validCreateOrderRequest();
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));

        RtmartResponse response = controller.createOrder(request);

        assertThat(response.getCode()).isEqualTo("E5000");
        assertThat(response.getMsg()).isEqualTo("服务暂时不可用");
        verify(commandFacade, never()).createPaymentOrder(any());
    }

    @Test
    void unexpectedRuntimeErrorMapsToSystemError() {
        RtmartCreateOrderRequest request = validCreateOrderRequest();
        when(commandFacade.createPaymentOrder(any())).thenThrow(new IllegalStateException("boom"));

        RtmartResponse response = controller.createOrder(request);

        assertThat(response.getCode()).isEqualTo("E5000");
    }

    private RtmartCreateOrderRequest validCreateOrderRequest() {
        RtmartCreateOrderRequest request = new RtmartCreateOrderRequest();
        request.setAppKey(APP_KEY);
        request.setVersion("v1");
        long ts = System.currentTimeMillis() / 1000;
        request.setTimestamp(ts);
        request.setNonceStr("nonce-create-001");
        request.setOrderId("RT-ORDER-001");
        request.setTradeId("RT-TRADE-001");
        request.setUserCode("247");
        request.setCompanyCode("X0000A");
        request.setTotalAmount(1650L);
        request.setOrderTime(ts - 1);
        request.setOrderType(1);
        request.setShopCode("1001");
        request.setShopName("宜山路店");
        request.setSign(signCreateOrder(request));
        return request;
    }

    private RtmartQueryPaymentRequest validQueryPaymentRequest() {
        RtmartQueryPaymentRequest request = new RtmartQueryPaymentRequest();
        request.setAppKey(APP_KEY);
        request.setVersion("v1");
        request.setTimestamp(System.currentTimeMillis() / 1000);
        request.setNonceStr("nonce-query-pay-001");
        request.setOrderId("RT-ORDER-001");
        request.setTradeId("RT-TRADE-001");
        request.setUserCode("247");
        request.setCompanyCode("X0000A");
        request.setSign(signQueryPayment(request));
        return request;
    }

    private RtmartCancelPaymentRequest validCancelPaymentRequest() {
        RtmartCancelPaymentRequest request = new RtmartCancelPaymentRequest();
        request.setAppKey(APP_KEY);
        request.setVersion("v1");
        request.setTimestamp(System.currentTimeMillis() / 1000);
        request.setNonceStr("nonce-cancel-001");
        request.setOrderId("RT-ORDER-001");
        request.setTradeId("RT-TRADE-001");
        request.setUserCode("247");
        request.setCompanyCode("X0000A");
        request.setSign(signCancelPayment(request));
        return request;
    }

    private RtmartAcceptRefundRequest validAcceptRefundRequest() {
        RtmartAcceptRefundRequest request = new RtmartAcceptRefundRequest();
        request.setAppKey(APP_KEY);
        request.setVersion("v1");
        request.setTimestamp(System.currentTimeMillis() / 1000);
        request.setNonceStr("nonce-refund-001");
        request.setOrderId("RT-ORDER-001");
        request.setTradeId("RT-TRADE-001");
        request.setRefundBn("RT-REFUND-001");
        request.setRefundFee(5634L);
        request.setUserCode("247");
        request.setCompanyCode("X0000A");
        request.setSign(signAcceptRefund(request));
        return request;
    }

    private RtmartQueryRefundRequest validQueryRefundRequest() {
        RtmartQueryRefundRequest request = new RtmartQueryRefundRequest();
        request.setAppKey(APP_KEY);
        request.setVersion("v1");
        request.setTimestamp(System.currentTimeMillis() / 1000);
        request.setNonceStr("nonce-query-refund-001");
        request.setOrderId("RT-ORDER-001");
        request.setTradeId("RT-TRADE-001");
        request.setRefundBn("RT-REFUND-001");
        request.setUserCode("247");
        request.setCompanyCode("X0000A");
        request.setSign(signQueryRefund(request));
        return request;
    }

    private String signCreateOrder(RtmartCreateOrderRequest request) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("app_key", request.getAppKey());
        fields.put("version", request.getVersion());
        fields.put("timestamp", String.valueOf(request.getTimestamp()));
        fields.put("nonce_str", request.getNonceStr());
        fields.put("order_id", request.getOrderId());
        fields.put("trade_id", request.getTradeId());
        fields.put("user_code", request.getUserCode());
        fields.put("company_code", request.getCompanyCode());
        fields.put("total_amount", String.valueOf(request.getTotalAmount()));
        fields.put("order_time", String.valueOf(request.getOrderTime()));
        fields.put("order_type", String.valueOf(request.getOrderType()));
        fields.put("shop_code", request.getShopCode());
        fields.put("shop_name", request.getShopName());
        if (request.getDynamicCode() != null) {
            fields.put("dynamic_code", request.getDynamicCode());
        }
        return sign(fields);
    }

    private String signQueryPayment(RtmartQueryPaymentRequest request) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("app_key", request.getAppKey());
        fields.put("version", request.getVersion());
        fields.put("timestamp", String.valueOf(request.getTimestamp()));
        fields.put("nonce_str", request.getNonceStr());
        fields.put("order_id", request.getOrderId());
        fields.put("trade_id", request.getTradeId());
        fields.put("user_code", request.getUserCode());
        fields.put("company_code", request.getCompanyCode());
        return sign(fields);
    }

    private String signCancelPayment(RtmartCancelPaymentRequest request) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("app_key", request.getAppKey());
        fields.put("version", request.getVersion());
        fields.put("timestamp", String.valueOf(request.getTimestamp()));
        fields.put("nonce_str", request.getNonceStr());
        fields.put("order_id", request.getOrderId());
        fields.put("trade_id", request.getTradeId());
        fields.put("user_code", request.getUserCode());
        fields.put("company_code", request.getCompanyCode());
        return sign(fields);
    }

    private String signAcceptRefund(RtmartAcceptRefundRequest request) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("app_key", request.getAppKey());
        fields.put("version", request.getVersion());
        fields.put("timestamp", String.valueOf(request.getTimestamp()));
        fields.put("nonce_str", request.getNonceStr());
        fields.put("order_id", request.getOrderId());
        fields.put("trade_id", request.getTradeId());
        fields.put("refund_bn", request.getRefundBn());
        fields.put("refund_fee", String.valueOf(request.getRefundFee()));
        fields.put("user_code", request.getUserCode());
        fields.put("company_code", request.getCompanyCode());
        return sign(fields);
    }

    private String signAcceptRefundExcludingNullFee(RtmartAcceptRefundRequest request) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("app_key", request.getAppKey());
        fields.put("version", request.getVersion());
        fields.put("timestamp", String.valueOf(request.getTimestamp()));
        fields.put("nonce_str", request.getNonceStr());
        fields.put("order_id", request.getOrderId());
        fields.put("trade_id", request.getTradeId());
        fields.put("refund_bn", request.getRefundBn());
        fields.put("user_code", request.getUserCode());
        fields.put("company_code", request.getCompanyCode());
        return sign(fields);
    }

    private String signQueryRefund(RtmartQueryRefundRequest request) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("app_key", request.getAppKey());
        fields.put("version", request.getVersion());
        fields.put("timestamp", String.valueOf(request.getTimestamp()));
        fields.put("nonce_str", request.getNonceStr());
        fields.put("order_id", request.getOrderId());
        fields.put("trade_id", request.getTradeId());
        fields.put("refund_bn", request.getRefundBn());
        fields.put("user_code", request.getUserCode());
        fields.put("company_code", request.getCompanyCode());
        return sign(fields);
    }

    private String sign(Map<String, String> fields) {
        try {
            StringBuilder canonical = new StringBuilder();
            fields.forEach((key, value) -> canonical.append(key).append(value));

            Signature signer = Signature.getInstance("SHA1withRSA");
            signer.initSign(keyPair.getPrivate());
            signer.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
