package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 华昇卡片协议模型、载荷强类型与安全边界单元测试。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
 */
class HuashengProtocolCardTest {

    private static final String SECRET_CODE = "SECRET_CARD_CODE_123456";
    private static final String SECRET_PASS = "SECRET_PASS_987654";
    private static final String SECRET_CRC = "SECRET_CRC_777";
    private static final String SECRET_URL = "https://card.supplier.com/redeem?token=xyz";
    private static final String SECRET_URL_PASS = "SECRET_URL_PASS_888";

    @Test
    void card201CreationAndSecurityInvariants() {
        var cardPayload = new HuashengCardPayload.Card201(SECRET_CODE, SECRET_PASS);
        var card = new HuashengProtocolCard(
                HuashengField.ofValue(1001L),
                HuashengCardType.T201,
                HuashengField.ofValue(SECRET_CODE),
                HuashengField.ofValue(SECRET_PASS),
                HuashengField.absent(),
                HuashengField.absent(),
                HuashengField.absent(),
                HuashengField.ofValue("2026-12-31"),
                cardPayload
        );

        assertEquals(HuashengCardType.T201, card.type());
        assertTrue(card.id().isPresent());
        assertEquals(1001L, card.id().asOptional().orElseThrow());
        assertEquals(SECRET_CODE, card.code().asOptional().orElseThrow());
        assertEquals(SECRET_PASS, card.pass().asOptional().orElseThrow());
        assertTrue(card.crc().asOptional().isEmpty());
        assertEquals("2026-12-31", card.validTime().asOptional().orElseThrow());

        // 强类型载荷断言
        assertInstanceOf(HuashengCardPayload.Card201.class, card.payload());
        var p = (HuashengCardPayload.Card201) card.payload();
        assertEquals(SECRET_CODE, p.code());
        assertEquals(SECRET_PASS, p.pass());

        // 敏感信息不可在 toString 中泄漏
        String str = card.toString();
        assertFalse(str.contains(SECRET_CODE), "card.toString() 严禁包含卡号");
        assertFalse(str.contains(SECRET_PASS), "card.toString() 严禁包含卡密");
        assertTrue(str.contains("[redacted]") || str.contains("redacted"), "card.toString() 必须包含脱敏标识");

        String payloadStr = cardPayload.toString();
        assertFalse(payloadStr.contains(SECRET_CODE), "payload.toString() 严禁包含卡号");
        assertFalse(payloadStr.contains(SECRET_PASS), "payload.toString() 严禁包含卡密");

        // 禁止意外 JSON 序列化
        ObjectMapper mapper = new ObjectMapper();
        JsonMappingException ex1 = assertThrows(JsonMappingException.class, () -> mapper.writeValueAsString(card));
        assertTrue(ex1.getMessage().contains("内部协议对象禁止序列化"));

        JsonMappingException ex2 = assertThrows(JsonMappingException.class, () -> mapper.writeValueAsString(cardPayload));
        assertTrue(ex2.getMessage().contains("内部协议对象禁止序列化"));
    }

    @Test
    void allSevenCardPayloadsCoverageAndSecurityInvariants() {
        ObjectMapper mapper = new ObjectMapper();

        // 202: 仅code、仅pass、两者皆有
        var p202Both = new HuashengCardPayload.Card202(HuashengField.ofValue(SECRET_CODE), HuashengField.ofValue(SECRET_PASS));
        assertEquals(SECRET_CODE, p202Both.code().asOptional().orElseThrow());
        assertEquals(SECRET_PASS, p202Both.pass().asOptional().orElseThrow());
        assertFalse(p202Both.toString().contains(SECRET_CODE));
        assertThrows(JsonMappingException.class, () -> mapper.writeValueAsString(p202Both));

        var p202CodeOnly = new HuashengCardPayload.Card202(HuashengField.ofValue(SECRET_CODE), HuashengField.absent());
        assertTrue(p202CodeOnly.code().isPresent());
        assertTrue(p202CodeOnly.pass().asOptional().isEmpty());

        // 203: code + pass + crc
        var p203 = new HuashengCardPayload.Card203(SECRET_CODE, SECRET_PASS, SECRET_CRC);
        assertEquals(SECRET_CODE, p203.code());
        assertEquals(SECRET_PASS, p203.pass());
        assertEquals(SECRET_CRC, p203.crc());
        assertFalse(p203.toString().contains(SECRET_CRC));
        assertThrows(JsonMappingException.class, () -> mapper.writeValueAsString(p203));

        // 301: url
        var p301 = new HuashengCardPayload.Card301(SECRET_URL);
        assertEquals(SECRET_URL, p301.url());
        assertFalse(p301.toString().contains("redeem"));
        assertThrows(JsonMappingException.class, () -> mapper.writeValueAsString(p301));

        // 302: url + urlPass
        var p302 = new HuashengCardPayload.Card302(SECRET_URL, SECRET_URL_PASS);
        assertEquals(SECRET_URL, p302.url());
        assertEquals(SECRET_URL_PASS, p302.urlPass());
        assertFalse(p302.toString().contains(SECRET_URL_PASS));
        assertThrows(JsonMappingException.class, () -> mapper.writeValueAsString(p302));

        // 303: code + url + urlPass
        var p303 = new HuashengCardPayload.Card303(SECRET_CODE, SECRET_URL, SECRET_URL_PASS);
        assertEquals(SECRET_CODE, p303.code());
        assertEquals(SECRET_URL, p303.url());
        assertEquals(SECRET_URL_PASS, p303.urlPass());
        assertFalse(p303.toString().contains(SECRET_CODE));
        assertThrows(JsonMappingException.class, () -> mapper.writeValueAsString(p303));

        // 304: code + url
        var p304 = new HuashengCardPayload.Card304(SECRET_CODE, SECRET_URL);
        assertEquals(SECRET_CODE, p304.code());
        assertEquals(SECRET_URL, p304.url());
        assertFalse(p304.toString().contains(SECRET_URL));
        assertThrows(JsonMappingException.class, () -> mapper.writeValueAsString(p304));
    }

    @Test
    void fieldThreeStateInvariants() {
        HuashengField<String> absent = HuashengField.absent();
        assertFalse(absent.isPresent());
        assertTrue(absent.asOptional().isEmpty());
        assertEquals("HuashengField[ABSENT]", absent.toString());

        HuashengField<String> ofNull = HuashengField.ofNull();
        assertFalse(ofNull.isPresent());
        assertTrue(ofNull.asOptional().isEmpty());
        assertEquals("HuashengField[NULL]", ofNull.toString());

        HuashengField<String> value = HuashengField.ofValue(SECRET_PASS);
        assertTrue(value.isPresent());
        assertEquals(SECRET_PASS, value.asOptional().orElseThrow());
        assertEquals("HuashengField[VALUE]", value.toString());
        assertFalse(value.toString().contains(SECRET_PASS), "Value.toString() 严禁包含真实值");

        assertThrows(NullPointerException.class, () -> HuashengField.ofValue(null));
    }
}
