package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 华昇生产履约执行器契约测试。
 * 遵循规格：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#31-边界与责任矩阵。
 */
class HuashengOperationExecutorContractTest {

    private HuashengCallContextFactory contextFactory;
    private HuashengOperationExecutor executor;
    private MockFacadeDispatcher mockDispatcher;

    @BeforeEach
    void setUp() {
        mockDispatcher = new MockFacadeDispatcher();
        contextFactory = new HuashengCallContextFactory();
        executor = new HuashengOperationExecutor(mockDispatcher, contextFactory);
    }

    @Test
    @DisplayName("端口支持适配器类型与方法签名契约")
    void verifyPortSupportsAndSignatures() throws NoSuchMethodException {
        assertThat(executor.supports(HuashengOrderV1Metadata.TYPE)).isTrue();
        assertThat(executor.supports("QNM_GAME_ORDER_V1")).isFalse();
        assertThat(executor.supports(null)).isFalse();

        Method executeMethod = OperationExecutorPort.class.getMethod("execute", OperationTicket.class);
        assertThat(executeMethod.getReturnType()).isEqualTo(OperationOutcome.class);
    }

    @Test
    @DisplayName("领域对象拒绝意外JSON序列化且toString严格脱敏")
    void verifySecurityAndAntiSerialization() {
        ObjectMapper mapper = new ObjectMapper();

        OperationTicket.CredentialMaterial material = new OperationTicket.CredentialMaterial("secret123", "-----BEGIN PRIVATE KEY-----");
        assertThat(material.toString()).doesNotContain("secret123", "PRIVATE KEY");
        assertThatThrownBy(() -> mapper.writeValueAsString(material)).isInstanceOf(IOException.class);

        OperationTicket ticket = createValidTicket(AdapterStepCode.PLACE_ORDER);
        assertThat(ticket.toString()).doesNotContain("secret123", "PRIVATE KEY", "13800138000");
        assertThatThrownBy(() -> mapper.writeValueAsString(ticket)).isInstanceOf(IOException.class);

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.ACCEPTED)
                .externalOrderNo("HS_ORD_001")
                .reportedSettlementAmount(1000L)
                .build();
        assertThat(outcome.toString()).doesNotContain("1000");
        assertThatThrownBy(() -> mapper.writeValueAsString(outcome)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Ticket缺少凭据材料或非16字节appId时安全拒绝")
    void ticketMissingCredentialsOrInvalidAppIdRejected() {
        OperationTicket ticketNoMaterial = OperationTicket.builder()
                .operationNo("OP_001")
                .bOrderNo("BO_001")
                .billno("BILL_001")
                .adapterType(HuashengOrderV1Metadata.TYPE)
                .connectorVersionId(10L)
                .offerVersionId(20L)
                .operationKind(FulfillmentOperationKind.FULFILL)
                .adapterStepCode(AdapterStepCode.PLACE_ORDER)
                .externalSkuCode("SKU_001")
                .baseUrl("https://api.huasheng.com")
                .merchantAccount("1234567890123456")
                .credentialMaterial(null)
                .build();

        assertThatThrownBy(() -> executor.execute(ticketNoMaterial))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentialMaterial");

        OperationTicket ticketBadAppId = OperationTicket.builder()
                .operationNo("OP_001")
                .bOrderNo("BO_001")
                .billno("BILL_001")
                .adapterType(HuashengOrderV1Metadata.TYPE)
                .connectorVersionId(10L)
                .offerVersionId(20L)
                .operationKind(FulfillmentOperationKind.FULFILL)
                .adapterStepCode(AdapterStepCode.PLACE_ORDER)
                .externalSkuCode("SKU_001")
                .baseUrl("https://api.huasheng.com")
                .merchantAccount("shortAppId")
                .credentialMaterial(new OperationTicket.CredentialMaterial("secret", "pem"))
                .build();

        assertThatThrownBy(() -> executor.execute(ticketBadAppId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appId");
    }

    @Test
    @DisplayName("执行PLACE端点成功：映射为中间事实ACCEPTED，包含HuashengStepFact")
    void executePlaceSuccessReturnsAcceptedWithProtocolFact() {
        OperationTicket ticket = createValidTicket(AdapterStepCode.PLACE_ORDER);
        HuashengOrderResponse mockRes = createMockOrderResponse("HS_ORDER_999", ticket.billno());
        mockDispatcher.orderResultToReturn = new HuashengCallResult.Parsed<>(mockRes, List.of(), true);

        OperationOutcome outcome = executor.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.ACCEPTED);
        assertThat(outcome.externalOrderNo()).isEqualTo("HS_ORDER_999");
        assertThat(mockDispatcher.placeCallCount.get()).isEqualTo(1);
        assertThat(mockDispatcher.confirmCallCount.get()).isEqualTo(0);

        assertThat(outcome.protocolFact()).isNotNull();
        assertThat(outcome.protocolFact()).isInstanceOf(HuashengStepFact.class);
        HuashengStepFact fact = (HuashengStepFact) outcome.protocolFact();
        assertThat(fact.endpoint()).isEqualTo(HuashengStepFact.Endpoint.PLACE);
        assertThat(fact.orderSn()).isEqualTo("HS_ORDER_999");
        assertThat(fact.outOrderSn()).isEqualTo(ticket.billno());
    }

    @Test
    @DisplayName("执行CONFIRM端点成功：映射为中间事实ACCEPTED")
    void executeConfirmSuccessReturnsAccepted() {
        OperationTicket ticket = createValidTicket(AdapterStepCode.CONFIRM_ORDER);
        HuashengOrderResponse mockRes = createMockOrderResponse("HS_ORDER_999", ticket.billno());
        mockDispatcher.orderResultToReturn = new HuashengCallResult.Parsed<>(mockRes, List.of(), true);

        OperationOutcome outcome = executor.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.ACCEPTED);
        assertThat(outcome.externalOrderNo()).isEqualTo("HS_ORDER_999");
        assertThat(mockDispatcher.confirmCallCount.get()).isEqualTo(1);
        assertThat(mockDispatcher.placeCallCount.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("执行QUERY端点成功：映射为中间事实ACCEPTED")
    void executeQuerySuccessReturnsAccepted() {
        OperationTicket ticket = createValidTicket(AdapterStepCode.QUERY_ORDER);
        HuashengOrderResponse mockRes = createMockOrderResponse("HS_ORDER_999", ticket.billno());
        mockDispatcher.orderResultToReturn = new HuashengCallResult.Parsed<>(mockRes, List.of(), true);

        OperationOutcome outcome = executor.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.ACCEPTED);
        assertThat(outcome.externalOrderNo()).isEqualTo("HS_ORDER_999");
        assertThat(mockDispatcher.queryCallCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("执行TAKE端点成功：单张T201卡转换资产事实，无自生金额")
    void executeTakeSuccessProducesAssetFactWithoutManufacturedAmount() {
        OperationTicket ticket = createValidTicket(AdapterStepCode.TAKE_CARD);
        HuashengProtocolCard card = Mockito.mock(HuashengProtocolCard.class);
        Mockito.when(card.type()).thenReturn(HuashengCardType.T201);
        Mockito.when(card.code()).thenReturn(HuashengField.ofValue("CARD123"));
        Mockito.when(card.pass()).thenReturn(HuashengField.ofValue("PASS456"));
        Mockito.when(card.id()).thenReturn(HuashengField.ofValue(101L));
        Mockito.when(card.validTime()).thenReturn(HuashengField.ofValue("2026-12-31"));
        Mockito.when(card.crc()).thenReturn(HuashengField.absent());
        Mockito.when(card.url()).thenReturn(HuashengField.absent());
        Mockito.when(card.urlPass()).thenReturn(HuashengField.absent());

        HuashengTakeResponse mockTakeRes = Mockito.mock(HuashengTakeResponse.class);
        Mockito.when(mockTakeRes.orderSn()).thenReturn("HS_ORDER_999");
        Mockito.when(mockTakeRes.num()).thenReturn(1);
        Mockito.when(mockTakeRes.list()).thenReturn(List.of(card));
        mockDispatcher.takeResultToReturn = new HuashengCallResult.Parsed<>(mockTakeRes, List.of(), true);

        OperationOutcome outcome = executor.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.ACCEPTED);
        assertThat(outcome.externalOrderNo()).isEqualTo("HS_ORDER_999");
        assertThat(outcome.reportedSettlementAmount()).isNull(); // TAKE端点严禁自生金额
        assertThat(outcome.assets()).hasSize(1);
        OperationAssetFact asset = outcome.assets().get(0);
        assertThat(asset.cardNo()).isEqualTo("CARD123");
        assertThat(asset.cardSecret()).isEqualTo("PASS456");
        assertThat(asset.hasProtectedPayload()).isTrue();
    }

    @Test
    void executesType202TakeAsCompleteSingleCredentialAssetWithoutInventingPrice() {
        var card = HuashengType201AssetCodecTest.card("{\"type\":202,\"code\":\" TAKE_202 \",\"pass\":\"\"}");
        var response = Mockito.mock(HuashengTakeResponse.class);
        Mockito.when(response.orderSn()).thenReturn("HS_ORDER_999");
        Mockito.when(response.num()).thenReturn(1);
        Mockito.when(response.list()).thenReturn(List.of(card));
        mockDispatcher.takeResultToReturn = new HuashengCallResult.Parsed<>(response, List.of(), true);
        var outcome = executor.execute(createValidTicket(AdapterStepCode.TAKE_CARD));
        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.ACCEPTED);
        assertThat(outcome.reportedSettlementAmount()).isNull();
        assertThat(outcome.assets()).hasSize(1);
        assertThat(outcome.assets().getFirst().cardNo()).isEqualTo(" TAKE_202 ");
        assertThat(outcome.assets().getFirst().cardSecret()).isEqualTo(" TAKE_202 ");
    }

    @Test
    @DisplayName("执行TAKE端点返回非T201或多卡：降级为RESULT_UNKNOWN，不伪造成功交付")
    void executeTakeInvalidCardProducesResultUnknown() {
        OperationTicket ticket = createValidTicket(AdapterStepCode.TAKE_CARD);
        HuashengProtocolCard card202 = Mockito.mock(HuashengProtocolCard.class);
        Mockito.when(card202.type()).thenReturn(HuashengCardType.T202);

        HuashengTakeResponse mockTakeRes = Mockito.mock(HuashengTakeResponse.class);
        Mockito.when(mockTakeRes.orderSn()).thenReturn("HS_ORDER_999");
        Mockito.when(mockTakeRes.num()).thenReturn(1);
        Mockito.when(mockTakeRes.list()).thenReturn(List.of(card202));
        mockDispatcher.takeResultToReturn = new HuashengCallResult.Parsed<>(mockTakeRes, List.of(), true);

        OperationOutcome outcome = executor.execute(ticket);

        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
        assertThat(outcome.assets()).isEmpty();
    }

    @Test
    @DisplayName("远端失败或业务错误：统一映射为RESULT_UNKNOWN，绝不冒充REJECTED")
    void failuresMapToResultUnknownNeverRejected() {
        OperationTicket ticket = createValidTicket(AdapterStepCode.PLACE_ORDER);

        // 1. Business Error
        HuashengBusinessError mockBe = Mockito.mock(HuashengBusinessError.class);
        Mockito.when(mockBe.rawCode()).thenReturn(7014);
        Mockito.when(mockBe.knownErrorKind()).thenReturn(HuashengErrorCode.ORDER_NOT_FOUND);
        mockDispatcher.orderResultToReturn = new HuashengCallResult.BusinessError<>(mockBe, List.of(), true);
        OperationOutcome outcome1 = executor.execute(ticket);
        assertThat(outcome1.outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
        assertThat(outcome1.externalErrorCode()).isEqualTo("7014");

        // 2. Transport Failure / Network timeout
        mockDispatcher.orderResultToReturn = null;
        mockDispatcher.errorToThrow = new RuntimeException("Connection timed out");
        OperationOutcome outcome2 = executor.execute(ticket);
        assertThat(outcome2.outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);

        // 3. HuashengProtocolException
        HuashengFailureDiagnostics d = Mockito.mock(HuashengFailureDiagnostics.class);
        mockDispatcher.errorToThrow = HuashengProtocolException.fromDiagnostics(d);
        OperationOutcome outcome3 = executor.execute(ticket);
        assertThat(outcome3.outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
    }

    private HuashengOrderResponse createMockOrderResponse(String orderSn, String outOrderSn) {
        HuashengOrderResponse res = Mockito.mock(HuashengOrderResponse.class);
        Mockito.when(res.orderSn()).thenReturn(orderSn);
        Mockito.when(res.outOrderSn()).thenReturn(outOrderSn);
        Mockito.when(res.num()).thenReturn(1);
        Mockito.when(res.status()).thenReturn(HuashengOrderStatus.SUCCESS);
        Mockito.when(res.refundStatus()).thenReturn(Optional.empty());
        Mockito.when(res.isTake()).thenReturn(Optional.empty());

        HuashengPrice price = Mockito.mock(HuashengPrice.class);
        Mockito.when(price.rawYuan()).thenReturn("10.00");
        Mockito.when(price.minorUnits()).thenReturn(1000L);
        Mockito.when(res.totalPrice()).thenReturn(price);

        return res;
    }

    private OperationTicket createValidTicket(AdapterStepCode step) {
        return OperationTicket.builder()
                .operationNo("OP_HS_001")
                .bOrderNo("BO_HS_001")
                .billno("BILL_HS_001")
                .externalOrderNo(step == AdapterStepCode.PLACE_ORDER ? null : "HS_ORDER_999")
                .supplierCode("SPL_HS")
                .adapterType(HuashengOrderV1Metadata.TYPE)
                .connectorVersionId(10L)
                .offerVersionId(20L)
                .operationKind(FulfillmentOperationKind.FULFILL)
                .adapterStepCode(step)
                .sequenceNo(1)
                .idempotencyKey("IDEM_001")
                .externalSkuCode("1001")
                .quantity(1L)
                .baseUrl("https://open.huasheng.test")
                .merchantAccount("1234567890123456") // strictly 16 chars
                .credentialMaterial(new OperationTicket.CredentialMaterial("appSecKey123456", "MIIEvgIBADANBgkqhkiG9w0BAQEFAASC..."))
                .connectTimeoutMs(3000)
                .readTimeoutMs(5000)
                .traceId("trace-001")
                .extraParameters(Map.of("outMobile", "13800138000", "expireTime", "300"))
                .callbackUrl("https://sc.test/callbacks/suppliers/huasheng/10")
                .build();
    }

    private static class MockFacadeDispatcher extends HuashengCommunicationFacade {
        AtomicInteger placeCallCount = new AtomicInteger();
        AtomicInteger confirmCallCount = new AtomicInteger();
        AtomicInteger queryCallCount = new AtomicInteger();
        AtomicInteger takeCallCount = new AtomicInteger();

        HuashengCallResult<HuashengOrderResponse> orderResultToReturn;
        HuashengCallResult<HuashengTakeResponse> takeResultToReturn;
        Throwable errorToThrow;

        MockFacadeDispatcher() {
            super(dummyTokenManager(), dummyRequestBuilder(), dummyTransport(), Clock.systemUTC(), () -> 0L, dummySafeLog());
        }

        @Override
        public HuashengCallResult<HuashengOrderResponse> place(HuashengAuthorizedCallContext ctx, HuashengPlaceRequest req, HuashengExpectedOrder exp, HuashengCallBudget b) {
            placeCallCount.incrementAndGet();
            return handleOrderCall();
        }

        @Override
        public HuashengCallResult<HuashengOrderResponse> confirm(HuashengAuthorizedCallContext ctx, HuashengConfirmRequest req, HuashengExpectedOrder exp, HuashengCallBudget b) {
            confirmCallCount.incrementAndGet();
            return handleOrderCall();
        }

        @Override
        public HuashengCallResult<HuashengOrderResponse> query(HuashengAuthorizedCallContext ctx, HuashengQueryRequest req, HuashengExpectedOrder exp, HuashengCallBudget b) {
            queryCallCount.incrementAndGet();
            return handleOrderCall();
        }

        @Override
        public HuashengCallResult<HuashengTakeResponse> take(HuashengAuthorizedCallContext ctx, HuashengTakeRequest req, HuashengExpectedOrder exp, HuashengCallBudget b) {
            takeCallCount.incrementAndGet();
            if (errorToThrow != null) {
                if (errorToThrow instanceof RuntimeException re) throw re;
                throw new RuntimeException(errorToThrow);
            }
            if (takeResultToReturn != null) {
                return takeResultToReturn;
            }
            HuashengFailureDiagnostics d = Mockito.mock(HuashengFailureDiagnostics.class);
            Mockito.when(d.reasonCode()).thenReturn(HuashengFailureDiagnostics.HuashengFailureReasonCode.RESPONSE_TOO_LARGE);
            return new HuashengCallResult.InvalidResponse<>(d, List.of(), false);
        }

        private HuashengCallResult<HuashengOrderResponse> handleOrderCall() {
            if (errorToThrow != null) {
                if (errorToThrow instanceof RuntimeException re) throw re;
                throw new RuntimeException(errorToThrow);
            }
            if (orderResultToReturn != null) {
                return orderResultToReturn;
            }
            HuashengFailureDiagnostics d = Mockito.mock(HuashengFailureDiagnostics.class);
            Mockito.when(d.reasonCode()).thenReturn(HuashengFailureDiagnostics.HuashengFailureReasonCode.RESPONSE_TOO_LARGE);
            return new HuashengCallResult.InvalidResponse<>(d, List.of(), false);
        }

        private static com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengTokenManager dummyTokenManager() {
            return org.mockito.Mockito.mock(com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengTokenManager.class);
        }
        private static HuashengRequestBuilder dummyRequestBuilder() {
            return org.mockito.Mockito.mock(HuashengRequestBuilder.class);
        }
        private static com.aifuli.module.supplychain.supplieradapter.huasheng.http.HuashengHttpTransportPort dummyTransport() {
            return org.mockito.Mockito.mock(com.aifuli.module.supplychain.supplieradapter.huasheng.http.HuashengHttpTransportPort.class);
        }
        private static com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengSafeLog dummySafeLog() {
            return org.mockito.Mockito.mock(com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengSafeLog.class);
        }
    }
}
