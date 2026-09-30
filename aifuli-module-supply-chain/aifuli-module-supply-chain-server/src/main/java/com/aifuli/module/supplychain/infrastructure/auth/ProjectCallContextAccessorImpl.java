package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import org.springframework.stereotype.Component;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;

/**
 * 项目调用可信上下文真实实现：从认证线程快照精确产出注册表 scope 对应的业务上下文。
 *
 * <p>缺少认证快照或调用了不匹配的访问器时统一失败关闭，不回显 scope 值，也不尝试从 DTO、
 * 普通请求头或租户上下文补齐身份。buyerUserId 保持不透明字符串。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#65-projectauthcontextholder-与-projectcallcontextaccessorimpl}。</p>
 */
@Component
public class ProjectCallContextAccessorImpl implements ProjectCallContextAccessor {

    @Override
    public BuyerContext requireBuyerContext() {
        ProjectAuthContext context = requireScope(ProjectAuthOperations.ScopeType.BUYER_USER_ID);
        return new BuyerContext(context.projectId(), context.buyerId(), context.buyerUserId());
    }

    @Override
    public ChannelContext requireChannelContext() {
        ProjectAuthContext context = requireScope(ProjectAuthOperations.ScopeType.CHANNEL_CODE);
        return new ChannelContext(context.projectId(), context.channelCode());
    }

    @Override
    public ProjectContext requireProjectContext() {
        ProjectAuthContext context = requireScope(ProjectAuthOperations.ScopeType.NONE);
        return new ProjectContext(context.projectId());
    }

    private ProjectAuthContext requireScope(ProjectAuthOperations.ScopeType expectedScope) {
        ProjectAuthContext context = ProjectAuthContextHolder.get();
        if (context == null || context.scopeType() != expectedScope) {
            throw exception(BUYER_AUTH_INVALID);
        }
        return context;
    }
}
