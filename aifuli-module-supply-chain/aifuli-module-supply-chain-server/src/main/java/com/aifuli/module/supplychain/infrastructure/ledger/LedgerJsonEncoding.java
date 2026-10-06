package com.aifuli.module.supplychain.infrastructure.ledger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;

/**
 * 精确十进制 JSON 基础比较；不使用或修改共享 ObjectMapper。
 * 设计：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md §6.1。
 * B 业务封闭 Schema 尚未冻结，本类不声明 B 业务载荷可用。
 */
public final class LedgerJsonEncoding {
    static final int MAX_INPUT_UTF8 = 1048576;
    static final int MAX_DEPTH = 64;
    static final int MAX_TOKENS = 65536;
    static final int MAX_NUMBER_LEXEME = 1000;
    static final int MAX_NUMBER_OUTPUT = 4096;
    static final int MAX_OUTPUT_UTF8 = 4194304;
    private static final com.fasterxml.jackson.core.JsonFactory FACTORY =
            com.fasterxml.jackson.core.JsonFactory.builder()
                    .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                            .maxNestingDepth(MAX_DEPTH).maxTokenCount(MAX_TOKENS)
                            .maxNumberLength(MAX_NUMBER_LEXEME)
                            .maxStringLength(MAX_INPUT_UTF8).maxNameLength(MAX_INPUT_UTF8).build())
                    .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .disable(com.fasterxml.jackson.core.StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                    .build();
    private static final ObjectMapper MAPPER = new ObjectMapper(FACTORY)
            .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private LedgerJsonEncoding() { }

    public static boolean legacyMatches(String left, String right) {
        return java.util.Arrays.equals(canonicalBytes(left), canonicalBytes(right));
    }

    /** 仅基础 JSON 编码，不代替 B 封闭 Schema／版本／信封投影校验。 */
    public static byte[] canonicalBytes(String json) {
        try {
            if (json == null) throw new IllegalArgumentException("缺少JSON值");
            if (utf8Length(json, false) > MAX_INPUT_UTF8) throw budget("INPUT_UTF8");
            // 流式预检先于构树，数长按完整原词法计；不转十进制、不展开指数。
            try (var parser = FACTORY.createParser(json)) {
                com.fasterxml.jackson.core.JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token.isNumeric() && parser.getTextLength() > MAX_NUMBER_LEXEME)
                        throw budget("NUMBER_LEXEME");
                }
            } catch (com.fasterxml.jackson.core.exc.StreamConstraintsException e) {
                String message = e.getMessage();
                String reason = message.contains("depth") ? "DEPTH"
                        : message.contains("Token count") ? "TOKENS" : "NUMBER_LEXEME";
                var rejection = budget(reason);
                rejection.initCause(e);
                throw rejection;
            }
            JsonNode node = MAPPER.readTree(json);
            if (node == null || node.isMissingNode()) throw new IllegalArgumentException("缺少JSON值");
            validateUnicode(node);
            long size = measure(node, 0, new long[1]);
            // 第二次相同规则复核先于编码分配；树仅在本方法持有，无可变别名。
            if (measure(node, 0, new long[1]) != size) throw budget("OUTPUT_UTF8");
            StringBuilder output = new StringBuilder(Math.toIntExact(size));
            encode(node, output);
            String encoded = output.toString();
            if (utf8Length(encoded, false) != size) throw budget("OUTPUT_UTF8");
            return encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception cause) {
            throw new SystemIntegrityException("载荷JSON比对解析异常", cause);
        }
    }

    private static void encode(JsonNode node, StringBuilder output) {
        if (node.isObject()) {
            var names = new java.util.ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort((left, right) -> {
                var a = left.codePoints().iterator();
                var b = right.codePoints().iterator();
                while (a.hasNext() && b.hasNext()) {
                    int comparison = Integer.compare(a.nextInt(), b.nextInt());
                    if (comparison != 0) return comparison;
                }
                return Boolean.compare(a.hasNext(), b.hasNext());
            });
            output.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i != 0) output.append(',');
                String name = names.get(i);
                encodeString(name, output);
                output.append(':');
                encode(node.get(name), output);
            }
            output.append('}');
        } else if (node.isArray()) {
            output.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i != 0) output.append(',');
                encode(node.get(i), output);
            }
            output.append(']');
        } else if (node.isTextual()) {
            encodeString(node.textValue(), output);
        } else if (node.isNumber()) {
            output.append(plainNumber(node));
        } else {
            output.append(node.toString());
        }
    }

    private record NumberShape(String digits, int end, long scale, boolean negative, long length) { }

    /** 去尾零仅扫描受词法限制的unscaled位串，所有长度/scale运算用long。 */
    private static NumberShape numberShape(JsonNode node) {
        BigDecimal value = node.decimalValue();
        if (value.signum() == 0) return new NumberShape("0", 1, 0, false, 1);
        String digits = value.unscaledValue().abs().toString();
        if (digits.length() > MAX_NUMBER_LEXEME) throw budget("NUMBER_LEXEME");
        int end = digits.length();
        while (digits.charAt(end - 1) == '0') end--;
        long p = end;
        long s = (long) value.scale() - (digits.length() - end);
        long n = value.signum() < 0 ? 1L : 0L;
        long length = s <= 0 ? n + p - s : s < p ? n + p + 1 : n + s + 2;
        if (length > MAX_NUMBER_OUTPUT) throw budget("NUMBER_OUTPUT");
        return new NumberShape(digits, end, s, n == 1, length);
    }

    private static String plainNumber(JsonNode node) {
        NumberShape shape = numberShape(node);
        return new BigDecimal(new java.math.BigInteger((shape.negative() ? "-" : "")
                + shape.digits().substring(0, shape.end())), Math.toIntExact(shape.scale())).toPlainString();
    }

    /** 完整树分配前计量：含起止/字段名token及全部规范标点、转义UTF8。 */
    private static long measure(JsonNode node, int depth, long[] tokens) {
        if (++tokens[0] > MAX_TOKENS) throw budget("TOKENS");
        long length;
        if (node.isContainerNode()) {
            if (++depth > MAX_DEPTH) throw budget("DEPTH");
            length = 2;
            int count = 0;
            if (node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (++tokens[0] > MAX_TOKENS) throw budget("TOKENS");
                    length += utf8Length(field.getKey(), true) + 1;
                    length += measure(field.getValue(), depth, tokens);
                    if (count++ > 0) length++;
                    if (length > MAX_OUTPUT_UTF8) throw budget("OUTPUT_UTF8");
                }
            } else {
                for (JsonNode child : node) {
                    length += measure(child, depth, tokens);
                    if (count++ > 0) length++;
                    if (length > MAX_OUTPUT_UTF8) throw budget("OUTPUT_UTF8");
                }
            }
            if (++tokens[0] > MAX_TOKENS) throw budget("TOKENS");
        } else if (node.isTextual()) length = utf8Length(node.textValue(), true);
        else if (node.isNumber()) length = numberShape(node).length();
        else if (node.isNull()) length = 4;
        else if (node.isBoolean()) length = node.booleanValue() ? 4 : 5;
        else throw new IllegalArgumentException("不支持的JSON节点");
        if (length > MAX_OUTPUT_UTF8) throw budget("OUTPUT_UTF8");
        return length;
    }

    private static void encodeString(String text, StringBuilder output) {
        output.append('"');
        final String hex = "0123456789abcdef";
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '"' || value == '\\') output.append('\\').append(value);
            else if (value <= 0x1f) {
                output.append("\\u00").append(hex.charAt(value >>> 4)).append(hex.charAt(value & 15));
            } else output.append(value);
        }
        output.append('"');
    }

    private static void validateUnicode(JsonNode node) {
        if (node.isTextual()) validateString(node.textValue());
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                validateString(field.getKey());
                validateUnicode(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) validateUnicode(child);
        }
    }

    private static IllegalArgumentException budget(String reason) {
        return new IllegalArgumentException("JSON预算超限: " + reason);
    }

    /** 无UTF8副本；原输入和规范字符串共用码点/代理项规则。 */
    private static long utf8Length(String text, boolean escaped) {
        long length = escaped ? 2L : 0L;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IllegalArgumentException("JSON字符串含未配对代理项");
                length += 4;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("JSON字符串含未配对代理项");
            } else if (escaped && c <= 0x1f) length += 6;
            else if (escaped && (c == '"' || c == '\\')) length += 2;
            else length += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
        }
        return length;
    }

    private static void validateString(String text) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException("JSON字符串含未配对代理项");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("JSON字符串含未配对代理项");
            }
        }
    }
}
