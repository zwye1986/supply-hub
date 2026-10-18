package com.aifuli.module.supplychain.border.fulfillment.application.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepRuntimeCheckpoint;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentInternalOnlySerializer;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 单步骤检查点的封闭认证边界；只编码、加密及计算内容摘要，不读数据库、不作资金决策。
 * 原单绑定来自持久Operation，不能信任密文自报身份；明文诊断不能透出Jackson异常。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#31-单步骤检查点v1。
 */
@org.springframework.stereotype.Component
public final class SingleStepCheckpointCodec {
    private static final int MAX_BYTES = 65_536;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_STRING_LENGTH = 4096;
    private static final String AAD_PURPOSE = "SINGLE_STEP_CHECKPOINT";
    private static final ObjectMapper JSON = mapper();
    private final EnvelopeCryptoPort crypto;

    public SingleStepCheckpointCodec(EnvelopeCryptoPort crypto) { this.crypto = Objects.requireNonNull(crypto); }

    public Stored encrypt(SingleStepCheckpoint checkpoint) {
        byte[] plaintext = null;
        try {
            plaintext = encodePlaintext(checkpoint);
            var encrypted = crypto.encrypt(plaintext, aad(checkpoint.binding()));
            require(encrypted != null && encrypted.ciphertext().length > 0 && validKey(encrypted.keyVersion()));
            return new Stored(SingleStepCheckpoint.SCHEMA_VERSION, encrypted.keyVersion(), encrypted.ciphertext());
        } catch (IOException | RuntimeException invalid) {
            throw invalid();
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** 查询端点仍传原SEND绑定；大小/类型/Schema/内外身份均验证，不存在空快照首次创建回退。 */
    public SingleStepCheckpoint decrypt(Stored stored, SingleStepCheckpoint.Binding expectedBinding) {
        return decryptRuntime(stored, expectedBinding).frozen();
    }

    /** V1只读提升为初始运行态；V2必须认证自己的版本与原SEND身份，不能降级重解。 */
    public SingleStepRuntimeCheckpoint decryptRuntime(Stored stored, SingleStepCheckpoint.Binding expectedBinding) {
        byte[] plaintext = null;
        try {
            require(stored != null && (Objects.equals(stored.schemaVersion(), SingleStepCheckpoint.SCHEMA_VERSION)
                    || Objects.equals(stored.schemaVersion(), SingleStepRuntimeCheckpoint.SCHEMA_VERSION))
                    && validKey(stored.keyVersion()) && stored.ciphertext() != null
                    && stored.ciphertext().length > 0 && stored.ciphertext().length <= MAX_BYTES + 1024 && expectedBinding != null);
            plaintext = crypto.decrypt(stored.ciphertext(), stored.keyVersion(), aad(expectedBinding, stored.schemaVersion()));
            require(plaintext != null && plaintext.length > 0 && plaintext.length <= MAX_BYTES);
            var runtime = stored.schemaVersion() == SingleStepCheckpoint.SCHEMA_VERSION
                    ? SingleStepRuntimeCheckpoint.initial(JSON.readValue(plaintext, SingleStepCheckpoint.class))
                    : JSON.readValue(plaintext, SingleStepRuntimeCheckpoint.class);
            validateTree(JSON.valueToTree(runtime), 1);
            require(runtime.frozen().binding().equals(expectedBinding));
            return runtime;
        } catch (IOException | RuntimeException invalid) {
            throw invalid();
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** 运行态更新不改变frozen/configDigest；使用独立V2 AAD，临时明文仍按原边界擦除。 */
    public Stored encryptRuntime(SingleStepRuntimeCheckpoint runtime) {
        byte[] plaintext = null;
        try {
            require(runtime != null);
            var node = JSON.valueToTree(runtime);
            validateTree(node, 1);
            plaintext = JSON.writeValueAsBytes(canonical(node));
            require(plaintext.length <= MAX_BYTES);
            var encrypted = crypto.encrypt(plaintext, aad(runtime.frozen().binding(), SingleStepRuntimeCheckpoint.SCHEMA_VERSION));
            require(encrypted != null && encrypted.ciphertext().length > 0 && validKey(encrypted.keyVersion()));
            return new Stored(SingleStepRuntimeCheckpoint.SCHEMA_VERSION, encrypted.keyVersion(), encrypted.ciphertext());
        } catch (IOException | RuntimeException invalid) {
            throw invalid();
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** 返回值所有权交调用者；用于Ticket时必须在复制后擦除临时数组。 */
    public byte[] encodePlaintext(SingleStepCheckpoint checkpoint) {
        byte[] plaintext = null;
        try {
            require(checkpoint != null);
            JsonNode node = JSON.valueToTree(checkpoint);
            validateTree(node, 1);
            plaintext = JSON.writeValueAsBytes(canonical(node));
            require(plaintext.length <= MAX_BYTES);
            return plaintext;
        } catch (IOException | RuntimeException invalid) {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            throw invalid();
        }
    }

    /** 显式非秘密内容投影的SHA-256；集合由其领域投影排序，不重排有语义的普通数组。 */
    public static String contentDigest(JsonNode configuration, SingleStepCheckpoint.Business business,
            SingleStepCheckpoint.CredentialIdentity credentials, SingleStepCheckpoint.RecoveryContract recovery) {
        byte[] bytes = null;
        try {
            require(configuration != null && configuration.isObject() && business != null && credentials != null && recovery != null);
            ObjectNode node = JSON.createObjectNode();
            node.set("configuration", configuration);
            node.set("business", JSON.valueToTree(business));
            node.set("credentialIdentity", JSON.valueToTree(credentials));
            node.set("recoveryContract", JSON.valueToTree(recovery));
            validateTree(node, 1);
            bytes = JSON.writeValueAsBytes(canonical(node));
            require(bytes.length <= MAX_BYTES);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException | RuntimeException invalid) {
            throw invalid();
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private static byte[] aad(SingleStepCheckpoint.Binding binding) throws IOException {
        return aad(binding, SingleStepCheckpoint.SCHEMA_VERSION);
    }

    private static byte[] aad(SingleStepCheckpoint.Binding binding, int schemaVersion) throws IOException {
        var node = JSON.createObjectNode().put("purpose", AAD_PURPOSE).put("schemaVersion", schemaVersion);
        node.set("binding", JSON.valueToTree(binding));
        return JSON.writeValueAsBytes(canonical(node));
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = JSON.createObjectNode();
            var keys = new TreeSet<String>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, canonical(node.get(key))));
            return result;
        }
        if (node.isArray()) {
            var result = JSON.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node;
    }

    private static void validateTree(JsonNode node, int depth) {
        require(depth <= MAX_DEPTH && (!node.isTextual() || node.textValue().length() <= MAX_STRING_LENGTH));
        if (node.isContainerNode()) node.forEach(child -> validateTree(child, depth + 1));
    }

    private static ObjectMapper mapper() {
        var mapper = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH)
                                .maxStringLength(MAX_STRING_LENGTH).maxNumberLength(20).build()).build())
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
        mapper.coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    private static boolean validKey(String key) { return key != null && !key.isBlank() && key.length() <= 64; }
    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("单步骤检查点无效"); }

    /** 三列持久值；允许残缺输入交decrypt统一拒绝，数组双向复制且不能通用序列化。 */
    @JsonSerialize(using = FulfillmentInternalOnlySerializer.class)
    public record Stored(Integer schemaVersion, String keyVersion, byte[] ciphertext) {
        public Stored { ciphertext = ciphertext == null ? null : ciphertext.clone(); }
        @Override public byte[] ciphertext() { return ciphertext == null ? null : ciphertext.clone(); }
        @Override public String toString() { return "SingleStepCheckpoint.Stored[redacted]"; }
    }
}
