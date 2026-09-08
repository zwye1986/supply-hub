package com.aifuli.module.supplychain.api.instore;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.dto.AcceptRefundReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.ConfirmPaymentReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CreatePaymentOrderReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentStatusRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentTicketRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryPaymentStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryRefundStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundAcceptanceRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundStatusRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.ReportRefundResultReqDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 到店购支付/退款命令与收银台回调 RPC 的字段边界与校验合同。
 *
 * <p>上下文派生字段（projectId/channelCode/buyerId/路由字段）禁止进入 Wire DTO。
 * 详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#2-api-层}。</p>
 */
class InstorePaymentCommandApiContractTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void keepsExactExternalDtoFieldBoundaries() {
        // Map.of 最多支持 10 组键值对，CreatePaymentOrderReqDTO 有 12 个字段，改用 Map.ofEntries
        assertBusinessFields(CreatePaymentOrderReqDTO.class, Map.ofEntries(
                Map.entry("channelOrderId", String.class),
                Map.entry("tradeId", String.class),
                Map.entry("userCode", String.class),
                Map.entry("payAmount", Long.class),
                Map.entry("orderType", Integer.class),
                Map.entry("totalAmount", Long.class),
                Map.entry("companyCode", String.class),
                Map.entry("externalMerchantId", String.class),
                Map.entry("shopCode", String.class),
                Map.entry("shopName", String.class),
                Map.entry("subject", String.class),
                Map.entry("traceId", String.class)));
        assertBusinessFields(QueryPaymentStatusReqDTO.class, Map.of(
                "tradeId", String.class, "traceId", String.class));
        assertBusinessFields(CancelPaymentFlowReqDTO.class, Map.of(
                "tradeId", String.class, "traceId", String.class));
        assertBusinessFields(AcceptRefundReqDTO.class, Map.of(
                "channelRefundNo", String.class,
                "tradeId", String.class,
                "refundType", String.class,
                "channelRefundAmount", Long.class,
                "traceId", String.class));
        assertBusinessFields(QueryRefundStatusReqDTO.class, Map.of(
                "channelRefundNo", String.class, "traceId", String.class));
        assertBusinessFields(PaymentTicketRespDTO.class, Map.of(
                "orderNo", String.class,
                "outTradeNo", String.class,
                "cashierUrl", String.class,
                "payStatus", Integer.class));
        assertBusinessFields(PaymentStatusRespDTO.class, Map.of(
                "paymentFlowNo", String.class,
                "outTradeNo", String.class,
                "tradeId", String.class,
                "payStatus", Integer.class,
                "payAmount", Long.class,
                "buyerPaidAmount", Long.class,
                "cashierReference", String.class,
                "externalTransactionId", String.class));
        assertBusinessFields(CancelPaymentFlowRespDTO.class, Map.of(
                "outTradeNo", String.class,
                "refundCaseNo", String.class,
                "convertedToRefund", Boolean.class,
                "claimedForDispatch", Boolean.class));
        assertBusinessFields(RefundAcceptanceRespDTO.class, Map.of(
                "refundCaseNo", String.class,
                "refundStatus", Integer.class,
                "claimedForDispatch", Boolean.class));
        assertBusinessFields(RefundStatusRespDTO.class, Map.of(
                "refundCaseNo", String.class,
                "channelRefundNo", String.class,
                "refundType", String.class,
                "status", Integer.class,
                "channelRefundAmount", Long.class,
                "buyerActualRefundAmount", Long.class));
        assertBusinessFields(ConfirmPaymentReqDTO.class, Map.of(
                "outTradeNo", String.class,
                "actualAmountCny", Long.class,
                "cashierOrderNo", String.class,
                "cashierTransactionNo", String.class,
                "traceId", String.class));
        assertBusinessFields(ReportRefundResultReqDTO.class, Map.of(
                "refundCaseNo", String.class,
                "actualAmountCny", Long.class,
                "cashierRefundReference", String.class,
                "traceId", String.class));
    }

    @Test
    void forbidsInternalRoutingAndIdentityFieldsInWireDtos() {
        Set<String> forbiddenFields = Set.of("projectId", "buyerId", "buyerUserId", "channelCode",
                "sourceSystem", "externalTenantId", "cashierProfileVersionId", "appId", "keyVersion");
        for (Class<?> dtoType : Set.of(CreatePaymentOrderReqDTO.class, QueryPaymentStatusReqDTO.class,
                CancelPaymentFlowReqDTO.class, AcceptRefundReqDTO.class, QueryRefundStatusReqDTO.class,
                PaymentTicketRespDTO.class, PaymentStatusRespDTO.class, CancelPaymentFlowRespDTO.class,
                RefundAcceptanceRespDTO.class, RefundStatusRespDTO.class,
                ConfirmPaymentReqDTO.class, ReportRefundResultReqDTO.class)) {
            assertThat(businessFields(dtoType).keySet()).doesNotContainAnyElementsOf(forbiddenFields);
        }
    }

    @Test
    void rejectsEveryRequiredField() {
        CreatePaymentOrderReqDTO create = new CreatePaymentOrderReqDTO();
        create.setPayAmount(10_000L);
        create.setOrderType(1);
        create.setTotalAmount(10_000L);
        assertViolationProperties(create, "channelOrderId", "tradeId", "userCode",
                "companyCode", "externalMerchantId");

        CreatePaymentOrderReqDTO zeroAmount = validCreateRequest();
        zeroAmount.setPayAmount(0L);
        assertViolationProperties(zeroAmount, "payAmount");

        QueryPaymentStatusReqDTO query = new QueryPaymentStatusReqDTO();
        query.setTradeId(" ");
        assertViolationProperties(query, "tradeId");

        CancelPaymentFlowReqDTO cancel = new CancelPaymentFlowReqDTO();
        cancel.setTradeId(" ");
        assertViolationProperties(cancel, "tradeId");

        AcceptRefundReqDTO refund = new AcceptRefundReqDTO();
        refund.setChannelRefundAmount(300L);
        assertViolationProperties(refund, "channelRefundNo", "tradeId", "refundType");

        QueryRefundStatusReqDTO queryRefund = new QueryRefundStatusReqDTO();
        queryRefund.setChannelRefundNo(" ");
        assertViolationProperties(queryRefund, "channelRefundNo");

        ConfirmPaymentReqDTO confirm = new ConfirmPaymentReqDTO();
        confirm.setActualAmountCny(10_000L);
        assertViolationProperties(confirm, "outTradeNo");

        ReportRefundResultReqDTO report = new ReportRefundResultReqDTO();
        report.setActualAmountCny(300L);
        assertViolationProperties(report, "refundCaseNo");
    }

    @Test
    void acceptsCompleteRequests() {
        assertThat(validator.validate(validCreateRequest())).isEmpty();

        QueryPaymentStatusReqDTO query = new QueryPaymentStatusReqDTO();
        query.setTradeId("TRADE001");
        assertThat(validator.validate(query)).isEmpty();

        CancelPaymentFlowReqDTO cancel = new CancelPaymentFlowReqDTO();
        cancel.setTradeId("TRADE001");
        assertThat(validator.validate(cancel)).isEmpty();

        AcceptRefundReqDTO refund = new AcceptRefundReqDTO();
        refund.setChannelRefundNo("R9");
        refund.setTradeId("TRADE001");
        refund.setRefundType("CHANNEL_REFUND");
        refund.setChannelRefundAmount(300L);
        assertThat(validator.validate(refund)).isEmpty();

        QueryRefundStatusReqDTO queryRefund = new QueryRefundStatusReqDTO();
        queryRefund.setChannelRefundNo("R9");
        assertThat(validator.validate(queryRefund)).isEmpty();

        ConfirmPaymentReqDTO confirm = new ConfirmPaymentReqDTO();
        confirm.setOutTradeNo("IPF_OTN");
        confirm.setActualAmountCny(10_000L);
        assertThat(validator.validate(confirm)).isEmpty();

        ReportRefundResultReqDTO report = new ReportRefundResultReqDTO();
        report.setRefundCaseNo("IRC_9");
        report.setActualAmountCny(300L);
        assertThat(validator.validate(report)).isEmpty();
    }

    @Test
    void enforcesSpecifiedRequestFieldLengthBoundaries() {
        CreatePaymentOrderReqDTO request = validCreateRequest();
        assertThat(validator.validate(request)).isEmpty();

        request.setChannelOrderId("c".repeat(129));
        assertViolationProperties(request, "channelOrderId");
        request.setChannelOrderId("c".repeat(128));
        request.setTradeId("t".repeat(129));
        assertViolationProperties(request, "tradeId");
        request.setTradeId("t".repeat(128));
        request.setUserCode("u".repeat(129));
        assertViolationProperties(request, "userCode");
        request.setUserCode("u".repeat(128));
        request.setCompanyCode("c".repeat(33));
        assertViolationProperties(request, "companyCode");
        request.setCompanyCode("c".repeat(32));
        request.setExternalMerchantId("m".repeat(129));
        assertViolationProperties(request, "externalMerchantId");
        request.setExternalMerchantId("m".repeat(128));
        request.setShopCode("s".repeat(129));
        assertViolationProperties(request, "shopCode");
        request.setShopCode("s".repeat(128));
        request.setShopName("n".repeat(257));
        assertViolationProperties(request, "shopName");
        request.setShopName("n".repeat(256));
        request.setSubject("j".repeat(257));
        assertViolationProperties(request, "subject");
        request.setSubject("j".repeat(256));
        request.setTraceId("x".repeat(129));
        assertViolationProperties(request, "traceId");

        AcceptRefundReqDTO refund = new AcceptRefundReqDTO();
        refund.setChannelRefundNo("r".repeat(129));
        assertViolationProperties(refund, "channelRefundNo", "tradeId", "refundType", "channelRefundAmount");
        refund.setChannelRefundNo("r".repeat(128));
        refund.setTradeId("t".repeat(128));
        refund.setRefundType("f".repeat(33));
        assertViolationProperties(refund, "refundType", "channelRefundAmount");
        refund.setRefundType("f".repeat(32));
        refund.setChannelRefundAmount(300L);
        assertThat(validator.validate(refund)).isEmpty();

        ReportRefundResultReqDTO report = new ReportRefundResultReqDTO();
        report.setRefundCaseNo("i".repeat(33));
        assertViolationProperties(report, "refundCaseNo", "actualAmountCny");
        report.setRefundCaseNo("i".repeat(32));
        report.setActualAmountCny(300L);
        assertThat(validator.validate(report)).isEmpty();
    }

    @Test
    void publishesOnlyCommonResultRpcMethodsWithExactPaths() throws Exception {
        assertApiMethod(InstoreChannelCommandApi.class, "createPaymentOrder",
                CreatePaymentOrderReqDTO.class, PaymentTicketRespDTO.class,
                "/rpc-api/supply-chain/instore/channel-command/createPaymentOrder");
        assertApiMethod(InstoreChannelCommandApi.class, "queryPaymentStatus",
                QueryPaymentStatusReqDTO.class, PaymentStatusRespDTO.class,
                "/rpc-api/supply-chain/instore/channel-command/queryPaymentStatus");
        assertApiMethod(InstoreChannelCommandApi.class, "cancelPaymentFlow",
                CancelPaymentFlowReqDTO.class, CancelPaymentFlowRespDTO.class,
                "/rpc-api/supply-chain/instore/channel-command/cancelPaymentFlow");
        assertApiMethod(InstoreChannelCommandApi.class, "acceptRefund",
                AcceptRefundReqDTO.class, RefundAcceptanceRespDTO.class,
                "/rpc-api/supply-chain/instore/channel-command/acceptRefund");
        assertApiMethod(InstoreChannelCommandApi.class, "queryRefundStatus",
                QueryRefundStatusReqDTO.class, RefundStatusRespDTO.class,
                "/rpc-api/supply-chain/instore/channel-command/queryRefundStatus");
        assertApiMethod(InstoreBuyerCashierCallbackApi.class, "confirmPayment",
                ConfirmPaymentReqDTO.class, Void.class,
                "/rpc-api/supply-chain/buyer/instore/payment/confirmPayment");
        assertApiMethod(InstoreBuyerCashierCallbackApi.class, "reportRefundResult",
                ReportRefundResultReqDTO.class, Void.class,
                "/rpc-api/supply-chain/buyer/instore/payment/reportRefundResult");
    }

    @Test
    void omitsUserCodeAndCashierUrlFromToString() {
        CreatePaymentOrderReqDTO create = new CreatePaymentOrderReqDTO();
        create.setUserCode("USER-CODE-SENTINEL");

        PaymentTicketRespDTO ticket = new PaymentTicketRespDTO();
        ticket.setCashierUrl("https://cashier/pay?token=CASHIER-URL-SENTINEL");

        assertThat(create.toString()).doesNotContain("USER-CODE-SENTINEL");
        assertThat(ticket.toString()).doesNotContain("CASHIER-URL-SENTINEL");
    }

    private static CreatePaymentOrderReqDTO validCreateRequest() {
        CreatePaymentOrderReqDTO request = new CreatePaymentOrderReqDTO();
        request.setChannelOrderId("ORD001");
        request.setTradeId("TRADE001");
        request.setUserCode("opaque-user");
        request.setPayAmount(10_000L);
        request.setOrderType(1);
        request.setTotalAmount(10_000L);
        request.setCompanyCode("COMPANY");
        request.setExternalMerchantId("MERCHANT1");
        return request;
    }

    private static void assertApiMethod(Class<?> apiType, String methodName, Class<?> requestType,
                                        Class<?> responseType, String expectedPath) throws Exception {
        Method method = apiType.getMethod(methodName, requestType);
        assertThat(method.getReturnType()).isEqualTo(CommonResult.class);
        assertThat(((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments())
                .containsExactly(responseType);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(expectedPath);
    }

    private static void assertBusinessFields(Class<?> type, Map<String, Class<?>> expected) {
        assertThat(businessFields(type)).containsExactlyInAnyOrderEntriesOf(expected);
    }

    private static Map<String, Class<?>> businessFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic()
                        && !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .collect(Collectors.toMap(Field::getName, Field::getType));
    }

    private static void assertViolationProperties(Object request, String... expectedProperties) {
        Set<String> properties = validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertThat(properties).containsExactlyInAnyOrder(expectedProperties);
    }
}
