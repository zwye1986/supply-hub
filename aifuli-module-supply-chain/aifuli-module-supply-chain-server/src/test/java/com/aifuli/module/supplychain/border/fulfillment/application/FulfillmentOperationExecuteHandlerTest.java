package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationExecutorPort;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.infrastructure.security.CredentialProvider;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import com.aifuli.module.supplychain.supplieradapter.metadata.Capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 履约执行器 Timer 任务处理器单测。
 */
@ExtendWith(MockitoExtension.class)
class FulfillmentOperationExecuteHandlerTest {

    @Mock
    private FulfillmentOperationMapper operationMapper;
    @Mock
    private BOrderMapper bOrderMapper;
    @Mock
    private FulfillmentRequestMapper requestMapper;
    @Mock
    private SupplyChainOrderMapper orderMapper;
    @Mock
    private SupplierMapper supplierMapper;
    @Mock
    private SupplierOfferVersionMapper offerVersionMapper;
    @Mock
    private CredentialSlotMapper credentialSlotMapper;
    @Mock
    private PublishedConnectorConfigurationResolver connectorResolver;
    @Mock
    private CredentialProvider credentialProvider;
    @Mock
    private EnvelopeCryptoPort envelopeCrypto;
    @Mock
    private OperationExecutorPort operationExecutorPort;
    @Mock
    private SupplierOrderLifecycle supplierOrderLifecycle;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FulfillmentOperationExecuteHandler executeHandler;
    private FulfillmentCallbackProperties callbackProperties;

    @BeforeEach
    void setUp() {
        callbackProperties = new FulfillmentCallbackProperties();
        callbackProperties.setPublicCallbackBaseUrl("https://api.aifuli.com");
        executeHandler = new FulfillmentOperationExecuteHandler(
                operationMapper,
                bOrderMapper,
                requestMapper,
                orderMapper,
                supplierMapper,
                offerVersionMapper,
                credentialSlotMapper,
                connectorResolver,
                List.of(credentialProvider),
                envelopeCrypto,
                operationExecutorPort,
                supplierOrderLifecycle,
                objectMapper,
                callbackProperties
        );
    }

    @Test
    void taskTypeMatchesFulfillmentTimerTaskTypes() {
        assertThat(executeHandler.taskType()).isEqualTo(FulfillmentTimerTaskTypes.OPERATION_EXECUTE);
        assertThat(executeHandler.maxAttempts()).isEqualTo(3);
    }

    @Test
    void returnsMissingOperationNoWhenPayloadEmpty() {
        TimerTaskContext context = new TimerTaskContext("TASK_01", 1L, 1, 100L, "worker-1");
        JsonNode emptyPayload = objectMapper.createObjectNode();

        String result = executeHandler.handle(context, emptyPayload);
        assertThat(result).isEqualTo("MISSING_OPERATION_NO");
    }

    @Test
    void returnsNotFoundWhenOperationMissing() {
        TimerTaskContext context = new TimerTaskContext("TASK_02", 2L, 1, 100L, "worker-1");
        JsonNode payload = objectMapper.createObjectNode().put("operationNo", "NON_EXISTENT");
        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        String result = executeHandler.handle(context, payload);
        assertThat(result).isEqualTo("NOT_FOUND");
    }

    @Test
    void returnsAlreadyCompletedWhenOperationIsTerminal() {
        TimerTaskContext context = new TimerTaskContext("TASK_03", 3L, 1, 100L, "worker-1");
        JsonNode payload = objectMapper.createObjectNode().put("operationNo", "OP_DONE");
        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .operationNo("OP_DONE")
                .status((short) 40)
                .build();
        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);

        String result = executeHandler.handle(context, payload);
        assertThat(result).isEqualTo("ALREADY_COMPLETED");
        verify(operationExecutorPort, never()).execute(any());
    }

    @Test
    void happyPathAssemblesTicketAndInvokesExecutorAndLifecycle() {
        String operationNo = "OP_EXEC_01";
        TimerTaskContext context = new TimerTaskContext("TASK_04", 4L, 1, 100L, "worker-1");
        JsonNode payload = objectMapper.createObjectNode().put("operationNo", operationNo);

        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .id(1L)
                .operationNo(operationNo)
                .bOrderId(2L)
                .operationKind(FulfillmentOperationKind.FULFILL.name())
                .adapterStepCode(AdapterStepCode.SEND.name())
                .sequenceNo(1)
                .idempotencyKey("IDEM_01")
                .status((short) 10)
                .build();

        BOrderDO bOrder = BOrderDO.builder()
                .id(2L)
                .bOrderNo("BO_EXEC_01")
                .billno("BILL_01")
                .externalOrderNo("EXT_BO_01")
                .fulfillmentRequestId(3L)
                .orderId(4L)
                .supplierId(5L)
                .connectorVersionId(6L)
                .offerVersionId(7L)
                .requestedQuantity(1L)
                .build();

        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .id(3L)
                .requestNo("FR_EXEC_01")
                .buyerId(8L)
                .targetSchemaCode("DIRECT_RECHARGE_TARGET")
                .targetSchemaVersion(1)
                .targetCiphertext(new byte[]{10, 20})
                .targetKeyVersion("1")
                .build();

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(4L)
                .orderNo("SC_EXEC_01")
                .buyerOrderNo("BUYER_ORDER_01")
                .build();

        SupplierDO supplier = SupplierDO.builder()
                .id(5L)
                .supplierCode("QNM")
                .build();

        SupplierOfferVersionDO offerVersion = SupplierOfferVersionDO.builder()
                .id(7L)
                .externalSkuCode("QNM_SKU_001")
                .build();

        PublishedConnectorConfiguration connectorConfig = new PublishedConnectorConfiguration(
                "CONN_01", 6L, 1, "QNM", "https://supplier.example.com", "merchant123", 9L,
                "/qnm/callback",
                Set.of(Capability.CREATE_RECHARGE));

        CredentialSlotDO slot = CredentialSlotDO.builder()
                .id(9L)
                .providerType("CONFIG_PROPERTIES")
                .status((short) 10)
                .build();

        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);
        when(bOrderMapper.selectById(2L)).thenReturn(bOrder);
        when(requestMapper.selectById(3L)).thenReturn(request);
        when(orderMapper.selectById(4L)).thenReturn(order);
        when(supplierMapper.selectById(5L)).thenReturn(supplier);
        when(offerVersionMapper.selectById(7L)).thenReturn(offerVersion);
        when(connectorResolver.resolve(6L)).thenReturn(connectorConfig);
        when(credentialSlotMapper.selectById(9L)).thenReturn(slot);
        when(credentialProvider.supports("CONFIG_PROPERTIES")).thenReturn(true);
        when(credentialProvider.resolve(slot)).thenReturn("secret_key_123".toCharArray());

        // Target ciphertext decrypt mock
        String targetJson = "{\"schemaCode\":\"DIRECT_RECHARGE_TARGET\",\"schemaVersion\":1,\"values\":{\"accountValue\":\"13812345678\"}}";
        when(envelopeCrypto.decrypt(eq(request.getTargetCiphertext()), eq("1"), any()))
                .thenReturn(targetJson.getBytes(StandardCharsets.UTF_8));

        OperationOutcome successOutcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.SUCCEEDED)
                .externalOrderNo("EXT_QNM_999")
                .build();
        when(supplierOrderLifecycle.claimForExecution(operationNo)).thenReturn(true);
        when(operationExecutorPort.execute(any(OperationTicket.class))).thenReturn(successOutcome);

        String result = executeHandler.handle(context, payload);

        assertThat(result).isEqualTo("SUCCEEDED");

        // Verify ticket passed to executor
        ArgumentCaptor<OperationTicket> ticketCaptor = ArgumentCaptor.forClass(OperationTicket.class);
        verify(operationExecutorPort).execute(ticketCaptor.capture());
        OperationTicket ticket = ticketCaptor.getValue();
        assertThat(ticket.bOrderNo()).isEqualTo("BO_EXEC_01");
        assertThat(ticket.supplierCode()).isEqualTo("QNM");
        assertThat(ticket.adapterType()).isEqualTo("QNM");
        assertThat(ticket.externalSkuCode()).isEqualTo("QNM_SKU_001");
        assertThat(ticket.rechargeAccount()).isEqualTo("13812345678");
        assertThat(ticket.merchantAccount()).isEqualTo("merchant123");
        assertThat(ticket.secretKey()).isEqualTo("secret_key_123");
        assertThat(ticket.timeoutSeconds()).isEqualTo(10);
        assertThat(ticket.callbackUrl()).isEqualTo("https://api.aifuli.com/qnm/callback");

        org.mockito.InOrder inOrder = inOrder(supplierOrderLifecycle, operationExecutorPort);
        inOrder.verify(supplierOrderLifecycle).claimForExecution(operationNo);
        inOrder.verify(operationExecutorPort).execute(any(OperationTicket.class));
        verify(supplierOrderLifecycle).transition(eq(operationNo), eq(successOutcome), eq("4"));
    }

    @Test
    void executorExceptionConvertsToResultUnknown() {
        String operationNo = "OP_EXEC_02";
        TimerTaskContext context = new TimerTaskContext("TASK_05", 5L, 1, 100L, "worker-1");
        JsonNode payload = objectMapper.createObjectNode().put("operationNo", operationNo);

        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .id(11L)
                .operationNo(operationNo)
                .bOrderId(21L)
                .operationKind(FulfillmentOperationKind.FULFILL.name())
                .adapterStepCode(AdapterStepCode.SEND_KM.name())
                .sequenceNo(1)
                .idempotencyKey("IDEM_02")
                .status((short) 10)
                .build();

        BOrderDO bOrder = BOrderDO.builder()
                .id(21L)
                .bOrderNo("BO_EXEC_02")
                .billno("BILL_02")
                .externalOrderNo("EXT_BO_02")
                .fulfillmentRequestId(31L)
                .orderId(41L)
                .supplierId(51L)
                .connectorVersionId(61L)
                .requestedQuantity(1L)
                .build();

        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .id(31L)
                .requestNo("FR_EXEC_02")
                .build();

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(41L)
                .orderNo("SC_EXEC_02")
                .build();

        SupplierDO supplier = SupplierDO.builder()
                .id(51L)
                .supplierCode("QNM")
                .build();

        PublishedConnectorConfiguration connectorConfig = new PublishedConnectorConfiguration(
                "CONN_02", 61L, 1, "QNM", "https://supplier.example.com", "merchant123", null,
                "/qnm/callback",
                Set.of(Capability.CREATE_RECHARGE));

        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);
        when(bOrderMapper.selectById(21L)).thenReturn(bOrder);
        when(requestMapper.selectById(31L)).thenReturn(request);
        when(orderMapper.selectById(41L)).thenReturn(order);
        when(supplierMapper.selectById(51L)).thenReturn(supplier);
        when(connectorResolver.resolve(61L)).thenReturn(connectorConfig);
        when(supplierOrderLifecycle.claimForExecution(operationNo)).thenReturn(true);

        when(operationExecutorPort.execute(any(OperationTicket.class)))
                .thenThrow(new RuntimeException("Network timeout"));

        String result = executeHandler.handle(context, payload);

        assertThat(result).isEqualTo("RESULT_UNKNOWN");

        ArgumentCaptor<OperationOutcome> outcomeCaptor = ArgumentCaptor.forClass(OperationOutcome.class);
        verify(supplierOrderLifecycle).transition(eq(operationNo), outcomeCaptor.capture(), eq("5"));
        assertThat(outcomeCaptor.getValue().outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
        assertThat(outcomeCaptor.getValue().externalErrorMessage()).contains("Network timeout");
    }

    @Test
    void decryptFailureDoesNotCallExecutorAndTransitionsToManual() {
        String operationNo = "OP_EXEC_DECRYPT";
        TimerTaskContext context = new TimerTaskContext("TASK_06", 6L, 1, 100L, "worker-1");
        JsonNode payload = objectMapper.createObjectNode().put("operationNo", operationNo);

        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .id(12L)
                .operationNo(operationNo)
                .bOrderId(22L)
                .operationKind(FulfillmentOperationKind.FULFILL.name())
                .adapterStepCode(AdapterStepCode.SEND.name())
                .sequenceNo(1)
                .idempotencyKey("IDEM_D")
                .status((short) 10)
                .build();
        BOrderDO bOrder = BOrderDO.builder()
                .id(22L)
                .bOrderNo("BO_DEC")
                .billno("BILL_DEC")
                .fulfillmentRequestId(32L)
                .orderId(42L)
                .supplierId(52L)
                .connectorVersionId(62L)
                .offerVersionId(72L)
                .requestedQuantity(1L)
                .build();
        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .id(32L)
                .requestNo("FR_DEC")
                .buyerId(8L)
                .targetSchemaCode("DIRECT_RECHARGE_TARGET")
                .targetSchemaVersion(1)
                .targetCiphertext(new byte[]{1, 2, 3})
                .targetKeyVersion("1")
                .build();
        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(42L)
                .orderNo("SC_DEC")
                .buyerOrderNo("BUYER_DEC")
                .build();
        SupplierDO supplier = SupplierDO.builder().id(52L).supplierCode("QNM").build();
        PublishedConnectorConfiguration connectorConfig = new PublishedConnectorConfiguration(
                "CONN_DEC", 62L, 1, "QNM", "https://supplier.example.com", "merchant123", null,
                "/qnm/callback", Set.of(Capability.CREATE_RECHARGE));

        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);
        when(bOrderMapper.selectById(22L)).thenReturn(bOrder);
        when(requestMapper.selectById(32L)).thenReturn(request);
        when(orderMapper.selectById(42L)).thenReturn(order);
        when(supplierMapper.selectById(52L)).thenReturn(supplier);
        when(offerVersionMapper.selectById(72L)).thenReturn(null);
        when(connectorResolver.resolve(62L)).thenReturn(connectorConfig);
        when(envelopeCrypto.decrypt(any(), eq("1"), any())).thenThrow(new IllegalStateException("bad key"));

        String result = executeHandler.handle(context, payload);

        assertThat(result).isEqualTo("MANUAL_REQUIRED");
        verify(operationExecutorPort, never()).execute(any());
        verify(supplierOrderLifecycle, never()).claimForExecution(any());
        ArgumentCaptor<OperationOutcome> outcomeCaptor = ArgumentCaptor.forClass(OperationOutcome.class);
        verify(supplierOrderLifecycle).transition(eq(operationNo), outcomeCaptor.capture(), eq("6"));
        assertThat(outcomeCaptor.getValue().outcomeClass()).isEqualTo(OperationOutcomeClass.MANUAL_REQUIRED);
        assertThat(outcomeCaptor.getValue().externalErrorCode()).isEqualTo("TARGET_DECRYPT_FAILED");
    }

    @Test
    void skipsOutboundWhenAlreadyExecuting() {
        TimerTaskContext context = new TimerTaskContext("TASK_07", 7L, 1, 100L, "worker-1");
        JsonNode payload = objectMapper.createObjectNode().put("operationNo", "OP_EXECUTING");
        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(
                FulfillmentOperationDO.builder()
                        .operationNo("OP_EXECUTING")
                        .status((short) 20)
                        .build());

        String result = executeHandler.handle(context, payload);

        assertThat(result).isEqualTo("ALREADY_EXECUTING");
        verify(operationExecutorPort, never()).execute(any());
        verify(supplierOrderLifecycle, never()).claimForExecution(any());
    }

    @Test
    void skipsOutboundWhenResultUnknown() {
        TimerTaskContext context = new TimerTaskContext("TASK_08", 8L, 1, 100L, "worker-1");
        JsonNode payload = objectMapper.createObjectNode().put("operationNo", "OP_UNKNOWN");
        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(
                FulfillmentOperationDO.builder()
                        .operationNo("OP_UNKNOWN")
                        .status((short) 30)
                        .build());

        String result = executeHandler.handle(context, payload);

        assertThat(result).isEqualTo("RESULT_UNKNOWN");
        verify(operationExecutorPort, never()).execute(any());
    }
}
