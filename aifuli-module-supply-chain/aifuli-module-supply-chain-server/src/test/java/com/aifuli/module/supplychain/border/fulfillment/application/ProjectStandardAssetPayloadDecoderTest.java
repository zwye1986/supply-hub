package com.aifuli.module.supplychain.border.fulfillment.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAssetDO;
import com.aifuli.module.supplychain.border.fulfillment.domain.ProjectStandardAssetAuditReason;
import com.aifuli.module.supplychain.border.fulfillment.domain.ProjectStandardAssetQueryFailure;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

class ProjectStandardAssetPayloadDecoderTest {
    @Test
    void deliversLegacyVoucherWithoutOtherFieldsAndErasesPlaintext() {
        var crypto = mock(EnvelopeCryptoPort.class);
        var plain =
                "{\"cardCode\":\"VOUCHER\",\"cardSecret\":\"hidden\"}"
                        .getBytes(StandardCharsets.UTF_8);
        when(crypto.decrypt(any(), any(), any())).thenReturn(plain);
        var decoder =
                new ProjectStandardAssetPayloadDecoder(
                        crypto, new ObjectMapper(), mock(SupplyChainDatabaseClock.class));
        var asset =
                FulfillmentAssetDO.builder()
                        .assetNo("FA_1")
                        .bOrderId(1L)
                        .sequenceNo(1)
                        .assetType("VOUCHER_CODE")
                        .keyVersion("k1")
                        .encryptedPayload(new byte[] {1})
                        .lifecycleStatus((short) 10)
                        .build();
        var result = decoder.decode(asset);
        assertThat(result.getVoucherCode()).isEqualTo("VOUCHER");
        assertThat(result.getCardCode()).isNull();
        assertThat(result.getCardSecret()).isNull();
        assertThat(plain).containsOnly((byte) 0);
        verify(crypto)
                .decrypt(
                        eq(new byte[] {1}),
                        eq("k1"),
                        eq("1|1|VOUCHER_CODE".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsNonStringAndConflictingAliasAndMalformedPayloadAndErasesBytes() {
        for (String value :
                new String[] {
                    "[]",
                    "{\"voucherCode\":[]}",
                    "{\"voucherCode\":\"a\",\"cardCode\":\"b\"}",
                    "{\"assetType\":\"VOUCHER_URL\",\"voucherCode\":\"a\"}",
                    "{",
                    "{}",
                    "{\"voucherCode\":\"a\"} {}"
                }) {
            var crypto = mock(EnvelopeCryptoPort.class);
            var plain = value.getBytes(StandardCharsets.UTF_8);
            when(crypto.decrypt(any(), any(), any())).thenReturn(plain);
            var decoder =
                    new ProjectStandardAssetPayloadDecoder(
                            crypto, new ObjectMapper(), mock(SupplyChainDatabaseClock.class));
            assertThatThrownBy(
                            () ->
                                    decoder.decode(
                                            FulfillmentAssetDO.builder()
                                                    .bOrderId(1L)
                                                    .sequenceNo(1)
                                                    .assetType("VOUCHER_CODE")
                                                    .lifecycleStatus((short) 10)
                                                    .build()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(plain).containsOnly((byte) 0);
        }
    }

    @Test
    void closedCardAndUrlTypesNeverReturnOtherPayloadFields() {
        for (String type : new String[] {"CARD_CODE_SECRET", "VOUCHER_URL"}) {
            var crypto = mock(EnvelopeCryptoPort.class);
            var plain =
                    (type.equals("VOUCHER_URL")
                                    ? "{\"cardCode\":\"url\",\"cardSecret\":\"hidden\",\"voucherCode\":\"hidden\"}"
                                    : "{\"cardCode\":\"card\",\"cardSecret\":\"password\",\"voucherCode\":\"hidden\",\"voucherUrl\":\"hidden\"}")
                            .getBytes(StandardCharsets.UTF_8);
            when(crypto.decrypt(any(), any(), any())).thenReturn(plain);
            var decoder =
                    new ProjectStandardAssetPayloadDecoder(
                            crypto, new ObjectMapper(), mock(SupplyChainDatabaseClock.class));
            var result =
                    decoder.decode(
                            FulfillmentAssetDO.builder()
                                    .bOrderId(1L)
                                    .sequenceNo(1)
                                    .assetType(type)
                                    .lifecycleStatus((short) 20)
                                    .build());
            if (type.equals("VOUCHER_URL")) {
                assertThat(result.getVoucherUrl()).isEqualTo("url");
                assertThat(result.getCardCode()).isNull();
                assertThat(result.getCardSecret()).isNull();
            } else {
                assertThat(result.getCardCode()).isEqualTo("card");
                assertThat(result.getCardSecret()).isEqualTo("password");
                assertThat(result.getVoucherUrl()).isNull();
            }
            assertThat(result.getVoucherCode()).isNull();
            assertThat(plain).containsOnly((byte) 0);
        }
    }

    /** 已知字段无论属于哪种资产，一旦存在都须满足封闭载荷的字符串契约。 */
    @ParameterizedTest(name = "{0}: {1}={2}")
    @MethodSource("invalidKnownFields")
    void rejectsExplicitNullAndNonStringKnownFieldsForEveryAssetType(
            String assetType, String field, String invalidValue) throws Exception {
        var payload = new ObjectMapper().createObjectNode();
        payload.put("cardCode", "delivery-secret");
        payload.set(field, new ObjectMapper().readTree(invalidValue));
        var plaintext = payload.toString().getBytes(StandardCharsets.UTF_8);
        var crypto = mock(EnvelopeCryptoPort.class);
        when(crypto.decrypt(any(), any(), any())).thenReturn(plaintext);
        var decoder =
                new ProjectStandardAssetPayloadDecoder(
                        crypto, new ObjectMapper(), mock(SupplyChainDatabaseClock.class));
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        FulfillmentAssetDO.builder()
                                                .bOrderId(1L)
                                                .sequenceNo(1)
                                                .assetType(assetType)
                                                .lifecycleStatus((short) 10)
                                                .build()))
                .isInstanceOfSatisfying(
                        ProjectStandardAssetQueryFailure.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .isEqualTo(ProjectStandardAssetAuditReason.PAYLOAD_FAILED);
                            assertThat(failure.getCause())
                                    .isInstanceOf(IllegalArgumentException.class);
                            assertThat(failure.getMessage()).doesNotContain("delivery-secret");
                        });
        assertThat(plaintext).containsOnly((byte) 0);
    }

    /** 重复字段必须在局部reader解析时拒绝，不能让后值覆盖契约违规或改写共享mapper。 */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"cardSecret\":null,\"cardSecret\":\"ignored\",\"voucherCode\":\"delivery-secret\"}",
                "{\"assetType\":\"VOUCHER_URL\",\"assetType\":\"VOUCHER_CODE\",\"voucherCode\":\"delivery-secret\"}",
                "{\"voucherCode\":\"delivery-secret\",\"voucherCode\":\"delivery-secret\"}",
                "{\"voucherCode\":\"delivery-secret\",\"unknown\":{\"nested\":1,\"nested\":2}}",
                "{\"voucherCode\":\"delivery-secret\",\"unknown\":[{\"nested\":1,\"nested\":1}]}"
            })
    void rejectsDuplicateKeysAtEveryDepthWithoutChangingSharedMapper(String raw) throws Exception {
        var plaintext = raw.getBytes(StandardCharsets.UTF_8);
        var crypto = mock(EnvelopeCryptoPort.class);
        when(crypto.decrypt(any(), any(), any())).thenReturn(plaintext);
        var sharedMapper = new ObjectMapper();
        var decoder =
                new ProjectStandardAssetPayloadDecoder(
                        crypto, sharedMapper, mock(SupplyChainDatabaseClock.class));
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        FulfillmentAssetDO.builder()
                                                .bOrderId(1L)
                                                .sequenceNo(1)
                                                .assetType("VOUCHER_CODE")
                                                .lifecycleStatus((short) 10)
                                                .build()))
                .isInstanceOfSatisfying(
                        ProjectStandardAssetQueryFailure.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .isEqualTo(ProjectStandardAssetAuditReason.PAYLOAD_FAILED);
                            assertThat(failure.getCause())
                                    .isInstanceOf(
                                            com.fasterxml.jackson.core.JsonParseException.class);
                            assertThat(failure.getMessage()).doesNotContain("delivery-secret");
                        });
        assertThat(plaintext).containsOnly((byte) 0);
        assertThat(
                        sharedMapper.isEnabled(
                                com.fasterxml.jackson.core.JsonParser.Feature
                                        .STRICT_DUPLICATE_DETECTION))
                .isFalse();
        assertThat(sharedMapper.readTree("{\"value\":1,\"value\":2}").get("value").intValue())
                .isEqualTo(2);
    }

    private static Stream<Arguments> invalidKnownFields() {
        return Stream.of("VOUCHER_CODE", "CARD_CODE_SECRET", "VOUCHER_URL")
                .flatMap(
                        type ->
                                Stream.of(
                                                "assetType",
                                                "cardCode",
                                                "cardSecret",
                                                "voucherCode",
                                                "voucherUrl")
                                        .flatMap(
                                                field ->
                                                        Stream.of("null", "[]", "{}")
                                                                .map(
                                                                        value ->
                                                                                Arguments.of(
                                                                                        type, field,
                                                                                        value))));
    }

    @Test
    void ignoresUnknownFieldsWithoutDeliveringThem() {
        var plaintext =
                "{\"cardCode\":\"legacy-voucher\",\"unknown\":{\"value\":\"hidden-secret\"}}"
                        .getBytes(StandardCharsets.UTF_8);
        var crypto = mock(EnvelopeCryptoPort.class);
        when(crypto.decrypt(any(), any(), any())).thenReturn(plaintext);
        var response =
                new ProjectStandardAssetPayloadDecoder(
                                crypto, new ObjectMapper(), mock(SupplyChainDatabaseClock.class))
                        .decode(
                                FulfillmentAssetDO.builder()
                                        .bOrderId(1L)
                                        .sequenceNo(1)
                                        .assetType("VOUCHER_CODE")
                                        .lifecycleStatus((short) 10)
                                        .build());
        assertThat(response.getVoucherCode()).isEqualTo("legacy-voucher");
        assertThat(response.toString()).doesNotContain("legacy-voucher", "hidden-secret");
        assertThat(plaintext).containsOnly((byte) 0);
    }

    @Test
    void cryptoFailurePreservesCauseWithSafeMessage() {
        var crypto = mock(EnvelopeCryptoPort.class);
        var cause = new IllegalStateException("secret-provider-value");
        when(crypto.decrypt(any(), any(), any())).thenThrow(cause);
        var decoder =
                new ProjectStandardAssetPayloadDecoder(
                        crypto, new ObjectMapper(), mock(SupplyChainDatabaseClock.class));
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        FulfillmentAssetDO.builder()
                                                .bOrderId(1L)
                                                .sequenceNo(1)
                                                .assetType("VOUCHER_CODE")
                                                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(cause)
                .hasMessageNotContaining("secret-provider-value");
    }
}
