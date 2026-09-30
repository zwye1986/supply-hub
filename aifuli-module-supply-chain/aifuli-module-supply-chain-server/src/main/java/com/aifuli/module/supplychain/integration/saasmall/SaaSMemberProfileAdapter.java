package com.aifuli.module.supplychain.integration.saasmall;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.tenant.core.context.TenantContextHolder;
import com.aifuli.module.member.api.profile.MemberProfileResolveApi;
import com.aifuli.module.member.api.profile.dto.MemberProfileResolveReqDTO;
import com.aifuli.module.member.api.profile.dto.MemberProfileResolveRespDTO;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationLogSanitizer;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * SaaS 商城会员资料适配器：按冻结路由调用 member-server 专用三元解析 RPC。
 *
 * <p>本适配器只校验 {@code AIFULI_MALL/RPC/SAAS_MEMBER_RPC/v1} 路由、原样透传字符串三元组并在
 * 租户上下文中执行 Feign 调用；绝不把 buyerUserId 转成数值，兼容解释只发生在 member-server。
 * 路由或租户头不合法、RPC 异常、错误信封统一返回 {@code UNAVAILABLE}；会员不存在或手机号为空
 * 返回 {@code NOT_FOUND}。日志只含路由摘要、状态、错误码与耗时。设计见
 * {@code docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#33-supply-chain-server}。</p>
 */
@Slf4j
@Component
public class SaaSMemberProfileAdapter implements BuyerMemberProfilePort {

    private final MemberProfileResolveApi memberProfileResolveApi;

    @Autowired
    public SaaSMemberProfileAdapter(MemberProfileResolveApi memberProfileResolveApi) {
        this.memberProfileResolveApi = memberProfileResolveApi;
    }

    /**
     * 按可信 MEMBER_PROFILE 路由解析会员资料；三元身份全程以字符串透传。
     */
    @Override
    public MemberProfileResult resolve(ResolveMemberCommand command) {
        BuyerIntegrationRoute route = command.route();
        long startedAt = System.nanoTime();
        String tenantDigest = BuyerIntegrationLogSanitizer.externalTenantDigest(route.externalTenantId());
        log.info("[SAAS-MEMBER][resolve] request projectId={}, profileVersionId={}, sourceSystem={}, "
                        + "externalTenantDigest={}, adapterCode={}",
                route.projectId(), route.profileVersionId(),
                SaaSMemberProfileRoutePolicy.safeSourceSystem(route.sourceSystem()), tenantDigest,
                SaaSMemberProfileRoutePolicy.safeAdapterCode(route.adapterCode()));

        if (!SaaSMemberProfileRoutePolicy.supports(route)) {
            logCompletion(route, tenantDigest, "UNAVAILABLE", null, startedAt);
            return unavailable();
        }
        Long tenantId = SaaSMemberProfileRoutePolicy.frameworkTenantId(route.externalTenantId());
        if (tenantId == null) {
            logCompletion(route, tenantDigest, "UNAVAILABLE", null, startedAt);
            return unavailable();
        }

        MemberProfileResolveReqDTO request = new MemberProfileResolveReqDTO();
        request.setSourceSystem(route.sourceSystem());
        request.setExternalTenantId(route.externalTenantId());
        request.setBuyerUserId(command.buyerUserId());

        CommonResult<MemberProfileResolveRespDTO> envelope;
        Long oldTenantId = TenantContextHolder.getTenantId();
        Boolean oldIgnore = TenantContextHolder.isIgnore();
        try {
            TenantContextHolder.setTenantId(tenantId);
            TenantContextHolder.setIgnore(false);
            envelope = memberProfileResolveApi.resolve(request);
        } catch (RuntimeException failure) {
            logFailure(route, tenantDigest, failure, startedAt);
            return unavailable();
        } finally {
            TenantContextHolder.setTenantId(oldTenantId);
            TenantContextHolder.setIgnore(oldIgnore);
        }
        if (envelope == null || envelope.isError()) {
            logCompletion(route, tenantDigest, "UNAVAILABLE",
                    envelope == null ? null : envelope.getCode(), startedAt);
            return unavailable();
        }
        MemberProfileResolveRespDTO member = envelope.getData();
        if (member == null || member.getMobile() == null || member.getMobile().isBlank()) {
            logCompletion(route, tenantDigest, "NOT_FOUND", envelope.getCode(), startedAt);
            return new MemberProfileResult(MemberResolutionStatus.NOT_FOUND, null, null);
        }
        logCompletion(route, tenantDigest, "FOUND", envelope.getCode(), startedAt);
        return new MemberProfileResult(MemberResolutionStatus.FOUND, member.getMobile(), member.getUserName());
    }

    private static MemberProfileResult unavailable() {
        return new MemberProfileResult(MemberResolutionStatus.UNAVAILABLE, null, null);
    }

    private void logCompletion(BuyerIntegrationRoute route, String tenantDigest, String status,
                               Integer remoteCode, long startedAt) {
        log.info("[SAAS-MEMBER][resolve] response projectId={}, profileVersionId={}, sourceSystem={}, "
                        + "externalTenantDigest={}, adapterCode={}, status={}, remoteCode={}, costMs={}",
                route.projectId(), route.profileVersionId(),
                SaaSMemberProfileRoutePolicy.safeSourceSystem(route.sourceSystem()), tenantDigest,
                SaaSMemberProfileRoutePolicy.safeAdapterCode(route.adapterCode()), status, remoteCode,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private void logFailure(BuyerIntegrationRoute route, String tenantDigest,
                            RuntimeException failure, long startedAt) {
        log.warn("[SAAS-MEMBER][resolve] failed projectId={}, profileVersionId={}, sourceSystem={}, "
                        + "externalTenantDigest={}, adapterCode={}, status=UNAVAILABLE, errorClass={}, "
                        + "rootCauseClass={}, costMs={}",
                route.projectId(), route.profileVersionId(),
                SaaSMemberProfileRoutePolicy.safeSourceSystem(route.sourceSystem()), tenantDigest,
                SaaSMemberProfileRoutePolicy.safeAdapterCode(route.adapterCode()),
                failure.getClass().getName(), rootCauseClass(failure),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private static String rootCauseClass(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current.getCause() != null && visited.add(current)) {
            current = current.getCause();
        }
        return current.getClass().getName();
    }
}
