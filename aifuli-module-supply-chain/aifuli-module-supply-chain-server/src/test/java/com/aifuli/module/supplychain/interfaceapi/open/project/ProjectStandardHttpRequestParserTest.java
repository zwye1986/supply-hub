package com.aifuli.module.supplychain.interfaceapi.open.project;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.interfaceapi.open.project.dto.ProjectStandardOrderCreateReqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目标准 HTTP 请求专用严格解析器单元测试。
 * 验证严格数量、未知字段拒绝、重复键拒绝、尾随字符拒绝与 target 纯字符串平铺契约。
 * 详见 {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#1-controller与严格请求解析}。
 */
class ProjectStandardHttpRequestParserTest {

    private ProjectStandardHttpRequestParser parser;

    @BeforeEach
    void setUp() {
        parser = new ProjectStandardHttpRequestParser();
    }

    @Test
    void parseCreateSuccessWithValidPayload() {
        String json = """
                {
                    "buyerOrderNo": "ORD_123456",
                    "buyerSkuCode": "SKU_8888",
                    "quantity": 1,
                    "target": {
                        "accountType": "MOBILE",
                        "account": "13800138000"
                    },
                    "previousReceiptVersion": 2
                }
                """;
        ProjectStandardOrderCreateReqDTO dto = parser.parseCreate(json.getBytes(StandardCharsets.UTF_8));
        assertThat(dto.getBuyerOrderNo()).isEqualTo("ORD_123456");
        assertThat(dto.getBuyerSkuCode()).isEqualTo("SKU_8888");
        assertThat(dto.getQuantity()).isEqualTo(1);
        assertThat(dto.getTarget())
                .containsEntry("accountType", "MOBILE")
                .containsEntry("account", "13800138000");
        assertThat(dto.getPreviousReceiptVersion()).isEqualTo(2L);
    }

    @Test
    void parseCreateSuccessWithoutOptionalPreviousReceiptVersion() {
        String json = """
                {
                    "buyerOrderNo": "ORD_123456",
                    "buyerSkuCode": "SKU_8888",
                    "quantity": 1,
                    "target": {
                        "deliveryMode": "BUYER_ASSET_QUERY"
                    }
                }
                """;
        ProjectStandardOrderCreateReqDTO dto = parser.parseCreate(json.getBytes(StandardCharsets.UTF_8));
        assertThat(dto.getPreviousReceiptVersion()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"buyerOrderNo\":\"ORD_1\",\"buyerSkuCode\":\"SKU_1\",\"quantity\":\"1\",\"target\":{\"k\":\"v\"}}", // 字符串 1
            "{\"buyerOrderNo\":\"ORD_1\",\"buyerSkuCode\":\"SKU_1\",\"quantity\":1.0,\"target\":{\"k\":\"v\"}}", // 浮点 1.0
            "{\"buyerOrderNo\":\"ORD_1\",\"buyerSkuCode\":\"SKU_1\",\"quantity\":0,\"target\":{\"k\":\"v\"}}",   // 0
            "{\"buyerOrderNo\":\"ORD_1\",\"buyerSkuCode\":\"SKU_1\",\"quantity\":2,\"target\":{\"k\":\"v\"}}",   // 2
            "{\"buyerOrderNo\":\"ORD_1\",\"buyerSkuCode\":\"SKU_1\",\"quantity\":-1,\"target\":{\"k\":\"v\"}}",  // 负数
            "{\"buyerOrderNo\":\"ORD_1\",\"buyerSkuCode\":\"SKU_1\",\"quantity\":null,\"target\":{\"k\":\"v\"}}" // null
    })
    void parseCreateRejectsInvalidQuantity(String json) {
        assertThatThrownBy(() -> parser.parseCreate(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"k\":\"v\"},\"buyerUserId\":\"U123\"}",
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"k\":\"v\"},\"limitMobile\":\"13800000000\"}",
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"k\":\"v\"},\"listingVersion\":1}",
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"k\":\"v\"},\"pricingQuoteToken\":\"T1\"}",
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"k\":\"v\"},\"paidAt\":\"2026-10-02T12:00:00\"}",
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"k\":\"v\"},\"paidAmountCents\":100}",
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"k\":\"v\"},\"unknownField\":\"x\"}"
    })
    void parseCreateRejectsUnknownOrDeprecatedFields(String json) {
        assertThatThrownBy(() -> parser.parseCreate(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void parseCreateRejectsDuplicateKeys() {
        String json = """
                {
                    "buyerOrderNo": "ORD_1",
                    "buyerOrderNo": "ORD_2",
                    "buyerSkuCode": "SKU_1",
                    "quantity": 1,
                    "target": {"k": "v"}
                }
                """;
        assertThatThrownBy(() -> parser.parseCreate(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void parseCreateRejectsDuplicateKeysInTarget() {
        String json = """
                {
                    "buyerOrderNo": "ORD_1",
                    "buyerSkuCode": "SKU_1",
                    "quantity": 1,
                    "target": {
                        "account": "13800000001",
                        "account": "13800000002"
                    }
                }
                """;
        assertThatThrownBy(() -> parser.parseCreate(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void parseCreateRejectsTrailingContent() {
        String json = "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"k\":\"v\"}} {\"extra\":1}";
        assertThatThrownBy(() -> parser.parseCreate(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"nested\":{\"a\":\"b\"}}}",
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"num\":123}}",
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"bool\":true}}",
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"nil\":null}}",
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"arr\":[\"a\"]}}"
    })
    void parseCreateRejectsNonStringTargetValues(String json) {
        assertThatThrownBy(() -> parser.parseCreate(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "null", "[]", "\"hello\"", "123", "{}",
            "{\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{\"k\":\"v\"}}", // 缺 buyerOrderNo
            "{\"buyerOrderNo\":\"O1\",\"quantity\":1,\"target\":{\"k\":\"v\"}}",  // 缺 buyerSkuCode
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"target\":{\"k\":\"v\"}}", // 缺 quantity
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1}", // 缺 target
            "{\"buyerOrderNo\":\"O1\",\"buyerSkuCode\":\"S1\",\"quantity\":1,\"target\":{}}" // target 为空对象
    })
    void parseCreateRejectsIncompleteOrInvalidRoot(String json) {
        assertThatThrownBy(() -> parser.parseCreate(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void parseQuerySuccessWithValidPayload() {
        String json = """
                {"buyerOrderNo":"ORD_123456"}
                """;
        String buyerOrderNo = parser.parseQuery(json.getBytes(StandardCharsets.UTF_8));
        assertThat(buyerOrderNo).isEqualTo("ORD_123456");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "{}", "null", "[]", "123",
            "{\"buyerOrderNo\":\"\"}",
            "{\"buyerOrderNo\":\"   \"}",
            "{\"buyerOrderNo\":123}",
            "{\"orderNo\":\"ORD_123\"}",
            "{\"requestId\":\"REQ_123\"}",
            "{\"buyerOrderNo\":\"ORD_1\",\"extra\":\"x\"}",
            "{\"buyerOrderNo\":\"ORD_1\",\"buyerOrderNo\":\"ORD_2\"}",
            "{\"buyerOrderNo\":\"ORD_1\"} {\"extra\":1}"
    })
    void parseQueryRejectsInvalidOrUnknownFields(String json) {
        assertThatThrownBy(() -> parser.parseQuery(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void parseAssetsQueryReusesStrictValidation() {
        String json = """
                {"buyerOrderNo":"ORD_999"}
                """;
        assertThat(parser.parseAssetsQuery(json.getBytes(StandardCharsets.UTF_8))).isEqualTo("ORD_999");

        assertThatThrownBy(() -> parser.parseAssetsQuery("{\"buyerOrderNo\":null}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class);
    }
}
