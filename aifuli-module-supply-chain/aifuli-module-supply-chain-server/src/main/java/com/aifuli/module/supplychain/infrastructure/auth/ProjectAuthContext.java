package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;

/**
 * 已认证项目调用的包级不可变快照：只允许同包验签链路在全部认证步骤成功后创建。
 *
 * <p>构造器通过统一注册表解析一次操作并缓存 scope，避免后续改动绕过过滤器校验；
 * buyerUserId 始终是不透明字符串，禁止数值化。日志摘要不会输出应用标识或 scope 声明值。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#65-projectauthcontextholder-与-projectcallcontextaccessorimpl}。</p>
 */
final class ProjectAuthContext {

    private final Long projectId;
    private final Long buyerId;
    private final String appId;
    private final int keyVersion;
    private final String operationCode;
    private final String buyerUserId;
    private final String channelCode;
    private final ProjectAuthOperations.ScopeType scopeType;

    ProjectAuthContext(Long projectId, Long buyerId, String appId, int keyVersion,
                       String operationCode, String buyerUserId, String channelCode) {
        requirePositive(projectId, "projectId");
        requirePositive(buyerId, "buyerId");
        requireNonBlank(appId, "appId");
        if (keyVersion <= 0) {
            throw new IllegalArgumentException("keyVersion 必须为正数");
        }
        requireNonBlank(operationCode, "operationCode");

        ProjectAuthOperations.ScopeType resolvedScope = ProjectAuthOperations
                .findByOperationCode(operationCode)
                .map(ProjectAuthOperations.Operation::scopeType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "operationCode 未在认证注册表中唯一登记"));
        String normalizedBuyerUserId = normalizeAbsentScope(buyerUserId);
        String normalizedChannelCode = normalizeAbsentScope(channelCode);
        validateScope(resolvedScope, normalizedBuyerUserId, normalizedChannelCode);

        this.projectId = projectId;
        this.buyerId = buyerId;
        this.appId = appId;
        this.keyVersion = keyVersion;
        this.operationCode = operationCode;
        this.buyerUserId = normalizedBuyerUserId;
        this.channelCode = normalizedChannelCode;
        this.scopeType = resolvedScope;
    }

    Long projectId() {
        return projectId;
    }

    Long buyerId() {
        return buyerId;
    }

    String appId() {
        return appId;
    }

    int keyVersion() {
        return keyVersion;
    }

    String operationCode() {
        return operationCode;
    }

    String buyerUserId() {
        return buyerUserId;
    }

    String channelCode() {
        return channelCode;
    }

    ProjectAuthOperations.ScopeType scopeType() {
        return scopeType;
    }

    /**
     * 仅输出不含 appId、buyerUserId、channelCode 的安全诊断摘要。
     */
    @Override
    public String toString() {
        return "ProjectAuthContext[projectId=" + projectId
                + ", buyerId=" + buyerId
                + ", keyVersion=" + keyVersion
                + ", operationCode=" + operationCode
                + ", scopeType=" + scopeType + ']';
    }

    private static void validateScope(ProjectAuthOperations.ScopeType scopeType,
                                      String buyerUserId, String channelCode) {
        boolean buyerPresent = buyerUserId != null;
        boolean channelPresent = channelCode != null;
        boolean valid = switch (scopeType) {
            case BUYER_USER_ID -> buyerPresent && !channelPresent;
            case CHANNEL_CODE -> !buyerPresent && channelPresent;
            case NONE -> !buyerPresent && !channelPresent;
        };
        if (!valid) {
            throw new IllegalArgumentException("scope 声明与认证操作不匹配");
        }
    }

    private static String normalizeAbsentScope(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " 必须为正数");
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }
}
