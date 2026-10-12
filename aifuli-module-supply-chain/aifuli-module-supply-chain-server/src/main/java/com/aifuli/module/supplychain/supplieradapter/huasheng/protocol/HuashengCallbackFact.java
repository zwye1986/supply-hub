package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

/**
 * 华昇回调成功解析后的不可变事实载荷。
 *
 * <p>职责：封装经过冻结版本认证、验签、AES 解密及严格 JSON 校验后的回调数据事实，
 * 提供确定性规范化摘要计算与 eventId 生成。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#82-持久防重与收敛。
 */
public record HuashengCallbackFact(
        long timestamp,
        String nonce,
        String orderSn,
        String outOrderSn,
        Integer num,
        HuashengPrice totalPrice,
        HuashengOrderStatus status,
        Optional<HuashengProtocolCard> card,
        String rawTotalPrice,
        String appId
) {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 首期成功回调完整交付一张卡；失败回调未交付任何卡券。 */
    public static final int SINGLE_CARD_DELIVERY_QUANTITY = 1;
    public static final int FAILED_DELIVERY_QUANTITY = 0;

    public HuashengCallbackFact {
        Objects.requireNonNull(nonce, "nonce must not be null");
        Objects.requireNonNull(orderSn, "orderSn must not be null");
        Objects.requireNonNull(outOrderSn, "outOrderSn must not be null");
        Objects.requireNonNull(totalPrice, "totalPrice must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(card, "card must not be null");
        Objects.requireNonNull(rawTotalPrice, "rawTotalPrice must not be null");
        Objects.requireNonNull(appId, "appId must not be null");
        // num 是回调交付数量而非订购数量；省略与显式零保留在协议事实及 Inbox 摘要中。
        // 设计：docs/superpowers/specs/2026-10-12-huasheng-fulfillment-hardening-design.md#21-订购数量与交付数量。
        boolean validQuantity = status == HuashengOrderStatus.SUCCESS && Integer.valueOf(SINGLE_CARD_DELIVERY_QUANTITY).equals(num)
                || status == HuashengOrderStatus.FAILED && (num == null || num == FAILED_DELIVERY_QUANTITY);
        if (!validQuantity) throw new IllegalArgumentException("回调 num 与交付状态不一致");
    }

    /**
     * 生成规范化完整解密事实的 UTF-8 字节，供 HmacFingerprintPort 计算 payloadDigest。
     * 三态字段、金额原字符串及 connectorVersionId 均参与摘要。
     */
    public byte[] canonicalFactBytes(Long connectorVersionId) {
        ObjectNode root = JSON.createObjectNode();
        root.put("connectorVersionId", connectorVersionId); root.put("timestamp", timestamp);
        root.put("appId", appId); root.put("nonce", nonce); root.put("orderSn", orderSn); root.put("outOrderSn", outOrderSn);
        if (num != null) root.put("num", num);
        root.put("rawTotalPrice", rawTotalPrice); root.put("totalPriceMinorUnits", totalPrice.minorUnits());
        root.put("status", status.name());
        if (card.isPresent()) {
            HuashengProtocolCard c = card.get();
            ObjectNode node = root.putObject("card");
            node.put("type", c.type().name());
            field(node, "id", c.id()); field(node, "code", c.code()); field(node, "pass", c.pass());
            field(node, "crc", c.crc()); field(node, "url", c.url()); field(node, "urlPass", c.urlPass());
            field(node, "validTime", c.validTime());
        } else {
            root.putNull("card");
        }
        return bytes(root);
    }

    /**
     * 固定字段顺序 UTF-8 JSON (supplierId, appid, nonce) 之 SHA-256 hex 作为 Inbox eventId。
     */
    public static String computeEventId(Long supplierId, String appId, String nonce) {
        ObjectNode identity = JSON.createObjectNode();
        identity.put("supplierId", supplierId); identity.put("appid", appId); identity.put("nonce", nonce);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes(identity)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** ABSENT 省略键、NULL 写 null、VALUE 原样，encoder 转义避免分隔符歧义；设计 §8.2。 */
    private static void field(ObjectNode node, String name, HuashengField<?> field) {
        if (field instanceof HuashengField.Null<?>) node.putNull(name);
        else if (field instanceof HuashengField.Value<?> value) {
            if (value.value() instanceof String text) node.put(name, text);
            else if (value.value() instanceof Long number) node.put(name, number);
            else throw new IllegalArgumentException("Invalid callback field");
        }
    }

    private static byte[] bytes(ObjectNode node) {
        try { return JSON.writeValueAsBytes(node); }
        catch (IOException invalid) { throw new IllegalStateException("Callback canonical encoding failed"); }
    }

    @Override public String toString() { return "HuashengCallbackFact[redacted]"; }
}
