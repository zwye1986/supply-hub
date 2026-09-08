package com.aifuli.module.supplychain.integration.buyer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 采购商收银台端口契约测试。
 *
 * <p>锁定项目身份与 SaaS 路由坐标的边界，避免外部会员标识被错误转成中台 Long 主键。
 * 设计见
 * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.2-项目集成档案}。</p>
 */
class BuyerCashierPortContractTest {

    @Test
    void shouldRequireProjectRouteAndOpaqueBuyerIdentityForEveryCashierOperation() {
        Map<String, Class<?>> nestedTypes = Arrays.stream(BuyerCashierPort.class.getDeclaredClasses())
                .collect(Collectors.toMap(Class::getSimpleName, Function.identity()));

        assertThat(nestedTypes).containsKeys(
                "CreateCashierOrderCommand", "QueryPaymentCommand",
                "RefundCommand", "QueryRefundCommand");
        assertRecordComponent(nestedTypes.get("CreateCashierOrderCommand"),
                "route", BuyerIntegrationRoute.class);
        assertRecordComponent(nestedTypes.get("CreateCashierOrderCommand"),
                "buyerUserId", String.class);
        assertRecordComponent(nestedTypes.get("QueryPaymentCommand"),
                "route", BuyerIntegrationRoute.class);
        assertRecordComponent(nestedTypes.get("QueryPaymentResult"),
                "cashierOrderNo", String.class);
        assertRecordComponent(nestedTypes.get("QueryPaymentResult"),
                "cashierTransactionNo", String.class);
        assertRecordComponent(nestedTypes.get("RefundCommand"),
                "route", BuyerIntegrationRoute.class);
        assertRecordComponent(nestedTypes.get("RefundCommand"),
                "refundCaseNo", String.class);
        assertRecordComponent(nestedTypes.get("QueryRefundCommand"),
                "route", BuyerIntegrationRoute.class);
        assertRecordComponent(nestedTypes.get("QueryRefundCommand"),
                "refundCaseNo", String.class);
    }

    @Test
    void shouldExposeCommandObjectsInsteadOfUnroutedScalarQueries() {
        Map<String, Class<?>> nestedTypes = Arrays.stream(BuyerCashierPort.class.getDeclaredClasses())
                .collect(Collectors.toMap(Class::getSimpleName, Function.identity()));

        assertMethodParameter("queryPayment", nestedTypes.get("QueryPaymentCommand"));
        assertMethodParameter("queryRefund", nestedTypes.get("QueryRefundCommand"));
    }

    @Test
    void shouldReturnActualRefundAmountFromRefundAndAuthoritativeQuery() {
        Map<String, Class<?>> nestedTypes = Arrays.stream(BuyerCashierPort.class.getDeclaredClasses())
                .collect(Collectors.toMap(Class::getSimpleName, Function.identity()));

        assertRecordComponent(nestedTypes.get("RefundResult"),
                "actualRefundAmountCny", Long.class);
        assertRecordComponent(nestedTypes.get("QueryRefundResult"),
                "actualRefundAmountCny", Long.class);
    }

    @Test
    void shouldFailFastForWrongRouteBlankIdentityAndMissingSuccessfulRefundAmount() {
        BuyerIntegrationRoute wrongRoute = new BuyerIntegrationRoute(
                101L, 201L, BuyerIntegrationCapabilityCode.MEMBER_PROFILE,
                "AIFULI_MALL", "tenant-a", "RPC", "MEMBER_RPC", "v1", null);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThatThrownBy(() -> new BuyerCashierPort.CreateCashierOrderCommand(
                wrongRoute, "IOP_1", "IPF_1", "opaque-user", 1_000L, "到店购", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CASHIER");
        assertThatThrownBy(() -> new BuyerCashierPort.CreateCashierOrderCommand(
                cashierRoute(), "IOP_1", "IPF_1", " ", 1_000L, "到店购", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buyerUserId");
        assertThatThrownBy(() -> new BuyerCashierPort.QueryPaymentCommand(cashierRoute(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outTradeNo");
        assertThatThrownBy(() -> new BuyerCashierPort.RefundResult(
                BuyerCashierPort.RefundStatus.SUCCEEDED, null, "refund-1", null, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actualRefundAmountCny");
        assertThatThrownBy(() -> new BuyerCashierPort.QueryRefundResult(
                BuyerCashierPort.RefundQueryStatus.SUCCEEDED, null, "refund-1", null, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actualRefundAmountCny");
    }

    private static BuyerIntegrationRoute cashierRoute() {
        return new BuyerIntegrationRoute(
                101L, 202L, BuyerIntegrationCapabilityCode.CASHIER,
                "AIFULI_MALL", "tenant-a", "RPC", "SAAS_CASHIER_RPC", "v1", null);
    }

    private static void assertMethodParameter(String methodName, Class<?> expectedParameterType) {
        assertThat(Arrays.stream(BuyerCashierPort.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .map(Method::getParameterTypes)
                .map(parameterTypes -> parameterTypes.length == 1 ? parameterTypes[0] : null))
                .contains(expectedParameterType);
    }

    private static void assertRecordComponent(Class<?> recordType, String componentName, Class<?> componentType) {
        assertThat(recordType).as("record type for component %s", componentName).isNotNull();
        Map<String, Class<?>> components = Arrays.stream(recordType.getRecordComponents())
                .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType));
        assertThat(components).containsEntry(componentName, componentType);
    }
}
