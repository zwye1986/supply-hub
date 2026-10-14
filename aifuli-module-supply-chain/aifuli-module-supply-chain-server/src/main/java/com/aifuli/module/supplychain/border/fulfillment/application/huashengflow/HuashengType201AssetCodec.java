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
import java.time.LocalDateTime;
import com.aifuli.module.supplychain.api.fulfillment.dto.SupplierValidTimeState;
import java.util.Set;

/**
 * 201／202协议卡片到标准受保护payload的唯一封闭桥接，保留历史类名；不访问DAL或资金。
 * 字段三态与原字符串保真；id只进入payload，不用日期解析有效期。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#73-201202卡号卡密兼容与新单成功闭环。
 */
public final class HuashengType201AssetCodec {
    public static final int PAYLOAD_SCHEMA_VERSION = 1;
    // A枚举的rawValue为协议包内部接口；封闭schema明确白名单，不放宽A访问范围。
    public static final int CARD_TYPE = 201;
    public static final int SINGLE_CREDENTIAL_CARD_TYPE = 202;
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

    /** 201保留独立双字段；202与卡管一致将原单值投影为同值卡号／卡密。设计同 §7.3。 */
    public OperationAssetFact toFact(HuashengProtocolCard card) {
        check(card != null && (card.type() == HuashengCardType.T201 || card.type() == HuashengCardType.T202));
        String code = card.code().asOptional().orElse(null); String pass = card.pass().asOptional().orElse(null);
        int type = card.type() == HuashengCardType.T201 ? CARD_TYPE : SINGLE_CREDENTIAL_CARD_TYPE;
        if (type == SINGLE_CREDENTIAL_CARD_TYPE) code = pass = singleCredential(code, pass);
        ObjectNode supplier = JSON.createObjectNode();
        supplier.put("adapterType", ADAPTER_TYPE); supplier.put("type", type);
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
            ObjectNode parsed = canonicalPayload(payload);
            check(text(parsed, "cardCode").equals(fact.cardNo()) && text(parsed, "cardSecret").equals(fact.cardSecret()));
            return bytes(parsed);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(INVALID);
        } finally { if (payload != null) Arrays.fill(payload, (byte) 0); }
    }

    /**
     * 授权查询共享严格校验，不构造另一个持有敏感payload副本的事实对象；调用者擦除明文。
     * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#72-task7授权查询与有效期展示契约。
     */
    public StoredCard readStoredPayload(byte[] plaintext, int sequenceNo, String assetType,
            String externalAssetId, LocalDateTime validFrom, LocalDateTime validUntil) {
        check(sequenceNo == 1 && ASSET_TYPE.equals(assetType) && externalAssetId == null
                && validFrom == null && validUntil == null);
        ObjectNode parsed = canonicalPayload(plaintext);
        JsonNode value = parsed.get("supplierPayload").get("validTime");
        SupplierValidTimeState state = value == null ? SupplierValidTimeState.ABSENT
                : value.isNull() ? SupplierValidTimeState.NULL : SupplierValidTimeState.VALUE;
        return new StoredCard(text(parsed, "cardCode"), text(parsed, "cardSecret"),
                state == SupplierValidTimeState.VALUE ? value.textValue() : null, state);
    }

    /** 内部授权投影值；诊断输出不得携带卡号、卡密或供应商原文。设计同 §7.2。 */
    public record StoredCard(String cardCode, String cardSecret, String validTimeRaw,
            SupplierValidTimeState validTimeState) {
        @Override public String toString() { return "StoredCard[redacted]"; }
    }

    /** 持久与查询共享同一封闭schema校验，固定异常不携带JSON解析原值。 */
    private ObjectNode canonicalPayload(byte[] payload) {
        try {
            check(payload != null && payload.length > 0 && payload.length <= 1_048_576);
            ObjectNode parsed;
            try (var parser = JSON.getFactory().createParser(payload)) {
                parsed = object(JSON.readTree(parser), ROOT_KEYS);
                check(parser.nextToken() == null);
            }
            check(number(parsed, "payloadSchemaVersion") == PAYLOAD_SCHEMA_VERSION && ASSET_TYPE.equals(text(parsed, "assetType")));
            String code = text(parsed, "cardCode"); String pass = text(parsed, "cardSecret");
            check(!code.isBlank() && !pass.isBlank());
            ObjectNode source = object(parsed.get("supplierPayload"), SUPPLIER_KEYS);
            long type = number(source, "type");
            check(ADAPTER_TYPE.equals(text(source, "adapterType")) && (type == CARD_TYPE || type == SINGLE_CREDENTIAL_CARD_TYPE));
            if (type == CARD_TYPE) check(code.equals(text(source, "code")) && pass.equals(text(source, "pass")));
            else {
                String selected = singleCredential(source.hasNonNull("code") ? text(source, "code") : null,
                        source.hasNonNull("pass") ? text(source, "pass") : null);
                check(code.equals(selected) && pass.equals(selected));
            }
            ObjectNode supplier = JSON.createObjectNode();
            supplier.put("adapterType", ADAPTER_TYPE); supplier.put("type", type);
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
            return root(code, pass, supplier);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException(INVALID);
        }
    }

    /** 固定JSON字段顺序避免冒号歧义；只返回HMAC输入，绝不裸SHA哈希卡密。设计同 §7。 */
    public byte[] fingerprintInput(long supplierId, OperationAssetFact fact) {
        check(supplierId > 0);
        byte[] verified = validate(fact);
        try {
            ObjectNode input = JSON.createObjectNode();
            input.put("supplierId", supplierId); input.put("type", canonicalPayload(verified).get("supplierPayload").get("type").intValue());
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
    private static String singleCredential(String code, String pass) {
        String selected = code != null && !code.isBlank() ? code : pass;
        check(selected != null && !selected.isBlank());
        return selected;
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
