package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengDiagnosticProjector;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 华昇严格响应解析器单元测试。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#51-json-接受规则、§15.3。
 */
class HuashengResponseParserTest {

    private static final String SECRET_SENTINEL = "MALICIOUS_SENTINEL_TOKEN_XYZ";

    @Test
    void security66ClassifiesOnlyTheOfficialFixedMessages() {
        assertEquals(HuashengSecurityReason.MISSING_FIELD, HuashengSecurityReason.fromOfficialMessage("缺少签名"));
        assertEquals(HuashengSecurityReason.KEY_CONFIG_ERROR, HuashengSecurityReason.fromOfficialMessage("RSA公钥解析失败"));
        assertEquals(HuashengSecurityReason.REQUEST_EXPIRED, HuashengSecurityReason.fromOfficialMessage("请求已过期"));
        assertEquals(HuashengSecurityReason.DUPLICATE_NONCE, HuashengSecurityReason.fromOfficialMessage("重复请求"));
        assertNull(HuashengSecurityReason.fromOfficialMessage("未知业务错误，含签名、密钥与nonce关键词"));
        assertNull(HuashengSecurityReason.fromOfficialMessage("签名验证失败\n"));
    }

    @Test
    void envelopeParsingBusinessErrorKnownAndUnknownCodes() {
        // code=61 应当解析为 Business(TOKEN_UNAUTHORIZED)
        String json61 = "{\"code\":61,\"message\":\"token unauthorized\",\"data\":{\"sensitive\":\"do_not_parse\"}}";
        var attempt61 = HuashengResponseParser.parseEnvelope(json61);
        assertTrue(attempt61 instanceof HuashengInternalAttempt.Parsed, "code!=0 应当作为 ParseFact.Business 成功解析事实");
        var parsed61 = (HuashengInternalAttempt.Parsed<HuashengParseFact<JsonNode>>) attempt61;
        assertInstanceOf(HuashengParseFact.Business.class, parsed61.value());
        var business61 = (HuashengParseFact.Business<JsonNode>) parsed61.value();
        assertEquals(61, business61.error().rawCode());
        assertEquals(HuashengErrorCode.TOKEN_UNAUTHORIZED, business61.error().knownErrorKind());
        assertTrue(business61.error().safeExchangeEvidence().isEmpty());

        // 未知错误码保真为 UNKNOWN
        String jsonUnknown = "{\"code\":99999,\"message\":\"unknown future code\"}";
        var attemptUnknown = HuashengResponseParser.parseEnvelope(jsonUnknown);
        assertTrue(attemptUnknown instanceof HuashengInternalAttempt.Parsed);
        var parsedUnknown = (HuashengInternalAttempt.Parsed<HuashengParseFact<JsonNode>>) attemptUnknown;
        var businessUnknown = (HuashengParseFact.Business<JsonNode>) parsedUnknown.value();
        assertEquals(99999, businessUnknown.error().rawCode());
        assertEquals(HuashengErrorCode.UNKNOWN, businessUnknown.error().knownErrorKind());

        // 目录码 5001 兜底为 UNKNOWN
        String json5001 = "{\"code\":5001,\"message\":\"catalog error\"}";
        var attempt5001 = HuashengResponseParser.parseEnvelope(json5001);
        assertTrue(attempt5001 instanceof HuashengInternalAttempt.Parsed);
        var parsed5001 = (HuashengInternalAttempt.Parsed<HuashengParseFact<JsonNode>>) attempt5001;
        var business5001 = (HuashengParseFact.Business<JsonNode>) parsed5001.value();
        assertEquals(5001, business5001.error().rawCode());
        assertEquals(HuashengErrorCode.UNKNOWN, business5001.error().knownErrorKind());
    }

    @Test
    void envelopeSecretSentinelAndDuplicateDetection() {
        // 重复 key 恶意包含秘密哨兵
        String dupJson = "{\"code\":0,\"data\":{},\"secret\":\"" + SECRET_SENTINEL + "\",\"secret\":\"" + SECRET_SENTINEL + "\"}";
        var attempt = HuashengResponseParser.parseEnvelope(dupJson);
        assertTrue(attempt instanceof HuashengInternalAttempt.Failed, "重复key必须被严格拒绝");
        var failed = (HuashengInternalAttempt.Failed<HuashengParseFact<JsonNode>>) attempt;
        var protectedFailure = failed.failure();

        // 诊断投影单向脱敏，不泄漏秘密
        HuashengFailureDiagnostics safe = HuashengDiagnosticProjector.toSafeDiagnostics(protectedFailure);
        assertEquals(HuashengFailureKind.ENVELOPE_INVALID, safe.failureKind());
        assertTrue(safe.evidence().isEmpty());
        assertFalse(safe.toString().contains(SECRET_SENTINEL), "诊断toString严禁泄漏秘密");
    }

    @Test
    void tokenParsingHappyAndBoundaryAndNegativeCases() {
        // 1. 最小合法 TOKEN 夹具 (F_TOKEN_MINIMAL)
        String jsonMinimal = "{\"code\":0,\"data\":{\"access_token\":\"TOKEN_FIXTURE\",\"expires_in\":7200}}";
        var attemptMinimal = HuashengResponseParser.parseToken(jsonMinimal);
        assertTrue(attemptMinimal instanceof HuashengInternalAttempt.Parsed, "合法TOKEN响应应当成功解析");
        var factMinimal = ((HuashengInternalAttempt.Parsed<HuashengParseFact<HuashengTokenResponse>>) attemptMinimal).value();
        assertInstanceOf(HuashengParseFact.Data.class, factMinimal);
        HuashengTokenResponse tokenResp = ((HuashengParseFact.Data<HuashengTokenResponse>) factMinimal).data();
        assertEquals("TOKEN_FIXTURE", tokenResp.accessToken());
        assertEquals(7200, tokenResp.expiresInSeconds());

        // 2. 7201 边界在 A2 阶段合法通过 (F_TOKEN_7201_BOUNDARY，本地7200上限属于A5策略)
        String json7201 = "{\"code\":0,\"data\":{\"access_token\":\"TOKEN_FIXTURE_7201\",\"expires_in\":7201}}";
        var attempt7201 = HuashengResponseParser.parseToken(json7201);
        assertTrue(attempt7201 instanceof HuashengInternalAttempt.Parsed);
        var token7201 = ((HuashengParseFact.Data<HuashengTokenResponse>) ((HuashengInternalAttempt.Parsed<HuashengParseFact<HuashengTokenResponse>>) attempt7201).value()).data();
        assertEquals(7201, token7201.expiresInSeconds());

        // 3. 缺失 access_token -> DATA_MISSING
        String jsonNoToken = "{\"code\":0,\"data\":{\"expires_in\":7200}}";
        var attemptNoToken = HuashengResponseParser.parseToken(jsonNoToken);
        assertTrue(attemptNoToken instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.DATA_MISSING, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptNoToken).failure()).failureKind());

        // 4. 空白 access_token -> DATA_MISSING
        String jsonBlankToken = "{\"code\":0,\"data\":{\"access_token\":\"   \",\"expires_in\":7200}}";
        var attemptBlankToken = HuashengResponseParser.parseToken(jsonBlankToken);
        assertTrue(attemptBlankToken instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.DATA_MISSING, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptBlankToken).failure()).failureKind());

        // 5. access_token 包含 CRLF 或 NUL -> DATA_MISSING
        String jsonCrlf = "{\"code\":0,\"data\":{\"access_token\":\"TOKEN\\nEVIL\",\"expires_in\":7200}}";
        var attemptCrlf = HuashengResponseParser.parseToken(jsonCrlf);
        assertTrue(attemptCrlf instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.DATA_MISSING, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptCrlf).failure()).failureKind());

        // 6. expires_in 为非整数 token (如 0.0 小数或非数字) -> DATA_MISSING
        String jsonDoubleExpires = "{\"code\":0,\"data\":{\"access_token\":\"TOKEN_FIXTURE\",\"expires_in\":0.0}}";
        var attemptDouble = HuashengResponseParser.parseToken(jsonDoubleExpires);
        assertTrue(attemptDouble instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.DATA_MISSING, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptDouble).failure()).failureKind());

        // 7. expires_in <= 0 -> DATA_MISSING
        String jsonZeroExpires = "{\"code\":0,\"data\":{\"access_token\":\"TOKEN_FIXTURE\",\"expires_in\":0}}";
        var attemptZero = HuashengResponseParser.parseToken(jsonZeroExpires);
        assertTrue(attemptZero instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.DATA_MISSING, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptZero).failure()).failureKind());

        // 8. 响应为业务错误时正确透传 (如 code=61)
        String jsonToken61 = "{\"code\":61,\"message\":\"unauthorized\"}";
        var attemptToken61 = HuashengResponseParser.parseToken(jsonToken61);
        assertTrue(attemptToken61 instanceof HuashengInternalAttempt.Parsed);
        assertInstanceOf(HuashengParseFact.Business.class, ((HuashengInternalAttempt.Parsed<HuashengParseFact<HuashengTokenResponse>>) attemptToken61).value());
    }

    @Test
    void takeParsingHappyAndBoundaryAndNegativeCases() {
        var expectedOrder = new HuashengExpectedOrder("OUT_FIXTURE_1", Optional.of("HS_FIXTURE_1"), 1);

        // 1. 最小合法 TAKE 夹具 (F_TAKE_MINIMAL)
        String jsonMinimal = "{\"code\":0,\"data\":{\"orderSn\":\"HS_FIXTURE_1\",\"num\":1,\"list\":[{\"type\":201,\"code\":\"TEST_CODE\",\"pass\":\"TEST_PASS\"}]}}";
        var attemptMinimal = HuashengResponseParser.parseTake(jsonMinimal, expectedOrder);
        assertTrue(attemptMinimal instanceof HuashengInternalAttempt.Parsed, "合法TAKE响应应当成功解析");
        var factMinimal = ((HuashengInternalAttempt.Parsed<HuashengParseFact<HuashengTakeResponse>>) attemptMinimal).value();
        assertInstanceOf(HuashengParseFact.Data.class, factMinimal);
        HuashengTakeResponse takeResp = ((HuashengParseFact.Data<HuashengTakeResponse>) factMinimal).data();
        assertEquals("HS_FIXTURE_1", takeResp.orderSn());
        assertEquals(1, takeResp.num());
        assertEquals(1, takeResp.list().size());
        HuashengProtocolCard card = takeResp.list().get(0);
        assertEquals(HuashengCardType.T201, card.type());
        assertEquals("TEST_CODE", card.code().asOptional().orElseThrow());
        assertEquals("TEST_PASS", card.pass().asOptional().orElseThrow());
        assertTrue(card.id().asOptional().isEmpty(), "未声明id时保持Absent");
        assertTrue(card.validTime().asOptional().isEmpty(), "未声明validTime时保持Absent");

        // 2. data 额外 totalPrice 属性忽略，绝不合成或升级为结算事实
        String jsonExtraPrice = "{\"code\":0,\"data\":{\"orderSn\":\"HS_FIXTURE_1\",\"num\":1,\"totalPrice\":\"9.99\",\"list\":[{\"type\":201,\"code\":\"TEST_CODE\",\"pass\":\"TEST_PASS\"}]}}";
        var attemptExtraPrice = HuashengResponseParser.parseTake(jsonExtraPrice, expectedOrder);
        assertTrue(attemptExtraPrice instanceof HuashengInternalAttempt.Parsed);

        // 3. orderSn 身份不匹配 -> IDENTITY_MISMATCH
        var expectedMismatch = new HuashengExpectedOrder("OUT_FIXTURE_1", Optional.of("HS_OTHER_ORDER"), 1);
        var attemptMismatch = HuashengResponseParser.parseTake(jsonMinimal, expectedMismatch);
        assertTrue(attemptMismatch instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.IDENTITY_MISMATCH, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptMismatch).failure()).failureKind());

        // 4. expectedOrder 缺失 orderSn -> IDENTITY_MISMATCH
        var expectedNoRemote = new HuashengExpectedOrder("OUT_FIXTURE_1", Optional.empty(), 1);
        var attemptNoRemote = HuashengResponseParser.parseTake(jsonMinimal, expectedNoRemote);
        assertTrue(attemptNoRemote instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.IDENTITY_MISMATCH, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptNoRemote).failure()).failureKind());

        // 5. 数量不匹配：num != expectedOrder.quantity() -> QUANTITY_MISMATCH
        String jsonQuantityMismatch = "{\"code\":0,\"data\":{\"orderSn\":\"HS_FIXTURE_1\",\"num\":2,\"list\":[{\"type\":201,\"code\":\"C1\",\"pass\":\"P1\"},{\"type\":201,\"code\":\"C2\",\"pass\":\"P2\"}]}}";
        var attemptQuantityMismatch = HuashengResponseParser.parseTake(jsonQuantityMismatch, expectedOrder);
        assertTrue(attemptQuantityMismatch instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.QUANTITY_MISMATCH, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptQuantityMismatch).failure()).failureKind());

        // 6. 数量不匹配：list.size() != num -> QUANTITY_MISMATCH
        String jsonListSizeMismatch = "{\"code\":0,\"data\":{\"orderSn\":\"HS_FIXTURE_1\",\"num\":1,\"list\":[]}}";
        var attemptListMismatch = HuashengResponseParser.parseTake(jsonListSizeMismatch, expectedOrder);
        assertTrue(attemptListMismatch instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.QUANTITY_MISMATCH, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptListMismatch).failure()).failureKind());

        // 7. 卡片未知非空载荷属性拒绝 -> CARD_PAYLOAD_INVALID
        String jsonCardExtraProp = "{\"code\":0,\"data\":{\"orderSn\":\"HS_FIXTURE_1\",\"num\":1,\"list\":[{\"type\":201,\"code\":\"C\",\"pass\":\"P\",\"extraSecret\":\"SECRET_VALUE\"}]}}";
        var attemptExtraProp = HuashengResponseParser.parseTake(jsonCardExtraProp, expectedOrder);
        assertTrue(attemptExtraProp instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.CARD_PAYLOAD_INVALID, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptExtraProp).failure()).failureKind());

        // 8. 卡片未知属性若为 null 或空白/空串则忽略 -> 成功
        String jsonCardNullProp = "{\"code\":0,\"data\":{\"orderSn\":\"HS_FIXTURE_1\",\"num\":1,\"list\":[{\"type\":201,\"code\":\"C\",\"pass\":\"P\",\"extraNull\":null,\"extraEmpty\":\"\"}]}}";
        var attemptNullProp = HuashengResponseParser.parseTake(jsonCardNullProp, expectedOrder);
        assertTrue(attemptNullProp instanceof HuashengInternalAttempt.Parsed);

        // 9. 卡片缺少必填字段（201 缺少 pass）-> CARD_PAYLOAD_INVALID
        String jsonMissingPass = "{\"code\":0,\"data\":{\"orderSn\":\"HS_FIXTURE_1\",\"num\":1,\"list\":[{\"type\":201,\"code\":\"C\"}]}}";
        var attemptMissingPass = HuashengResponseParser.parseTake(jsonMissingPass, expectedOrder);
        assertTrue(attemptMissingPass instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.CARD_PAYLOAD_INVALID, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptMissingPass).failure()).failureKind());

        // 10. 卡片未知卡型 -> CARD_PAYLOAD_INVALID
        String jsonUnknownType = "{\"code\":0,\"data\":{\"orderSn\":\"HS_FIXTURE_1\",\"num\":1,\"list\":[{\"type\":999,\"code\":\"C\"}]}}";
        var attemptUnknownType = HuashengResponseParser.parseTake(jsonUnknownType, expectedOrder);
        assertTrue(attemptUnknownType instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.CARD_PAYLOAD_INVALID, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptUnknownType).failure()).failureKind());
    }

    @Test
    void orderParsingHappyAndStageAnd56StateMatrixCoverage() {
        var expectedOrder = new HuashengExpectedOrder("OUT_FIXTURE_1", Optional.of("HS_FIXTURE_1"), 1);

        // 1. PLACE 阶段最小合法夹具 (F_PLACE_CONFIRM_MINIMAL, refundStatus 与 isTake 缺省)
        String jsonPlaceMinimal = "{\"code\":0,\"data\":{\"orderSn\":\"HS_FIXTURE_1\",\"outOrderSn\":\"OUT_FIXTURE_1\",\"num\":1,\"totalPrice\":\"9.9999\",\"status\":\"WAIT\"}}";
        var expectedPlace = new HuashengExpectedOrder("OUT_FIXTURE_1", Optional.empty(), 1);
        var attemptPlace = HuashengResponseParser.parseOrder(jsonPlaceMinimal, expectedPlace, HuashengEndpoint.PLACE);
        assertTrue(attemptPlace instanceof HuashengInternalAttempt.Parsed, "合法PLACE响应应当成功解析");
        var factPlace = ((HuashengInternalAttempt.Parsed<HuashengParseFact<HuashengOrderResponse>>) attemptPlace).value();
        assertInstanceOf(HuashengParseFact.Data.class, factPlace);
        HuashengOrderResponse orderPlace = ((HuashengParseFact.Data<HuashengOrderResponse>) factPlace).data();
        assertEquals("HS_FIXTURE_1", orderPlace.orderSn());
        assertEquals("OUT_FIXTURE_1", orderPlace.outOrderSn());
        assertEquals(1, orderPlace.num());
        assertEquals(1000L, orderPlace.totalPrice().minorUnits());
        assertEquals(HuashengOrderStatus.WAIT, orderPlace.status());
        assertTrue(orderPlace.refundStatus().isEmpty(), "PLACE阶段refundStatus可缺省");
        assertTrue(orderPlace.isTake().isEmpty(), "PLACE阶段isTake可缺省");

        // 2. QUERY 阶段最小合法夹具 (F_QUERY_MINIMAL, 强制两维度存在)
        String jsonQueryMinimal = "{\"code\":0,\"data\":{\"orderSn\":\"HS_FIXTURE_1\",\"outOrderSn\":\"OUT_FIXTURE_1\",\"num\":1,\"totalPrice\":\"9.9999\",\"status\":\"WAIT\",\"refundStatus\":\"NONE\",\"isTake\":\"N\"}}";
        var attemptQuery = HuashengResponseParser.parseOrder(jsonQueryMinimal, expectedOrder, HuashengEndpoint.QUERY);
        assertTrue(attemptQuery instanceof HuashengInternalAttempt.Parsed, "合法QUERY响应应当成功解析");
        HuashengOrderResponse orderQuery = ((HuashengParseFact.Data<HuashengOrderResponse>) ((HuashengInternalAttempt.Parsed<HuashengParseFact<HuashengOrderResponse>>) attemptQuery).value()).data();
        assertEquals(HuashengRefundStatus.NONE, orderQuery.refundStatus().orElseThrow());
        assertEquals(HuashengTakeStatus.N, orderQuery.isTake().orElseThrow());

        // 3. QUERY 阶段缺少 refundStatus 或 isTake 均必须拒绝为 DATA_MISSING
        var attemptQueryMissingRefund = HuashengResponseParser.parseOrder(jsonPlaceMinimal, expectedOrder, HuashengEndpoint.QUERY);
        assertTrue(attemptQueryMissingRefund instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.DATA_MISSING, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptQueryMissingRefund).failure()).failureKind());

        // 4. stage 非法参数拒绝 (非 PLACE/CONFIRM/QUERY)
        assertThrows(IllegalArgumentException.class, () -> HuashengResponseParser.parseOrder(jsonPlaceMinimal, expectedOrder, HuashengEndpoint.TOKEN));
        assertThrows(IllegalArgumentException.class, () -> HuashengResponseParser.parseOrder(jsonPlaceMinimal, expectedOrder, HuashengEndpoint.TAKE));

        // 5. 56 组状态矛盾矩阵验证 (48 全组合: 24 矛盾 + 24 合法；另 8 组规范最小矛盾；另 11 例未知枚举值)
        String[] statuses = {"WAIT", "SUCCESS", "FAILED", "CANCEL"};
        String[] refunds = {null, "NONE", "WAIT", "SUCCESS"};
        String[] takes = {null, "N", "Y"};

        int conflictCount = 0;
        int validCount = 0;

        for (String st : statuses) {
            for (String rf : refunds) {
                for (String tk : takes) {
                    StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":{");
                    sb.append("\"orderSn\":\"HS_FIXTURE_1\",\"outOrderSn\":\"OUT_FIXTURE_1\",\"num\":1,\"totalPrice\":\"10.00\",\"status\":\"").append(st).append("\"");
                    if (rf != null) sb.append(",\"refundStatus\":\"").append(rf).append("\"");
                    if (tk != null) sb.append(",\"isTake\":\"").append(tk).append("\"");
                    sb.append("}}");

                    boolean isConflict = isStateConflict(st, rf, tk);
                    var attempt = HuashengResponseParser.parseOrder(sb.toString(), expectedOrder, HuashengEndpoint.CONFIRM);
                    if (isConflict) {
                        conflictCount++;
                        assertTrue(attempt instanceof HuashengInternalAttempt.Failed, "状态组合应冲突: st=" + st + ", rf=" + rf + ", tk=" + tk);
                        assertEquals(HuashengFailureKind.STATE_CONFLICT, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attempt).failure()).failureKind());
                    } else {
                        validCount++;
                        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed, "状态组合应合法: st=" + st + ", rf=" + rf + ", tk=" + tk);
                    }
                }
            }
        }
        assertEquals(24, conflictCount, "48全组合中应有恰好24组状态矛盾");
        assertEquals(24, validCount, "48全组合中应有恰好24组合法通过");

        // 8 组规范显式最小矛盾 (字段出现时)
        String[][] minConflictPairs = {
                {"WAIT", null, "Y"},
                {"WAIT", "SUCCESS", null},
                {"FAILED", null, "Y"},
                {"FAILED", "NONE", null},
                {"FAILED", "WAIT", null},
                {"SUCCESS", "WAIT", null},
                {"SUCCESS", "SUCCESS", null},
                {"CANCEL", null, "Y"}
        };
        for (String[] pair : minConflictPairs) {
            StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":{\"orderSn\":\"HS_FIXTURE_1\",\"outOrderSn\":\"OUT_FIXTURE_1\",\"num\":1,\"totalPrice\":\"10.00\",\"status\":\"").append(pair[0]).append("\"");
            if (pair[1] != null) sb.append(",\"refundStatus\":\"").append(pair[1]).append("\"");
            if (pair[2] != null) sb.append(",\"isTake\":\"").append(pair[2]).append("\"");
            sb.append("}}");
            var att = HuashengResponseParser.parseOrder(sb.toString(), expectedOrder, HuashengEndpoint.CONFIRM);
            assertTrue(att instanceof HuashengInternalAttempt.Failed);
            assertEquals(HuashengFailureKind.STATE_CONFLICT, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) att).failure()).failureKind());
        }

        // 11 例未知枚举值全部归入 STATE_CONFLICT
        String[] unknownValues = {
                // 未知 status (3 例)
                "\"status\":\"UNKNOWN\",\"refundStatus\":\"NONE\",\"isTake\":\"N\"",
                "\"status\":\"INIT\",\"refundStatus\":\"NONE\",\"isTake\":\"N\"",
                "\"status\":\"PENDING\",\"refundStatus\":\"NONE\",\"isTake\":\"N\"",
                // 未知 refundStatus (4 例)
                "\"status\":\"WAIT\",\"refundStatus\":\"REFUNDED\",\"isTake\":\"N\"",
                "\"status\":\"WAIT\",\"refundStatus\":\"FAIL\",\"isTake\":\"N\"",
                "\"status\":\"WAIT\",\"refundStatus\":\"UNKNOWN\",\"isTake\":\"N\"",
                "\"status\":\"WAIT\",\"refundStatus\":\"WAITING\",\"isTake\":\"N\"",
                // 未知 isTake (4 例)
                "\"status\":\"WAIT\",\"refundStatus\":\"NONE\",\"isTake\":\"YES\"",
                "\"status\":\"WAIT\",\"refundStatus\":\"NONE\",\"isTake\":\"NO\"",
                "\"status\":\"WAIT\",\"refundStatus\":\"NONE\",\"isTake\":\"1\"",
                "\"status\":\"WAIT\",\"refundStatus\":\"NONE\",\"isTake\":\"0\""
        };
        for (String uv : unknownValues) {
            String jsonUv = "{\"code\":0,\"data\":{\"orderSn\":\"HS_FIXTURE_1\",\"outOrderSn\":\"OUT_FIXTURE_1\",\"num\":1,\"totalPrice\":\"10.00\"," + uv + "}}";
            var att = HuashengResponseParser.parseOrder(jsonUv, expectedOrder, HuashengEndpoint.QUERY);
            assertTrue(att instanceof HuashengInternalAttempt.Failed);
            assertEquals(HuashengFailureKind.STATE_CONFLICT, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) att).failure()).failureKind());
        }

        // 6. 金额一致性检验：price * num != totalPrice -> MONEY_INVALID
        String jsonMoneyMismatch = "{\"code\":0,\"data\":{\"orderSn\":\"HS_FIXTURE_1\",\"outOrderSn\":\"OUT_FIXTURE_1\",\"num\":1,\"price\":\"10.00\",\"totalPrice\":\"9.99\",\"status\":\"WAIT\"}}";
        var attemptMoneyMismatch = HuashengResponseParser.parseOrder(jsonMoneyMismatch, expectedPlace, HuashengEndpoint.PLACE);
        assertTrue(attemptMoneyMismatch instanceof HuashengInternalAttempt.Failed);
        assertEquals(HuashengFailureKind.MONEY_INVALID, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) attemptMoneyMismatch).failure()).failureKind());

        // 7. 逐 required 删字段验证 -> DATA_MISSING
        String[] requiredKeys = {"orderSn", "outOrderSn", "num", "totalPrice", "status"};
        for (String req : requiredKeys) {
            String jsonMissingReq = jsonPlaceMinimal.replace("\"" + req + "\":", "\"del_" + req + "\":");
            var att = HuashengResponseParser.parseOrder(jsonMissingReq, expectedPlace, HuashengEndpoint.PLACE);
            assertTrue(att instanceof HuashengInternalAttempt.Failed);
            assertEquals(HuashengFailureKind.DATA_MISSING, HuashengDiagnosticProjector.toSafeDiagnostics(((HuashengInternalAttempt.Failed<?>) att).failure()).failureKind(), "缺少" + req + "应为DATA_MISSING");
        }
    }

    private static boolean isStateConflict(String st, String rf, String tk) {
        if ("WAIT".equals(st)) {
            if ("Y".equals(tk)) return true;
            if ("SUCCESS".equals(rf)) return true;
        } else if ("FAILED".equals(st)) {
            if ("Y".equals(tk)) return true;
            if ("NONE".equals(rf) || "WAIT".equals(rf)) return true;
        } else if ("SUCCESS".equals(st)) {
            if ("WAIT".equals(rf) || "SUCCESS".equals(rf)) return true;
        } else if ("CANCEL".equals(st)) {
            if ("Y".equals(tk)) return true;
        }
        return false;
    }
}
