package com.aifuli.module.supplychain.integration.rtmart;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 旅通 HOME/DETAIL 响应的最小 envelope，仅保留协议判断所需字段。
 *
 * <p>旅通可新增无关字段而不影响兼容；消息与跳转地址可能含凭证，因此安全摘要绝不输出其原文。设计见
 * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#5-响应与错误语义}。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record RtmartEntryResponse(String msg, String result, String code, String jumpUrl) {

    /** @return 不包含远端消息或完整跳转地址的安全诊断摘要。 */
    @Override
    public String toString() {
        return "RtmartEntryResponse[code=" + safeCode(code) + ", result=" + safeResult(result)
                + ", msg=<redacted>, jumpUrl=<redacted>]";
    }

    private static String safeCode(String code) {
        return code != null && code.matches("E\\d{4}") ? code : "UNKNOWN";
    }

    private static String safeResult(String result) {
        return "success".equals(result) ? "success" : "non-success";
    }
}
