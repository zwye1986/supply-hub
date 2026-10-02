package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 项目资产查询封闭审计原因。设计见
 * docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#5-审计事务与数据库增量。
 */
public enum ProjectStandardAssetAuditReason {
    AUTHORIZED_QUERY,
    WAITING,
    MANUAL_WAITING,
    UNSUPPORTED_PRODUCT_TYPE,
    ORDER_NOT_ACCEPTED,
    ORDER_TERMINATED,
    ORDER_NOT_FOUND,
    REFERENCE_INCONSISTENT,
    DECRYPT_FAILED,
    PAYLOAD_FAILED
}
