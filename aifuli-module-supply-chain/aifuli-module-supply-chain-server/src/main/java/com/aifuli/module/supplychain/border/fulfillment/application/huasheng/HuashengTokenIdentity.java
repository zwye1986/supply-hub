package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCredentialIdentity.CredentialItemSnapshot;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 华昇 Token 复合缓存身份主键模型。
 *
 * <p>职责：
 * 聚合供应商、连接器版本、规范化根地址、应用标识、凭据集 ID、已排序凭据项版本快照以及内存材料指纹，
 * 作为 Token 内存缓存的绝对唯一主键，杜绝不同环境、凭据轮换或材料变更时串用缓存。
 *
 * <p>不变量：
 * <ul>
 *   <li>所有维度均非空且非空白；</li>
 *   <li>材料指纹使用进程启动时随机生成的 32 字节 HMAC-SHA256 密钥在内存计算，仅存活于当前 JVM；</li>
 *   <li>材料指纹杜绝持久化、杜绝写入日志、审计或配置，防止彩虹表离线碰撞；</li>
 *   <li>{@link #equals(Object)} 与 {@link #hashCode()} 完整覆盖所有维度及材料指纹字节比较；</li>
 *   <li>类级标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class)；</li>
 *   <li>{@link #toString()} 严格脱敏，不输出 appId、baseUrl 或指纹/密钥原文。</li>
 * </ul>
 *
 * <p>关键决策：
 * 材料指纹采用长度分帧（Length-Framing）安全编码：{@code [4 字节 secret 长度 | secret 字节 | 4 字节 DER 长度 | DER 字节]}，
 * 避免不同字段拼接时的边界歧义注入漏洞。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#182-复合身份材料指纹与缓存条目ad-a5-02--ad-a5-04。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengTokenIdentity {

    private static final byte[] PROCESS_HMAC_KEY = new byte[32];

    static {
        new SecureRandom().nextBytes(PROCESS_HMAC_KEY);
    }

    private final String supplierCode;
    private final Long connectorVersionId;
    private final String normalizedBaseUrl;
    private final String appId;
    private final Long credentialSetId;
    private final List<CredentialItemSnapshot> sortedItems;
    private final byte[] materialFingerprint;

    /**
     * 构造 Token 身份复合主键。
     *
     * @param supplierCode 供应商编码，非空非空白
     * @param connectorVersionId 连接器版本 ID，大于 0
     * @param normalizedBaseUrl 规范化供应商基础 URL，非空非空白
     * @param appId 商户应用标识，非空
     * @param credentialSetId 凭据集 ID，大于 0
     * @param sortedItems 凭据项快照列表，非空
     * @param materialFingerprint 内存材料指纹字节数组，非空
     */
    public HuashengTokenIdentity(
            String supplierCode,
            Long connectorVersionId,
            String normalizedBaseUrl,
            String appId,
            Long credentialSetId,
            List<CredentialItemSnapshot> sortedItems,
            byte[] materialFingerprint
    ) {
        if (supplierCode == null || supplierCode.isBlank()) {
            throw new IllegalArgumentException("supplierCode不能为空或空白");
        }
        if (connectorVersionId == null || connectorVersionId <= 0L) {
            throw new IllegalArgumentException("connectorVersionId必须为正整数");
        }
        if (normalizedBaseUrl == null || normalizedBaseUrl.isBlank()) {
            throw new IllegalArgumentException("normalizedBaseUrl不能为空或空白");
        }
        Objects.requireNonNull(appId, "appId不能为null");
        if (credentialSetId == null || credentialSetId <= 0L) {
            throw new IllegalArgumentException("credentialSetId必须为正整数");
        }
        Objects.requireNonNull(sortedItems, "sortedItems不能为null");
        Objects.requireNonNull(materialFingerprint, "materialFingerprint不能为null");

        List<CredentialItemSnapshot> itemsCopy = new ArrayList<>(sortedItems);
        Collections.sort(itemsCopy);

        this.supplierCode = supplierCode;
        this.connectorVersionId = connectorVersionId;
        this.normalizedBaseUrl = normalizedBaseUrl;
        this.appId = appId;
        this.credentialSetId = credentialSetId;
        this.sortedItems = Collections.unmodifiableList(itemsCopy);
        this.materialFingerprint = materialFingerprint.clone();
    }

    /**
     * 工厂方法：通过出站明文凭据材料自动计算长度分帧内存材料指纹。
     *
     * @param supplierCode 供应商编码
     * @param connectorVersionId 连接器版本 ID
     * @param normalizedBaseUrl 基础 URL
     * @param appId 应用标识
     * @param credentialSetId 凭据集 ID
     * @param sortedItems 凭据项列表
     * @param appSecret 应用密钥明文
     * @param privateKeyDer RSA 私钥 DER 规范字节
     * @return 完整的 Token 身份对象
     */
    public static HuashengTokenIdentity of(
            String supplierCode,
            Long connectorVersionId,
            String normalizedBaseUrl,
            String appId,
            Long credentialSetId,
            List<CredentialItemSnapshot> sortedItems,
            String appSecret,
            byte[] privateKeyDer
    ) {
        byte[] fingerprint = computeMaterialFingerprint(appSecret, privateKeyDer);
        return new HuashengTokenIdentity(
                supplierCode,
                connectorVersionId,
                normalizedBaseUrl,
                appId,
                credentialSetId,
                sortedItems,
                fingerprint
        );
    }

    /**
     * 计算材料指纹。
     */
    public static byte[] computeMaterialFingerprint(String appSecret, byte[] privateKeyDer) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(PROCESS_HMAC_KEY, "HmacSHA256"));
            byte[] secretBytes = appSecret != null ? appSecret.getBytes(StandardCharsets.UTF_8) : new byte[0];
            byte[] derBytes = privateKeyDer != null ? privateKeyDer : new byte[0];

            ByteBuffer buffer = ByteBuffer.allocate(4 + secretBytes.length + 4 + derBytes.length);
            buffer.putInt(secretBytes.length);
            buffer.put(secretBytes);
            buffer.putInt(derBytes.length);
            buffer.put(derBytes);

            return mac.doFinal(buffer.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("进程内材料指纹 HMAC 计算失败", e);
        }
    }

    public String supplierCode() {
        return supplierCode;
    }

    public Long connectorVersionId() {
        return connectorVersionId;
    }

    public String normalizedBaseUrl() {
        return normalizedBaseUrl;
    }

    public String appId() {
        return appId;
    }

    public Long credentialSetId() {
        return credentialSetId;
    }

    public List<CredentialItemSnapshot> sortedItems() {
        return sortedItems;
    }

    public byte[] materialFingerprint() {
        return materialFingerprint.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HuashengTokenIdentity that = (HuashengTokenIdentity) o;
        return Objects.equals(supplierCode, that.supplierCode)
                && Objects.equals(connectorVersionId, that.connectorVersionId)
                && Objects.equals(normalizedBaseUrl, that.normalizedBaseUrl)
                && Objects.equals(appId, that.appId)
                && Objects.equals(credentialSetId, that.credentialSetId)
                && Objects.equals(sortedItems, that.sortedItems)
                && Arrays.equals(materialFingerprint, that.materialFingerprint);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(supplierCode, connectorVersionId, normalizedBaseUrl, appId, credentialSetId, sortedItems);
        result = 31 * result + Arrays.hashCode(materialFingerprint);
        return result;
    }

    @Override
    public String toString() {
        return "HuashengTokenIdentity[supplierCode=" + supplierCode
                + ",connectorVersionId=" + connectorVersionId
                + ",credentialSetId=" + credentialSetId + "]";
    }
}
