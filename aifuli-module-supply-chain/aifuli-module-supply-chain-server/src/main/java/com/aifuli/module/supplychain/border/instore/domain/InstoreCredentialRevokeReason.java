package com.aifuli.module.supplychain.border.instore.domain;

/**
 * 到店购入口凭证稳定撤销原因；数据库只保存枚举名，禁止把外部异常正文写入 revoke_reason。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#8-凭证状态机}。</p>
 */
public enum InstoreCredentialRevokeReason {
    /** 旅通入口 URL 调用失败或超时；结果未知时撤销当前 token，调用方重试签发新 token。 */
    ENTRY_URL_CALL_FAILED,
    /** 签发时冻结的 MEMBER_PROFILE 档案版本已退役。 */
    MEMBER_PROFILE_RETIRED,
    /** 018 前向迁移时历史 ACTIVE/PROCESSING 凭证无法证明冻结路由。 */
    MIGRATION_UNFROZEN_ROUTE
}
