package com.aifuli.module.supplychain.infrastructure.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;

/**
 * 项目调用可信上下文的生产失败关闭自动配置。
 *
 * <p>未接入可信认证实现时必须拒绝调用，绝不能用 DTO、普通请求头或租户上下文伪造身份。
 * 本类经 Boot 自动配置 imports 加载，在用户组件注册后判断 {@link ConditionalOnMissingBean}，
 * 保证真实访问器存在时兜底可靠退位。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#65-projectauthcontextholder-与-projectcallcontextaccessorimpl}。</p>
 */
@AutoConfiguration
public class ProjectCallContextConfiguration {

    /**
     * 注册拒绝式默认访问器，保证认证链路遗漏时不会以猜测身份继续执行。
     *
     * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#42-可信上下文}。</p>
     *
     * @return 仅会抛出采购商调用身份无效异常的默认访问器
     */
    @Bean
    @ConditionalOnMissingBean(ProjectCallContextAccessor.class)
    public ProjectCallContextAccessor projectCallContextAccessor() {
        return new ProjectCallContextAccessor() {
            @Override
            public BuyerContext requireBuyerContext() {
                throw exception(BUYER_AUTH_INVALID);
            }

            @Override
            public ChannelContext requireChannelContext() {
                throw exception(BUYER_AUTH_INVALID);
            }

            @Override
            public ProjectContext requireProjectContext() {
                throw exception(BUYER_AUTH_INVALID);
            }
        };
    }
}
