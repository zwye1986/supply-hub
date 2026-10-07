package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.nio.charset.StandardCharsets;

/**
 * 华昇鉴权 Token 获取请求值对象。
 *
 * <p>职责：封装用于请求华昇 AccessToken 所需的商户身份材料（app_id 与 app_secret）。
 *
 * <p>不变量：
 * <ul>
 *   <li>appId 必须非空且 UTF-8 字节长度恒为 16；</li>
 *   <li>appSecret 必须非空非空白且 UTF-8 字节长度严格为 16、24 或 32（对应 AES-128/192/256 密钥长度）；</li>
 *   <li>类级标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class)，严禁意外 Jackson 序列化；</li>
 *   <li>{@link #toString()} 严格脱敏，绝不输出 appSecret 明文。</li>
 * </ul>
 *
 * <p>关键决策：仅持有纯字符串凭据，不持有网络或通信状态；出站序列化由 {@link HuashengRequestBuilder} 统一组装为 snake_case 字段。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#41-通信与凭据身份、§4.2、§16.2。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengTokenRequest implements HuashengRequest {

    private final String appId;
    private final String appSecret;

    /**
     * 构造华昇鉴权 Token 请求。
     *
     * <p>职责：校验并初始化商户 appId 与密钥材料，确保满足华昇密码学规范。
     *
     * <p>不变量：appId UTF-8 字节长度恒为 16；appSecret UTF-8 字节长度严格为 16, 24 或 32；违规抛出 {@link IllegalArgumentException}。
     *
     * @param appId 商户应用标识，UTF-8 字节长度恒为 16
     * @param appSecret 商户对称密钥，UTF-8 字节长度为 16, 24 或 32
     * @throws IllegalArgumentException 当 appId 或 appSecret 为空或字节长度不符合规范时抛出
     */
    public HuashengTokenRequest(String appId, String appSecret) {
        if (appId == null || appId.getBytes(StandardCharsets.UTF_8).length != 16) {
            throw new IllegalArgumentException("appId必须非空且UTF-8字节长度恒为16");
        }
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("appSecret不能为空或全空白");
        }
        int secretBytes = appSecret.getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes != 16 && secretBytes != 24 && secretBytes != 32) {
            throw new IllegalArgumentException("appSecret UTF-8字节长度必须为16, 24或32");
        }
        this.appId = appId;
        this.appSecret = appSecret;
    }

    /**
     * 获取商户应用标识。
     *
     * @return appId 字符串
     */
    public String appId() {
        return appId;
    }

    /**
     * 获取商户通信密钥。
     *
     * @return appSecret 明文字符串
     */
    public String appSecret() {
        return appSecret;
    }

    @Override
    public HuashengEndpoint endpoint() {
        return HuashengEndpoint.TOKEN;
    }

    @Override
    public String toString() {
        return "HuashengTokenRequest[appId=" + appId + ", appSecret=***]";
    }
}
