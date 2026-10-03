package com.aifuli.module.supplychain.api.o2o.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "O2O H5 入口响应")
@Data
public class O2OEntryRespDTO implements Serializable {

    /** 中台生成的高熵随机渠道匿名用户标识，作为渠道 externalUserId/open_id */
    @Schema(description = "中台生成的高熵随机渠道匿名用户标识，作为渠道 externalUserId/open_id",
            example = "ch-anon-9f3a...")
    private String channelUserId;

    /** 可跳转的淘宝闪购/盒马 H5 入口地址；由场景编码选择配置 */
    @Schema(description = "可跳转的淘宝闪购/盒马 H5 入口地址；由场景编码选择配置")
    private String h5Url;

    /** 渠道会话令牌；具体形态由渠道协议决定 */
    @Schema(description = "渠道会话令牌；具体形态由渠道协议决定")
    private String token;
}
