package com.aifuli.module.supplychain.application.facade;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.instore.dto.InstoreMemberRespDTO;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService.ClaimedCredential;
import com.aifuli.module.supplychain.border.instore.domain.InstoreCredentialRevokeReason;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationLogSanitizer;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationProfileRetiredException;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRouteResolver;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort.MemberProfileResult;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort.MemberResolutionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_REVOKED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_MEMBER_PROFILE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_MEMBER_PROFILE_UNAVAILABLE;

/**
 * 旅通接口①会员资料解析门面：事务外编排凭证认领、冻结路由解析、SaaS 会员外呼
 * 与凭证消费/释放/撤销。
 *
 * <p>本类自身禁止开启数据库事务：{@code claimForMemberLookup}、{@code markLookupSucceeded}、
 * {@code releaseAfterLookupFailure}、{@code revoke} 各自是独立本地事务，会员外呼位于
 * 事务之间。状态矩阵：FOUND 且手机号非空 → markUsed 成功后才组装资料；NOT_FOUND 或
 * 手机号为空 → 释放并返回 044；UNAVAILABLE/null/外呼异常 → 释放并返回 045（保留
 * cause）；冻结档案退役 → 撤销并返回 043；任意凭证 CAS 失败 → 041 且不返回会员资料。
 * 日志禁止出现手机号、姓名、token、userCode 与完整外部正文；第三方调用只记录路由
 * 摘要、结果分类和耗时。详见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#93-旅通接口会员解析}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstoreChannelMemberFacade {

    private final InstoreEntryCredentialService credentialService;
    private final BuyerIntegrationRouteResolver routeResolver;
    private final BuyerMemberProfilePort memberProfilePort;

    /**
     * 按短期凭证解析采购商会员资料。
     *
     * <p>固定顺序：claim（凭证转 PROCESSING）→ resolveFrozen（认领时冻结的
     * MEMBER_PROFILE 版本）→ 会员外呼 → markUsed/release/revoke。外呼身份三元组
     * 只来自冻结路由与凭证固化的 buyerUserId，调用方无法注入。</p>
     */
    public InstoreMemberRespDTO resolveMember(ResolveMemberCommand command) {
        Objects.requireNonNull(command, "command");
        long startMs = System.currentTimeMillis();
        log.info("[INSTORE][member] resolveMember 入口 projectId={}, channelCode={}",
                command.projectId(), command.channelCode());

        ClaimedCredential claimed = credentialService.claimForMemberLookup(
                command.projectId(), command.channelCode(), command.token());

        BuyerIntegrationRoute route = resolveFrozenRoute(claimed);
        MemberProfileResult result = callMemberPort(claimed, route, startMs);

        MemberResolutionStatus status =
                result == null ? MemberResolutionStatus.UNAVAILABLE : result.status();
        if (status == MemberResolutionStatus.FOUND && hasText(result.mobile())) {
            credentialService.markLookupSucceeded(claimed.credentialId(), claimed.processingVersion());
            log.info("[INSTORE][member] resolveMember 完成 projectId={}, channelCode={}, credentialId={}, "
                            + "purpose={}, status=FOUND, cost={}ms",
                    command.projectId(), command.channelCode(), claimed.credentialId(),
                    claimed.purpose(), System.currentTimeMillis() - startMs);
            return toResponse(claimed, result);
        }

        credentialService.releaseAfterLookupFailure(claimed.credentialId(), claimed.processingVersion());
        log.warn("[INSTORE][member] 会员解析未命中，已释放凭证 projectId={}, channelCode={}, credentialId={}, "
                        + "status={}, cost={}ms",
                command.projectId(), command.channelCode(), claimed.credentialId(), status,
                System.currentTimeMillis() - startMs);
        if (status == MemberResolutionStatus.NOT_FOUND || status == MemberResolutionStatus.FOUND) {
            throw exception(INSTORE_MEMBER_PROFILE_INVALID);
        }
        throw exception(INSTORE_MEMBER_PROFILE_UNAVAILABLE);
    }

    /**
     * 解析认领时冻结的 MEMBER_PROFILE 路由。
     *
     * <p>版本退役是终态：撤销凭证并返回 043，不再读取该版本配置，也不调用 Port；
     * 其他解析失败按暂时不可用处理：释放凭证并返回 045，保留原始异常。</p>
     */
    private BuyerIntegrationRoute resolveFrozenRoute(ClaimedCredential claimed) {
        try {
            return routeResolver.resolveFrozen(claimed.projectId(), claimed.memberProfileVersionId(),
                    BuyerIntegrationCapabilityCode.MEMBER_PROFILE);
        } catch (BuyerIntegrationProfileRetiredException retired) {
            log.warn("[INSTORE][member] 冻结会员档案已退役，撤销凭证 projectId={}, profileVersionId={}, "
                            + "credentialId={}",
                    claimed.projectId(), claimed.memberProfileVersionId(), claimed.credentialId());
            credentialService.revoke(claimed.credentialId(), claimed.processingVersion(),
                    InstoreCredentialRevokeReason.MEMBER_PROFILE_RETIRED);
            throw exception(INSTORE_CREDENTIAL_REVOKED);
        } catch (RuntimeException routeFailure) {
            log.warn("[INSTORE][member] 冻结路由解析失败，释放凭证 projectId={}, profileVersionId={}, "
                            + "credentialId={}, errorClass={}",
                    claimed.projectId(), claimed.memberProfileVersionId(), claimed.credentialId(),
                    routeFailure.getClass().getName());
            credentialService.releaseAfterLookupFailure(claimed.credentialId(), claimed.processingVersion());
            ServiceException unavailable = exception(INSTORE_MEMBER_PROFILE_UNAVAILABLE);
            unavailable.initCause(routeFailure);
            throw unavailable;
        }
    }

    /**
     * 执行 SaaS 会员外呼；异常与 null 结果统一按暂时不可用处理：释放凭证、
     * 返回 045 并通过 {@code initCause} 保留原始异常。请求日志只输出外部租户的
     * 不可逆短摘要，禁止完整 externalTenantId。
     * 设计见 {@code docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#4-日志与异常边界}。
     */
    private MemberProfileResult callMemberPort(ClaimedCredential claimed, BuyerIntegrationRoute route,
                                               long startMs) {
        try {
            log.info("[INSTORE][member] resolve 请求 projectId={}, profileVersionId={}, sourceSystem={}, "
                            + "externalTenantDigest={}",
                    route.projectId(), route.profileVersionId(), route.sourceSystem(),
                    BuyerIntegrationLogSanitizer.externalTenantDigest(route.externalTenantId()));
            MemberProfileResult result = memberProfilePort.resolve(
                    new BuyerMemberProfilePort.ResolveMemberCommand(route, claimed.buyerUserId()));
            log.info("[INSTORE][member] resolve 响应 projectId={}, profileVersionId={}, status={}, cost={}ms",
                    route.projectId(), route.profileVersionId(),
                    result == null ? "UNAVAILABLE" : result.status(),
                    System.currentTimeMillis() - startMs);
            return result;
        } catch (RuntimeException portFailure) {
            log.warn("[INSTORE][member] resolve 异常 projectId={}, profileVersionId={}, errorClass={}, cost={}ms",
                    route.projectId(), route.profileVersionId(), portFailure.getClass().getName(),
                    System.currentTimeMillis() - startMs);
            credentialService.releaseAfterLookupFailure(claimed.credentialId(), claimed.processingVersion());
            ServiceException unavailable = exception(INSTORE_MEMBER_PROFILE_UNAVAILABLE);
            unavailable.initCause(portFailure);
            throw unavailable;
        }
    }

    private static InstoreMemberRespDTO toResponse(ClaimedCredential claimed, MemberProfileResult result) {
        InstoreMemberRespDTO response = new InstoreMemberRespDTO();
        response.setUserCode(claimed.buyerUserId());
        response.setMobile(result.mobile());
        response.setUserName(result.userName());
        response.setCompanyCode(claimed.companyCode());
        return response;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Facade 内部命令。projectId/channelCode 来自可信渠道上下文，token 为渠道回传的
     * 原始短期凭证；禁止扩展为携带路由或会员字段。
     */
    public record ResolveMemberCommand(Long projectId, String channelCode, String token) {
    }
}
