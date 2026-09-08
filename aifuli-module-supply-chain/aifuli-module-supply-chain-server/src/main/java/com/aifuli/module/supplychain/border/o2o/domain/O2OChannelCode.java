package com.aifuli.module.supplychain.border.o2o.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * O2O 渠道编码，对应 {@code sc_o2o_channel_detail.channel_code}。
 *
 * <p>一期固定为 ELEME；盒马与淘宝闪购的区别仅体现在 H5 入口 URL 配置，不引入独立渠道编码。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.3-本期范围裁剪决策}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum O2OChannelCode {
    /** 饿了么（淘宝闪购/盒马同渠道）。 */
    ELEME("饿了么");

    /** 中文名称，与 DDL COMMENT 语义一致。 */
    private final String chineseName;
}
