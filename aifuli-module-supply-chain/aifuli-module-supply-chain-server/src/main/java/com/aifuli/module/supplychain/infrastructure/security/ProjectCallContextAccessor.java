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
     * 获取标准商品操作的可信项目应用快照；旧 SPI 实现默认失败关闭，避免伪造用户身份补齐。
     * 调用方必须明确期望操作，真实实现核对认证快照的操作与能力。
     * 详见 {@code docs/superpowers/specs/2026-09-30-project-signed-standard-order-http-design.md#21-认证实施边界}。
     */
    default StandardProductContext requireStandardProductContext(String expectedOperationCode) {
        throw com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception(
                com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID);
    }

    /**
     * 标准商品的不可变认证快照：仅含项目、分销商、应用、版本和操作，无用户/渠道身份。
     * 详见 {@code docs/superpowers/specs/2026-09-30-project-signed-standard-order-http-design.md#21-认证实施边界}。
     */
    record StandardProductContext(Long projectId, Long buyerId, String appId, int keyVersion, String operationCode) {
        public StandardProductContext {
            if (projectId == null || projectId <= 0) { throw new IllegalArgumentException("projectId 必须为正数"); }
            if (buyerId == null || buyerId <= 0) { throw new IllegalArgumentException("buyerId 必须为正数"); }
            if (appId == null || appId.isBlank()) { throw new IllegalArgumentException("appId 不能为空"); }
            if (keyVersion <= 0) { throw new IllegalArgumentException("keyVersion 必须为正数"); }
            if (operationCode == null || operationCode.isBlank()) {
                throw new IllegalArgumentException("operationCode 不能为空");
            }
        }

        /** 诊断摘要不包含应用标识、密钥或 scope 声明，避免日志泄漏调用身份。 */
        @Override
        public String toString() {
            return "StandardProductContext[projectId=" + projectId + ", buyerId=" + buyerId
                    + ", keyVersion=" + keyVersion + ", operationCode=" + operationCode + ']';
        }
    }

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
