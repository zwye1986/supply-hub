package com.aifuli.module.supplychain.infrastructure.identity;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_MANUAL_NOT_ALLOWED;

/**
 * 统一拒绝供应商、分销商和项目创建入口提交的永久编码字段。
 * 管理端传入字段出现标记，兼容命令以非 null 值表示提交；空字符串仍属于手填。
 * 设计见 {@code docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#6-接口前端与错误提示}。
 */
public final class SystemAssignedBusinessCodeGuard {

    private SystemAssignedBusinessCodeGuard() {
    }

    public static void rejectSubmitted(ScopedBusinessCodeService.Kind kind, boolean submitted) {
        if (kind != ScopedBusinessCodeService.Kind.SPL && kind != ScopedBusinessCodeService.Kind.BYR
                && kind != ScopedBusinessCodeService.Kind.PRJ) {
            throw new IllegalArgumentException("非系统生成的编码类型: " + kind);
        }
        if (submitted) {
            throw exception(BUSINESS_CODE_MANUAL_NOT_ALLOWED, kind.label());
        }
    }
}
