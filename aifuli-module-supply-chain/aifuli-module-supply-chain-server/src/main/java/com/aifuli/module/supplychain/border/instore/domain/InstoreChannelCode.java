package com.aifuli.module.supplychain.border.instore.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 到店购渠道编码，对应 {@code sc_instore_channel_order.channel_code}。
 *
 * <p>一期固定为 RT_MART（大润发/旅通到店扫码购）。与 O2O 渠道编码独立，不混用 ELEME。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#4-中台侧场景详情模块}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum InstoreChannelCode {
    /** 大润发到店扫码购（旅通支付中台）。 */
    RT_MART("大润发到店购");

    /** 中文名称，与 DDL COMMENT 语义一致。 */
    private final String chineseName;
}
