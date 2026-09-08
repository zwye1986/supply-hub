package com.aifuli.module.supplychain.infrastructure.auth;

import java.util.Objects;

/**
 * 认证上下文的请求线程容器：过滤器写入并在 {@code finally} 中清理。
 *
 * <p>使用普通 {@link ThreadLocal}，上下文不会继承到子线程；业务代码禁止直接读取本类，必须经
 * {@code ProjectCallContextAccessor} 取得与 scope 匹配的可信上下文。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#65-projectauthcontextholder-与-projectcallcontextaccessorimpl}。</p>
 */
final class ProjectAuthContextHolder {

    private static final ThreadLocal<ProjectAuthContext> CONTEXT = new ThreadLocal<>();

    private ProjectAuthContextHolder() {
    }

    /**
     * 设置或替换当前线程的已认证快照。
     *
     * @param context 非空已认证快照
     */
    static void set(ProjectAuthContext context) {
        CONTEXT.set(Objects.requireNonNull(context, "认证上下文不能为空"));
    }

    /**
     * 获取当前线程快照；未认证或已清理时返回 {@code null}，由可信访问器统一失败关闭。
     */
    static ProjectAuthContext get() {
        return CONTEXT.get();
    }

    /**
     * 删除当前线程快照，避免容器线程复用造成身份串号。
     */
    static void clear() {
        CONTEXT.remove();
    }
}
