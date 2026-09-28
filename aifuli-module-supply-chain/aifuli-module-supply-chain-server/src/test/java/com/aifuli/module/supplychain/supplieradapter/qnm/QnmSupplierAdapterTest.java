package com.aifuli.module.supplychain.supplieradapter.qnm;

import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 奇那梦供应商适配器单测与模拟挡板集成测试。
 *
 * <p>基于 {@link MockWebServer} 搭建供应商 HTTP 挡板，覆盖直充、发码、主动查单及各类超时、异常场景。</p>
 */
class QnmSupplierAdapterTest {

    private static final String SECRET_KEY = "test_qnm_secret_key_888";
    private static final String MERCHANT_ACCOUNT = "M10001";

    private MockWebServer server;
    private QnmSupplierAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        objectMapper = new ObjectMapper();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .writeTimeout(Duration.ofSeconds(2))
                .build();
        QnmHttpExecutor httpExecutor = new QnmHttpExecutor(httpClient);
        adapter = new QnmSupplierAdapter(httpExecutor, objectMapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("直充创建 /GameOrder/Send：成功受理并进入 ACCEPTED 状态")
    void shouldExecuteSendSuccessfully() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("""
                        {
                            "ResCode": "1",
                            "ResMsg": "提交成功",
                            "orderSn": "QNM_ORDER_12345",
                            "sumPrice": 9800
                        }
                        """));

        OperationTicket ticket = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND, "BILL_SEND_001", "13800138000");

        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.ACCEPTED);
        assertThat(outcome.externalOrderNo()).isEqualTo("QNM_ORDER_12345");
        assertThat(outcome.reportedSettlementAmount()).isEqualTo(9800L);
        assertThat(outcome.externalErrorCode()).isNull();
        assertThat(outcome.assets()).isEmpty();
        assertThat(outcome.callDigest()).isNotNull();
        assertThat(outcome.callDigest().endpointCode()).isEqualTo(QnmEndpointConstants.ENDPOINT_CODE_SEND);
        assertThat(outcome.callDigest().httpStatus()).isEqualTo(200);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/GameOrder/Send");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("customerid=M10001")
                .contains("billno=BILL_SEND_001")
                .contains("account=13800138000")
                .contains("number=1")
                .contains("productid=QNM_SKU_01")
                .contains("sign=");
    }

    @Test
    @DisplayName("直充创建 /GameOrder/Send：供应商业务拒绝返回 REJECTED")
    void shouldExecuteSendRejected() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("""
                        {
                            "ResCode": "-102",
                            "ResMsg": "商户余额不足"
                        }
                        """));

        OperationTicket ticket = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND, "BILL_SEND_002", "13800138000");

        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.REJECTED);
        assertThat(outcome.externalErrorCode()).isEqualTo("-102");
        assertThat(outcome.externalErrorMessage()).isEqualTo("商户余额不足");
        assertThat(outcome.callDigest().resultClass()).isEqualTo(OperationOutcomeClass.REJECTED.name());
    }

    @Test
    @DisplayName("卡密发券 /GameOrder/SendKm：成功返回并解析多张卡密资产")
    void shouldExecuteSendKmWithCards() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("""
                        {
                            "ResCode": "OK",
                            "ResMsg": "出单成功",
                            "orderSn": "QNM_KM_9999",
                            "sumPrice": 5000,
                            "cards": [
                                {
                                    "cardNo": "CARD_NO_01",
                                    "cardPwd": "CARD_PWD_01",
                                    "expireTime": "2028-06-30 23:59:59"
                                },
                                {
                                    "cardNo": "CARD_NO_02",
                                    "cardPwd": "CARD_PWD_02",
                                    "expireTime": "2028-06-30 23:59:59"
                                }
                            ]
                        }
                        """));

        OperationTicket ticket = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND_KM, "BILL_KM_001", null);

        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.SUCCEEDED);
        assertThat(outcome.externalOrderNo()).isEqualTo("QNM_KM_9999");
        assertThat(outcome.reportedSettlementAmount()).isEqualTo(5000L);
        assertThat(outcome.assets()).hasSize(2);

        OperationAssetFact card1 = outcome.assets().get(0);
        assertThat(card1.sequenceNo()).isEqualTo(1);
        assertThat(card1.cardNo()).isEqualTo("CARD_NO_01");
        assertThat(card1.cardSecret()).isEqualTo("CARD_PWD_01");
        assertThat(card1.validUntil()).isEqualTo("2028-06-30T23:59:59");

        OperationAssetFact card2 = outcome.assets().get(1);
        assertThat(card2.sequenceNo()).isEqualTo(2);
        assertThat(card2.cardNo()).isEqualTo("CARD_NO_02");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/GameOrder/SendKm");
    }

    @Test
    @DisplayName("卡密发券 /GameOrder/SendKm：响应成功但缺少卡密时返回 SUCCEEDED 且 assets 为空（支持 QNM-ASSET-RECOVERY-001 风险）")
    void shouldExecuteSendKmEmptyCardsSupportRecovery() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("""
                        {
                            "ResCode": "1",
                            "ResMsg": "成功",
                            "orderSn": "QNM_KM_EMPTY",
                            "sumPrice": 5000,
                            "cards": []
                        }
                        """));

        OperationTicket ticket = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND_KM, "BILL_KM_002", null);

        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.SUCCEEDED);
        assertThat(outcome.externalOrderNo()).isEqualTo("QNM_KM_EMPTY");
        assertThat(outcome.assets()).isEmpty();
    }

    @Test
    @DisplayName("主动查单 /GameOrder/Query：处理中状态 orderStatus=0 映射为 ACCEPTED")
    void shouldQueryOrderProcessing() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("""
                        {
                            "ResCode": "1",
                            "orderStatus": 0,
                            "orderSn": "QNM_QUERY_001",
                            "sumPrice": 9900
                        }
                        """));

        OperationTicket ticket = createQueryTicket("BILL_QUERY_001", "QNM_QUERY_001");
        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.ACCEPTED);
        assertThat(outcome.externalOrderNo()).isEqualTo("QNM_QUERY_001");
    }

    @Test
    @DisplayName("主动查单 /GameOrder/Query：终态成功 orderStatus=1 映射为 SUCCEEDED 并解析卡密")
    void shouldQueryOrderSuccessWithCards() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("""
                        {
                            "ResCode": "1",
                            "orderStatus": 1,
                            "orderSn": "QNM_QUERY_002",
                            "sumPrice": 9900,
                            "cards": [
                                {
                                    "cardNo": "RECOVERED_CARD",
                                    "cardPwd": "RECOVERED_PWD"
                                }
                            ]
                        }
                        """));

        OperationTicket ticket = createQueryTicket("BILL_QUERY_002", "QNM_QUERY_002");
        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.SUCCEEDED);
        assertThat(outcome.assets()).hasSize(1);
        assertThat(outcome.assets().get(0).cardNo()).isEqualTo("RECOVERED_CARD");
        assertThat(outcome.assets().get(0).cardSecret()).isEqualTo("RECOVERED_PWD");
    }

    @Test
    @DisplayName("主动查单 /GameOrder/Query：终态失败 orderStatus=2 映射为 REJECTED")
    void shouldQueryOrderFailed() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("""
                        {
                            "ResCode": "1",
                            "orderStatus": 2,
                            "ResMsg": "手机欠费停机充值失败"
                        }
                        """));

        OperationTicket ticket = createQueryTicket("BILL_QUERY_003", null);
        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.REJECTED);
        assertThat(outcome.externalErrorMessage()).isEqualTo("手机欠费停机充值失败");
    }

    @Test
    @DisplayName("主动查单 /GameOrder/Query：订单不存在映射为 NOT_FOUND")
    void shouldQueryOrderNotFound() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("""
                        {
                            "ResCode": "404",
                            "ResMsg": "订单不存在"
                        }
                        """));

        OperationTicket ticket = createQueryTicket("BILL_QUERY_404", null);
        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.NOT_FOUND);
    }

    @Test
    @DisplayName("网络超时：映射为 RESULT_UNKNOWN 并记录传输审计")
    void shouldHandleTimeoutGracefully() {
        server.enqueue(new MockResponse()
                .setBodyDelay(3, TimeUnit.SECONDS)
                .setBody("{\"ResCode\":\"1\"}"));

        OperationTicket ticket = OperationTicket.builder()
                .operationNo("OP_TIMEOUT_01")
                .bOrderNo("BO_TIMEOUT_01")
                .billno("BILL_TIMEOUT_01")
                .adapterType(QnmGameOrderV1Metadata.TYPE)
                .operationKind(FulfillmentOperationKind.FULFILL)
                .adapterStepCode(AdapterStepCode.SEND)
                .externalSkuCode("SKU_01")
                .baseUrl(server.url("/").toString())
                .merchantAccount(MERCHANT_ACCOUNT)
                .secretKey(SECRET_KEY)
                .timeoutSeconds(1) // 1 秒超时
                .build();

        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
        assertThat(outcome.externalErrorCode()).isEqualTo("NETWORK_ERROR");
        assertThat(outcome.callDigest()).isNotNull();
        assertThat(outcome.callDigest().resultClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN.name());
    }

    @Test
    @DisplayName("网络连接中断：映射为 RESULT_UNKNOWN")
    void shouldHandleConnectionRefusedGracefully() throws IOException {
        server.shutdown(); // 关闭服务器模拟断网

        OperationTicket ticket = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND, "BILL_ERR_01", "13800138000");

        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
        assertThat(outcome.externalErrorCode()).isEqualTo("NETWORK_ERROR");
    }

    @Test
    @DisplayName("响应体格式错误非 JSON：映射为 MANUAL_REQUIRED")
    void shouldHandleInvalidJsonGracefully() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("<html>502 Bad Gateway</html>"));

        OperationTicket ticket = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND, "BILL_BAD_JSON", "13800138000");

        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.MANUAL_REQUIRED);
        assertThat(outcome.externalErrorCode()).isEqualTo("INVALID_RESPONSE_JSON");
    }

    @Test
    @DisplayName("缺少必要参数时应抛出非法参数异常")
    void shouldThrowWhenMissingRequiredTicketParameters() {
        OperationTicket noBaseUrl = OperationTicket.builder()
                .operationNo("OP_NO_BASE")
                .bOrderNo("BO_NO_BASE")
                .billno("B_01")
                .adapterType(QnmGameOrderV1Metadata.TYPE)
                .merchantAccount(MERCHANT_ACCOUNT)
                .secretKey(SECRET_KEY)
                .build();
        assertThatThrownBy(() -> adapter.execute(noBaseUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    @DisplayName("不支持的操作类型（如 VOID_ASSET）应抛出 UnsupportedOperationException")
    void shouldThrowWhenUnsupportedOperationKind() {
        OperationTicket voidTicket = OperationTicket.builder()
                .operationNo("OP_VOID")
                .bOrderNo("BO_VOID")
                .billno("B_VOID")
                .adapterType(QnmGameOrderV1Metadata.TYPE)
                .operationKind(FulfillmentOperationKind.VOID_ASSET)
                .baseUrl("http://localhost:8080")
                .merchantAccount(MERCHANT_ACCOUNT)
                .secretKey(SECRET_KEY)
                .build();
        assertThatThrownBy(() -> adapter.execute(voidTicket))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Unsupported operationKind");
    }

    @Test
    @DisplayName("卡密发券：支持浮点数金额转分与纯字符串卡券列表解析")
    void shouldParseFloatingPointSumPriceAndStringCardsArray() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("""
                        {
                            "ResCode": "1",
                            "ResMsg": "发券成功",
                            "orderSn": "QNM_FLOAT_001",
                            "sumPrice": 98.50,
                            "cards": ["VOUCHER_CODE_AAA", "VOUCHER_CODE_BBB"]
                        }
                        """));

        OperationTicket ticket = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND_KM, "BILL_FLOAT_001", null);

        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.SUCCEEDED);
        assertThat(outcome.externalOrderNo()).isEqualTo("QNM_FLOAT_001");
        assertThat(outcome.reportedSettlementAmount()).isEqualTo(9850L);
        assertThat(outcome.assets()).hasSize(2);
        assertThat(outcome.assets().get(0).cardSecret()).isEqualTo("VOUCHER_CODE_AAA");
        assertThat(outcome.assets().get(0).sequenceNo()).isEqualTo(1);
        assertThat(outcome.assets().get(1).cardSecret()).isEqualTo("VOUCHER_CODE_BBB");
        assertThat(outcome.assets().get(1).sequenceNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("HTTP 502 网关错误（JSON 或 HTML 响应）：必须映射为 RESULT_UNKNOWN 而非 REJECTED 或 MANUAL_REQUIRED")
    void shouldMapHttp502ToResultUnknown() {
        // 1. HTTP 502 with JSON error
        server.enqueue(new MockResponse()
                .setResponseCode(502)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":502,\"error\":\"Bad Gateway\",\"message\":\"Upstream connect error\"}"));

        OperationTicket ticketJson = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND, "BILL_502_JSON", "13800138000");
        OperationOutcome outcomeJson = adapter.execute(ticketJson);

        assertThat(outcomeJson.outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
        assertThat(outcomeJson.externalErrorCode()).isEqualTo("NETWORK_ERROR");
        assertThat(outcomeJson.callDigest().httpStatus()).isEqualTo(502);

        // 2. HTTP 502 with HTML error
        server.enqueue(new MockResponse()
                .setResponseCode(502)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>502 Bad Gateway</body></html>"));

        OperationTicket ticketHtml = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND, "BILL_502_HTML", "13800138000");
        OperationOutcome outcomeHtml = adapter.execute(ticketHtml);

        assertThat(outcomeHtml.outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
        assertThat(outcomeHtml.callDigest().httpStatus()).isEqualTo(502);
    }

    @Test
    @DisplayName("HTTP 500 服务端内部错误与 HTTP 504 网关超时：映射为 RESULT_UNKNOWN")
    void shouldMapHttp500And504ToResultUnknown() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":500,\"message\":\"Internal DB failure\"}"));

        OperationTicket ticket500 = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND, "BILL_500", "13800138000");
        OperationOutcome outcome500 = adapter.execute(ticket500);

        assertThat(outcome500.outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
        assertThat(outcome500.callDigest().httpStatus()).isEqualTo(500);

        server.enqueue(new MockResponse()
                .setResponseCode(504)
                .setBody("Gateway Timeout"));

        OperationTicket ticket504 = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND, "BILL_504", "13800138000");
        OperationOutcome outcome504 = adapter.execute(ticket504);

        assertThat(outcome504.outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
        assertThat(outcome504.callDigest().httpStatus()).isEqualTo(504);
    }

    @Test
    @DisplayName("HTTP 429 限流响应：映射为 RESULT_UNKNOWN 以便重试")
    void shouldMapHttp429ToResultUnknown() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Too Many Requests\"}"));

        OperationTicket ticket429 = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND, "BILL_429", "13800138000");
        OperationOutcome outcome429 = adapter.execute(ticket429);

        assertThat(outcome429.outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
        assertThat(outcome429.callDigest().httpStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("主动查单返回 HTTP 404：映射为 NOT_FOUND")
    void shouldMapHttp404QueryToNotFound() {
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("<html>Not Found</html>"));

        OperationTicket ticket404 = createQueryTicket("BILL_Q_404", null);
        OperationOutcome outcome404 = adapter.execute(ticket404);

        assertThat(outcome404.outcomeClass()).isEqualTo(OperationOutcomeClass.NOT_FOUND);
        assertThat(outcome404.externalErrorCode()).isEqualTo("404");
    }

    @Test
    @DisplayName("HTTP 401 鉴权失败：映射为 MANUAL_REQUIRED")
    void shouldMapHttp401ToManualRequired() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\":\"Unauthorized\"}"));

        OperationTicket ticket401 = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND, "BILL_401", "13800138000");
        OperationOutcome outcome401 = adapter.execute(ticket401);

        assertThat(outcome401.outcomeClass()).isEqualTo(OperationOutcomeClass.MANUAL_REQUIRED);
        assertThat(outcome401.externalErrorCode()).isEqualTo("HTTP_401");
    }

    @Test
    @DisplayName("未指定数量或数量为 0 时：OperationTicket 默认保证 quantity 为 1")
    void shouldDefaultQuantityToOne() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"ResCode\":\"1\"}"));

        OperationTicket ticket = OperationTicket.builder()
                .operationNo("OP_QTY_0")
                .bOrderNo("BO_QTY_0")
                .billno("BILL_QTY_0")
                .adapterType(QnmGameOrderV1Metadata.TYPE)
                .operationKind(FulfillmentOperationKind.FULFILL)
                .adapterStepCode(AdapterStepCode.SEND)
                .externalSkuCode("SKU_QTY")
                .quantity(0L) // 传入 0
                .baseUrl(server.url("/").toString())
                .merchantAccount(MERCHANT_ACCOUNT)
                .secretKey(SECRET_KEY)
                .build();

        assertThat(ticket.quantity()).isEqualTo(1L);

        adapter.execute(ticket);
        RecordedRequest req = server.takeRequest();
        assertThat(req.getBody().readUtf8()).contains("number=1");
    }

    @Test
    @DisplayName("extraParameters: 自定义扩展参数应合并至外呼请求并参与 MD5 签名")
    void shouldMergeExtraParametersInSendAndQuery() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"ResCode\":\"1\",\"orderSn\":\"QNM_EXTRA_001\"}"));

        OperationTicket ticket = OperationTicket.builder()
                .operationNo("OP_EXTRA_01")
                .bOrderNo("BO_EXTRA_01")
                .billno("BILL_EXTRA_01")
                .adapterType(QnmGameOrderV1Metadata.TYPE)
                .operationKind(FulfillmentOperationKind.FULFILL)
                .adapterStepCode(AdapterStepCode.SEND)
                .externalSkuCode("SKU_EXTRA")
                .baseUrl(server.url("/").toString())
                .merchantAccount(MERCHANT_ACCOUNT)
                .secretKey(SECRET_KEY)
                .extraParameters(java.util.Map.of("game_server", "s101", "role_id", "r888"))
                .build();

        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.ACCEPTED);
        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("game_server=s101").contains("role_id=r888");

        // 验证签名中也包含了 extraParameters
        java.util.Map<String, String> expectedParams = new java.util.HashMap<>(ticket.extraParameters());
        expectedParams.put(QnmEndpointConstants.PARAM_CUSTOMER_ID, MERCHANT_ACCOUNT);
        expectedParams.put(QnmEndpointConstants.PARAM_PRODUCT_ID, "SKU_EXTRA");
        expectedParams.put(QnmEndpointConstants.PARAM_BILLNO, "BILL_EXTRA_01");
        expectedParams.put(QnmEndpointConstants.PARAM_NUMBER, "1");
        expectedParams.put(QnmEndpointConstants.PARAM_TIMESTAMP, body.replaceAll(".*timestamp=([0-9]+).*", "$1"));
        String expectedSign = QnmSignUtils.sign(expectedParams, SECRET_KEY);
        assertThat(body).contains("sign=" + expectedSign);
    }

    @Test
    @DisplayName("主动查单：支持 order_sn 与 order_status / status 别名返回")
    void shouldSupportOrderSnAndOrderStatusAliasesInQuery() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                            "ResCode": "1",
                            "order_sn": "QNM_ALIAS_QUERY",
                            "order_status": 1,
                            "sumPrice": 8800
                        }
                        """));

        OperationTicket ticket = createQueryTicket("BILL_Q_ALIAS", null);
        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.SUCCEEDED);
        assertThat(outcome.externalOrderNo()).isEqualTo("QNM_ALIAS_QUERY");
        assertThat(outcome.reportedSettlementAmount()).isEqualTo(8800L);
    }

    @Test
    @DisplayName("主动查单：供应商返回签名或商户配置错误 ResCode=-100 时映射为 MANUAL_REQUIRED 并保留错误码")
    void shouldMapClientSignatureErrorInQueryToManualRequired() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                            "ResCode": "-100",
                            "ResMsg": "商户签名校验未通过"
                        }
                        """));

        OperationTicket ticket = createQueryTicket("BILL_Q_ERR_100", null);
        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.MANUAL_REQUIRED);
        assertThat(outcome.externalErrorCode()).isEqualTo("-100");
        assertThat(outcome.externalErrorMessage()).isEqualTo("商户签名校验未通过");
    }

    @Test
    @DisplayName("瞬时网关错误（HTTP 502）：rawObservation 的 payloadDigest 应匹配 responseDigest 而非 requestDigest")
    void shouldUseResponseDigestForTransientErrorWithResponseBody() {
        server.enqueue(new MockResponse()
                .setResponseCode(502)
                .setBody("<html>502 Bad Gateway</html>"));

        OperationTicket ticket = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND, "BILL_DIGEST_502", "13800138000");
        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
        byte[] expectedDigest = QnmHttpExecutor.sha256("<html>502 Bad Gateway</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(outcome.rawObservation().payloadDigest()).isEqualTo(expectedDigest);
    }

    @Test
    @DisplayName("直充创建：支持 upstream 返回 code 与 msg 别名，正确提取错误码与错误信息")
    void shouldExtractCodeAndMsgAliasesOnRejection() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                            "code": -102,
                            "msg": "商户余额不足，请充值后重试"
                        }
                        """));

        OperationTicket ticket = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND, "BILL_CODE_ALIAS", "13800138000");

        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.REJECTED);
        assertThat(outcome.externalErrorCode()).isEqualTo("-102");
        assertThat(outcome.externalErrorMessage()).isEqualTo("商户余额不足，请充值后重试");
    }

    @Test
    @DisplayName("卡密发券：支持 cardList 别名，并正确提取 externalAssetId、validFrom 与 cardNumber 别名")
    void shouldSupportCardListAliasAndRichFieldsInSendKm() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                            "ResCode": "1",
                            "ResMsg": "发券成功",
                            "orderSn": "QNM_KM_LIST_01",
                            "sumPrice": 10000,
                            "cardList": [
                                {
                                    "cardId": "ASSET_KM_888",
                                    "cardNumber": "CARD_KM_NUM_01",
                                    "card_secret": "CARD_KM_SEC_01",
                                    "startTime": "2026-06-01 00:00:00",
                                    "expireTime": "2028-12-31 23:59:59"
                                }
                            ]
                        }
                        """));

        OperationTicket ticket = createBaseTicket(
                FulfillmentOperationKind.FULFILL, AdapterStepCode.SEND_KM, "BILL_KM_LIST_01", null);

        OperationOutcome outcome = adapter.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.SUCCEEDED);
        assertThat(outcome.assets()).hasSize(1);
        OperationAssetFact asset = outcome.assets().get(0);
        assertThat(asset.externalAssetId()).isEqualTo("ASSET_KM_888");
        assertThat(asset.cardNo()).isEqualTo("CARD_KM_NUM_01");
        assertThat(asset.cardSecret()).isEqualTo("CARD_KM_SEC_01");
        assertThat(asset.validFrom()).isEqualTo("2026-06-01T00:00:00");
        assertThat(asset.validUntil()).isEqualTo("2028-12-31T23:59:59");
    }

    @Test
    @DisplayName("超时控制：当 defaultClient 配置 2s 但 ticket 指定 10s 时，能够正确按 10s 重构客户端")
    void shouldRebuildClientWhenTicketTimeoutIsTenSeconds() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"ResCode\":\"1\"}"));

        OperationTicket ticket = OperationTicket.builder()
                .operationNo("OP_TIMEOUT_10")
                .bOrderNo("BO_TIMEOUT_10")
                .billno("BILL_TIMEOUT_10")
                .adapterType(QnmGameOrderV1Metadata.TYPE)
                .operationKind(FulfillmentOperationKind.FULFILL)
                .adapterStepCode(AdapterStepCode.SEND)
                .externalSkuCode("SKU_10")
                .baseUrl(server.url("/").toString())
                .merchantAccount(MERCHANT_ACCOUNT)
                .secretKey(SECRET_KEY)
                .timeoutSeconds(10) // 恰好等于 10s
                .build();

        OperationOutcome outcome = adapter.execute(ticket);
        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.ACCEPTED);
    }

    private OperationTicket createBaseTicket(FulfillmentOperationKind kind, AdapterStepCode stepCode,
                                            String billno, String account) {
        return OperationTicket.builder()
                .operationNo("OP_" + billno)
                .bOrderNo("BO_" + billno)
                .billno(billno)
                .supplierCode("QINAMENG")
                .adapterType(QnmGameOrderV1Metadata.TYPE)
                .connectorVersionId(1L)
                .offerVersionId(1L)
                .operationKind(kind)
                .adapterStepCode(stepCode)
                .sequenceNo(1)
                .idempotencyKey("IDEM_" + billno)
                .externalSkuCode("QNM_SKU_01")
                .quantity(1L)
                .rechargeAccount(account)
                .callbackUrl("https://notify.aifuli.com/qnm/cb")
                .baseUrl(server.url("/").toString())
                .merchantAccount(MERCHANT_ACCOUNT)
                .secretKey(SECRET_KEY)
                .timeoutSeconds(5)
                .traceId("TRACE_" + billno)
                .build();
    }

    private OperationTicket createQueryTicket(String billno, String orderSn) {
        return OperationTicket.builder()
                .operationNo("OP_Q_" + billno)
                .bOrderNo("BO_Q_" + billno)
                .billno(billno)
                .externalOrderNo(orderSn)
                .supplierCode("QINAMENG")
                .adapterType(QnmGameOrderV1Metadata.TYPE)
                .connectorVersionId(1L)
                .offerVersionId(1L)
                .operationKind(FulfillmentOperationKind.QUERY_ORDER)
                .sequenceNo(2)
                .idempotencyKey("IDEM_Q_" + billno)
                .baseUrl(server.url("/").toString())
                .merchantAccount(MERCHANT_ACCOUNT)
                .secretKey(SECRET_KEY)
                .timeoutSeconds(5)
                .traceId("TRACE_Q_" + billno)
                .build();
    }
}
