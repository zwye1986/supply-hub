package com.aifuli.module.supplychain.error;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 调用方收到稳定业务错误后的处置指令。
 */
@Schema(description = "错误处置指令：禁止重试、条件修复后原键重试、原键退避重试、按业务键查询或人工处置")
public enum RetryDirective {

    DO_NOT_RETRY,
    RETRY_SAME_KEY_AFTER_CONDITION_CHANGE,
    RETRY_SAME_KEY,
    QUERY_BY_BUSINESS_KEY,
    MANUAL
}
