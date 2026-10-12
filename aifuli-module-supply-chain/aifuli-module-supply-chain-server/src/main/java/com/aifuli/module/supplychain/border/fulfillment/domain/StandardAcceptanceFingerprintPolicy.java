package com.aifuli.module.supplychain.border.fulfillment.domain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 标准受理格式2／格式3无用户指纹的确定性规范化编码策略（纯域，禁止依赖 Spring/MyBatis/Jackson）。
 *
 * <p>外部不变量字段集：项目 projectCode、分销商 buyerId、原始 buyerOrderNo、buyerSkuCode、
 * 服务端冻结商品身份（可解析时为 platformSkuId，可空）、quantity、冻结目标 Schema/版本与
 * 规范化目标摘要。nonce、当前 Listing 业务版本、CAS 版本、app 密钥版本、内部 token、paidAt
 * 与任何购买人身份一律不参与；同一算法供新请求与可信历史重建调用。</p>
 *
 * <p>编码规则（字节级确定性）：每个字段先写 UTF-8 字段名（int32 大端长度前缀 + 字节），
 * 再写 UTF-8 字段值（int32 大端长度前缀 + 字节；null 值写 -1 哨兵、无字节）。字段顺序固定，
 * 长度前缀天然消除「a|b + c」与「a + b|c」这类无分隔拼接歧义。首位固定写入
 * 对应入口的格式版本，保证格式2、格式3及旧「格式0/1」字节永不混用。</p>
 *
 * <p>分销商选用内部数字 {@code buyerId} 而非 {@code buyerCode}：buyerId 不可变且两个受理入口
 * 均必然持有（HTTP 认证主体 / 内部经 BuyerResolver 解析），buyerCode 存在改名与换码的可能，
 * 不适合作为重放比较身份；服务端冻结商品身份选 {@code platformSkuId} 而非 listingId：
 * 分销 Listing 可换绑/重发，platformSkuId 与报价永久冻结的商品身份一致。</p>
 *
 * <p>格式3仅替换原请求商品码语义并增加PLATFORM_SKU类型标识，不更改格式2与总订单摘要。
 * 设计见 {@code docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#5-永久幂等与格式3}。</p>
 */
public final class StandardAcceptanceFingerprintPolicy {

    /** 规范化请求指纹格式版本：2=无用户格式；与 052 增量中的 fingerprint_version 列取值一致。 */
    public static final int FORMAT_VERSION = 2;

    /** 平台SKU请求独立格式，不升级格式2或总订单业务摘要；详见上述设计第5章。 */
    public static final short PLATFORM_SKU_FORMAT_VERSION = 3;
    private static final String PLATFORM_SKU_CODE_TYPE = "PLATFORM_SKU";

    /**
     * 无用户请求身份：全部字段来自认证／解析／规范化结果，不携带购买人、支付时间、调用方版本或报价token。
     * buyerSkuCode保留格式2命名；格式3入口必须传本次原始平台SKU请求码，不能传内部BPD。
     */
    public record RequestIdentity(
            Long buyerId,
            String projectCode,
            String buyerOrderNo,
            String buyerSkuCode,
            Long frozenPlatformSkuId,
            long quantity,
            String targetSchemaCode,
            int targetSchemaVersion,
            String targetDigestHex) {
    }

    /**
     * 输出请求身份的规范化字节（供 sha256 得到 invariant/request 指纹、或历史重建复算）。
     */
    public static byte[] canonicalRequest(RequestIdentity identity) {
        return canonicalRequest(identity, false);
    }

    /**
     * 格式3专用入口：identity 的商品码必须为本次原始平台SKU请求码，不得替换为解析BPD或回执旧码。
     * 字段顺序见 docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#51-不改变永久键。
     */
    public static byte[] canonicalPlatformSkuRequest(RequestIdentity identity) {
        return canonicalRequest(identity, true);
    }

    private static byte[] canonicalRequest(RequestIdentity identity, boolean platformSku) {
        Objects.requireNonNull(identity, "request identity is required");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        writeField(buffer, "formatVersion", String.valueOf(platformSku ? PLATFORM_SKU_FORMAT_VERSION : FORMAT_VERSION));
        writeField(buffer, "buyerId", String.valueOf(identity.buyerId()));
        writeField(buffer, "projectCode", identity.projectCode());
        writeField(buffer, "buyerOrderNo", identity.buyerOrderNo());
        if (platformSku) {
            writeField(buffer, "productCodeType", PLATFORM_SKU_CODE_TYPE);
        }
        writeField(buffer, platformSku ? "platformSkuCode" : "buyerSkuCode", identity.buyerSkuCode());
        writeField(buffer, "frozenPlatformSkuId",
                identity.frozenPlatformSkuId() == null
                        ? null : String.valueOf(identity.frozenPlatformSkuId()));
        writeField(buffer, "quantity", String.valueOf(identity.quantity()));
        writeField(buffer, "targetSchemaCode", identity.targetSchemaCode());
        writeField(buffer, "targetSchemaVersion", String.valueOf(identity.targetSchemaVersion()));
        writeField(buffer, "targetDigestHex", identity.targetDigestHex());
        return buffer.toByteArray();
    }

    /**
     * 总订单 buyer_order_digest 的规范化输入：仅「项目编码 + 采购商订单号」形成不可逆摘要，
     * 移除旧实现的用户成分。同一编码规则，供新订单与 052 历史清理复算。
     */
    public static byte[] canonicalBuyerOrderDigestInput(String projectCode, String buyerOrderNo) {
        if (projectCode == null || projectCode.isBlank()
                || buyerOrderNo == null || buyerOrderNo.isBlank()) {
            throw new IllegalArgumentException("buyer order digest input is incomplete");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        writeField(buffer, "formatVersion", String.valueOf(FORMAT_VERSION));
        writeField(buffer, "projectCode", projectCode);
        writeField(buffer, "buyerOrderNo", buyerOrderNo);
        return buffer.toByteArray();
    }

    /** 单字段写入：int32 大端字段名长度 + UTF-8 字段名 + int32 大端字段值长度 + UTF-8 字段值（null 写 -1 哨兵）。 */
    private static void writeField(ByteArrayOutputStream buffer, String name, String value) {
        writeUtf8(buffer, name);
        if (value == null) {
            writeInt(buffer, -1);
            return;
        }
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        writeInt(buffer, valueBytes.length);
        buffer.write(valueBytes, 0, valueBytes.length);
    }

    private static void writeUtf8(ByteArrayOutputStream buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeInt(buffer, bytes.length);
        buffer.write(bytes, 0, bytes.length);
    }

    /** int32 大端：高位在前，字节序固定，不依赖平台编码。 */
    private static void writeInt(ByteArrayOutputStream buffer, int value) {
        buffer.write(value >>> 24);
        buffer.write(value >>> 16);
        buffer.write(value >>> 8);
        buffer.write(value);
    }

    private StandardAcceptanceFingerprintPolicy() {
    }
}
