package com.aifuli.module.supplychain.infrastructure.auth;

/**
 * 认证链路涉及的状态编码：三表只共享 ENABLED=10；应用/项目支持 20、90，
 * 项目另有 0 草稿，能力则只支持 10 启用/20 停用，不能把三表全量状态视为相同。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#62-projectappcredentialservice应用服务}。</p>
 */
public enum ProjectAuthStatus {

    /** 三表共享的启用状态。 */
    ENABLED((short) 10),
    /** 应用/项目为暂停，能力为停用。 */
    SUSPENDED((short) 20),
    /** 仅应用/项目支持的归档状态。 */
    ARCHIVED((short) 90);

    private final short code;

    ProjectAuthStatus(short code) {
        this.code = code;
    }

    public short code() {
        return code;
    }

    /** 判断 DO 状态字段是否等于本枚举编码。 */
    public boolean matches(Short status) {
        return status != null && status == code;
    }
}
