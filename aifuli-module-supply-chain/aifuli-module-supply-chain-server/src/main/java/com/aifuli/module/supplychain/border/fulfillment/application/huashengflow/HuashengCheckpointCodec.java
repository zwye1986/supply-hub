package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

import static com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint.require;

/**
 * 华昇 V1 checkpoint 唯一封闭编解码入口，不读取数据库、不执行外呼、不决定资金动作。
 *
 * <p>先用锁后 expectedBinding 构造 AAD，再解密并核内部绑定/摘要，不能信任密文内自报身份。
 * 固定字段白名单与顺序；拒绝未知版本、重复键、尾随和类型宽松转换。
 * 临时明文 finally 擦除；错误只含固定安全说明，不传播 Jackson 的明文诊断。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#42-检查点-v1。
 */
@org.springframework.stereotype.Component
public final class HuashengCheckpointCodec {
    private static final HexFormat HEX = HexFormat.of();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper();
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "binding", "placeParameters",
            "placeParametersDigest", "orderSn", "observation", "progress", "pendingAssetPayload");
    private final EnvelopeCryptoPort crypto;
    private final ObjectMapper json;

    public HuashengCheckpointCodec(EnvelopeCryptoPort crypto) {
        this.crypto = Objects.requireNonNull(crypto);
        this.json = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                // 待核资产的 Base64 可超过普通字符串上限；普通字段在 text() 单独限长。
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(HuashengFlowCodes.CHECKPOINT_MAX_DEPTH)
                        .maxStringLength(HuashengFlowCodes.CHECKPOINT_MAX_BYTES).maxNumberLength(32).build())
                .build());
    }

    /** 固定顺序编码后加密成组返回；任何调用路径均擦除临时明文。设计同 §4.2.1。 */
    public Stored encrypt(HuashengCheckpoint checkpoint) {
        byte[] plaintext = null;
        try {
            require(checkpoint != null);
            plaintext = encode(checkpoint);
            require(plaintext.length <= HuashengFlowCodes.CHECKPOINT_MAX_BYTES);
            var encrypted = crypto.encrypt(plaintext, aad(checkpoint.binding()));
            require(encrypted != null && encrypted.keyVersion() != null && !encrypted.keyVersion().isBlank()
                    && encrypted.keyVersion().length() <= 64 && encrypted.ciphertext().length > 0);
            return new Stored(HuashengFlowCodes.CHECKPOINT_SCHEMA_VERSION, encrypted.keyVersion(), encrypted.ciphertext());
        } catch (IOException | RuntimeException rejected) {
            throw invalid();
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * 解密并验证锁后绑定；Stored 空/残缺也拒绝，不能退回首次下单。
     * 原 Operation 步骤提供 AAD，即使外呼端点是 QUERY 也不替换。设计同 §4.1、§4.2。
     */
    public HuashengCheckpoint decrypt(Stored stored, HuashengCheckpoint.Binding expectedBinding) {
        byte[] plaintext = null;
        try {
            require(stored != null && expectedBinding != null
                    && Objects.equals(stored.schemaVersion(), HuashengFlowCodes.CHECKPOINT_SCHEMA_VERSION)
                    && stored.keyVersion() != null && !stored.keyVersion().isBlank()
                    && stored.keyVersion().length() <= 64 && stored.ciphertext() != null);
            byte[] ciphertext = stored.ciphertext();
            // 当前 Envelope AES-GCM 有固定 12 字节 IV/16 字节 tag；留出有界信封余量。
            require(ciphertext.length > 0 && ciphertext.length <= HuashengFlowCodes.CHECKPOINT_MAX_BYTES + 1024);
            plaintext = crypto.decrypt(ciphertext, stored.keyVersion(), aad(expectedBinding));
            require(plaintext != null && plaintext.length > 0 && plaintext.length <= HuashengFlowCodes.CHECKPOINT_MAX_BYTES);
            HuashengCheckpoint checkpoint = decodePlaintext(plaintext);
            require(sameBinding(checkpoint.binding(), expectedBinding));
            return checkpoint;
        } catch (RuntimeException rejected) {
            throw invalid();
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * 解码已解密的明文检查点 JSON 字节。
     */
    public HuashengCheckpoint decodePlaintext(byte[] plaintext) {
        try {
            require(plaintext != null && plaintext.length > 0 && plaintext.length <= HuashengFlowCodes.CHECKPOINT_MAX_BYTES);
            ObjectNode root;
            try (var parser = json.getFactory().createParser(plaintext)) {
                root = object(json.readTree(parser), ROOT_FIELDS);
                require(parser.nextToken() == null);
            }
            require(integer(root, "schemaVersion") == HuashengFlowCodes.CHECKPOINT_SCHEMA_VERSION);
            var binding = readBinding(root.get("binding"));
            var place = readPlace(root.get("placeParameters"));
            require(placeDigest(place).equals(text(root, "placeParametersDigest")));
            String orderSn = optionalText(root, "orderSn");
            var observation = root.has("observation") ? readObservation(root.get("observation")) : null;
            var progress = readProgress(root.get("progress"));
            byte[] pending = root.has("pendingAssetPayload") ? Base64.getDecoder()
                    .decode(text(root, "pendingAssetPayload", HuashengFlowCodes.CHECKPOINT_MAX_BYTES)) : null;
            try {
                return new HuashengCheckpoint(binding, place, orderSn, observation, progress, pending);
            } finally {
                if (pending != null) Arrays.fill(pending, (byte) 0);
            }
        } catch (IOException | RuntimeException e) {
            throw invalid();
        }
    }

    /**
     * 将检查点编码为明文 JSON 字节（由 TicketFactory 传递给执行器）。
     */
    public byte[] encodePlaintext(HuashengCheckpoint checkpoint) {
        try {
            require(checkpoint != null);
            return encode(checkpoint);
        } catch (IOException | RuntimeException e) {
            throw invalid();
        }
    }

    private byte[] encode(HuashengCheckpoint checkpoint) throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("schemaVersion", HuashengFlowCodes.CHECKPOINT_SCHEMA_VERSION);
        var b = checkpoint.binding();
        ObjectNode binding = root.putObject("binding");
        binding.put("bOrderId", b.bOrderId()); binding.put("bOrderNo", b.bOrderNo());
        binding.put("operationNo", b.operationNo()); binding.put("sequenceNo", b.sequenceNo());
        binding.put("step", b.step().name()); binding.put("connectorVersionId", b.connectorVersionId());
        binding.put("offerVersionId", b.offerVersionId()); binding.put("configDigest", HEX.formatHex(b.configDigest()));
        root.set("placeParameters", placeNode(json, checkpoint.placeParameters()));
        root.put("placeParametersDigest", checkpoint.placeParametersDigest());
        if (checkpoint.orderSn() != null) root.put("orderSn", checkpoint.orderSn());
        var o = checkpoint.observation();
        if (o != null) {
            ObjectNode node = root.putObject("observation");
            node.put("endpoint", o.endpoint().name()); node.put("orderSn", o.orderSn());
            node.put("outOrderSn", o.outOrderSn()); node.put("num", o.num());
            node.put("totalPriceRaw", o.totalPriceRaw()); node.put("totalPriceMinorUnits", o.totalPriceMinorUnits());
            node.put("status", o.status().name());
            writeField(node, "refundStatus", o.refundStatus()); writeField(node, "isTake", o.isTake());
        }
        var p = checkpoint.progress();
        ObjectNode progress = root.putObject("progress");
        progress.put("queryCount", p.queryCount()); progress.put("confirmCount", p.confirmCount());
        progress.put("takeCount", p.takeCount());
        if (p.firstSendAt() != null) {
            progress.put("firstSendAt", TIME.format(p.firstSendAt()));
            progress.put("recoveryDeadlineAt", TIME.format(p.recoveryDeadlineAt()));
        }
        if (p.originTraceId() != null) progress.put("originTraceId", p.originTraceId());
        byte[] pending = checkpoint.pendingAssetPayload();
        try {
            if (pending != null) root.put("pendingAssetPayload", Base64.getEncoder().encodeToString(pending));
            return json.writeValueAsBytes(root);
        } finally {
            if (pending != null) Arrays.fill(pending, (byte) 0);
        }
    }

    /** 只对封闭非认证 PLACE 业务参数计算确定性摘要，不对卡密做裸 SHA 哈希。设计同 §4.2.1。 */
    static String placeDigest(HuashengCheckpoint.PlaceParameters place) {
        byte[] canonical = null;
        try {
            canonical = CANONICAL_JSON.writeValueAsBytes(placeNode(CANONICAL_JSON, place));
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("华昇业务参数摘要不可用");
        } finally {
            if (canonical != null) Arrays.fill(canonical, (byte) 0);
        }
    }

    private static ObjectNode placeNode(ObjectMapper mapper, HuashengCheckpoint.PlaceParameters p) {
        ObjectNode place = mapper.createObjectNode();
        place.put("outOrderSn", p.outOrderSn()); place.put("couponId", p.couponId());
        place.put("num", p.num()); place.put("expireTime", p.expireTime());
        if (p.outMobile() != null) place.put("outMobile", p.outMobile());
        place.put("notifyUrl", p.notifyUrl());
        return place;
    }

    private static byte[] aad(HuashengCheckpoint.Binding b) {
        return String.join("|", HuashengFlowCodes.CHECKPOINT_AAD_PURPOSE,
                Integer.toString(HuashengFlowCodes.CHECKPOINT_SCHEMA_VERSION), Long.toString(b.bOrderId()),
                b.operationNo(), Integer.toString(b.sequenceNo()), b.step().name(),
                Long.toString(b.connectorVersionId()), Long.toString(b.offerVersionId())).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean sameBinding(HuashengCheckpoint.Binding a, HuashengCheckpoint.Binding b) {
        return a.bOrderId() == b.bOrderId() && a.bOrderNo().equals(b.bOrderNo())
                && a.operationNo().equals(b.operationNo()) && a.sequenceNo() == b.sequenceNo() && a.step() == b.step()
                && a.connectorVersionId() == b.connectorVersionId() && a.offerVersionId() == b.offerVersionId()
                && MessageDigest.isEqual(a.configDigest(), b.configDigest());
    }

    private static HuashengCheckpoint.Binding readBinding(JsonNode value) {
        ObjectNode node = object(value, Set.of("bOrderId", "bOrderNo", "operationNo", "sequenceNo", "step",
                "connectorVersionId", "offerVersionId", "configDigest"));
        String digest = text(node, "configDigest");
        require(digest.matches("[0-9a-f]{64}"));
        return new HuashengCheckpoint.Binding(number(node, "bOrderId"), text(node, "bOrderNo"), text(node, "operationNo"),
                integer(node, "sequenceNo"), AdapterStepCode.valueOf(text(node, "step")),
                number(node, "connectorVersionId"), number(node, "offerVersionId"), HEX.parseHex(digest));
    }

    private static HuashengCheckpoint.PlaceParameters readPlace(JsonNode value) {
        ObjectNode node = object(value, Set.of("outOrderSn", "couponId", "num", "expireTime", "outMobile", "notifyUrl"));
        return new HuashengCheckpoint.PlaceParameters(text(node, "outOrderSn"), number(node, "couponId"),
                integer(node, "num"), integer(node, "expireTime"), optionalText(node, "outMobile"), text(node, "notifyUrl"));
    }

    private static HuashengCheckpoint.Observation readObservation(JsonNode value) {
        ObjectNode node = object(value, Set.of("endpoint", "orderSn", "outOrderSn", "num", "totalPriceRaw",
                "totalPriceMinorUnits", "status", "refundStatus", "isTake"));
        return new HuashengCheckpoint.Observation(HuashengEndpoint.valueOf(text(node, "endpoint")), text(node, "orderSn"),
                text(node, "outOrderSn"), integer(node, "num"), text(node, "totalPriceRaw"),
                number(node, "totalPriceMinorUnits"), HuashengOrderStatus.valueOf(text(node, "status")),
                readField(node, "refundStatus", HuashengRefundStatus.class), readField(node, "isTake", HuashengTakeStatus.class));
    }

    private static HuashengCheckpoint.Progress readProgress(JsonNode value) {
        ObjectNode node = object(value, Set.of("queryCount", "confirmCount", "takeCount", "firstSendAt",
                "recoveryDeadlineAt", "originTraceId"));
        String first = optionalText(node, "firstSendAt"); String deadline = optionalText(node, "recoveryDeadlineAt");
        return new HuashengCheckpoint.Progress(integer(node, "queryCount"), integer(node, "confirmCount"),
                integer(node, "takeCount"), first == null ? null : LocalDateTime.parse(first, TIME),
                deadline == null ? null : LocalDateTime.parse(deadline, TIME), optionalText(node, "originTraceId"));
    }

    private static <E extends Enum<E>> void writeField(ObjectNode node, String name, HuashengField<E> field) {
        if (field instanceof HuashengField.Null<E>) node.putNull(name);
        else if (field instanceof HuashengField.Value<E> value) node.put(name, value.value().name());
    }

    private static <E extends Enum<E>> HuashengField<E> readField(ObjectNode node, String name, Class<E> type) {
        if (!node.has(name)) return HuashengField.absent();
        if (node.get(name).isNull()) return HuashengField.ofNull();
        return HuashengField.ofValue(Enum.valueOf(type, text(node, name)));
    }

    private static ObjectNode object(JsonNode node, Set<String> fields) {
        require(node instanceof ObjectNode);
        node.fieldNames().forEachRemaining(name -> require(fields.contains(name)));
        return (ObjectNode) node;
    }

    private static String text(ObjectNode node, String name) {
        return text(node, name, HuashengFlowCodes.CHECKPOINT_MAX_STRING_LENGTH);
    }

    private static String text(ObjectNode node, String name, int maxLength) {
        JsonNode value = node.get(name);
        require(value != null && value.isTextual() && value.textValue().length() <= maxLength);
        return value.textValue();
    }

    private static String optionalText(ObjectNode node, String name) { return node.has(name) ? text(node, name) : null; }

    private static long number(ObjectNode node, String name) {
        JsonNode value = node.get(name);
        require(value != null && value.isIntegralNumber() && value.canConvertToLong());
        return value.longValue();
    }

    private static int integer(ObjectNode node, String name) {
        long value = number(node, name);
        require(value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE);
        return (int) value;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException(HuashengFlowCodes.INVALID_CHECKPOINT); }

    /**
     * 与三个 Operation 列一一对应；允许承载残缺持久值以便 decrypt 明确拒绝。
     * 不把全空认作首次下单；数组双向复制、禁止通用序列化。设计同 §4.2。
     */
    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    public record Stored(Integer schemaVersion, String keyVersion, byte[] ciphertext) {
        public Stored { ciphertext = ciphertext == null ? null : ciphertext.clone(); }
        @Override public byte[] ciphertext() { return ciphertext == null ? null : ciphertext.clone(); }
        @Override public String toString() { return "HuashengCheckpoint.Stored[redacted]"; }
    }
}
