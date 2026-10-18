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
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.security.*;
import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
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
    private String callback(String state, String external, String mobile) throws Exception { var f = new TreeMap<String, String>(); f.put("OrderNo", external); f.put("RechargeAccount", mobile); f.put("RechargeStatus", state); f.put("StoreNo", bill()); f.put("ReturnSign", encrypt(canonical(f) + "&MerchantKey=" + KEY)); return encode(f); }
    private static String encrypt(String plain) throws Exception { var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding"); cipher.init(Cipher.ENCRYPT_MODE, keys.getPublic()); byte[] bytes = plain.getBytes(StandardCharsets.UTF_8); var out = new ByteArrayOutputStream(); for (int i = 0; i < bytes.length; i += 245) out.write(cipher.doFinal(bytes, i, Math.min(245, bytes.length - i))); return Base64.getEncoder().encodeToString(out.toByteArray()); }
    private static MockResponse json(Map<String, ?> value) throws Exception { return new MockResponse().setBody(new ObjectMapper().writeValueAsString(value)).setHeader("Content-Type", "application/json"); }
    private static String canonical(Map<String, String> value) { return new TreeMap<>(value).entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("&")); }
    private static String encode(Map<String, String> value) { return value.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&")); }
    private static Map<String, String> form(String body) { var f = new TreeMap<String, String>(); for (var part : body.split("&")) { var pair = part.split("=", 2); f.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); } return f; }

    @Configuration(proxyBeanMethods = false)
    @Import({SingleStepCallbackApplicationService.class, FrozenCredentialSetResolver.class})
    static class Config {
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
    static class TestRenyueMetadata extends SingleStepFrozenConfigurationIntegrationTest.TestMetadata {
        @Override public String adapterType() { return RenyueSupplierAdapter.TYPE; }
        @Override public Set<String> credentialKeys() { return Set.of("merchantKey", "privateKey"); }
        @Override public CredentialBindingMode credentialBindingMode() { return CredentialBindingMode.CREDENTIAL_SET; }
        @Override public SingleStepCheckpoint.RecoveryContract singleStepRecoveryContract() { return SingleStepCheckpoint.RecoveryContract.initial(600); }
    }
}
