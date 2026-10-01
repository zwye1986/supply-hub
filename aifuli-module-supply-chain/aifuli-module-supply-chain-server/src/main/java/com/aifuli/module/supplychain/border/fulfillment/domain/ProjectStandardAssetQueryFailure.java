package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 安全错误摘要保留因果关系，不把供应商载荷或原始异常消息拼进诊断信息。设计见
 * docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#4-资产结果与封闭载荷。
 */
public class ProjectStandardAssetQueryFailure extends IllegalStateException {
    private final ProjectStandardAssetAuditReason reason;

    public ProjectStandardAssetQueryFailure(ProjectStandardAssetAuditReason reason) {
        this(reason, null);
    }

    public ProjectStandardAssetQueryFailure(
            ProjectStandardAssetAuditReason reason, Throwable cause) {
        super("项目标准订单查询失败：" + reason.name(), cause);
        this.reason = reason;
    }

    public ProjectStandardAssetAuditReason reason() {
        return reason;
    }
}
