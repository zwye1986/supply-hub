package com.aifuli.module.supplychain.supplieradapter.renyue;

import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.crypto.Cipher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 合成密码与本机HTTP验证临时直充合同，不替代供应商向量/重投许可。 */
class RenyueSupplierAdapterContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BILL = "202610180001000000000001";
    private static final String KEY = "SYNTHETIC-MerchantKey+甲";
    private static KeyPair keys;
    private MockWebServer server;
    @BeforeAll static void key() throws Exception { var g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); keys = g.generateKeyPair(); }
    @BeforeEach void start() throws Exception { server = new MockWebServer(); server.start(); }
    @AfterEach void stop() throws Exception { server.close(); }

    @Test void createSignsOriginalFormAndAuthenticatesRequestFieldsNotResponse() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    assertThat(request.getPath()).isEqualTo("/api/v2/getOrderRechargeInfo");
                    var form = form(request.getBody().readUtf8());
                    assertThat(form).hasSize(9).containsEntry("AgreedPrice", "8.84").containsEntry("ThirdOrderNo", BILL)
                            .containsEntry("ProductCode", "SKU+甲").containsEntry("CallbackUrl", "https://callback.invalid/a?x=+&b=甲");
                    assertThat(form.get("TimeStamp")).hasSize(10);
                    var verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(keys.getPublic());
                    verifier.update((canonical(form, Set.of("SignSecret")) + "&MerchantKey=" + KEY).getBytes(StandardCharsets.UTF_8));
                    assertThat(verifier.verify(Base64.getDecoder().decode(form.get("SignSecret")))).isTrue();
                    return json(Map.of("CodeRes", 1000, "OrderNo", "UNAUTHENTICATED-ORDER", "ReturnSign",
                            encrypt(canonical(form, Set.of("SignSecret", "CallbackUrl")) + "&MerchantKey=" + KEY)));
                } catch (Exception e) { throw new AssertionError(e); }
            }
        });
        try (var ticket = ticket(false)) {
            var outcome = new RenyueSupplierAdapter().execute(ticket, System.nanoTime() + 5_000_000_000L);
            var fact = (SingleStepSupplierFact) outcome.protocolFact();
            assertThat(fact).isNotNull();
            assertThat(fact.verification()).isEqualTo(SingleStepSupplierFact.Verification.VERIFIED);
            assertThat(fact.observation()).isEqualTo(SingleStepSupplierFact.Observation.ACCEPTED);
            assertThat(fact.externalOrderNo()).isNull(); // 未包含在回执内的外部号不能抢先绑定。
        }
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"01", "02", "03", "04", "99"})
    void queryUsesShortReceiptAndNeverReleasesOrFabricatesSettlement(String state) throws Exception {
        server.enqueue(json(Map.of("CodeRes", "1000", "RegState", state, "OrderNo", "UNSIGNED",
                "ReturnSign", encrypt("RegState=" + state + "&ThirdOrderNo=" + BILL + "&MerchantKey=" + KEY))));
        try (var ticket = ticket(true)) {
            var fact = (SingleStepSupplierFact) new RenyueSupplierAdapter().execute(ticket, System.nanoTime() + 5_000_000_000L).protocolFact();
            assertThat(fact).isNotNull();
            assertThat(fact.observation().name()).isEqualTo(switch (state) {
                case "01" -> "PROCESSING"; case "02" -> "SUCCESS"; case "03" -> "FAILURE";
                case "04" -> "EXISTS_UNKNOWN"; default -> "RESPONSE_UNKNOWN";
            });
            assertThat(fact.zeroDeliveryConfirmed()).isFalse(); assertThat(fact.reportedSettlementAmount()).isNull();
            assertThat(fact.externalOrderNo()).isNull();
        }
        var req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/v2/getCardInquiryInfo");
        assertThat(form(req.getBody().readUtf8())).hasSize(4).containsEntry("ThirdOrderNo", BILL).doesNotContainKeys("RechargeNo", "OrderNo");
    }

    @ParameterizedTest @ValueSource(strings = {"CASE", "OLD_FIELDS", "OTHER_BILL", "DUPLICATE_JSON", "TRAILING", "OVERSIZE", "HTTP503"})
    void unsafeOrBadReceiptsNeverProduceVerifiedSuccess(String fault) throws Exception {
        String plain = "RegState=02&ThirdOrderNo=" + BILL + "&MerchantKey=" + KEY;
        if (fault.equals("CASE")) plain = plain.toLowerCase(Locale.ROOT);
        if (fault.equals("OLD_FIELDS")) plain = "FaceValue=100&TimesTamp=1&MerchantKey=" + KEY;
        if (fault.equals("OTHER_BILL")) plain = plain.replace(BILL, "202610180001000000000002");
        var response = json(Map.of("CodeRes", 1000, "RegState", "02", "ReturnSign", encrypt(plain)));
        if (fault.equals("DUPLICATE_JSON")) response.setBody("{\"CodeRes\":1000,\"RegState\":\"01\",\"RegState\":\"02\"}");
        if (fault.equals("TRAILING")) response.setBody("{} {}");
        if (fault.equals("OVERSIZE")) response.setBody(" ".repeat(1024 * 1024 + 1));
        if (fault.equals("HTTP503")) response.setResponseCode(503).setHeader("Retry-After", "0");
        server.enqueue(response);
        try (var ticket = ticket(true)) {
            var fact = (SingleStepSupplierFact) new RenyueSupplierAdapter().execute(ticket, System.nanoTime() + 5_000_000_000L).protocolFact();
            assertThat(fact).isNotNull();
            assertThat(fact.verification() != SingleStepSupplierFact.Verification.VERIFIED
                    || fact.observation() != SingleStepSupplierFact.Observation.SUCCESS).isTrue();
            assertThat(fact.observation()).isNotEqualTo(SingleStepSupplierFact.Observation.NOT_FOUND);
        }
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test void callbackAuthenticatesFourFieldsAndIgnoresTransmissionTimestampOnly() throws Exception {
        var fields = callback();
        try (var ticket = ticket(false)) {
            var parsed = RenyueCallbackParser.readForm(new ByteArrayInputStream(encode(fields).getBytes(StandardCharsets.UTF_8)));
            assertThat(parsed).containsAllEntriesOf(fields);
            for (String timestamp : List.of("1", "9999999999")) {
                fields.put("TimeStamp", timestamp);
                var fact = RenyueCallbackParser.parse(fields, ticket, frozen(), LocalDateTime.of(2026, 10, 18, 12, 0));
                assertThat(fact).isNotNull(); assertThat(fact.source()).isEqualTo(SingleStepSupplierFact.Source.CALLBACK);
                assertThat(fact.observation()).isEqualTo(SingleStepSupplierFact.Observation.SUCCESS);
                assertThat(fact.externalOrderNo()).isEqualTo("RY-Original"); assertThat(fact.zeroDeliveryConfirmed()).isFalse();
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"STORE", "ORDER", "TARGET", "STATUS", "CASE", "DUPLICATE", "UNKNOWN", "UTF8", "QUERY_UTF8", "OVERSIZE"})
    void callbackRejectsTamperingAndAmbiguousForms(String fault) throws Exception {
        var fields = callback();
        if (fault.equals("STORE")) fields.put("StoreNo", "202610180001000000000002");
        if (fault.equals("ORDER")) fields.put("OrderNo", "RY-Other");
        if (fault.equals("TARGET")) fields.put("RechargeAccount", "13900000000");
        if (fault.equals("STATUS")) fields.put("RechargeStatus", "3");
        if (fault.equals("CASE")) fields.put("ReturnSign", encrypt((canonical(fields, Set.of("TimeStamp", "ReturnSign")) + "&MerchantKey=" + KEY).toLowerCase(Locale.ROOT)));
        String form = encode(fields);
        if (fault.equals("DUPLICATE")) form += "&StoreNo=" + BILL;
        if (fault.equals("UNKNOWN")) form += "&sumPrice=0.01";
        if (fault.equals("UTF8")) form += "&TimeStamp=%FF";
        if (fault.equals("QUERY_UTF8")) form += "&%FF=x";
        if (fault.equals("OVERSIZE")) form = "x=" + "a".repeat(1024 * 1024);
        final var body = form.getBytes(StandardCharsets.UTF_8);
        try (var ticket = ticket(false)) {
            assertThatThrownBy(() -> RenyueCallbackParser.parse(RenyueCallbackParser.readForm(new ByteArrayInputStream(body)),
                    ticket, frozen(), LocalDateTime.now())).isInstanceOf(SingleStepCallbackException.class);
        }
    }

    private Map<String, String> callback() throws Exception {
        var f = new LinkedHashMap<String, String>(); f.put("StoreNo", BILL); f.put("OrderNo", "RY-Original");
        f.put("RechargeAccount", "13800000000"); f.put("RechargeStatus", "2");
        f.put("ReturnSign", encrypt("OrderNo=RY-Original&RechargeAccount=13800000000&RechargeStatus=2&StoreNo=" + BILL + "&MerchantKey=" + KEY));
        return f;
    }
    private OperationTicket ticket(boolean query) throws Exception {
        return OperationTicket.builder().operationNo("OP-synthetic").billno(BILL).adapterType(RenyueSupplierAdapter.TYPE)
                .operationKind(query ? FulfillmentOperationKind.QUERY_ORDER : FulfillmentOperationKind.FULFILL)
                .adapterStepCode(query ? AdapterStepCode.QUERY_ORDER : AdapterStepCode.SEND).connectorVersionId(5L).offerVersionId(6L)
                .quantity(1).merchantAccount("SyntheticMerchant").externalSkuCode("SKU+甲").rechargeAccount("13800000000")
                .callbackUrl("https://callback.invalid/a?x=+&b=甲").baseUrl(server.url("/").toString())
                .settlementUnitPriceMinorUnits(884L).currency("CNY").checkpointPayload(JSON.writeValueAsBytes(frozen()))
                .resolvedCredentialMaterial(ResolvedCredentialMaterial.takeOwnership(Set.of("merchantKey", "privateKey"), Map.of(
                        "merchantKey", KEY.toCharArray(), "privateKey", Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()).toCharArray()))).build();
    }
    private SingleStepCheckpoint frozen() {
        return new SingleStepCheckpoint(1, new SingleStepCheckpoint.Binding("OP-synthetic", 2, RenyueSupplierAdapter.TYPE, AdapterStepCode.SEND, 3, 5, 6, "a".repeat(64)),
                new SingleStepCheckpoint.Business(BILL, "SKU+甲", 1, "DIRECT_RECHARGE_TARGET",
                        new SingleStepCheckpoint.TargetReference(7, "REQ", 1, "b".repeat(64), "v1"), "https://callback.invalid/a?x=+&b=甲", 884, "CNY"),
                new SingleStepCheckpoint.CredentialIdentity(null, 4L, List.of(new FrozenCredentialItemIdentity(8L, "merchantKey", "ENV", 1), new FrozenCredentialItemIdentity(9L, "privateKey", "ENV", 1))),
                SingleStepCheckpoint.RecoveryContract.initial(600));
    }
    private static MockResponse json(Map<String, ?> f) throws Exception { return new MockResponse().setHeader("Content-Type", "application/json").setBody(JSON.writeValueAsString(f)); }
    private static String canonical(Map<String, String> f, Set<String> excluded) { return new TreeMap<>(f).entrySet().stream().filter(e -> !excluded.contains(e.getKey())).map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("&")); }
    private static String encrypt(String plain) throws Exception {
        var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding"); cipher.init(Cipher.ENCRYPT_MODE, keys.getPublic());
        var bytes = plain.getBytes(StandardCharsets.UTF_8); var out = new ByteArrayOutputStream();
        for (int i = 0; i < bytes.length; i += 245) out.write(cipher.doFinal(bytes, i, Math.min(245, bytes.length - i)));
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
    private static String encode(Map<String, String> f) { return f.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&")); }
    private static Map<String, String> form(String body) { var f = new LinkedHashMap<String, String>(); for (var part : body.split("&")) { var pair = part.split("=", 2); f.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); } return f; }
}
