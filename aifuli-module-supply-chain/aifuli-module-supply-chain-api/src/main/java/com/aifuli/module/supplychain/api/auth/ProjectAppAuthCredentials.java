package com.aifuli.module.supplychain.api.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 项目应用调用凭证：调用方注入的认证身份与 scope 声明值。
 *
 * <p>appKey 是密钥明文，仅允许存活于调用方内存，禁止落日志、缓存或序列化。
 * scope 字段按目标操作的 {@link ProjectAuthOperations.ScopeType} 填写，不适用的字段传 {@code null}；
 * contractVersion 由注册表按操作解析（设计 §4.1），凭证不承载该字段。
 * 详见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#7-签名客户端}。</p>
 *
 * @param appId       项目全局唯一稳定应用标识
 * @param appKey      appKey 明文
 * @param keyVersion  调用方使用的密钥版本号
 * @param buyerUserId 采购商用户不透明标识，BUYER_USER_ID scope 操作必填，其余传 null
 * @param channelCode 渠道编码，CHANNEL_CODE scope 操作必填，其余传 null
 */
public record ProjectAppAuthCredentials(String appId, @JsonIgnore String appKey, int keyVersion,
                                        String buyerUserId, String channelCode) {

    /**
     * 紧凑构造器把凭证错误提前到构造点（而非首次签名时）：appId/appKey 非空白、keyVersion ≥ 1。
     * 不校验 scope 字段——其合法性依赖目标操作的 ScopeType，由 Signer 的 validateScope 判定。
     */
    public ProjectAppAuthCredentials {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("appId 不能为空白");
        }
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("appKey 不能为空白");
        }
        if (keyVersion < 1) {
            throw new IllegalArgumentException("keyVersion 必须 >= 1");
        }
    }

    @Override
    public String toString() {
        return "ProjectAppAuthCredentials[appId=" + appId + ", keyVersion=" + keyVersion
                + ", buyerUserId=***, channelCode=" + channelCode + ", appKey=***]";
    }
}
