package com.aifuli.module.supplychain.supplieradapter.qnm;

import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 奇那梦协议通用解析器单元测试。
 */
class QnmProtocolParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("isTransientError: 识别 5xx、408、429 为瞬时网关/网络错误")
    void shouldRecognizeTransientErrors() {
        assertThat(QnmProtocolParser.isTransientError(500)).isTrue();
        assertThat(QnmProtocolParser.isTransientError(502)).isTrue();
        assertThat(QnmProtocolParser.isTransientError(503)).isTrue();
        assertThat(QnmProtocolParser.isTransientError(504)).isTrue();
        assertThat(QnmProtocolParser.isTransientError(408)).isTrue();
        assertThat(QnmProtocolParser.isTransientError(429)).isTrue();

        assertThat(QnmProtocolParser.isTransientError(200)).isFalse();
        assertThat(QnmProtocolParser.isTransientError(400)).isFalse();
        assertThat(QnmProtocolParser.isTransientError(401)).isFalse();
        assertThat(QnmProtocolParser.isTransientError(404)).isFalse();
        assertThat(QnmProtocolParser.isTransientError(null)).isFalse();
    }

    @Test
    @DisplayName("isOrderNotFound: 识别 HTTP 404、ResCode 404 或描述文本未找到")
    void shouldRecognizeOrderNotFound() {
        assertThat(QnmProtocolParser.isOrderNotFound(404, null, null)).isTrue();
        assertThat(QnmProtocolParser.isOrderNotFound(200, "404", "ok")).isTrue();
        assertThat(QnmProtocolParser.isOrderNotFound(200, "-404", "ok")).isTrue();
        assertThat(QnmProtocolParser.isOrderNotFound(200, "0", "订单未找到")).isTrue();
        assertThat(QnmProtocolParser.isOrderNotFound(200, "1", "order not found")).isTrue();

        assertThat(QnmProtocolParser.isOrderNotFound(200, "1", "成功")).isFalse();
        assertThat(QnmProtocolParser.isOrderNotFound(null, null, null)).isFalse();
    }

    @Test
    @DisplayName("parseSumPrice: 准确解析浮点数（元转分）、整数分及异常值")
    void shouldParseSumPriceCorrectly() throws Exception {
        assertThat(QnmProtocolParser.parseSumPrice("98.50")).isEqualTo(9850L);
        assertThat(QnmProtocolParser.parseSumPrice("0.01")).isEqualTo(1L);
        assertThat(QnmProtocolParser.parseSumPrice("100")).isEqualTo(100L);
        assertThat(QnmProtocolParser.parseSumPrice("")).isNull();
        assertThat(QnmProtocolParser.parseSumPrice((String) null)).isNull();
        assertThat(QnmProtocolParser.parseSumPrice("invalid_amount")).isNull();

        JsonNode floatNode = objectMapper.readTree("{\"price\": 98.50}").get("price");
        assertThat(QnmProtocolParser.parseSumPrice(floatNode)).isEqualTo(9850L);

        JsonNode intNode = objectMapper.readTree("{\"price\": 9850}").get("price");
        assertThat(QnmProtocolParser.parseSumPrice(intNode)).isEqualTo(9850L);

        JsonNode strNode = objectMapper.readTree("{\"price\": \"98.50\"}").get("price");
        assertThat(QnmProtocolParser.parseSumPrice(strNode)).isEqualTo(9850L);

        assertThat(QnmProtocolParser.parseSumPrice((JsonNode) null)).isNull();
    }

    @Test
    @DisplayName("parseCards: 解析包含字符串、对象别名、多时间格式及单卡号的资产列表")
    void shouldParseCardsMultiFormat() throws Exception {
        // 1. 纯字符串数组
        String stringArray = "[\"CARD_ONLY_PWD\", \"NO123,PWD123\", \"NO456:PWD456\"]";
        List<OperationAssetFact> stringFacts = QnmProtocolParser.parseCards(stringArray, objectMapper);
        assertThat(stringFacts).hasSize(3);
        assertThat(stringFacts.get(0).cardSecret()).isEqualTo("CARD_ONLY_PWD");
        assertThat(stringFacts.get(0).sequenceNo()).isEqualTo(1);
        assertThat(stringFacts.get(1).cardNo()).isEqualTo("NO123");
        assertThat(stringFacts.get(1).cardSecret()).isEqualTo("PWD123");
        assertThat(stringFacts.get(2).cardNo()).isEqualTo("NO456");
        assertThat(stringFacts.get(2).cardSecret()).isEqualTo("PWD456");

        // 2. 对象数组（含别名与时间）
        String objArray = """
                [
                    {"card_no": "C_01", "password": "P_01", "validUntil": "2029-01-01T12:00:00"},
                    {"cardno": "C_02", "pwd": "P_02", "expireTime": "2029-01-01 12:00:00"},
                    {"card": "VOUCHER_ONLY_CODE"}
                ]
                """;
        List<OperationAssetFact> objFacts = QnmProtocolParser.parseCards(objArray, objectMapper);
        assertThat(objFacts).hasSize(3);
        assertThat(objFacts.get(0).cardNo()).isEqualTo("C_01");
        assertThat(objFacts.get(0).cardSecret()).isEqualTo("P_01");
        assertThat(objFacts.get(0).validUntil()).isEqualTo(LocalDateTime.of(2029, 1, 1, 12, 0, 0));
        assertThat(objFacts.get(0).assetType()).isEqualTo("CARD_CODE_SECRET");

        assertThat(objFacts.get(1).cardNo()).isEqualTo("C_02");
        assertThat(objFacts.get(1).cardSecret()).isEqualTo("P_02");
        assertThat(objFacts.get(1).validUntil()).isEqualTo(LocalDateTime.of(2029, 1, 1, 12, 0, 0));

        assertThat(objFacts.get(2).cardNo()).isEqualTo("VOUCHER_ONLY_CODE");
        assertThat(objFacts.get(2).cardSecret()).isNull();
        assertThat(objFacts.get(2).assetType()).isEqualTo("VOUCHER_CODE");

        // 3. 空值容错与管道符支持
        String pipeArray = "[\"NO789|PWD789\", \"VOUCHER_ONLY|\"]";
        List<OperationAssetFact> pipeFacts = QnmProtocolParser.parseCards(pipeArray, objectMapper);
        assertThat(pipeFacts).hasSize(2);
        assertThat(pipeFacts.get(0).cardNo()).isEqualTo("NO789");
        assertThat(pipeFacts.get(0).cardSecret()).isEqualTo("PWD789");
        assertThat(pipeFacts.get(0).assetType()).isEqualTo("CARD_CODE_SECRET");
        assertThat(pipeFacts.get(1).cardNo()).isEqualTo("VOUCHER_ONLY");
        assertThat(pipeFacts.get(1).cardSecret()).isNull();
        assertThat(pipeFacts.get(1).assetType()).isEqualTo("VOUCHER_CODE");

        assertThat(QnmProtocolParser.parseCards("", objectMapper)).isEmpty();
        assertThat(QnmProtocolParser.parseCards((String) null, objectMapper)).isEmpty();
        assertThat(QnmProtocolParser.parseCards("not_a_json", objectMapper)).isEmpty();
    }

    @Test
    @DisplayName("extractOrderSn: 从 JsonNode 和 Map 中提取 orderSn 及其别名")
    void shouldExtractOrderSnWithAliases() throws Exception {
        JsonNode camelNode = objectMapper.readTree("{\"orderSn\": \"SN_001\"}");
        assertThat(QnmProtocolParser.extractOrderSn(camelNode)).isEqualTo("SN_001");

        JsonNode underNode = objectMapper.readTree("{\"order_sn\": \"SN_002\"}");
        assertThat(QnmProtocolParser.extractOrderSn(underNode)).isEqualTo("SN_002");

        JsonNode lowerNode = objectMapper.readTree("{\"ordersn\": \"SN_003\"}");
        assertThat(QnmProtocolParser.extractOrderSn(lowerNode)).isEqualTo("SN_003");

        assertThat(QnmProtocolParser.extractOrderSn((JsonNode) null)).isNull();

        Map<String, String> params = Map.of("order_sn", "SN_MAP_001");
        assertThat(QnmProtocolParser.extractOrderSn(params)).isEqualTo("SN_MAP_001");
        assertThat(QnmProtocolParser.extractOrderSn((Map<String, String>) null)).isNull();
    }

    @Test
    @DisplayName("extractOrderStatus: 支持 orderStatus、order_status 与 status，支持数字与字符串")
    void shouldExtractOrderStatusWithAliases() throws Exception {
        JsonNode n1 = objectMapper.readTree("{\"orderStatus\": 1}");
        assertThat(QnmProtocolParser.extractOrderStatus(n1)).isEqualTo(1);

        JsonNode n2 = objectMapper.readTree("{\"order_status\": \"2\"}");
        assertThat(QnmProtocolParser.extractOrderStatus(n2)).isEqualTo(2);

        JsonNode n3 = objectMapper.readTree("{\"status\": 0}");
        assertThat(QnmProtocolParser.extractOrderStatus(n3)).isEqualTo(0);

        JsonNode n4 = objectMapper.readTree("{\"OrderStatus\": 1}");
        assertThat(QnmProtocolParser.extractOrderStatus(n4)).isEqualTo(1);

        JsonNode n5 = objectMapper.readTree("{\"Status\": \"2\"}");
        assertThat(QnmProtocolParser.extractOrderStatus(n5)).isEqualTo(2);

        JsonNode empty = objectMapper.readTree("{}");
        assertThat(QnmProtocolParser.extractOrderStatus(empty)).isNull();
        assertThat(QnmProtocolParser.extractOrderStatus(null)).isNull();
    }

    @Test
    @DisplayName("extractResCode 与 extractResMsg: 支持 ResCode/resCode/res_code/code 与 ResMsg/resMsg/msg/message")
    void shouldExtractResCodeAndResMsgWithAliases() throws Exception {
        JsonNode n1 = objectMapper.readTree("{\"ResCode\": \"1\", \"ResMsg\": \"成功\"}");
        assertThat(QnmProtocolParser.extractResCode(n1)).isEqualTo("1");
        assertThat(QnmProtocolParser.extractResMsg(n1)).isEqualTo("成功");

        JsonNode n2 = objectMapper.readTree("{\"code\": -102, \"message\": \"余额不足\"}");
        assertThat(QnmProtocolParser.extractResCode(n2)).isEqualTo("-102");
        assertThat(QnmProtocolParser.extractResMsg(n2)).isEqualTo("余额不足");

        JsonNode n3 = objectMapper.readTree("{\"res_code\": \"OK\", \"msg\": \"操作完成\"}");
        assertThat(QnmProtocolParser.extractResCode(n3)).isEqualTo("OK");
        assertThat(QnmProtocolParser.extractResMsg(n3)).isEqualTo("操作完成");

        assertThat(QnmProtocolParser.extractResCode(null)).isEmpty();
        assertThat(QnmProtocolParser.extractResMsg(null)).isEmpty();
    }

    @Test
    @DisplayName("parseCards: 解析包含三段式（卡号|卡密|有效期）字符串与对象中 externalAssetId/validFrom/cardNumber")
    void shouldParseCardsThreePartStringAndRichObject() throws Exception {
        // 1. 三段式纯字符串
        String threePartArray = "[\"CARD_101|PWD_101|2029-12-31 23:59:59\", \"CARD_102,PWD_102,2029-12-31 23:59:59\"]";
        List<OperationAssetFact> threePartFacts = QnmProtocolParser.parseCards(threePartArray, objectMapper);
        assertThat(threePartFacts).hasSize(2);
        assertThat(threePartFacts.get(0).cardNo()).isEqualTo("CARD_101");
        assertThat(threePartFacts.get(0).cardSecret()).isEqualTo("PWD_101");
        assertThat(threePartFacts.get(0).validUntil()).isEqualTo(LocalDateTime.of(2029, 12, 31, 23, 59, 59));

        assertThat(threePartFacts.get(1).cardNo()).isEqualTo("CARD_102");
        assertThat(threePartFacts.get(1).cardSecret()).isEqualTo("PWD_102");
        assertThat(threePartFacts.get(1).validUntil()).isEqualTo(LocalDateTime.of(2029, 12, 31, 23, 59, 59));

        // 2. 富对象属性
        String richObjArray = """
                [
                    {
                        "cardId": "ASSET_999",
                        "cardNumber": "CARD_RICH_01",
                        "card_secret": "SECRET_RICH_01",
                        "startTime": "2026-01-01 00:00:00",
                        "expireTime": "2028-12-31 23:59:59"
                    }
                ]
                """;
        List<OperationAssetFact> richFacts = QnmProtocolParser.parseCards(richObjArray, objectMapper);
        assertThat(richFacts).hasSize(1);
        OperationAssetFact fact = richFacts.get(0);
        assertThat(fact.externalAssetId()).isEqualTo("ASSET_999");
        assertThat(fact.cardNo()).isEqualTo("CARD_RICH_01");
        assertThat(fact.cardSecret()).isEqualTo("SECRET_RICH_01");
        assertThat(fact.validFrom()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertThat(fact.validUntil()).isEqualTo(LocalDateTime.of(2028, 12, 31, 23, 59, 59));
    }
}
