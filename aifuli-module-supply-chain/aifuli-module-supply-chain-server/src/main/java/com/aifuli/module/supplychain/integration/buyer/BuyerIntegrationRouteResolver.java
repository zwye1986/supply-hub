package com.aifuli.module.supplychain.integration.buyer;

import com.aifuli.module.supplychain.integration.buyer.dal.dataobject.BuyerIntegrationCapabilityDO;
import com.aifuli.module.supplychain.integration.buyer.dal.dataobject.BuyerIntegrationProfileDO;
import com.aifuli.module.supplychain.integration.buyer.dal.dataobject.BuyerIntegrationProfileVersionDO;
import com.aifuli.module.supplychain.integration.buyer.dal.mysql.BuyerIntegrationCapabilityMapper;
import com.aifuli.module.supplychain.integration.buyer.dal.mysql.BuyerIntegrationProfileMapper;
import com.aifuli.module.supplychain.integration.buyer.dal.mysql.BuyerIntegrationProfileVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 按明确的“当前配置”或“业务冻结版本”语义解析可信采购项目集成路由。
 *
 * <p>新业务只能使用 ACTIVE 档案的 PUBLISHED 当前版本；已创建业务允许继续使用
 * PUBLISHED/SUPERSEDED 冻结版本，显式 RETIRED 则安全撤销。两条入口共用版本归属、
 * 状态和能力校验，调用方不能伪造 {@code sourceSystem/externalTenantId}。
 * 设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#5-项目集成档案解析}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerIntegrationRouteResolver {

    private final BuyerIntegrationProfileMapper profileMapper;
    private final BuyerIntegrationProfileVersionMapper versionMapper;
    private final BuyerIntegrationCapabilityMapper capabilityMapper;

    /**
     * 解析项目当前生效路由，仅供签发新业务凭证或创建新的业务操作使用。
     *
     * <p>档案必须 ACTIVE，且 {@code currentVersionId} 指向本档案的 PUBLISHED 版本。
     * 设计见
     * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#5-项目集成档案解析}。</p>
     *
     * @param projectId 采购项目内部主键
     * @param capability 本次调用所需能力
     * @return 当前已发布版本解析出的可信路由
     */
    public BuyerIntegrationRoute resolveCurrent(
            Long projectId, BuyerIntegrationCapabilityCode capability) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(capability, "capability");
        log.info("[BUYER_INTEGRATION][route] resolveCurrent 入口 projectId={}, capability={}",
                projectId, capability);

        BuyerIntegrationProfileDO profile = profileMapper.selectByProjectId(projectId);
        requireActiveProfile(projectId, profile);
        if (profile.getCurrentVersionId() == null) {
            throw new IllegalStateException("Buyer integration profile current version not found");
        }
        BuyerIntegrationProfileVersionDO profileVersion = versionMapper.selectById(profile.getCurrentVersionId());
        return resolveVersion(projectId, profile, profileVersion, capability, VersionRoutePolicy.CURRENT);
    }

    /**
     * 解析业务操作已经冻结的路由版本，发布切换后仍保持在途业务使用原版本。
     *
     * <p>PUBLISHED 与 SUPERSEDED 可以继续消费；RETIRED 表示显式安全撤销，必须在读取
     * 能力和凭据配置前抛出 {@link BuyerIntegrationProfileRetiredException}。
     * 设计见
     * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#5-项目集成档案解析}。</p>
     *
     * @param projectId 采购项目内部主键，业务归属唯一依据
     * @param profileVersionId 业务操作冻结的集成档案版本内部主键
     * @param capability 本次调用所需能力
     * @return 冻结版本解析出的可信路由
     */
    public BuyerIntegrationRoute resolveFrozen(
            Long projectId, Long profileVersionId, BuyerIntegrationCapabilityCode capability) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(profileVersionId, "profileVersionId");
        Objects.requireNonNull(capability, "capability");
        log.info("[BUYER_INTEGRATION][route] resolveFrozen 入口 projectId={}, "
                        + "profileVersionId={}, capability={}",
                projectId, profileVersionId, capability);

        BuyerIntegrationProfileVersionDO profileVersion = versionMapper.selectById(profileVersionId);
        if (profileVersion == null) {
            throw new IllegalStateException("Buyer integration profile version not found");
        }
        BuyerIntegrationProfileDO profile = profileMapper.selectById(profileVersion.getProfileId());
        return resolveVersion(projectId, profile, profileVersion, capability, VersionRoutePolicy.FROZEN);
    }

    /**
     * 统一执行档案启用状态、项目与版本归属、版本策略和能力配置校验。
     *
     * <p>此方法是不变量的唯一落点：只有通过完整校验的能力配置才能生成路由，且 RETIRED
     * 必须先于能力读取失败，避免已安全撤销版本继续接触配置。
     * 设计见
     * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#5-项目集成档案解析}。</p>
     */
    private BuyerIntegrationRoute resolveVersion(
            Long projectId,
            BuyerIntegrationProfileDO profile,
            BuyerIntegrationProfileVersionDO profileVersion,
            BuyerIntegrationCapabilityCode capability,
            VersionRoutePolicy routePolicy) {
        requireActiveProfile(projectId, profile);
        if (profileVersion == null) {
            throw new IllegalStateException("Buyer integration profile version not found");
        }
        if (!Objects.equals(profile.getId(), profileVersion.getProfileId())) {
            throw new IllegalStateException("Buyer integration profile version profile ownership mismatch");
        }
        requireAllowedVersionState(profileVersion, routePolicy);

        BuyerIntegrationCapabilityDO capabilityConfig =
                capabilityMapper.selectByProfileVersionAndCode(profileVersion.getId(), capability);
        if (capabilityConfig == null) {
            throw new IllegalStateException("Buyer integration capability not found");
        }
        if (!BuyerIntegrationCapabilityStatus.ENABLED.matches(capabilityConfig.getStatus())) {
            throw new IllegalStateException("Buyer integration capability is not enabled");
        }

        return new BuyerIntegrationRoute(projectId, profileVersion.getId(), capability,
                capabilityConfig.getSourceSystem(), capabilityConfig.getExternalTenantId(),
                capabilityConfig.getTransportMode(), capabilityConfig.getAdapterCode(),
                capabilityConfig.getContractVersion(), capabilityConfig.getCredentialSetId());
    }

    /**
     * 校验版本所属档案存在、处于 ACTIVE 且归属于目标采购项目。
     *
     * <p>设计见
     * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#5-项目集成档案解析}。</p>
     */
    private static void requireActiveProfile(Long projectId, BuyerIntegrationProfileDO profile) {
        if (profile == null) {
            throw new IllegalStateException("Buyer integration profile not found");
        }
        if (!BuyerIntegrationProfileStatus.ACTIVE.matches(profile.getStatus())) {
            throw new IllegalStateException("Buyer integration profile is not active");
        }
        if (!projectId.equals(profile.getProjectId())) {
            throw new IllegalStateException("Buyer integration profile project ownership mismatch");
        }
    }

    /**
     * 应用 current/frozen 的封闭版本状态策略，并优先识别显式安全退役。
     *
     * <p>current 仅接受 PUBLISHED；frozen 接受 PUBLISHED/SUPERSEDED；RETIRED 始终抛专用异常。
     * 设计见
     * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#5-项目集成档案解析}。</p>
     */
    private static void requireAllowedVersionState(
            BuyerIntegrationProfileVersionDO profileVersion, VersionRoutePolicy routePolicy) {
        if (BuyerIntegrationProfileVersionStatus.RETIRED.matches(profileVersion.getStatus())) {
            throw new BuyerIntegrationProfileRetiredException(profileVersion.getId());
        }
        if (routePolicy == VersionRoutePolicy.CURRENT) {
            if (!BuyerIntegrationProfileVersionStatus.PUBLISHED.matches(profileVersion.getStatus())) {
                throw new IllegalStateException("Buyer integration profile version is not published");
            }
            return;
        }
        if (!BuyerIntegrationProfileVersionStatus.PUBLISHED.matches(profileVersion.getStatus())
                && !BuyerIntegrationProfileVersionStatus.SUPERSEDED.matches(profileVersion.getStatus())) {
            throw new IllegalStateException("Buyer integration profile version is not available for frozen route");
        }
    }

    /**
     * 路由解析的两种固定版本策略，禁止用布尔值在调用处表达业务语义。
     * 设计见
     * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#5-项目集成档案解析}。
     */
    private enum VersionRoutePolicy {
        CURRENT,
        FROZEN
    }
}
