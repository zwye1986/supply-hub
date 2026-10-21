package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import com.aifuli.module.supplychain.border.fulfillment.application.singlestep.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.*;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.supplieradapter.renyue.*;
import com.aifuli.module.supplychain.infrastructure.security.*;
import com.aifuli.module.supplychain.interfaceapi.callback.SingleStepSupplierCallbackController;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandlerRegistry;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaService;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.security.*;
import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static com.aifuli.module.supplychain.tradecore.supply.domain.MerchantQuotaPlatform.RENYUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 隔离MySQL+真实本机HTTP+MockMvc原始回调；仅测试Metadata，未注册生产供给。 */
class RenyueSingleStepIntegrationTest {
    private static final String KEY = "SYNTHETIC-RENYUE-MERCHANT-KEY";
    private static KeyPair keys;
    private final SingleStepFrozenConfigurationIntegrationTest fixture = new SingleStepFrozenConfigurationIntegrationTest();
    private MockWebServer server;
    private MockMvc mvc;
    @BeforeAll static void keys() throws Exception { var g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); keys = g.generateKeyPair(); }
    @BeforeEach void start() throws Exception {
        server = new MockWebServer(); server.start(); fixture.startWith(Config.class);
        fixture.jdbc.update("UPDATE sc_supplier_connector SET adapter_type=?", RenyueSupplierAdapter.TYPE);
        fixture.jdbc.update("UPDATE sc_supplier_connector_version SET base_url=?,callback_path='/callbacks/suppliers/renyue/{connectorVersionId}'", server.url("/").toString());
        long supplier = fixture.jdbc.queryForObject("SELECT supplier_id FROM sc_supplier_connector", Long.class);
        fixture.jdbc.update("INSERT INTO sc_credential_set(supplier_id,set_code,status,created_at,updated_at) VALUES (?,'RENYUE_TEST',10,NOW(3),NOW(3))", supplier);
        long set = fixture.jdbc.queryForObject("SELECT id FROM sc_credential_set", Long.class);
        var snapshot = new ObjectMapper().createArrayNode();
        for (String key : List.of("merchantKey", "privateKey")) {
            fixture.jdbc.update("INSERT INTO sc_credential_item(credential_set_id,item_key,provider_type,secret_ref,masked_hint,active_secret_version,status,created_at,updated_at) VALUES (?,?,'ENV','env:SYNTHETIC','***',1,10,NOW(3),NOW(3))", set, key);
            long id = fixture.jdbc.queryForObject("SELECT id FROM sc_credential_item WHERE item_key=?", Long.class, key);
            snapshot.addObject().put("itemId", id).put("itemKey", key).put("providerType", "ENV").put("activeSecretVersion", 1);
        }
        fixture.jdbc.update("UPDATE sc_supplier_connector_version SET credential_slot_id=NULL,credential_set_id=?,credential_item_snapshot=?", set, snapshot.toString());
        // 只为隔离商户显式配置测试额度，不读取Connector字段或播种生产默认值。
        fixture.context.getBean(com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaService.class)
                .configure(new com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaService.Configure(
                        com.aifuli.module.supplychain.tradecore.supply.domain.MerchantQuotaPlatform.RENYUE, "fixture", 100, 20, null, 1L, "仁跃本机HTTP隔离夹具"));
        mvc = mvc();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    var f = form(request.getBody().clone().readUtf8()); String plain;
                    if (request.getPath().endsWith("getOrderRechargeInfo")) {
                        var expected = new TreeMap<>(f); expected.remove("CallbackUrl"); expected.remove("SignSecret");
                        plain = canonical(expected) + "&MerchantKey=" + KEY;
                        return json(Map.of("CodeRes", 1000, "OrderNo", "UNSIGNED", "ReturnSign", encrypt(plain)));
                    }
                    plain = "RegState=02&ThirdOrderNo=" + f.get("ThirdOrderNo") + "&MerchantKey=" + KEY;
                    return json(Map.of("CodeRes", 1000, "RegState", "02", "ReturnSign", encrypt(plain)));
                } catch (Exception e) { throw new AssertionError(e); }
            }
        });
    }
    @AfterEach void stop() throws Exception { fixture.stop(); if (server != null) server.close(); }

    @Test void createThenAuthenticatedCallbackAndAckLossReplayCommitOnlyOnce() throws Exception {
        accept(); assertThat(runner().runExecute(op().getOperationNo(), "http-create")).isEqualTo("ACCEPTED");
        held(); assertThat(op().getRecoveryCount()).isZero();
        String body = callback("2", "RY-original", mobile());
        ack(body); ack(body + "&TimeStamp=9999999999");
        settled();
        assertThat(fixture.jdbc.queryForObject("SELECT external_order_no FROM sc_b_order", String.class)).isEqualTo("RY-original");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_inbox_event WHERE status=20", Long.class)).isEqualTo(1);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
    @Test void createThenQueryUsesSameOriginalBillAndSettlesWithoutCallback() throws Exception {
        accept(); runner().runExecute(op().getOperationNo(), "create"); due();
        assertThat(runner().runRecovery(op().getOperationNo(), op().getExecutionGeneration(), "query")).isEqualTo("SUCCESS");
        settled(); assertThat(op().getRecoveryCount()).isEqualTo(1);
        assertThat(server.getRequestCount()).isEqualTo(2);
        var create = form(server.takeRequest().getBody().readUtf8()); var query = form(server.takeRequest().getBody().readUtf8());
        assertThat(query.get("ThirdOrderNo")).isEqualTo(create.get("ThirdOrderNo"));
    }
    @Test void markerBeforeHttpThenRestartCanOnlyQueryOriginalOrder() throws Exception {
        accept(); var op = op(); var frozen = frozen();
        fixture.context.getBean(SingleStepFulfillmentLifecycle.class).claim(op.getOperationNo(), SingleStepAction.CREATE, 0, frozen.binding(), "marker-only");
        fixture.jdbc.update("UPDATE sc_fulfillment_operation SET execution_lease_until=DATE_SUB(NOW(3), INTERVAL 1 SECOND)");
        fixture.reopenWith(Config.class); mvc = mvc();
        assertThat(runner().runExecute(op.getOperationNo(), "restart")).isEqualTo("CREATE_ALREADY_MARKED");
        fixture.context.getBean(SingleStepFulfillmentLifecycle.class).watchdog(op.getOperationNo(), 1, "watchdog"); due();
        runner().runRecovery(op.getOperationNo(), 1L, "recovery"); settled();
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(server.takeRequest().getPath()).endsWith("getCardInquiryInfo");
    }
    @Test void transactionFailureReturns200ErrorAndRestartReplayCommitsProtectedSuccess() throws Exception {
        accept(); runner().runExecute(op().getOperationNo(), "create");
        String body = callback("2", "RY-original", mobile());
        fixture.jdbc.execute("ALTER TABLE sc_inbox_event ADD CONSTRAINT fail_renyue CHECK (status<>20)");
        mvc.perform(post(path()).contentType("application/x-www-form-urlencoded").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("CALLBACK_PROCESSING_FAILED"));
        held(); assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_inbox_event", Long.class)).isZero();
        assertThat(fixture.context.getBean(SingleStepCheckpointCodec.class).decryptRuntime(stored(), frozen().binding()).successEvidence()).isNotNull();
        fixture.jdbc.execute("ALTER TABLE sc_inbox_event DROP CHECK fail_renyue");
        fixture.reopenWith(Config.class); mvc = mvc(); ack(body); settled();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings = {"WRONG_RECEIPT", "TARGET", "OTHER_ORDER", "FAILED"})
    void invalidOrFailureCallbackNeverReleasesOriginalReservation(String scenario) throws Exception {
        accept(); runner().runExecute(op().getOperationNo(), "create");
        String body = callback(scenario.equals("FAILED") ? "3" : "2", "RY-original", scenario.equals("TARGET") ? "13900000000" : mobile());
        if (scenario.equals("WRONG_RECEIPT")) body = body.replace("RechargeStatus=2", "RechargeStatus=3");
        if (scenario.equals("OTHER_ORDER")) {
            fixture.jdbc.update("UPDATE sc_b_order SET external_order_no='RY-other'");
        }
        var result = mvc.perform(post(path()).contentType("application/x-www-form-urlencoded").content(body)).andExpect(status().isOk());
        if (scenario.equals("FAILED")) result.andExpect(content().string("success"));
        else result.andExpect(jsonPath("$.code").value(scenario.equals("WRONG_RECEIPT") ? "AUTHENTICATION_FAILED" : "IDENTITY_CONFLICT"));
        held();
    }
    @Test void unparsableCreateWaits180AndKeepsReservation() throws Exception {
        accept(); server.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setBody("not-json"); } });
        assertThat(runner().runExecute(op().getOperationNo(), "bad-body")).isEqualTo("RESPONSE_UNKNOWN");
        held(); assertThat(op().getNextRecoverAt()).isNotNull();
        var runtime = fixture.context.getBean(SingleStepCheckpointCodec.class).decryptRuntime(stored(), frozen().binding());
        assertThat(op().getNextRecoverAt()).isEqualTo(runtime.progress().factAt().plusSeconds(180));
        assertThat(runtime.progress().trustedExists()).isFalse();
        assertThat(runtime.progress().consecutiveNotFound()).isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_exception_case", Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"04", "BAD_RECEIPT"})
    void manualUnknownAndBadAuthenticationStopOrdinaryQueries(String scenario) throws Exception {
        accept(); runner().runExecute(op().getOperationNo(), "create"); due();
        String sign = encrypt("RegState=04&ThirdOrderNo=" + bill() + "&MerchantKey=" + KEY);
        server.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest request) {
            try { return json(Map.of("CodeRes", 1000, "RegState", "04", "ReturnSign", scenario.equals("04") ? sign : "invalid")); }
            catch (Exception e) { throw new AssertionError(e); }
        } });
        assertThat(runner().runRecovery(op().getOperationNo(), op().getExecutionGeneration(), "manual")).isEqualTo("MANUAL");
        held(); assertThat(op().getNextRecoverAt()).isNull();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_exception_case", Long.class)).isEqualTo(1);
        runner().runRecovery(op().getOperationNo(), op().getExecutionGeneration(), "must-not-call");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    /** 查询损坏报文与未识别状态走冻结600秒，不复用创建未知的180秒。 */
    @ParameterizedTest @ValueSource(strings = {"MALFORMED", "UNKNOWN_STATE"})
    void unknownQueryWaits600AndKeepsReservation(String scenario) throws Exception {
        accept(); runner().runExecute(op().getOperationNo(), "create"); due();
        String receipt = encrypt("RegState=99&ThirdOrderNo=" + bill() + "&MerchantKey=" + KEY);
        server.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest request) {
            try { return scenario.equals("MALFORMED") ? new MockResponse().setBody("not-json")
                    : json(Map.of("CodeRes", 1000, "RegState", "99", "ReturnSign", receipt)); }
            catch (Exception e) { throw new AssertionError(e); }
        } });
        assertThat(runner().runRecovery(op().getOperationNo(), op().getExecutionGeneration(), "query-unknown")).isEqualTo("RESPONSE_UNKNOWN");
        held();
        var runtime = fixture.context.getBean(SingleStepCheckpointCodec.class).decryptRuntime(stored(), frozen().binding());
        assertThat(op().getNextRecoverAt()).isEqualTo(runtime.progress().factAt().plusSeconds(600));
        assertThat(runtime.progress().consecutiveNotFound()).isZero();
        assertThat(op().getRecoveryCount()).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_exception_case", Long.class)).isZero();
        runner().runRecovery(op().getOperationNo(), op().getExecutionGeneration(), "not-yet-due");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    /** 人工停止自动外呼不剥夺原单可信回调资格；仍只提交原1000分一次。 */
    @Test void authenticatedLateSuccessAfterManualQuerySettlesWithoutNewHttpOrClaim() throws Exception {
        accept(); runner().runExecute(op().getOperationNo(), "create"); due();
        String receipt = encrypt("RegState=04&ThirdOrderNo=" + bill() + "&MerchantKey=" + KEY);
        server.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest request) {
            try { return json(Map.of("CodeRes", 1000, "RegState", "04", "ReturnSign", receipt)); }
            catch (Exception e) { throw new AssertionError(e); }
        } });
        assertThat(runner().runRecovery(op().getOperationNo(), op().getExecutionGeneration(), "manual")).isEqualTo("MANUAL");
        held();
        long generation = op().getExecutionGeneration();
        ack(callback("2", "RY-late", mobile()));
        settled();
        assertThat(op().getExecutionGeneration()).isEqualTo(generation);
        assertThat(op().getRecoveryCount()).isEqualTo(1);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    /**
     * 两个容器的真实HTTP共同占用商户额度；5降2不取消4个在途，升3也只多准入一个。
     * 后两单使用另一个Connector，版本限额9999不能扩大同商户额度。
     * 设计：docs/superpowers/specs/2026-10-18-single-step-merchant-quota-design.md#81-双容器与真实本机http组合验收2026-10-19续作。
     */
    @Test void twoContainersShareRealHttpConcurrencyAcrossConnectorsAndCurrentLimitChanges() throws Exception {
        configure(100, 5);
        var orders = new ArrayList<HttpOrder>();
        for (int i = 0; i < 4; i++) orders.add(acceptHttpOrder(i));
        try (var peer = peer(); var pool = Executors.newFixedThreadPool(6); var http = new HeldHttp()) {
            assertThat(peer.getBean("supplyChainDataSource")).isNotSameAs(fixture.context.getBean("supplyChainDataSource"));
            assertThat(peer.getBean("supplyChainTransactionManager")).isNotSameAs(fixture.context.getBean("supplyChainTransactionManager"));
            var running = new ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++) {
                var node = i % 2 == 0 ? fixture.context : peer;
                var order = orders.get(i);
                running.add(pool.submit(() -> execute(node, order)));
            }
            var firstBills = new ArrayList<String>();
            for (int i = 0; i < 4; i++) firstBills.add(http.arrival());
            assertThat(firstBills).containsExactlyInAnyOrderElementsOf(orders.stream().map(HttpOrder::bill).toList());
            assertThat(http.active.get()).isEqualTo(4);
            cloneConnectorForNewOrders();
            orders.add(acceptHttpOrder(4)); orders.add(acceptHttpOrder(5));
            configure(100, 2);
            for (var node : List.of(fixture.context, peer)) {
                assertThat(execute(node, orders.get(4))).isEqualTo("CAPACITY_WAITING");
                assertThat(execute(node, orders.get(5))).isEqualTo("CAPACITY_WAITING");
            }
            assertUnsent(orders.get(4)); assertUnsent(orders.get(5));
            assertThat(server.getRequestCount()).isEqualTo(4);
            for (int i = 0; i < 3; i++) {
                http.release(orders.get(i).bill());
                assertThat(running.get(i).get(5, TimeUnit.SECONDS)).isEqualTo("ACCEPTED");
                assertThat(http.active.get()).isEqualTo(3 - i);
                if (i < 2) {
                    assertThat(wakeDue(fixture.context, orders.get(4))).isEqualTo("CAPACITY_WAITING");
                    assertThat(wakeDue(peer, orders.get(5))).isEqualTo("CAPACITY_WAITING");
                    assertThat(server.getRequestCount()).isEqualTo(4);
                }
            }
            var race = new CountDownLatch(1);
            var fifth = pool.submit(() -> { race.await(); return wakeDue(fixture.context, orders.get(4)); });
            var sixth = pool.submit(() -> { race.await(); return wakeDue(peer, orders.get(5)); });
            race.countDown();
            String admitted = http.arrival();
            assertThat(admitted).isIn(orders.get(4).bill(), orders.get(5).bill());
            var denied = admitted.equals(orders.get(4).bill()) ? sixth : fifth;
            var remaining = admitted.equals(orders.get(4).bill()) ? orders.get(5) : orders.get(4);
            assertThat(denied.get(5, TimeUnit.SECONDS)).isEqualTo("CAPACITY_WAITING");
            assertThat(http.active.get()).isEqualTo(2);
            assertThat(server.getRequestCount()).isEqualTo(5);
            assertUnsent(remaining);
            configure(100, 3);
            var raised = pool.submit(() -> wakeDue(peer, remaining));
            assertThat(http.arrival()).isEqualTo(remaining.bill());
            assertThat(http.active.get()).isEqualTo(3);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_merchant_quota", Long.class)).isEqualTo(1);
            assertFundsHeld(6000);
            http.close();
            assertThat(running.get(3).get(5, TimeUnit.SECONDS)).isEqualTo("ACCEPTED");
            assertThat((admitted.equals(orders.get(4).bill()) ? fifth : sixth).get(5, TimeUnit.SECONDS)).isEqualTo("ACCEPTED");
            assertThat(raised.get(5, TimeUnit.SECONDS)).isEqualTo("ACCEPTED");
            assertThat(http.active.get()).isZero();
            assertThat(server.getRequestCount()).isEqualTo(6);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_operation WHERE execution_generation=1 AND recovery_count=0", Long.class)).isEqualTo(6);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_single_step_capacity_wait WHERE active_flag=1", Long.class)).isZero();
            assertFundsHeld(6000);
        }
    }

    /** 测试DB时钟固定窗口，实际HTTP完成/容器重开不退还速率；边界前1ms拒绝，边界同单双实例只发一次。 */
    @Test void realHttpReleaseAndContainerRestartDoNotRefundRollingRateBeforeExactBoundary() throws Exception {
        configure(1, 3);
        var first = acceptHttpOrder(0); var waiting = acceptHttpOrder(1);
        try (var peer = peer()) {
            var anchor = fixture.context.getBean(SupplyChainDatabaseClock.class).now();
            time(fixture.context, anchor); time(peer, anchor);
            assertThat(execute(fixture.context, first)).isEqualTo("ACCEPTED");
            assertThat(fixture.jdbc.queryForObject("SELECT released_at FROM sc_supplier_merchant_call_lease WHERE operation_no=?", LocalDateTime.class, first.operationNo())).isNotNull();
            assertThat(execute(peer, waiting)).isEqualTo("CAPACITY_WAITING");
            assertUnsent(waiting); assertFundsHeld(2000);
            String persisted = capacityPayload(waiting);
            fixture.reopenWith(Config.class);
            time(fixture.context, anchor.plusNanos(999_000_000)); time(peer, anchor.plusNanos(999_000_000));
            assertThat(consume(fixture.context, persisted)).isEqualTo("STALE");
            assertThat(execute(peer, waiting)).isEqualTo("CAPACITY_WAITING");
            assertThat(server.getRequestCount()).isEqualTo(1);
            time(fixture.context, anchor.plusSeconds(1)); time(peer, anchor.plusSeconds(1));
            try (var pool = Executors.newFixedThreadPool(2); var http = new HeldHttp()) {
                var gate = new CountDownLatch(1);
                var a = pool.submit(() -> { gate.await(); return consume(fixture.context, persisted); });
                var b = pool.submit(() -> { gate.await(); return consume(peer, persisted); });
                gate.countDown();
                assertThat(http.arrival()).isEqualTo(waiting.bill());
                http.close();
                assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
                        .contains("ACCEPTED").allMatch(result -> Set.of("ACCEPTED", "STALE", "CLAIM_SKIPPED", "CREATE_ALREADY_MARKED").contains(result));
                assertThat(server.getRequestCount()).isEqualTo(2);
                assertThat(operation(waiting).getExecutionGeneration()).isEqualTo(1);
                assertThat(operation(waiting).getRecoveryCount()).isZero();
                assertFundsHeld(2000);
            }
        }
    }

    /** QUERY等额度时重开容器，另一个真实HTTP仍在途；原始可信回调不等出站额度且只结算一次。 */
    @Test void callbackAfterWaitingContainerRestartSettlesWhilePeerHttpIsStillInFlight() throws Exception {
        configure(100, 1);
        var original = acceptHttpOrder(0); var occupant = acceptHttpOrder(1);
        assertThat(execute(fixture.context, original)).isEqualTo("ACCEPTED");
        due();
        var before = operation(original);
        try (var peer = peer(); var pool = Executors.newSingleThreadExecutor(); var http = new HeldHttp()) {
            var inFlight = pool.submit(() -> execute(peer, occupant));
            assertThat(http.arrival()).isEqualTo(occupant.bill());
            assertThat(runner().runRecovery(original.operationNo(), before.getExecutionGeneration(), "quota-query")).isEqualTo("CAPACITY_WAITING");
            assertThat(operation(original).getRecoveryCount()).isZero();
            assertThat(operation(original).getNextRecoverAt()).isEqualTo(before.getNextRecoverAt());
            assertThat(operation(original).getRecoveryDeadlineAt()).isEqualTo(before.getRecoveryDeadlineAt());
            String persisted = capacityPayload(original);
            String body = callback("2", "RY-quota-success", original.mobile(), original.bill());
            String callbackPath = "/callbacks/suppliers/renyue/" + fixture.jdbc.queryForObject("SELECT connector_version_id FROM sc_b_order WHERE billno=?", Long.class, original.bill());
            fixture.reopenWith(Config.class); mvc = mvc();
            for (int i = 0; i < 2; i++) mvc.perform(post(callbackPath).contentType("application/x-www-form-urlencoded").content(body))
                    .andExpect(status().isOk()).andExpect(content().string("success"));
            assertThat(http.active.get()).isEqualTo(1);
            assertThat(inFlight.isDone()).isFalse();
            assertThat(operation(original).getExecutionGeneration()).isEqualTo(before.getExecutionGeneration());
            assertThat(operation(original).getRecoveryCount()).isZero();
            assertThat(fixture.jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(1000);
            assertThat(fixture.jdbc.queryForObject("SELECT SUM(committed_amount) FROM sc_fund_reservation", Long.class)).isEqualTo(1000);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type='COMMIT'", Long.class)).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type='RELEASE'", Long.class)).isZero();
            assertThat(consume(fixture.context, persisted)).isEqualTo("STALE");
            assertThat(runner().runRecovery(original.operationNo(), before.getExecutionGeneration(), "old-query")).isEqualTo("ALREADY_COMPLETED");
            assertThat(server.getRequestCount()).isEqualTo(2);
            http.release(occupant.bill());
            assertThat(inFlight.get(5, TimeUnit.SECONDS)).isEqualTo("ACCEPTED");
        }
    }

    private record HttpOrder(String operationNo, String bill, String mobile) { }
    private HttpOrder acceptHttpOrder(int index) {
        String buyerOrder = "RY-QUOTA-" + index; String mobile = "1380013800" + index;
        var request = FulfillmentAcceptanceIntegrationTest.request(buyerOrder);
        request.getTarget().setValues(Map.of("accountType", "MOBILE", "accountValue", mobile));
        assertThat(fixture.context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(request).getReceiptStatus())
                .isEqualTo(com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptStatus.ACCEPTED);
        var row = fixture.jdbc.queryForMap("SELECT op.operation_no,bo.billno FROM sc_fulfillment_operation op JOIN sc_b_order bo ON bo.id=op.b_order_id JOIN sc_supply_chain_order o ON o.id=bo.order_id WHERE o.buyer_order_no=?", buyerOrder);
        return new HttpOrder((String) row.get("operation_no"), (String) row.get("billno"), mobile);
    }
    private FulfillmentOperationDO operation(HttpOrder order) { return fixture.context.getBean(FulfillmentOperationMapper.class).selectOne(FulfillmentOperationDO::getOperationNo, order.operationNo()); }
    private ConfigurableApplicationContext peer() {
        return new FulfillmentAcceptanceIntegrationTest().applicationContext(SingleStepFrozenConfigurationIntegrationTest.Config.class, Config.class);
    }
    private String execute(ConfigurableApplicationContext node, HttpOrder order) { return node.getBean(SingleStepFulfillmentRunner.class).runExecute(order.operationNo(), "http-quota"); }
    private void configure(int rate, int concurrency) {
        long version = fixture.jdbc.queryForObject("SELECT version FROM sc_supplier_merchant_quota", Long.class);
        fixture.context.getBean(MerchantQuotaService.class).configure(new MerchantQuotaService.Configure(RENYUE, "fixture", rate, concurrency, version, 1L, "本机HTTP测试夹具，不是生产限额"));
    }
    private void assertUnsent(HttpOrder order) {
        assertThat(operation(order).getBusinessSendPossibleAt()).isNull();
        assertThat(operation(order).getExecutionGeneration()).isZero();
        assertThat(operation(order).getRecoveryCount()).isZero();
    }
    private void assertFundsHeld(long amount) {
        assertThat(fixture.jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(amount);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type IN ('COMMIT','RELEASE')", Long.class)).isZero();
    }
    private String capacityPayload(HttpOrder order) {
        return fixture.jdbc.queryForObject("SELECT payload_json FROM sc_timer_task WHERE task_type='SINGLE_STEP_CAPACITY_WAKE' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.operationNo'))=? ORDER BY id DESC LIMIT 1", String.class, order.operationNo());
    }
    private String wakeDue(ConfigurableApplicationContext node, HttpOrder order) throws Exception {
        fixture.jdbc.update("UPDATE sc_single_step_capacity_wait SET next_wake_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE operation_id=?", operation(order).getId());
        return consume(node, capacityPayload(order));
    }
    private String consume(ConfigurableApplicationContext node, String payload) throws Exception {
        return node.getBean(PersistentTimerHandlerRegistry.class).require(FulfillmentTimerTaskTypes.SINGLE_STEP_CAPACITY_WAKE)
                .handle(new TimerTaskContext("TMR-http-quota", 1L, 1, 1, "http-quota"), new ObjectMapper().readTree(payload));
    }
    private static void time(ConfigurableApplicationContext node, LocalDateTime now) { node.getBean(HttpQuotaClock.class).fixed = now; }
    private void cloneConnectorForNewOrders() {
        long connector = fixture.jdbc.queryForObject("SELECT id FROM sc_supplier_connector", Long.class);
        long version = fixture.jdbc.queryForObject("SELECT id FROM sc_supplier_connector_version", Long.class);
        fixture.jdbc.update("UPDATE sc_supplier_connector SET current_version_id=NULL WHERE id=?", connector);
        long nextConnector = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(fixture.jdbc, "sc_supplier_connector", connector, Map.of("connector_no", "CON_HTTP_NEXT", "connector_code", "HTTP_NEXT"));
        long nextVersion = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(fixture.jdbc, "sc_supplier_connector_version", version, Map.of("connector_id", nextConnector, "rate_limit", 9999, "concurrency_limit", 9999));
        for (String table : List.of("sc_supplier_connector_capability", "sc_status_mapping_definition"))
            for (long id : fixture.jdbc.queryForList("SELECT id FROM " + table + " WHERE connector_version_id=?", Long.class, version))
                RechargeFrequencyAcceptanceIntegrationTest.cloneRow(fixture.jdbc, table, id, Map.of("connector_version_id", nextVersion));
        fixture.jdbc.update("UPDATE sc_supplier_connector SET current_version_id=? WHERE id=?", nextVersion, nextConnector);
        fixture.jdbc.update("UPDATE sc_supplier_connector_version SET status=90 WHERE id=?", version);
        fixture.jdbc.update("UPDATE sc_status_mapping_definition SET status=90 WHERE connector_version_id=?", version);
        long offer = fixture.jdbc.queryForObject("SELECT id FROM sc_supplier_offer_version", Long.class);
        long nextOffer = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(fixture.jdbc, "sc_supplier_offer_version", offer, Map.of("version_no", 2, "connector_version_id", nextVersion));
        fixture.jdbc.update("UPDATE sc_supplier_offer SET current_version_id=?", nextOffer);
    }

    /** 逐单阻塞服务端真实HTTP响应；close先释放闩锁，避免测试断言失败后线程池等待到客户端超时。 */
    private final class HeldHttp implements AutoCloseable {
        private final Dispatcher original = server.getDispatcher();
        private final ConcurrentMap<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        private final BlockingQueue<String> arrived = new LinkedBlockingQueue<>();
        private final AtomicInteger active = new AtomicInteger();
        private volatile boolean closed;
        HeldHttp() {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    String bill = form(request.getBody().clone().readUtf8()).get("ThirdOrderNo");
                    var gate = gates.computeIfAbsent(bill, ignored -> new CountDownLatch(1));
                    active.incrementAndGet(); arrived.add(bill);
                    try {
                        if (closed) gate.countDown();
                        if (!gate.await(20, TimeUnit.SECONDS)) throw new AssertionError("本机HTTP未按测试阶段释放");
                        return original.dispatch(request);
                    } finally { active.decrementAndGet(); }
                }
            });
        }
        String arrival() throws InterruptedException {
            String bill = arrived.poll(5, TimeUnit.SECONDS);
            assertThat(bill).as("真实HTTP应到达本机服务端").isNotNull(); return bill;
        }
        void release(String bill) { assertThat(gates).containsKey(bill); gates.get(bill).countDown(); }
        @Override public void close() { closed = true; gates.values().forEach(CountDownLatch::countDown); }
    }

    private void accept() { fixture.context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("RY-HTTP")); }
    private SingleStepFulfillmentRunner runner() { return fixture.context.getBean(SingleStepFulfillmentRunner.class); }
    private FulfillmentOperationDO op() { return fixture.context.getBean(FulfillmentOperationMapper.class).selectList(null).getFirst(); }
    private String bill() { return fixture.jdbc.queryForObject("SELECT billno FROM sc_b_order", String.class); }
    private String mobile() { return "13800138000"; }
    private String path() { return "/callbacks/suppliers/renyue/" + fixture.jdbc.queryForObject("SELECT connector_version_id FROM sc_b_order", Long.class); }
    private void due() { fixture.jdbc.update("UPDATE sc_fulfillment_operation SET next_recover_at=DATE_SUB(NOW(3), INTERVAL 1 SECOND)"); }
    private void ack(String body) throws Exception { mvc.perform(post(path()).contentType("application/x-www-form-urlencoded").content(body)).andExpect(status().isOk()).andExpect(content().string("success")); }
    private MockMvc mvc() { return MockMvcBuilders.standaloneSetup(new SingleStepSupplierCallbackController(fixture.context.getBean(SingleStepCallbackApplicationService.class)))
            .addFilters(new com.aifuli.framework.tracer.core.filter.TraceFilter()).build(); }
    private SingleStepCheckpointCodec.Stored stored() { var op = op(); return new SingleStepCheckpointCodec.Stored(op.getCheckpointSchemaVersion(), op.getCheckpointKeyVersion(), op.getCheckpointCiphertext()); }
    private SingleStepCheckpoint frozen() {
        var op = op(); var bo = fixture.context.getBean(com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper.class).selectById(op.getBOrderId());
        return fixture.context.getBean(SingleStepCheckpointCodec.class).decrypt(stored(), new SingleStepCheckpoint.Binding(op.getOperationNo(), bo.getId(), RenyueSupplierAdapter.TYPE,
                AdapterStepCode.SEND, bo.getSupplierId(), bo.getConnectorVersionId(), bo.getOfferVersionId(), HexFormat.of().formatHex(op.getConfigDigest())));
    }
    private void held() { assertThat(fixture.jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(1000); assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type IN ('COMMIT','RELEASE')", Long.class)).isZero(); }
    private void settled() {
        assertThat(fixture.jdbc.queryForObject("SELECT committed_amount FROM sc_fund_reservation", Long.class)).isEqualTo(1000);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type='COMMIT'", Long.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT released_amount FROM sc_fund_reservation", Long.class)).isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event WHERE event_type='SUPPLY_CHAIN_ORDER_CHANGED' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.orderStatus'))='SUCCEEDED'", Long.class)).isEqualTo(1);
    }
    private String callback(String state, String external, String mobile) throws Exception { return callback(state, external, mobile, bill()); }
    private String callback(String state, String external, String mobile, String bill) throws Exception { var f = new TreeMap<String, String>(); f.put("OrderNo", external); f.put("RechargeAccount", mobile); f.put("RechargeStatus", state); f.put("StoreNo", bill); f.put("ReturnSign", encrypt(canonical(f) + "&MerchantKey=" + KEY)); return encode(f); }
    private static String encrypt(String plain) throws Exception { var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding"); cipher.init(Cipher.ENCRYPT_MODE, keys.getPublic()); byte[] bytes = plain.getBytes(StandardCharsets.UTF_8); var out = new ByteArrayOutputStream(); for (int i = 0; i < bytes.length; i += 245) out.write(cipher.doFinal(bytes, i, Math.min(245, bytes.length - i))); return Base64.getEncoder().encodeToString(out.toByteArray()); }
    private static MockResponse json(Map<String, ?> value) throws Exception { return new MockResponse().setBody(new ObjectMapper().writeValueAsString(value)).setHeader("Content-Type", "application/json"); }
    private static String canonical(Map<String, String> value) { return new TreeMap<>(value).entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("&")); }
    private static String encode(Map<String, String> value) { return value.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&")); }
    private static Map<String, String> form(String body) { var f = new TreeMap<String, String>(); for (var part : body.split("&")) { var pair = part.split("=", 2); f.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); } return f; }

    @Configuration(proxyBeanMethods = false)
    @Import({SingleStepCallbackApplicationService.class, FrozenCredentialSetResolver.class})
    static class Config {
        @Bean @Primary HttpQuotaClock httpQuotaClock(DataSource supplyChainDataSource) { return new HttpQuotaClock(supplyChainDataSource); }
        @Bean @Primary AdapterMetadataRegistry renyueRegistry() { return new AdapterMetadataRegistry(List.of(new QnmGameOrderV1Metadata(), new SingleStepFrozenConfigurationIntegrationTest.TestMetadata(), new TestRenyueMetadata())); }
        @Bean @Primary AdapterTypeAllowList renyueAllowList() { return new AdapterTypeAllowList(new MockEnvironment().withProperty("aifuli.supply-chain.connector.allowed-adapter-types", "TEST_SINGLE_STEP,QNM_GAME_ORDER_V1," + RenyueSupplierAdapter.TYPE)); }
        @Bean @Primary SupplierEndpointPolicy loopbackTestOnly() { return new SupplierEndpointPolicy(new MockEnvironment()) {
            @Override public void validate(String raw) { assertThat(URI.create(raw).getHost()).isIn("localhost", "qnm.invalid"); }
        }; }
        @Bean @Primary OperationExecutorPort renyueExecutor() { return new RenyueSupplierAdapter(); }
        @Bean CredentialItemResolver syntheticKeys() { return new CredentialItemResolver() {
            public boolean supports(String type) { return "ENV".equals(type); }
            public char[] resolve(CredentialItemDO item) { return (item.getItemKey().equals("merchantKey") ? KEY : Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())).toCharArray(); }
        }; }
    }
    /** 仅本测试可控的毫秒时钟，未固定时仍读真实MySQL，不改变生产时间来源。 */
    static class HttpQuotaClock extends SupplyChainDatabaseClock {
        volatile LocalDateTime fixed;
        HttpQuotaClock(DataSource dataSource) { super(dataSource); }
        @Override public LocalDateTime now() { var value = fixed; return value == null ? super.now() : value; }
    }
    static class TestRenyueMetadata extends SingleStepFrozenConfigurationIntegrationTest.TestMetadata {
        @Override public String adapterType() { return RenyueSupplierAdapter.TYPE; }
        @Override public Set<String> credentialKeys() { return Set.of("merchantKey", "privateKey"); }
        @Override public CredentialBindingMode credentialBindingMode() { return CredentialBindingMode.CREDENTIAL_SET; }
        @Override public SingleStepCheckpoint.RecoveryContract singleStepRecoveryContract() { return SingleStepCheckpoint.RecoveryContract.initial(600); }
    }
}
