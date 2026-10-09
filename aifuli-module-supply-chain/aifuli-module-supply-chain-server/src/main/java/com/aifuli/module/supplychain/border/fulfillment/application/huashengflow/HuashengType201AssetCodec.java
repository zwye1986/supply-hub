package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

/**
 * type201协议卡片到标准受保护payload的唯一封闭桥接，不访问DAL或资金、不给业务终结许可。
 * 字段三态与原字符串保真；id只进入payload，不用日期解析有效期。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#71-task2桥接与持久基础边界。
 */
public final class HuashengType201AssetCodec {
    public static final int PAYLOAD_SCHEMA_VERSION = 1;
    // A枚举的rawValue为协议包内部接口；此封闭schema固定201，不为接线放宽A访问范围。
    public static final int CARD_TYPE = 201;
    public static final String ADAPTER_TYPE = "HUA_SHENG_ORDER_V1";
    public static final String FINGERPRINT_PURPOSE = "FULFILLMENT_ASSET";
    private static final String INVALID = "华昇type201资产无效";
    private static final String ASSET_TYPE = FulfillmentType.CARD_CODE_SECRET.code();
    private static final Set<String> ROOT_KEYS = Set.of("payloadSchemaVersion", "assetType", "cardCode", "cardSecret", "supplierPayload");
    private static final Set<String> SUPPLIER_KEYS = Set.of("adapterType", "type", "id", "code", "pass", "crc", "url", "urlPass", "validTime");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(16384)
                    .maxNumberLength(32).build()).build());

    /** 仅桥接经A parser校验的单张T201，拒绝其他型非空载荷。设计同 §7.1。 */
    public OperationAssetFact toFact(HuashengProtocolCard card) {
        check(card != null && card.type() == HuashengCardType.T201);
        String code = value(card.code()); String pass = value(card.pass());
        ObjectNode supplier = JSON.createObjectNode();
        supplier.put("adapterType", ADAPTER_TYPE); supplier.put("type", CARD_TYPE);
        field(supplier, "id", card.id()); field(supplier, "code", card.code()); field(supplier, "pass", card.pass());
        field(supplier, "crc", card.crc()); field(supplier, "url", card.url()); field(supplier, "urlPass", card.urlPass());
        field(supplier, "validTime", card.validTime());
        byte[] payload = bytes(root(code, pass, supplier));
        try {
            var fact = OperationAssetFact.huashengType201(code, pass, payload);
            byte[] verified = validate(fact);
            Arrays.fill(verified, (byte) 0);
            return fact;
        } finally { Arrays.fill(payload, (byte) 0); }
    }

    /** 重新核封闭schema与两套凭证，返回固定顺序字节；调用者使用后擦除。设计同 §7.1。 */
    public byte[] validate(OperationAssetFact fact) {
        return validateOwnedPayload(fact, fact == null ? null : fact.protectedPayload());
    }

    /** 已有资产比较只用临时字节副本，不额外构造持有卡密的事实对象；入参不修改。设计同 §7.1。 */
    public byte[] validateStoredPayload(OperationAssetFact expected, byte[] decryptedPayload) {
        return validateOwnedPayload(expected, decryptedPayload == null ? null : decryptedPayload.clone());
    }

    private byte[] validateOwnedPayload(OperationAssetFact fact, byte[] payload) {
        try {
            check(fact != null && fact.sequenceNo() == 1 && ASSET_TYPE.equals(fact.assetType())
                    && fact.externalAssetId() == null && fact.validFrom() == null && fact.validUntil() == null);
            check(payload != null && payload.length > 0 && payload.length <= 1_048_576);
            ObjectNode parsed;
            try (var parser = JSON.getFactory().createParser(payload)) {
                parsed = object(JSON.readTree(parser), ROOT_KEYS);
                check(parser.nextToken() == null);
            }
            check(number(parsed, "payloadSchemaVersion") == PAYLOAD_SCHEMA_VERSION && ASSET_TYPE.equals(text(parsed, "assetType")));
            String code = text(parsed, "cardCode"); String pass = text(parsed, "cardSecret");
            check(!code.isBlank() && !pass.isBlank() && code.equals(fact.cardNo()) && pass.equals(fact.cardSecret()));
            ObjectNode source = object(parsed.get("supplierPayload"), SUPPLIER_KEYS);
            check(ADAPTER_TYPE.equals(text(source, "adapterType")) && number(source, "type") == CARD_TYPE);
            check(code.equals(text(source, "code")) && pass.equals(text(source, "pass")));
            ObjectNode supplier = JSON.createObjectNode();
            supplier.put("adapterType", ADAPTER_TYPE); supplier.put("type", CARD_TYPE);
            if (source.has("id")) {
                if (source.get("id").isNull()) supplier.putNull("id");
                else { long id = number(source, "id"); check(id >= 0); supplier.put("id", id); }
            }
            for (String name : new String[]{"code", "pass", "crc", "url", "urlPass", "validTime"}) {
                if (!source.has(name)) continue;
                if (source.get(name).isNull()) supplier.putNull(name);
                else {
                    String v = text(source, name);
                    if (name.equals("crc") || name.equals("url") || name.equals("urlPass")) check(v.isBlank());
                    supplier.put(name, v);
                }
            }
            return bytes(root(code, pass, supplier));
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException(INVALID);
        } finally { if (payload != null) Arrays.fill(payload, (byte) 0); }
    }

    /** 固定JSON字段顺序避免冒号歧义；只返回HMAC输入，绝不裸SHA哈希卡密。设计同 §7。 */
    public byte[] fingerprintInput(long supplierId, OperationAssetFact fact) {
        check(supplierId > 0);
        byte[] verified = validate(fact);
        try {
            ObjectNode input = JSON.createObjectNode();
            input.put("supplierId", supplierId); input.put("type", CARD_TYPE);
            input.put("code", fact.cardNo()); input.put("pass", fact.cardSecret());
            return bytes(input);
        } finally { Arrays.fill(verified, (byte) 0); }
    }

    private static ObjectNode root(String code, String pass, ObjectNode supplier) {
        ObjectNode root = JSON.createObjectNode();
        root.put("payloadSchemaVersion", PAYLOAD_SCHEMA_VERSION); root.put("assetType", ASSET_TYPE);
        root.put("cardCode", code); root.put("cardSecret", pass); root.set("supplierPayload", supplier);
        return root;
    }
    private static void field(ObjectNode node, String name, HuashengField<?> field) {
        if (field instanceof HuashengField.Null<?>) node.putNull(name);
        else if (field instanceof HuashengField.Value<?> value) {
            if (value.value() instanceof String text) node.put(name, text);
            else if (value.value() instanceof Long number) node.put(name, number);
            else throw new IllegalArgumentException(INVALID);
        }
    }
    private static String value(HuashengField<String> field) {
        check(field instanceof HuashengField.Value<String>);
        return ((HuashengField.Value<String>) field).value();
    }
    private static ObjectNode object(JsonNode node, Set<String> keys) {
        check(node instanceof ObjectNode); node.fieldNames().forEachRemaining(k -> check(keys.contains(k)));
        return (ObjectNode) node;
    }
    private static String text(ObjectNode node, String name) {
        JsonNode value = node.get(name);
        check(value != null && value.isTextual() && value.textValue().length() <= 16384);
        return value.textValue();
    }
    private static long number(ObjectNode node, String name) {
        JsonNode value = node.get(name); check(value != null && value.isIntegralNumber() && value.canConvertToLong());
        return value.longValue();
    }
    private static byte[] bytes(ObjectNode node) {
        try { return JSON.writeValueAsBytes(node); }
        catch (IOException invalid) { throw new IllegalArgumentException(INVALID); }
    }
    private static void check(boolean valid) { if (!valid) throw new IllegalArgumentException(INVALID); }
}
