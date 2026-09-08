package com.aifuli.module.supplychain.border.rtmart;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 旅通已确认的响应码目录，接口①～⑥共用。
 *
 * <p>协议码只能从本枚举产生，禁止在 Controller 中散落字符串字面量。详见
 * {@code docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#2-旅通响应码枚举}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum RtmartResponseCode {

    SUCCESS("E0000"),
    INVALID_ARGUMENT("E4001"),
    REQUEST_EXPIRED("E4002"),
    INVALID_APP_KEY("E4003"),
    INVALID_SIGNATURE("E4004"),
    SYSTEM_ERROR("E5000");

    private final String code;
}
