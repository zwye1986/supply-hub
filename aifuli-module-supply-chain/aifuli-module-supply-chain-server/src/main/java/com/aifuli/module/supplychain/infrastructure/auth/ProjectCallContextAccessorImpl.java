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

    /**
     * 标准商品要求 NONE scope、STANDARD_PRODUCT 能力和精确期望操作同时成立。
     * 只读取验签链写入的包级快照；旧 NONE 回调和其他标准操作不得借用该身份进入业务。
     * 详见 docs/superpowers/specs/2026-09-30-project-signed-standard-order-http-design.md#21-认证实施边界。
     */
    @Override
    public StandardProductContext requireStandardProductContext(String expectedOperationCode) {
        ProjectAuthContext context = requireScope(ProjectAuthOperations.ScopeType.NONE);
        ProjectAuthOperations.Operation operation = ProjectAuthOperations.findByOperationCode(expectedOperationCode)
                .orElseThrow(() -> exception(BUYER_AUTH_INVALID));
        if (operation.scopeType() != ProjectAuthOperations.ScopeType.NONE
                || !ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT.equals(operation.requiredCapability())
                || !operation.operationCode().equals(context.operationCode())) {
            throw exception(BUYER_AUTH_INVALID);
        }
        return new StandardProductContext(context.projectId(), context.buyerId(), context.appId(),
                context.keyVersion(), context.operationCode());
    }

    private ProjectAuthContext requireScope(ProjectAuthOperations.ScopeType expectedScope) {
        ProjectAuthContext context = ProjectAuthContextHolder.get();
        if (context == null || context.scopeType() != expectedScope) {
            throw exception(BUYER_AUTH_INVALID);
        }
        return context;
    }
}
