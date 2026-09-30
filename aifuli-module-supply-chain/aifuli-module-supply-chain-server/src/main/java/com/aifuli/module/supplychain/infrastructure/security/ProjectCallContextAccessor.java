package com.aifuli.module.supplychain.infrastructure.security;

import java.util.Objects;

/**
 * 读取已由受信任认证链路建立的项目调用上下文。
 *
 * <p>本接口不能从 DTO、普通请求头或租户上下文推断身份，避免调用方伪造项目或采购商归属。
 * 详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#42-可信上下文}。</p>
 */
public interface ProjectCallContextAccessor {

    /**
     * 获取采购商侧调用的受信任上下文；buyerUserId 是不透明字符串，禁止数值化转换。
     *
     * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#42-可信上下文}。</p>
     *
     * @return 已认证的项目、采购商和采购商用户上下文
     */
    BuyerContext requireBuyerContext();

    /**
     * 获取渠道侧调用的受信任上下文。
     *
     * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#42-可信上下文}。</p>
     *
     * @return 已认证的项目和渠道上下文
     */
    ChannelContext requireChannelContext();

    /**
     * 获取仅项目维度的受信任上下文：SaaS 收银台回调等不携带采购商用户身份的调用使用。
     *
     * <p>详见 {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#54-projectcallcontext}。</p>
     *
     * @return 已认证的项目上下文
     */
    ProjectContext requireProjectContext();

    /**
     * 采购商调用身份的不可变快照，三项均为可信认证结果。
     */
    record BuyerContext(Long projectId, Long buyerId, String buyerUserId) {

        public BuyerContext {
            if (Objects.isNull(projectId)) {
                throw new IllegalArgumentException("projectId 不能为空");
            }
            if (Objects.isNull(buyerId)) {
                throw new IllegalArgumentException("buyerId 不能为空");
            }
            if (Objects.isNull(buyerUserId) || buyerUserId.isBlank()) {
                throw new IllegalArgumentException("buyerUserId 不能为空");
            }
        }

        /**
         * 仅保留安全的项目和采购商标识，buyerUserId 不得通过日志、异常或调试输出泄漏。
         *
         * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#42-可信上下文}。</p>
         *
         * @return 不含采购商用户标识的安全上下文摘要
         */
        @Override
        public String toString() {
            return "BuyerContext[projectId=" + projectId + ", buyerId=" + buyerId + ']';
        }
    }

    /**
     * 渠道调用身份的不可变快照，项目和渠道编码均为可信认证结果。
     */
    record ChannelContext(Long projectId, String channelCode) {

        public ChannelContext {
            if (Objects.isNull(projectId)) {
                throw new IllegalArgumentException("projectId 不能为空");
            }
            if (Objects.isNull(channelCode) || channelCode.isBlank()) {
                throw new IllegalArgumentException("channelCode 不能为空");
            }
        }
    }

    /**
     * 项目调用身份的不可变快照，仅包含项目维度，用于回调等无采购商用户身份的调用。
     */
    record ProjectContext(Long projectId) {

        public ProjectContext {
            if (Objects.isNull(projectId)) {
                throw new IllegalArgumentException("projectId 不能为空");
            }
        }
    }
}
