package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.application.AcceptanceFailureInjector;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentCallbackProperties;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.*;
import com.aifuli.module.supplychain.border.fulfillment.application.singlestep.SingleStepCheckpointCodec;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint.*;
import com.aifuli.module.supplychain.infrastructure.security.AdapterTypeAllowList;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorCredentialBindingValidator;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorValidationService;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorValidationRecordService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/** 真实MySQL原单材料门禁；TEST_SINGLE_STEP仅测试启用，不注册三家生产协议或执行HTTP。 */
class SingleStepFrozenConfigurationIntegrationTest {
    static final String TYPE = "TEST_SINGLE_STEP";
    private final FulfillmentAcceptanceIntegrationTest fixture = new FulfillmentAcceptanceIntegrationTest();
    ConfigurableApplicationContext context;
    JdbcTemplate jdbc;
    private PublishedConnectorConfigurationResolver resolver;
    private long connectorVersionId;
    private long offerVersionId;

    @BeforeEach void start() { startWith(); }

    void startWith(Class<?>... additions) {
        fixture.startContainer();
        context = fixture.applicationContext(java.util.stream.Stream.concat(java.util.stream.Stream.of(Config.class),
                java.util.Arrays.stream(additions)).toArray(Class<?>[]::new));
        fixture.seed(context);
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        context.getBean(FulfillmentCallbackProperties.class).setPublicCallbackBaseUrl("https://platform.invalid");
        price(1000);
        jdbc.update("UPDATE sc_supplier_connector SET adapter_type=?", TYPE);
        jdbc.update("UPDATE sc_supplier_connector_version SET connect_timeout_ms=10000,read_timeout_ms=20000");
        jdbc.update("UPDATE sc_credential_slot SET active_secret_version='01'");
        jdbc.update("UPDATE sc_supplier_offer_version SET supplier_settlement_unit_price=884,min_quantity=1,max_quantity=10,quantity_step=3,create_idempotency_mode='NONE',result_multiplicity='SINGLE'");
        connectorVersionId = jdbc.queryForObject("SELECT id FROM sc_supplier_connector_version", Long.class);
        offerVersionId = jdbc.queryForObject("SELECT id FROM sc_supplier_offer_version", Long.class);
        var mappings = context.getBean(StatusMappingDefinitionMapper.class);
        int priority = 1;
        for (var template : new TestMetadata().mappingTemplates().statusMappings()) {
            mappings.insert(StatusMappingDefinitionDO.builder().connectorVersionId(connectorVersionId).mappingVersion(1)
                    .externalStatus(template.externalStatus()).externalSubStatus(template.externalSubStatus())
                    .businessPhase(template.businessPhase()).factType(template.factType()).terminalFlag((short) (template.terminal() ? 1 : 0))
                    .priority(priority++).status((short) 10).publishedAt(LocalDateTime.now()).createdBy(1L).createdAt(LocalDateTime.now()).build());
        }
        resolver = context.getBean(PublishedConnectorConfigurationResolver.class);
    }

    @AfterEach void stop() { if (context != null) context.close(); fixture.stopContainer(); }

    /** 保留隔离MySQL，销毁所有Spring单例及连接池后重开；不重做种子或持有旧Ticket。 */
    void reopenWith(Class<?>... additions) {
        context.close();
        context = fixture.applicationContext(java.util.stream.Stream.concat(java.util.stream.Stream.of(Config.class),
                java.util.Arrays.stream(additions)).toArray(Class<?>[]::new));
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
    }

    private void price(long amount) {
        com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService prices = org.springframework.test.util.AopTestUtils
                .getUltimateTargetObject(context.getBean(com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService.class));
        org.mockito.Mockito.when(prices.requireForProject(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            var quote = (com.aifuli.module.supplychain.tradecore.catalog.pricing.ProjectQuoteKeyContext) invocation.getArgument(0);
            return new com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService.FrozenQuote(
                    1L, 1L, 3L, amount, amount, amount * quote.quantity(), 0L, amount * quote.quantity(),
                    "{\"taxIncluded\":false,\"taxRateCode\":\"NON_TAXABLE\",\"taxRateLabel\":\"不征税\"}", false, null);
        });
    }

    @Test void acceptanceCommitsCheckpointReservationWatchdogAndReceiptAsOneUnit() {
        var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
        var request = FulfillmentAcceptanceIntegrationTest.request("SINGLE-ACCEPT");
        assertThat(facade.acceptFulfillment(request).getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
        assertThat(facade.acceptFulfillment(request).getIdempotentReplay()).isTrue();
        var operation = context.getBean(FulfillmentOperationMapper.class).selectList(null).getFirst();
        assertThat(operation.getCheckpointSchemaVersion()).isEqualTo(1);
        assertThat(operation.getCheckpointKeyVersion()).isEqualTo(context.getEnvironment().getProperty("aifuli.supply-chain.security.key-version"));
        assertThat(operation.getCheckpointCiphertext()).isNotEmpty();
        assertThat(operation.getExecutionGeneration()).isZero();
        assertThat(operation.getRecoveryCount()).isZero();
        assertThat(operation.getRecoveryDeadlineAt()).isNull();
        assertThat(operation.getExecutionLeaseUntil()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_b_order", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT supplier_settlement_unit_price FROM sc_b_order", Long.class)).isEqualTo(884);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fund_reservation", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(1000);
        assertThat(jdbc.queryForList("SELECT DISTINCT entry_type FROM sc_account_ledger WHERE entry_type IN ('FREEZE','COMMIT','RELEASE')", String.class)).containsExactly("FREEZE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE task_type='SINGLE_STEP_PREPARATION_WATCHDOG'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE task_type='FULFILLMENT_OPERATION_EXECUTE'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event WHERE event_type='FULFILLMENT_OPERATION_READY'", Long.class)).isEqualTo(1);
    }

    @Test void everyAcceptanceFailureRollsBackBusinessRowsFundsAndTasksButMayKeepProcessingClaim() {
        var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
        var failures = context.getBean(FulfillmentAcceptanceIntegrationTest.MutableFailureInjector.class);
        long outboxBefore = jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event", Long.class);
        for (var stage : AcceptanceFailureInjector.Stage.values()) {
            failures.failAt(stage);
            assertThatThrownBy(() -> facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("SINGLE-ROLLBACK-" + stage)))
                    .isInstanceOf(FulfillmentAcceptanceIntegrationTest.InjectedFailure.class);
            for (String table : List.of("sc_supply_chain_order", "sc_fulfillment_request", "sc_b_order", "sc_fulfillment_operation", "sc_fund_reservation", "sc_timer_task")) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).as(table + ":" + stage).isZero();
            }
            assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event", Long.class)).isEqualTo(outboxBefore);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_acceptance_receipt WHERE status<>0", Long.class)).isZero();
        }
        failures.clear();
    }

    @Test void publishedAndRetiredOriginalContentRemainsReadableWithoutOpeningNewOrderAdmission() {
        var checkpoint = freeze();
        assertThat(resolver.resolveForExistingOrder(checkpoint).offer().getSupplierSettlementUnitPrice()).isEqualTo(884);
        context.getBean(ConnectorPublicationService.class).retire(new ConnectorPublicationService.RetireConnectorVersionCommand(
                "CON_QNM", connectorVersionId, 1L, "隔离测试退役", "test", null, null));
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM sc_status_mapping_definition", Short.class)).containsExactly((short) 90);
        assertThatThrownBy(() -> resolver.resolve(connectorVersionId)).isInstanceOf(IllegalStateException.class).hasMessageContaining("not published");
        assertThat(resolver.resolveForExistingOrder(checkpoint).connector().baseUrl()).isEqualTo("https://qnm.invalid");
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM sc_supplier_connector", Long.class)).isNull();
        jdbc.update("UPDATE sc_supplier_connector_version SET status=0 WHERE id=?", connectorVersionId);
        assertThatThrownBy(() -> resolver.resolveForExistingOrder(checkpoint)).isInstanceOf(IllegalStateException.class);
    }

    @Test void sameVersionContentDriftAndOriginalCredentialRotationFailClosed() {
        var checkpoint = freeze();
        for (String[] mutation : List.of(
                new String[]{"sc_supplier_connector_version", "base_url", "https://changed.invalid", "https://qnm.invalid"},
                new String[]{"sc_supplier_connector_version", "merchant_account", "other-merchant", "fixture"},
                new String[]{"sc_supplier_connector_version", "callback_path", "/changed", "/callback"},
                new String[]{"sc_supplier_offer_version", "external_sku_code", "SKU-other", "QNM-TELCO-100"},
                new String[]{"sc_supplier_offer_version", "supplier_settlement_unit_price", "999", "884"},
                new String[]{"sc_status_mapping_definition", "priority", "91", "1"},
                new String[]{"sc_credential_slot", "active_secret_version", "1", "01"})) {
            long id = jdbc.queryForObject("SELECT MIN(id) FROM " + mutation[0], Long.class);
            jdbc.update("UPDATE " + mutation[0] + " SET " + mutation[1] + "=? WHERE id=?", mutation[2], id);
            assertThatThrownBy(() -> resolver.resolveForExistingOrder(checkpoint)).as(mutation[1]).isInstanceOf(RuntimeException.class);
            jdbc.update("UPDATE " + mutation[0] + " SET " + mutation[1] + "=? WHERE id=?", mutation[3], id);
            assertThat(resolver.resolveForExistingOrder(checkpoint).connector().connectorVersionId()).isEqualTo(connectorVersionId);
        }
        jdbc.update("UPDATE sc_credential_slot SET status=20");
        assertThatThrownBy(() -> resolver.resolveForExistingOrder(checkpoint)).isInstanceOf(RuntimeException.class);
    }

    @Test void persistedOriginalBuildsCreateAndQueryTicketsWithFrozenMobileCallbackAndMoney() throws Exception {
        context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("TICKET-original"));
        String billno = jdbc.queryForObject("SELECT billno FROM sc_b_order", String.class);
        context.getBean(FulfillmentCallbackProperties.class).setPublicCallbackBaseUrl("https://new-platform.invalid");
        for (var kind : List.of(com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind.FULFILL,
                com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind.QUERY_ORDER)) {
            var original = original();
            try (var ticket = ticket(original, kind)) {
                assertThat(ticket.billno()).isEqualTo(billno);
                assertThat(ticket.rechargeAccount()).isEqualTo("13800138000");
                assertThat(ticket.callbackUrl()).isEqualTo("https://platform.invalid/callback");
                assertThat(ticket.externalSkuCode()).isEqualTo("QNM-TELCO-100");
                assertThat(ticket.settlementUnitPriceMinorUnits()).isEqualTo(884);
                assertThat(ticket.currency()).isEqualTo("CNY");
                assertThat(ticket.operationKind()).isEqualTo(kind);
                assertThat(ticket.adapterStepCode()).isEqualTo(kind == com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind.QUERY_ORDER
                        ? AdapterStepCode.QUERY_ORDER : AdapterStepCode.SEND);
                assertThat(ticket.connectTimeoutMs()).isEqualTo(10000);
                assertThat(ticket.readTimeoutMs()).isEqualTo(20000);
                var plaintext = ticket.checkpointPayload();
                var json = new ObjectMapper().readTree(plaintext);
                assertThat(json.path("binding").path("adapterStepCode").textValue()).isEqualTo("SEND");
                assertThat(json.path("recoveryContract").path("totalCallTimeoutMs").intValue()).isEqualTo(30000);
                assertThat(json.path("recoveryContract").path("executionLeaseMs").intValue()).isEqualTo(60000);
                java.util.Arrays.fill(plaintext, (byte) 0);
                assertThat(ticket.secretKey()).isNull();
                assertThat(ticket.credentialMaterial()).isNull();
                ticket.resolvedCredentialMaterial().use("merchantKey", chars -> {
                    assertThat(new String(chars)).isEqualTo("test-secret"); return null;
                });
            }
        }
    }

    @Test void reopeningApplicationAfterPublishingNewVersionsAndRetiringOriginalRebuildsOriginalOnly() {
        var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
        assertThat(facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("RESTART-original")).getReceiptStatus())
                .isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
        var before = original();
        String billno = before.bOrder().getBillno();
        byte[] ciphertext = before.operation().getCheckpointCiphertext().clone();
        long oldSlot = jdbc.queryForObject("SELECT id FROM sc_credential_slot", Long.class);
        long newSlot = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc, "sc_credential_slot", oldSlot,
                java.util.Map.of("slot_no", "CRD_NEW", "active_secret_version", "02"));
        long nextConnector = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc, "sc_supplier_connector_version", connectorVersionId,
                java.util.Map.of("version_no", 2, "status", 0, "base_url", "https://new-supplier.invalid", "merchant_account", "new-merchant",
                        "callback_path", "/new-callback", "credential_slot_id", newSlot));
        for (long mapping : jdbc.queryForList("SELECT id FROM sc_status_mapping_definition WHERE connector_version_id=?", Long.class, connectorVersionId)) {
            RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc, "sc_status_mapping_definition", mapping,
                    java.util.Map.of("connector_version_id", nextConnector, "status", 0));
        }
        context.getBean(ConnectorValidationService.class).validate(new ConnectorValidationService.ValidateConnectorCommand(nextConnector,
                Set.of("CREATE_RECHARGE", "QUERY_ORDER", "RECEIVE_CALLBACK"), java.time.Duration.ofMinutes(30), 1L, "隔离测试新版本", "test"));
        long connectorAggregateVersion = jdbc.queryForObject("SELECT version FROM sc_supplier_connector", Long.class);
        context.getBean(ConnectorPublicationService.class).publish(new ConnectorPublicationService.PublishConnectorCommand(
                "CON_QNM", nextConnector, connectorAggregateVersion, 1L, "隔离测试新版本", "test", null, null));
        var offer = context.getBean(SupplierOfferMapper.class).selectList(null).getFirst();
        long nextOffer = context.getBean(SupplierOfferPublicationService.class).publish(new SupplierOfferPublicationService.PublishOfferCommand(
                offer.getOfferNo(), offer.getVersion(), "SKU-NEW", 999, nextConnector, "INV_QNM", 1, 10, 3,
                "CREATE_RECHARGE", "QUERY_OR_CALLBACK", "NONE", "QUERY_ORDER", "FORBIDDEN", "SINGLE", "{}",
                LocalDateTime.now().minusMinutes(1), null, "隔离测试调价", 1L, "test", null, null)).offerVersionId();
        context.getBean(ConnectorPublicationService.class).retire(new ConnectorPublicationService.RetireConnectorVersionCommand(
                "CON_QNM", connectorVersionId, 1L, "隔离测试退役原版本", "test", null, null));
        // 真正关闭旧Spring容器及连接池；保留同一个隔离MySQL实例，绝不复用旧Ticket/Resolver。
        context.close();
        context = fixture.applicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        resolver = context.getBean(PublishedConnectorConfigurationResolver.class);
        context.getBean(FulfillmentCallbackProperties.class).setPublicCallbackBaseUrl("https://new-platform.invalid");
        assertThat(resolver.resolve(nextConnector).baseUrl()).isEqualTo("https://new-supplier.invalid");
        assertThatThrownBy(() -> resolver.resolve(connectorVersionId)).isInstanceOf(IllegalStateException.class);
        var reopened = original();
        assertThat(reopened.operation()).isNotSameAs(before.operation());
        assertThat(reopened.operation().getCheckpointCiphertext()).isEqualTo(ciphertext);
        try (var ticket = ticket(reopened, com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind.QUERY_ORDER)) {
            assertThat(ticket.billno()).isEqualTo(billno);
            assertThat(ticket.rechargeAccount()).isEqualTo("13800138000");
            assertThat(ticket.baseUrl()).isEqualTo("https://qnm.invalid");
            assertThat(ticket.merchantAccount()).isEqualTo("fixture");
            assertThat(ticket.callbackUrl()).isEqualTo("https://platform.invalid/callback");
            assertThat(ticket.externalSkuCode()).isEqualTo("QNM-TELCO-100");
            assertThat(ticket.settlementUnitPriceMinorUnits()).isEqualTo(884);
        }
        assertThat(reopened.order().getBuyerSettlementAmount()).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT supplier_settlement_unit_price FROM sc_supplier_offer_version WHERE id=?", Long.class, nextOffer)).isEqualTo(999);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM sc_supplier_connector", Long.class)).isEqualTo(nextConnector);
        price(1200);
        assertThat(context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("RESTART-new")).getReceiptStatus())
                .isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT buyer_settlement_amount FROM sc_supply_chain_order WHERE buyer_order_no='RESTART-new'", Long.class)).isEqualTo(1200);
        assertThat(jdbc.queryForObject("SELECT supplier_settlement_unit_price FROM sc_b_order WHERE billno<>?", Long.class, billno)).isEqualTo(999);
        assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(2200);
        // 验证恢复没有借用新槽：即使旧槽停用、新槽仍可用，也必须拒绝而不是换密钥重试。
        jdbc.update("UPDATE sc_credential_slot SET status=20 WHERE id=?", oldSlot);
        assertThatThrownBy(() -> ticket(original(), com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind.QUERY_ORDER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_operation", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type IN ('COMMIT','RELEASE')", Long.class)).isZero();
    }

    @Test void concurrentReplayConflictsAndOutOfScopeTargetsCannotCreateExtraReservation() throws Exception {
        var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                start.await();
                assertThat(facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("SINGLE-CONCURRENT")).getReceiptStatus())
                        .isIn(FulfillmentReceiptStatus.PROCESSING, FulfillmentReceiptStatus.ACCEPTED);
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get();
        }
        var conflict = FulfillmentAcceptanceIntegrationTest.request("SINGLE-CONCURRENT");
        conflict.getTarget().setValues(java.util.Map.of("accountType", "MOBILE", "accountValue", "13700137000"));
        assertThatThrownBy(() -> facade.acceptFulfillment(conflict)).isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class);
        var external = FulfillmentAcceptanceIntegrationTest.request("SINGLE-EXTERNAL");
        external.getTarget().setValues(java.util.Map.of("accountType", "EXTERNAL_ACCOUNT", "accountValue", "123456789"));
        assertThatThrownBy(() -> facade.acceptFulfillment(external)).isInstanceOf(IllegalStateException.class).hasMessageContaining("手机号直充");
        var quantity = FulfillmentAcceptanceIntegrationTest.request("SINGLE-QUANTITY"); quantity.setQuantity(2L);
        assertThat(facade.acceptFulfillment(quantity).getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_FINAL);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_operation", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fund_reservation", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE task_type='SINGLE_STEP_PREPARATION_WATCHDOG'", Long.class)).isEqualTo(1);
    }

    @Test void transplantedOrDamagedOriginalAndAggregateTransactionCannotAssembleTicket() {
        context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("TICKET-invalid"));
        List<java.util.function.Consumer<Original>> mutations = List.of(
                row -> row.supplier().setId(row.supplier().getId() + 1),
                row -> row.operation().setOperationNo("OP-transplanted"),
                row -> row.operation().setBOrderId(row.operation().getBOrderId() + 1),
                row -> row.bOrder().setOfferVersionId(row.bOrder().getOfferVersionId() + 1),
                row -> row.bOrder().setBillno("202610170000000000000099"),
                row -> row.bOrder().setSupplierSettlementUnitPrice(999L),
                row -> row.request().setRequestNo("FR-transplanted"),
                row -> row.request().getTargetCiphertext()[0] ^= 1,
                row -> row.request().getTargetDigest()[0] ^= 1,
                row -> row.operation().getCheckpointCiphertext()[0] ^= 1);
        for (var mutate : mutations) {
            var row = original(); mutate.accept(row);
            assertThatThrownBy(() -> ticket(row, com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind.FULFILL))
                    .isInstanceOf(RuntimeException.class);
        }
        var transaction = new org.springframework.transaction.support.TransactionTemplate(context.getBean("supplyChainTransactionManager",
                org.springframework.transaction.PlatformTransactionManager.class));
        assertThatThrownBy(() -> transaction.execute(status -> ticket(original(), com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind.FULFILL)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("事务");
        assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(1000);
        assertThat(context.getBean(RechargeFrequencyExecutionIntegrationTest.CountingExecutor.class).calls.get()).isZero();
    }

    private Original original() {
        var operation = context.getBean(FulfillmentOperationMapper.class).selectById(
                jdbc.queryForObject("SELECT MIN(id) FROM sc_fulfillment_operation", Long.class));
        var bOrder = context.getBean(BOrderMapper.class).selectById(operation.getBOrderId());
        return new Original(operation, bOrder, context.getBean(FulfillmentRequestMapper.class).selectById(bOrder.getFulfillmentRequestId()),
                context.getBean(com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper.class).selectById(bOrder.getOrderId()),
                context.getBean(SupplierMapper.class).selectById(bOrder.getSupplierId()));
    }

    private com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket ticket(Original row,
            com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind kind) {
        return context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationTicketFactory.class)
                .createForSingleStep(row.operation(), row.bOrder(), row.request(), row.order(), row.supplier(), kind, "test");
    }

    private record Original(com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO operation,
                            com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO bOrder,
                            com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO request,
                            com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO order, SupplierDO supplier) {}

    private SingleStepCheckpoint freeze() {
        var frozen = resolver.resolveForSingleStepAcceptance(connectorVersionId, offerVersionId);
        var business = new Business("202610170000000000000001", frozen.offer().getExternalSkuCode(), 1,
                "DIRECT_RECHARGE_TARGET", new TargetReference(11, "FR-original", 1, "a".repeat(64), "test-v1"),
                "https://platform.invalid/callback", 884, "CNY");
        var recovery = RecoveryContract.initial(180);
        long supplierId = jdbc.queryForObject("SELECT supplier_id FROM sc_supplier_connector", Long.class);
        String digest = SingleStepCheckpointCodec.contentDigest(frozen.content(), business, frozen.credentials(), recovery);
        return new SingleStepCheckpoint(1, new Binding("OP-original", 13, TYPE, AdapterStepCode.SEND, supplierId,
                connectorVersionId, offerVersionId, digest), business, frozen.credentials(), recovery);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ConnectorCredentialBindingValidator.class, ConnectorPublicationService.class, ConnectorValidationService.class,
            ConnectorValidationRecordService.class, SupplierOfferPublicationService.class, SingleStepCheckpointCodec.class,
            com.aifuli.module.supplychain.border.fulfillment.application.singlestep.SingleStepFulfillmentConfiguration.class,
            com.aifuli.module.supplychain.border.fulfillment.application.singlestep.SingleStepFulfillmentLifecycle.class,
            com.aifuli.module.supplychain.border.fulfillment.application.singlestep.SingleStepCallbackInbox.class,
            com.aifuli.module.supplychain.border.fulfillment.application.singlestep.SingleStepFulfillmentRunner.class,
            com.aifuli.module.supplychain.border.fulfillment.application.singlestep.SingleStepCapacityWaitService.class,
            com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaService.class,
            com.aifuli.module.supplychain.infrastructure.security.FrozenCredentialSlotResolver.class})
    static class Config {
        @Bean AdapterMetadataRegistry testRegistry() { return new AdapterMetadataRegistry(List.of(new QnmGameOrderV1Metadata(), new TestMetadata())); }
        @Bean AdapterTypeAllowList testAllowList() { return new AdapterTypeAllowList(new MockEnvironment().withProperty(
                "aifuli.supply-chain.connector.allowed-adapter-types", TYPE + "," + QnmGameOrderV1Metadata.TYPE)); }
        @Bean com.aifuli.module.supplychain.infrastructure.security.SupplierEndpointPolicy testEndpointPolicy() {
            return new com.aifuli.module.supplychain.infrastructure.security.SupplierEndpointPolicy(new MockEnvironment().withProperty(
                    "aifuli.supply-chain.connector.allowed-hosts", "qnm.invalid,new-supplier.invalid"));
        }
        @Bean @Primary PublishedConnectorConfigurationResolver frozenResolver(SupplierConnectorVersionMapper versions,
                SupplierConnectorMapper connectors, SupplierConnectorCapabilityMapper capabilities,
                StatusMappingDefinitionMapper statuses, SupplierErrorCodeMapMapper errors, AdapterMetadataRegistry registry,
                ClosedJsonSchemaValidator schema, ObjectMapper json, ConnectorCredentialBindingValidator credentials,
                SupplierOfferVersionMapper offers, SupplierOfferMapper identities, CredentialSlotMapper slots) {
            return new PublishedConnectorConfigurationResolver(versions, connectors, capabilities, statuses, errors, registry,
                    schema, json, credentials, offers, identities, slots);
        }
    }

    static class TestMetadata implements AdapterMetadata {
        private final QnmGameOrderV1Metadata delegate = new QnmGameOrderV1Metadata();
        int firstQueryDelaySeconds = 180;
        public String adapterType() { return TYPE; }
        public RecoveryContract singleStepRecoveryContract() { return RecoveryContract.initial(firstQueryDelaySeconds); }
        public Set<Capability> capabilities() { return Set.of(Capability.CREATE_RECHARGE, Capability.QUERY_ORDER, Capability.RECEIVE_CALLBACK); }
        public Set<String> credentialKeys() { return Set.of("merchantKey"); }
        public String connectorSchemaResource() { return delegate.connectorSchemaResource(); }
        public String offerSchemaResource() { return delegate.offerSchemaResource(); }
        public int connectorSchemaVersion() { return 1; }
        public int offerSchemaVersion() { return 1; }
        public void validateConnectorConfig(int version, JsonNode config) { delegate.validateConnectorConfig(version, config); }
        public void validateOfferConfig(int version, JsonNode config) { delegate.validateOfferConfig(version, config); }
        public ExecutionProfileDefaults defaultExecutionProfile() { return delegate.defaultExecutionProfile(); }
        public MappingTemplates mappingTemplates() { return delegate.mappingTemplates(); }
    }
}
