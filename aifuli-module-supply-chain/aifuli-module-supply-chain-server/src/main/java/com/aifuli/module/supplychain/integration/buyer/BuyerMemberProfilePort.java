package com.aifuli.module.supplychain.integration.buyer;

import java.util.Objects;

import static com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode.MEMBER_PROFILE;

/**
 * 按冻结的采购商会员档案路由查询 SaaS 会员资料的出站端口。
 *
 * <p>外部会员身份固定为 {@code route.sourceSystem + route.externalTenantId + buyerUserId}；
 * {@code buyerUserId} 是不透明字符串，禁止数值化，也不得向核心领域暴露 SaaS 内部会员主键。
 * 设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#BuyerMemberProfilePort}。</p>
 */
public interface BuyerMemberProfilePort {

    /** 按可信 MEMBER_PROFILE 路由解析会员资料。 */
    MemberProfileResult resolve(ResolveMemberCommand command);

    /**
     * 会员解析命令，原样携带冻结路由和不透明采购商用户号。
     */
    record ResolveMemberCommand(BuyerIntegrationRoute route, String buyerUserId) {

        public ResolveMemberCommand {
            if (route == null || route.capability() != MEMBER_PROFILE) {
                throw new IllegalArgumentException("route capability must be MEMBER_PROFILE");
            }
            if (buyerUserId == null || buyerUserId.isBlank()) {
                throw new IllegalArgumentException("buyerUserId must not be blank");
            }
        }

        /**
         * 仅输出安全路由坐标，采购商用户号和 credentialSet 不得通过诊断字符串泄漏。
         *
         * <p>设计见
         * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#12-安全与日志}。</p>
         *
         * @return 不含采购商用户号与凭据集合标识的安全命令摘要
         */
        @Override
        public String toString() {
            return "ResolveMemberCommand[projectId=" + route.projectId()
                    + ", profileVersionId=" + route.profileVersionId()
                    + ", capability=" + route.capability()
                    + ", sourceSystem=" + route.sourceSystem() + ']';
        }
    }

    /**
     * 会员资料解析结果，不包含 SaaS 内部会员主键；FOUND 必须携带非空手机号，姓名允许为空。
     *
     * <p>设计见
     * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#BuyerMemberProfilePort}。</p>
     */
    record MemberProfileResult(MemberResolutionStatus status, String mobile, String userName) {

        public MemberProfileResult {
            Objects.requireNonNull(status, "status");
            if (status == MemberResolutionStatus.FOUND && (mobile == null || mobile.isBlank())) {
                throw new IllegalArgumentException("mobile must not be blank when member profile is found");
            }
        }

        /**
         * 会员手机号和姓名属于个人信息，诊断字符串只允许输出解析状态。
         *
         * <p>设计见
         * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#12-安全与日志}。</p>
         *
         * @return 不含手机号和姓名的安全结果摘要
         */
        @Override
        public String toString() {
            return "MemberProfileResult[status=" + status + ']';
        }
    }

    /** 会员资料解析的封闭结果状态。 */
    enum MemberResolutionStatus {
        FOUND,
        NOT_FOUND,
        UNAVAILABLE
    }
}
