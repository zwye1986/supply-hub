package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelUserBindingDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelUserBindingMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreChannelCode;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CHANNEL_USER_BINDING_MISSING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT;

/**
 * 到店购稳定会员绑定解析器。
 *
 * <p>绑定唯一键为 {@code (projectId, buyerUserId, channelCode)}，两个采购项目即使共享
 * 同一 SaaS 租户也不得合并绑定；{@code sourceSystem/externalTenantId} 只作为最近一次
 * 签发观察到的路由快照保存。{@code buyerUserId} 是不透明字符串，禁止直接转为 Long。
 * 短期入口凭证由独立凭证服务管理。详见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#6-稳定绑定}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstoreChannelUserBindingResolver {

    private final SupplyChainDatabaseClock databaseClock;

    /** 绑定状态：10 启用，20 停用。 */
    private static final short STATUS_ACTIVE = 10;
    private final InstoreChannelUserBindingMapper bindingMapper;
    private final BusinessNoGenerator businessNoGenerator;

    /**
     * 按项目会员三元组读取或创建稳定绑定，并刷新最近路由快照。
     *
     * <p>已有绑定必须仍为 ACTIVE 且采购商一致；当前会员路由与快照不同时以版本 CAS
     * 更新 {@code sourceSystem/externalTenantId}，影响行数不是 1 抛并发失败。
     * 并发首创建依赖 017 项目会员唯一键，冲突后仅按同一项目会员键回读。</p>
     */
    public BindingSnapshot resolveOrCreateStableBinding(Long buyerId, Long projectId,
                                                        String buyerUserId, String channelCode,
                                                        BuyerIntegrationRoute memberRoute) {
        log.info("[INSTORE][binding] resolveOrCreateStableBinding 入口 buyerId={}, projectId={}, channelCode={}, routeCapability={}",
                buyerId, projectId, channelCode, memberRoute.capability());
        requireMemberRoute(memberRoute, projectId);
        InstoreChannelUserBindingDO existing = bindingMapper.selectByProjectUser(
                projectId, buyerUserId, channelCode);
        if (existing != null) {
            validateExisting(existing, buyerId);
            return refreshRouteSnapshotIfNeeded(existing, memberRoute);
        }
        return createBinding(buyerId, projectId, buyerUserId, channelCode, memberRoute);
    }

    /** 按主键读取启用的稳定绑定，供独立凭证服务在消费后回查完整作用域。 */
    public BindingSnapshot resolveActiveById(Long bindingId) {
        log.info("[INSTORE][binding] resolveActiveById 入口 bindingId={}", bindingId);
        InstoreChannelUserBindingDO binding = bindingMapper.selectById(bindingId);
        if (binding == null || binding.getStatus() == null || binding.getStatus() != STATUS_ACTIVE) {
            log.warn("[INSTORE][binding] 启用绑定不存在 bindingId={}", bindingId);
            throw exception(INSTORE_CHANNEL_USER_BINDING_MISSING);
        }
        return snapshot(binding);
    }

    /**
     * 按项目会员三元组只读解析启用绑定，供 Wire 层建单编排获得采购商归属。
     *
     * <p>只读不创建、不刷新路由快照；绑定缺失或停用按渠道语义抛绑定缺失。
     * 详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#5-facade-wire-编排方案-a}。</p>
     *
     * @param projectId 采购项目内部主键
     * @param buyerUserId 旅通会员编号user_code，不透明字符串
     * @param channelCode 到店购渠道编码
     * @return 启用绑定快照
     */
    public BindingSnapshot requireActiveBinding(Long projectId, String buyerUserId, String channelCode) {
        log.info("[INSTORE][binding] requireActiveBinding 入口 projectId={}, channelCode={}",
                projectId, channelCode);
        InstoreChannelUserBindingDO binding = bindingMapper.selectByProjectUser(
                projectId, buyerUserId, channelCode);
        if (binding == null || binding.getStatus() == null || binding.getStatus() != STATUS_ACTIVE) {
            log.warn("[INSTORE][binding] 启用绑定不存在 projectId={}, channelCode={}", projectId, channelCode);
            throw exception(INSTORE_CHANNEL_USER_BINDING_MISSING);
        }
        return snapshot(binding);
    }

    private static void requireMemberRoute(BuyerIntegrationRoute memberRoute, Long projectId) {
        if (memberRoute.capability() != BuyerIntegrationCapabilityCode.MEMBER_PROFILE) {
            throw new IllegalArgumentException(
                    "Binding requires MEMBER_PROFILE route, but got " + memberRoute.capability());
        }
        if (!memberRoute.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Route projectId " + memberRoute.projectId() + " does not match binding projectId " + projectId);
        }
    }

    /** 路由快照不同则以版本 CAS 刷新；快照表示最近一次签发观察到的路由，不是唯一键。 */
    private BindingSnapshot refreshRouteSnapshotIfNeeded(InstoreChannelUserBindingDO existing,
                                                         BuyerIntegrationRoute memberRoute) {
        if (Objects.equals(existing.getSourceSystem(), memberRoute.sourceSystem())
                && Objects.equals(existing.getExternalTenantId(), memberRoute.externalTenantId())) {
            log.info("[INSTORE][binding] 复用已有绑定 bindingId={}", existing.getId());
            return snapshot(existing);
        }
        LocalDateTime now = databaseClock.now();
        int changed = bindingMapper.updateMemberRouteSnapshot(
                existing.getId(), existing.getVersion(),
                memberRoute.sourceSystem(), memberRoute.externalTenantId(), now);
        if (changed != 1) {
            log.warn("[INSTORE][binding] 路由快照 CAS 失败 bindingId={}, expectedVersion={}",
                    existing.getId(), existing.getVersion());
            throw exception(INSTORE_CONCURRENT_UPDATE);
        }
        log.info("[INSTORE][binding] 路由快照已刷新 bindingId={}, externalTenantId={}",
                existing.getId(), memberRoute.externalTenantId());
        return new BindingSnapshot(existing.getId(), existing.getBuyerId(), existing.getProjectId(),
                memberRoute.sourceSystem(), memberRoute.externalTenantId(),
                existing.getBuyerUserId(), existing.getChannelCode(), existing.getVersion() + 1);
    }

    private BindingSnapshot createBinding(Long buyerId, Long projectId, String buyerUserId,
                                          String channelCode, BuyerIntegrationRoute memberRoute) {
        requireSupportedChannel(channelCode);
        LocalDateTime now = databaseClock.now();
        InstoreChannelUserBindingDO binding = InstoreChannelUserBindingDO.builder()
                .bindingNo(businessNoGenerator.next(BusinessNoPrefixes.IUB))
                .buyerId(buyerId)
                .projectId(projectId)
                .sourceSystem(memberRoute.sourceSystem())
                .externalTenantId(memberRoute.externalTenantId())
                .buyerUserId(buyerUserId)
                .channelCode(channelCode)
                .channelUserId(null)
                .token(null)
                .tokenStatus(null)
                .tokenExpiresAt(null)
                .status(STATUS_ACTIVE)
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            bindingMapper.insert(binding);
            log.info("[INSTORE][binding] 新建绑定 bindingId={}, buyerId={}, projectId={}",
                    binding.getId(), buyerId, projectId);
        } catch (DuplicateKeyException duplicate) {
            // 并发首创建：只允许按同一项目会员三元组回读，避免跨项目身份串用。
            InstoreChannelUserBindingDO reread = bindingMapper.selectByProjectUser(
                    projectId, buyerUserId, channelCode);
            if (reread == null) {
                throw duplicate;
            }
            validateExisting(reread, buyerId);
            return refreshRouteSnapshotIfNeeded(reread, memberRoute);
        }
        return snapshot(binding);
    }

    private static void validateExisting(InstoreChannelUserBindingDO binding, Long expectedBuyerId) {
        if (!expectedBuyerId.equals(binding.getBuyerId())) {
            log.warn("[INSTORE][binding] 绑定采购商与租户映射不一致 bindingId={}, bindingBuyerId={}, expectedBuyerId={}",
                    binding.getId(), binding.getBuyerId(), expectedBuyerId);
            throw exception(INSTORE_TENANT_SCOPE_CONFLICT);
        }
        if (binding.getStatus() == null || binding.getStatus() != STATUS_ACTIVE) {
            log.warn("[INSTORE][binding] 绑定已停用 bindingId={}", binding.getId());
            throw exception(INSTORE_CHANNEL_USER_BINDING_MISSING);
        }
    }

    private static BindingSnapshot snapshot(InstoreChannelUserBindingDO binding) {
        return new BindingSnapshot(binding.getId(), binding.getBuyerId(), binding.getProjectId(),
                binding.getSourceSystem(), binding.getExternalTenantId(),
                binding.getBuyerUserId(), binding.getChannelCode(), binding.getVersion());
    }

    private static void requireSupportedChannel(String channelCode) {
        try {
            InstoreChannelCode.valueOf(channelCode);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unsupported instore channel code: " + channelCode);
        }
    }

    /** 绑定快照：受理时固化的采购商、采购项目与最近 SaaS 租户路由。 */
    public record BindingSnapshot(Long bindingId, Long buyerId, Long projectId,
                                  String sourceSystem, String externalTenantId,
                                  String buyerUserId, String channelCode, long version) {
    }
}
