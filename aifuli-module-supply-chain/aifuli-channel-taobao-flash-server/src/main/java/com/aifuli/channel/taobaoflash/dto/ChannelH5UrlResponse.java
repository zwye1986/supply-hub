package com.aifuli.channel.taobaoflash.dto;

import lombok.Data;

/**
 * SSO 免登录 H5 链接响应。
 *
 * <p>对应原 yudao-module-taobao TbsgH5UrlResponse。status 取 SUCCESS/FAIL，与原协议一致。</p>
 */
@Data
public class ChannelH5UrlResponse {

    /** H5 跳转链接 */
    private String h5Url;

    /** 状态：SUCCESS / FAIL */
    private String status;

    /** JWT 令牌（带 Bearer 前缀），失败时为空 */
    private String token;

    /** 错误信息，成功时为空 */
    private String errMessage;

    public ChannelH5UrlResponse(String h5Url, String status, String token, String errMessage) {
        this.h5Url = h5Url;
        this.status = status;
        this.token = token;
        this.errMessage = errMessage;
    }
}
