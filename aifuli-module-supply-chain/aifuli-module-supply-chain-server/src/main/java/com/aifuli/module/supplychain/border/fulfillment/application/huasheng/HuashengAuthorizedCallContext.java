package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

import com.aifuli.module.supplychain.supplieradapter.huasheng.HuashengCrypto;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengCallContext;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 华昇授权调用上下文值对象。
 *
 * <p>职责：
 * 包装纯协议层单次调用上下文 {@link HuashengCallContext} 与应用层凭据身份 {@link HuashengCredentialIdentity}，
 * 对外提供 {@link #toTokenIdentity()} 方法统一计算 Token 复合缓存主键模型。
 *
 * <p>不变量：
 * <ul>
 *   <li>callContext 与 credentialIdentity 必须非空；</li>
 *   <li>类级标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class) 拒绝意外序列化；</li>
 *   <li>{@link #toString()} 严格脱敏，仅输出 operationNo 与 connectorVersionId，杜绝打印任何密钥材料。</li>
 * </ul>
 *
 * <p>关键决策：
 * 在应用层将协议出站上下文与凭据集身份绑定，派生出具备内存材料指纹的 {@link HuashengTokenIdentity}；
 * 兼容标准 PKCS#8 PEM 私钥与测试专用桩字符串，统一提取规范 DER 或 UTF-8 字节。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#182-复合身份材料指纹与缓存条目ad-a5-02--ad-a5-04。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengAuthorizedCallContext {

    private final HuashengCallContext callContext;
    private final HuashengCredentialIdentity credentialIdentity;

    /**
     * 构造授权调用上下文。
     *
     * @param callContext 协议调用上下文，非空
     * @param credentialIdentity 凭据身份，非空
     */
    public HuashengAuthorizedCallContext(
            HuashengCallContext callContext,
            HuashengCredentialIdentity credentialIdentity
    ) {
        this.callContext = Objects.requireNonNull(callContext, "callContext不能为null");
        this.credentialIdentity = Objects.requireNonNull(credentialIdentity, "credentialIdentity不能为null");
    }

    /**
     * 获取协议调用上下文。
     *
     * @return 纯协议调用上下文
     */
    public HuashengCallContext callContext() {
        return callContext;
    }

    /**
     * 获取凭据身份模型。
     *
     * @return 凭据身份
     */
    public HuashengCredentialIdentity credentialIdentity() {
        return credentialIdentity;
    }

    /**
     * 将当前授权上下文投影计算为 Token 复合缓存主键模型。
     *
     * @return 包含内存材料指纹的不可变 Token 身份
     */
    public HuashengTokenIdentity toTokenIdentity() {
        byte[] der;
        try {
            der = HuashengCrypto.loadPkcs8PrivateKey(callContext.credentialMaterial().privateKeyPem()).getEncoded();
        } catch (RuntimeException e) {
            der = callContext.credentialMaterial().privateKeyPem().getBytes(StandardCharsets.UTF_8);
        }

        return HuashengTokenIdentity.of(
                callContext.supplierCode(),
                callContext.connectorVersionId(),
                callContext.baseUrl(),
                callContext.appId(),
                credentialIdentity.credentialSetId(),
                credentialIdentity.sortedItems(),
                callContext.credentialMaterial().appSecret(),
                der
        );
    }

    @Override
    public String toString() {
        return "HuashengAuthorizedCallContext[operationNo=" + callContext.operationNo()
                + ",connectorVersionId=" + callContext.connectorVersionId() + "]";
    }
}
