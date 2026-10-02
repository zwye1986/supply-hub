package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 不含敏感载荷的审计事件；查询资源使用履约请求，解密资源使用履约资产。设计见
 * docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#5-审计事务与数据库增量。
 */
public record ProjectStandardAssetAuditEvent(
        SensitiveAccessAuditAccessType accessType,
        Long resourceId,
        SensitiveAccessAuditResultStatus result,
        ProjectStandardAssetAuditReason reason) {
    public static final String PRINCIPAL_TYPE = "PROJECT_APPLICATION";
    public static final String REQUEST_RESOURCE = "FULFILLMENT_REQUEST";
    public static final String ASSET_RESOURCE = "FULFILLMENT_ASSET";
}
