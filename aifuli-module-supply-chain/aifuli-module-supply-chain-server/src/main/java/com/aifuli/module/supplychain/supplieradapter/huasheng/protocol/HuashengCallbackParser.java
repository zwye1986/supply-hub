package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.HuashengCrypto;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 华昇回调专用严格协议解析器。
 *
 * <p>职责：
 * <ul>
 *   <li>有界读取 2MiB 原 hex 密文，在验签与 AES 解密前超限即拒绝；</li>
 *   <li>严格限制 Header Appid（16 字节）与 X-Sign（4096 字节），并校验 Appid 与商户账号一致；</li>
 *   <li>先对原密文文本字节执行 SHA512withRSA 验签，再执行 AES-CBC 解密；</li>
 *   <li>解密正文上限 1MiB，流式约束（最大字符串 16384、最大深度 32、重复 key 拒绝、尾随 token 拒绝）；</li>
 *   <li>严格语义校验（时间戳整数秒、nonce 最多 64 字符、双号非空、totalPrice、SUCCESS/FAILED）；</li>
 *   <li>交付数量按回调状态校验：SUCCESS 为 1，FAILED 为 0 或省略，不能混用原单订购数量；</li>
 *   <li>SUCCESS 仅接受恰好 1 张 Type 201 卡片（六型拒绝，复用 ResponseParser 校验），FAILED 仅接受空卡券；</li>
 *   <li>临时解密字节数组在 finally 块中擦除清零；</li>
 *   <li>零 Spring/MyBatis 依赖。</li>
 * </ul>
 *
 * <p>设计见 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#81-冻结身份认证。
 */
public final class HuashengCallbackParser {

    public static final int MAX_BODY_BYTES = 2 * 1024 * 1024;     // 2MiB 原 hex 密文上限
    public static final int MAX_APP_ID_BYTES = 16;                 // Appid 16 字节上限
    public static final int MAX_X_SIGN_BYTES = 4096;               // X-Sign 4096 字节上限
    public static final int MAX_DECRYPTED_BYTES = 1024 * 1024;     // 1MiB 解密上限
    public static final int MAX_STRING_LENGTH = 16384;             // 字符串长度上限
    public static final int MAX_NESTING_DEPTH = 32;                // 嵌套深度上限
    public static final int MAX_NONCE_LENGTH = 64;                 // nonce 最多 64 字符

    private static final String AES_ALGORITHM = "AES";
    private static final String AES_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final ObjectMapper MAPPER = createMapper();
    private static final HexFormat HEX = HexFormat.of();

    private HuashengCallbackParser() {}

    private static ObjectMapper createMapper() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return new ObjectMapper(factory);
    }

    /**
     * 从输入流有界读取并解析华昇回调。
     */
    public static HuashengCallbackFact parse(
            InputStream inputStream,
            String appIdHeader,
            String xSignHeader,
            String expectedAppId,
            PublicKey callbackPublicKey,
            String appSecret) {
        if (inputStream == null) {
            throw new HuashengCallbackParseException("缺少回调输入流");
        }
        byte[] bodyBytes = readBounded(inputStream, MAX_BODY_BYTES);
        return parse(bodyBytes, appIdHeader, xSignHeader, expectedAppId, callbackPublicKey, appSecret);
    }

    /**
     * 解析字节数组形式的华昇回调。
     */
    public static HuashengCallbackFact parse(
            byte[] rawBodyBytes,
            String appIdHeader,
            String xSignHeader,
            String expectedAppId,
            PublicKey callbackPublicKey,
            String appSecret) {
        // 1. 体积预检（在验签/AES 前拒绝超限）
        if (rawBodyBytes == null || rawBodyBytes.length == 0) {
            throw new HuashengCallbackParseException("回调正文为空");
        }
        if (rawBodyBytes.length > MAX_BODY_BYTES) {
            throw new HuashengCallbackParseException("回调密文体积超过 2MiB 限制: " + rawBodyBytes.length);
        }

        // 2. Header 长度与身份核验
        if (appIdHeader == null || appIdHeader.isBlank()) {
            throw new HuashengCallbackParseException("缺少 Header Appid");
        }
        byte[] appIdBytes = appIdHeader.getBytes(StandardCharsets.UTF_8);
        if (appIdBytes.length > MAX_APP_ID_BYTES) {
            throw new HuashengCallbackParseException("Header Appid 超过 16 字节限制: " + appIdBytes.length);
        }
        if (!appIdHeader.equals(expectedAppId)) {
            throw new HuashengCallbackParseException("Header Appid 与商户账号不匹配: " + appIdHeader);
        }

        if (xSignHeader == null || xSignHeader.isBlank()) {
            throw new HuashengCallbackParseException("缺少 Header X-Sign");
        }
        byte[] xSignBytes = xSignHeader.getBytes(StandardCharsets.UTF_8);
        if (xSignBytes.length > MAX_X_SIGN_BYTES) {
            throw new HuashengCallbackParseException("Header X-Sign 超过 4096 字节限制: " + xSignBytes.length);
        }

        if (callbackPublicKey == null) {
            throw new HuashengCallbackParseException("缺少回调公钥");
        }
        if (appSecret == null || appSecret.isBlank()) {
            throw new HuashengCallbackParseException("缺少解密密钥");
        }

        // 3. 验签：原密文文本字节验签
        String cipherHex = new String(rawBodyBytes, StandardCharsets.UTF_8).trim();
        if (cipherHex.isEmpty()) {
            throw new HuashengCallbackParseException("回调密文为空白");
        }
        boolean verified = HuashengCrypto.verifyBodyHex(cipherHex, xSignHeader.trim(), callbackPublicKey);
        if (!verified) {
            throw new HuashengCallbackParseException("HUA_SHENG 回调签名验证失败");
        }

        // 4. AES 解密并有界读取与临时缓冲区擦除
        byte[] decryptedBytes = decryptAes(cipherHex, appSecret, appIdHeader);
        try {
            if (decryptedBytes.length > MAX_DECRYPTED_BYTES) {
                throw new HuashengCallbackParseException("解密正文超过 1MiB 限制: " + decryptedBytes.length);
            }
            return parsePlainJson(decryptedBytes, appIdHeader);
        } finally {
            Arrays.fill(decryptedBytes, (byte) 0);
        }
    }

    public static byte[] readBounded(InputStream in, int maxBytes) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int totalRead = 0;
        int read;
        try {
            while ((read = in.read(buffer)) != -1) {
                totalRead += read;
                if (totalRead > maxBytes) {
                    throw new HuashengCallbackParseException("回调密文体积超过 2MiB 限制: " + totalRead);
                }
                baos.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new HuashengCallbackParseException("读取回调正文失败", e);
        }
        return baos.toByteArray();
    }

    private static byte[] decryptAes(String hex, String secret, String appId) {
        try {
            byte[] cipherBytes = decodeHexStrict(hex);
            byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            byte[] ivBytes = appId.getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, AES_ALGORITHM), new IvParameterSpec(ivBytes));
            return cipher.doFinal(cipherBytes);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new HuashengCallbackParseException("HUA_SHENG 正文解密失败", e);
        }
    }

    private static byte[] decodeHexStrict(String value) {
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("hex 长度非法");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                throw new IllegalArgumentException("hex 编码非法");
            }
        }
        return HEX.parseHex(value);
    }

    private static HuashengCallbackFact parsePlainJson(byte[] jsonBytes, String appId) {
        JsonNode root;
        try (JsonParser parser = MAPPER.getFactory().createParser(jsonBytes)) {
            root = MAPPER.readTree(parser);
            if (root == null || !root.isObject()) {
                throw new HuashengCallbackParseException("回调根节点必须为 JSON 对象");
            }
            try {
                if (parser.nextToken() != null) {
                    throw new HuashengCallbackParseException("JSON 包含尾随数据");
                }
            } catch (JsonParseException e) {
                throw new HuashengCallbackParseException("JSON 包含尾随数据: 词法非法");
            }
        } catch (JsonParseException e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate field")) {
                throw new HuashengCallbackParseException("JSON 包含重复 key");
            }
            // Jackson cause 可能包含解密正文片段，不向普通日志传播（设计 §8.2）。
            throw new HuashengCallbackParseException("JSON 解析错误: 词法非法");
        } catch (IOException e) {
            throw new HuashengCallbackParseException("JSON 读取失败");
        }

        // 1. timestamp
        JsonNode tsNode = root.get("timestamp");
        if (tsNode == null || !(tsNode instanceof IntNode || tsNode instanceof LongNode)) {
            throw new HuashengCallbackParseException("缺少整数 token timestamp");
        }
        if (!tsNode.canConvertToLong() || tsNode.longValue() <= 0) {
            throw new HuashengCallbackParseException("timestamp 必须为正整数秒");
        }
        long timestamp = tsNode.longValue();

        // 2. nonce
        JsonNode nonceNode = root.get("nonce");
        if (nonceNode == null || !nonceNode.isTextual() || nonceNode.asText().isBlank()) {
            throw new HuashengCallbackParseException("缺少有效字符串 nonce");
        }
        String nonce = nonceNode.asText();
        if (nonce.length() > MAX_NONCE_LENGTH) {
            throw new HuashengCallbackParseException("nonce 长度超过 64 字符限制: " + nonce.length());
        }

        // 3. orderSn & outOrderSn
        JsonNode orderSnNode = root.get("orderSn");
        if (orderSnNode == null || !orderSnNode.isTextual() || orderSnNode.asText().isBlank()) {
            throw new HuashengCallbackParseException("缺少有效字符串 orderSn");
        }
        String orderSn = orderSnNode.asText();

        JsonNode outOrderSnNode = root.get("outOrderSn");
        if (outOrderSnNode == null || !outOrderSnNode.isTextual() || outOrderSnNode.asText().isBlank()) {
            throw new HuashengCallbackParseException("缺少有效字符串 outOrderSn");
        }
        String outOrderSn = outOrderSnNode.asText();

        // 4. num 原始存在性和整数类型；状态相关校验在读取 status 后执行。
        // 设计：docs/superpowers/specs/2026-10-12-huasheng-fulfillment-hardening-design.md#21-订购数量与交付数量。
        JsonNode numNode = root.get("num");
        Integer num = null;
        if (numNode != null) {
            if (!(numNode instanceof IntNode || numNode instanceof LongNode) || !numNode.canConvertToInt()) {
                throw new HuashengCallbackParseException("num 必须为有效整数");
            }
            num = numNode.intValue();
        }

        // 5. totalPrice
        JsonNode tpNode = root.get("totalPrice");
        if (tpNode == null || !tpNode.isTextual() || tpNode.asText().isBlank()) {
            throw new HuashengCallbackParseException("缺少有效字符串 totalPrice");
        }
        String rawTotalPrice = tpNode.asText();
        var priceAttempt = HuashengMoneyParser.parsePrice(rawTotalPrice);
        if (priceAttempt instanceof HuashengInternalAttempt.Failed<HuashengPrice> failedPrice) {
            throw new HuashengCallbackParseException("totalPrice 金额格式非法");
        }
        HuashengPrice totalPrice = ((HuashengInternalAttempt.Parsed<HuashengPrice>) priceAttempt).value();

        // 6. status (仅接受 SUCCESS 或 FAILED)
        JsonNode statusNode = root.get("status");
        if (statusNode == null || !statusNode.isTextual() || statusNode.asText().isBlank()) {
            throw new HuashengCallbackParseException("缺少有效字符串 status");
        }
        String statusText = statusNode.asText();
        HuashengOrderStatus status;
        if ("SUCCESS".equals(statusText)) {
            status = HuashengOrderStatus.SUCCESS;
        } else if ("FAILED".equals(statusText)) {
            status = HuashengOrderStatus.FAILED;
        } else {
            throw new HuashengCallbackParseException("status 必须为 SUCCESS 或 FAILED");
        }

        if (status == HuashengOrderStatus.SUCCESS && !Integer.valueOf(HuashengCallbackFact.SINGLE_CARD_DELIVERY_QUANTITY).equals(num)) {
            throw new HuashengCallbackParseException("SUCCESS 的 num 必须严格等于 1");
        }
        if (status == HuashengOrderStatus.FAILED && num != null && num != HuashengCallbackFact.FAILED_DELIVERY_QUANTITY) {
            throw new HuashengCallbackParseException("FAILED 的 num 必须为 0 或省略");
        }

        // 7. list & card 校验
        Optional<HuashengProtocolCard> optCard;
        if (status == HuashengOrderStatus.SUCCESS) {
            JsonNode listNode = root.get("list");
            if (listNode == null || !listNode.isArray()) {
                throw new HuashengCallbackParseException("SUCCESS 状态必须包含 array 类型的 list 节点");
            }
            if (listNode.size() != 1) {
                throw new HuashengCallbackParseException("SUCCESS 状态 list 卡片数量必须恰好为 1，收到: " + listNode.size());
            }
            JsonNode cardNode = listNode.get(0);
            if (cardNode == null || !cardNode.isObject()) {
                throw new HuashengCallbackParseException("卡片项必须为有效对象");
            }

            java.util.Set<String> knownCardKeys = java.util.Set.of("type", "id", "code", "pass", "crc", "url", "urlPass", "validTime");
            var fieldNames = cardNode.fieldNames();
            while (fieldNames.hasNext()) {
                String fn = fieldNames.next();
                if (!knownCardKeys.contains(fn)) {
                    JsonNode extraVal = cardNode.get(fn);
                    if (extraVal != null && !extraVal.isNull()) {
                        if (!extraVal.isTextual() || !extraVal.asText().isBlank()) {
                            throw new HuashengCallbackParseException("卡片包含未声明的非空载荷属性");
                        }
                    }
                }
            }

            JsonNode typeNode = cardNode.get("type");
            if (typeNode == null || !(typeNode instanceof IntNode || typeNode instanceof LongNode)) {
                throw new HuashengCallbackParseException("卡片缺少整数 token type");
            }
            if (!typeNode.canConvertToInt() || typeNode.intValue() != 201) {
                throw new HuashengCallbackParseException("回调仅支持 Type 201");
            }

            JsonNode codeNode = cardNode.get("code");
            if (codeNode == null || !codeNode.isTextual() || codeNode.asText().isBlank()) {
                throw new HuashengCallbackParseException("T201缺少code必填字段");
            }
            JsonNode passNode = cardNode.get("pass");
            if (passNode == null || !passNode.isTextual() || passNode.asText().isBlank()) {
                throw new HuashengCallbackParseException("T201缺少pass必填字段");
            }

            var cardAttempt = HuashengResponseParser.parseCard(cardNode);
            if (cardAttempt instanceof HuashengInternalAttempt.Failed<HuashengProtocolCard> failedCard) {
                throw new HuashengCallbackParseException("卡片校验失败");
            }
            HuashengProtocolCard card = ((HuashengInternalAttempt.Parsed<HuashengProtocolCard>) cardAttempt).value();
            optCard = Optional.of(card);
        } else {
            // FAILED 状态
            if (root.has("list") && !root.get("list").isNull()) {
                JsonNode listNode = root.get("list");
                if (!listNode.isArray()) {
                    throw new HuashengCallbackParseException("FAILED 状态 list 字段类型非法");
                }
                if (!listNode.isEmpty()) {
                    throw new HuashengCallbackParseException("FAILED 状态 list 必须为空，收到非空卡片数量: " + listNode.size());
                }
            }
            optCard = Optional.empty();
        }

        return new HuashengCallbackFact(
                timestamp,
                nonce,
                orderSn,
                outOrderSn,
                num,
                totalPrice,
                status,
                optCard,
                rawTotalPrice,
                appId
        );
    }
}
