package com.aifuli.module.supplychain.supplieradapter.renyue;

import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.border.fulfillment.domain.ResolvedCredentialMaterial;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/** 独立合成密钥验证密码及卡管直充请求合同；不冒充真实供应商业务认证验收。 */
class RenyueProtocolTest {
    private static JsonNode fixture;

    @BeforeAll static void loadVectors() throws Exception {
        try (var input = RenyueProtocolTest.class.getResourceAsStream("/supply-chain/renyue/synthetic-crypto-vector.json")) {
            fixture = new ObjectMapper().readTree(input);
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1})
    void signatureMatchesIndependentFixedVectorWithoutEncodingTrimmingOrCaseFolding(int index) {
        try (var material = material(index)) {
            Map<String, String> fields = request();
            assertThat(RenyueProtocol.signRequest(fields, material)).isEqualTo(vector(index).path("signature").textValue());
            assertThat(fields).isEqualTo(request());
            fields.put("RechargeNo", "SYNTHETIC%2Btarget%26%3D%E7%94%B2");
            assertThat(RenyueProtocol.signRequest(fields, material)).isNotEqualTo(vector(index).path("signature").textValue());
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1})
    void decryptsEveryRsaBlockAndPreservesExactUtf8Bytes(int index) {
        try (var material = material(index)) {
            String receipt = vector(index).path("receipt").textValue();
            String wrapped = " \r\n" + receipt.substring(0, 64) + "\t\n" + receipt.substring(64) + "\r\n";
            for (String input : List.of(wrapped, receipt + " ".repeat(1024 * 1024 - receipt.length()))) {
                byte[] plaintext = RenyueProtocol.decryptReceipt(input, material);
                try {
                    assertThat(plaintext).containsExactly(fixture.path("receiptPlaintext").textValue().getBytes(StandardCharsets.UTF_8));
                } finally { Arrays.fill(plaintext, (byte) 0); }
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"TRUNCATED", "LATER_BLOCK", "WRONG_KEY", "GARBAGE", "UNICODE_SPACE", "OVERSIZE"})
    void rejectsInvalidCiphertextInsteadOfReturningPartialPlaintext(String fault) throws Exception {
        byte[] ciphertext = Base64.getDecoder().decode(vector(0).path("receipt").textValue());
        if (fault.equals("TRUNCATED")) ciphertext = Arrays.copyOf(ciphertext, ciphertext.length - 1);
        if (fault.equals("LATER_BLOCK")) Arrays.fill(ciphertext, ciphertext.length - 256, ciphertext.length, (byte) 0);
        String receipt = Base64.getEncoder().encodeToString(ciphertext);
        if (fault.equals("GARBAGE")) receipt = receipt.substring(0, 70) + "#SYNTHETIC_RECEIPT_MARKER" + receipt.substring(70);
        if (fault.equals("UNICODE_SPACE")) receipt = receipt.substring(0, 70) + "\u2003" + receipt.substring(70);
        if (fault.equals("OVERSIZE")) receipt = "A".repeat(1024 * 1024 + 1);
        String privateKey = vector(0).path("privateKeyPem").textValue();
        if (fault.equals("WRONG_KEY")) {
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            privateKey = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        }
        try (var material = material(privateKey)) {
            String input = receipt;
            var failure = catchThrowable(() -> RenyueProtocol.decryptReceipt(input, material));
            assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessage("仁跃回执解密失败");
            assertThat(failure.getCause()).isNotNull();
            assertThat(stack(failure)).doesNotContain("SYNTHETIC_RECEIPT_MARKER", fixture.path("merchantKey").textValue());
        }
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" \r\n\t", "Encryption failed, link timed out"})
    void rejectsAbsentOrFailureTextReceipt(String receipt) {
        try (var material = material(0)) {
            assertThatThrownBy(() -> RenyueProtocol.decryptReceipt(receipt, material))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("仁跃回执解密失败");
        }
    }

    @Test void acceptsBarePkcs8AndRejectsWrongPemContainerWithoutExposingInput() {
        String pem = vector(0).path("privateKeyPem").textValue();
        String bare = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "");
        try (var material = material(bare)) {
            assertThat(RenyueProtocol.signRequest(request(), material)).isEqualTo(vector(0).path("signature").textValue());
        }
        for (String key : new String[]{pem.replace("PRIVATE KEY", "RSA PRIVATE KEY"), "SYNTHETIC_PRIVATE_MARKER#"}) {
            try (var material = material(key)) {
                var failure = catchThrowable(() -> RenyueProtocol.signRequest(request(), material));
                assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessage("仁跃请求签名失败");
                assertThat(stack(failure)).doesNotContain("SYNTHETIC_PRIVATE_MARKER", "BEGIN RSA PRIVATE KEY");
            }
        }
    }

    @Test void rejectsReservedMerchantKeyAndNonAsciiFieldNames() {
        try (var material = material(0)) {
            for (String name : new String[]{"MerchantKey", "字段", "bad&name", "", "bad=name"}) {
                var fields = request(); fields.put(name, "SYNTHETIC_FIELD_MARKER");
                assertThatThrownBy(() -> RenyueProtocol.signRequest(fields, material))
                        .isInstanceOf(IllegalArgumentException.class).hasMessage("仁跃请求签名失败");
            }
        }
    }

    @ParameterizedTest @CsvSource({"884,8.84", "100,1.00", "9007199254740993,90071992547409.93"})
    void buildsOnlyDirectRechargeCreateFieldsFromFrozenTicket(long cents, String yuan) throws Exception {
        try (var material = material(0)) {
            var ticket = ticket(material).settlementUnitPriceMinorUnits(cents).build();
            var form = RenyueProtocol.buildRequest(ticket, Instant.ofEpochMilli(1712650373123L));
            var unsigned = new LinkedHashMap<>(form); unsigned.remove("SignSecret");
            assertThat(unsigned).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "MerchantNo", "SYNTHETIC-MERCHANT", "ProductCode", "SKU+甲", "ThirdOrderNo", "202610180001000000000001",
                    "RechargeNum", "1", "RechargeNo", "13800000000", "AgreedPrice", yuan,
                    "TimeStamp", "1712650373", "CallbackUrl", "https://callback.example/直充?token=a+b&v=1"));
            verifySignature(form.get("SignSecret"), "AgreedPrice=" + yuan
                    + "&CallbackUrl=https://callback.example/直充?token=a+b&v=1&MerchantNo=SYNTHETIC-MERCHANT"
                    + "&ProductCode=SKU+甲&RechargeNo=13800000000&RechargeNum=1"
                    + "&ThirdOrderNo=202610180001000000000001&TimeStamp=1712650373");
            assertThatThrownBy(() -> form.put("ProductCode", "OVERRIDE")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test void queryUsesOriginalMerchantOrderOnlyWithoutCreateFieldsOrClosingTicketMaterial() throws Exception {
        try (var material = material(0)) {
            var ticket = ticket(material).operationKind(FulfillmentOperationKind.QUERY_ORDER).adapterStepCode(AdapterStepCode.QUERY_ORDER)
                    .externalSkuCode(null).rechargeAccount(null).callbackUrl(null).settlementUnitPriceMinorUnits(null).currency(null).build();
            var form = RenyueProtocol.buildRequest(ticket, Instant.ofEpochSecond(1712650374));
            assertThat(form).containsOnlyKeys("MerchantNo", "ThirdOrderNo", "TimeStamp", "SignSecret")
                    .containsEntry("MerchantNo", "SYNTHETIC-MERCHANT").containsEntry("ThirdOrderNo", "202610180001000000000001")
                    .containsEntry("TimeStamp", "1712650374");
            verifySignature(form.get("SignSecret"), "MerchantNo=SYNTHETIC-MERCHANT&ThirdOrderNo=202610180001000000000001&TimeStamp=1712650374");
            assertThat(RenyueProtocol.buildRequest(ticket, Instant.ofEpochSecond(1712650375))).containsEntry("TimeStamp", "1712650375");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"SEND_KM", "QUERY_ASSET", "TAKE_CARD", "WRONG_QUERY_STEP", "QUANTITY",
            "MISSING_PRICE", "NEGATIVE_PRICE", "CURRENCY", "MERCHANT", "SKU", "TARGET", "CALLBACK", "CREDENTIALS"})
    void rejectsNonDirectRechargeOrIncompleteCreateInputs(String fault) {
        try (var material = material(0)) {
            var builder = ticket(material);
            switch (fault) {
                case "SEND_KM" -> builder.adapterStepCode(AdapterStepCode.SEND_KM);
                case "QUERY_ASSET" -> builder.operationKind(FulfillmentOperationKind.QUERY_ASSET);
                case "TAKE_CARD" -> builder.adapterStepCode(AdapterStepCode.TAKE_CARD);
                case "WRONG_QUERY_STEP" -> builder.operationKind(FulfillmentOperationKind.QUERY_ORDER);
                case "QUANTITY" -> builder.quantity(2);
                case "MISSING_PRICE" -> builder.settlementUnitPriceMinorUnits(null);
                case "NEGATIVE_PRICE" -> builder.settlementUnitPriceMinorUnits(-1L);
                case "CURRENCY" -> builder.currency("USD");
                case "MERCHANT" -> builder.merchantAccount(" ");
                case "SKU" -> builder.externalSkuCode(null);
                case "TARGET" -> builder.rechargeAccount("");
                case "CALLBACK" -> builder.callbackUrl(null);
                case "CREDENTIALS" -> builder.resolvedCredentialMaterial(null);
                default -> throw new AssertionError(fault);
            }
            assertThatThrownBy(() -> RenyueProtocol.buildRequest(builder.build(), Instant.ofEpochSecond(1712650373)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 全合成Ticket；覆盖原商户单号与外部号不相等、extra恶意覆盖及特殊字符不得提前编码。 */
    private static OperationTicket.OperationTicketBuilder ticket(ResolvedCredentialMaterial material) {
        return OperationTicket.builder().operationNo("SYNTHETIC-OP").billno("202610180001000000000001")
                .externalOrderNo("SYNTHETIC-OTHER-ORDER").adapterType("RENYUE_RECHARGE_V2")
                .operationKind(FulfillmentOperationKind.FULFILL).adapterStepCode(AdapterStepCode.SEND)
                .merchantAccount("SYNTHETIC-MERCHANT").externalSkuCode("SKU+甲").quantity(1).rechargeAccount("13800000000")
                .callbackUrl("https://callback.example/直充?token=a+b&v=1").settlementUnitPriceMinorUnits(884L).currency("CNY")
                .resolvedCredentialMaterial(material).extraParameters(Map.of("AgreedPrice", "0.01", "ProductCode", "OVERRIDE",
                        "ThirdOrderNo", "OVERRIDE", "RechargeNo", "OVERRIDE", "CallbackUrl", "OVERRIDE",
                        "SignSecret", "OVERRIDE", "MerchantKey", "OVERRIDE", "cardNo", "OUT_OF_SCOPE"));
    }

    /** 预期规范串由用例手写，独立公钥验签，不复用生产参数排序或签名来生成预期。 */
    private static void verifySignature(String encoded, String canonical) throws Exception {
        String bare = vector(0).path("privateKeyPem").textValue().replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        var factory = KeyFactory.getInstance("RSA");
        var privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(bare)));
        var verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(factory.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent())));
        verifier.update((canonical + "&MerchantKey=" + fixture.path("merchantKey").textValue()).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(encoded))).isTrue();
    }

    private static JsonNode vector(int index) { return fixture.path("vectors").get(index); }
    private static ResolvedCredentialMaterial material(int index) { return material(vector(index).path("privateKeyPem").textValue()); }
    private static ResolvedCredentialMaterial material(String pem) {
        return ResolvedCredentialMaterial.takeOwnership(Set.of(RenyueProtocol.MERCHANT_KEY, RenyueProtocol.PRIVATE_KEY), Map.of(
                RenyueProtocol.MERCHANT_KEY, fixture.path("merchantKey").textValue().toCharArray(),
                RenyueProtocol.PRIVATE_KEY, pem.toCharArray()));
    }
    private static Map<String, String> request() {
        Map<String, String> request = new LinkedHashMap<>();
        fixture.path("request").fields().forEachRemaining(e -> request.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().textValue()));
        return request;
    }
    private static String stack(Throwable failure) {
        var text = new StringWriter(); failure.printStackTrace(new PrintWriter(text)); return text.toString();
    }
}
