package com.aifuli.channel.taobaoflash.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 外部商城 SSO 免登录获取淘宝闪购 H5 链接请求。
 *
 * <p>对应原 yudao-module-taobao TbsgSsoH5UrlContext，由外部采购商商城后端带签名调用。
 * 详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7。</p>
 */
@Data
public class ChannelSsoH5UrlRequest {

    /** 外部商城用户唯一标识，作为 ele.me open_id */
    @NotBlank
    private String externalUserId;

    /** 用户手机号 */
    @NotBlank
    private String mobile;

    /** 环境：PROD / PPE */
    @NotBlank
    private String env;

    /** 随机串，防重放 */
    @NotBlank
    private String nonce;

    /** 请求时间戳（秒） */
    @NotNull
    private Long timestamp;

    /** HMAC-SHA256 签名 */
    @NotBlank
    private String sign;

    /** 入口变体：留空走默认淘宝闪购 URL；hema 走盒马 URL */
    private String variant;
}
