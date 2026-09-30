package com.aifuli.module.supplychain.integration.buyer;

import com.aifuli.module.supplychain.integration.buyer.dal.dataobject.BuyerIntegrationCapabilityDO;
import com.aifuli.module.supplychain.integration.buyer.dal.dataobject.BuyerIntegrationProfileDO;
import com.aifuli.module.supplychain.integration.buyer.dal.dataobject.BuyerIntegrationProfileVersionDO;
import com.aifuli.module.supplychain.integration.buyer.dal.mysql.BuyerIntegrationCapabilityMapper;
import com.aifuli.module.supplychain.integration.buyer.dal.mysql.BuyerIntegrationProfileMapper;
import com.aifuli.module.supplychain.integration.buyer.dal.mysql.BuyerIntegrationProfileVersionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode.CASHIER;
import static com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode.MEMBER_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 项目版本化集成路由解析测试，固定当前版本与业务冻结版本两套不同的状态策略。
 *
 * <p>当前路由只能读取项目 ACTIVE 档案的 PUBLISHED 当前版本；冻结路由允许继续读取
 * PUBLISHED/SUPERSEDED，但 RETIRED 必须在访问能力配置前以专用异常失败。
 * 设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#5-项目集成档案解析}。</p>
 */
@ExtendWith(MockitoExtension.class)
class BuyerIntegrationRouteResolverTest {

    private static final long PROJECT_ID = 101L;
    private static final long OTHER_PROJECT_ID = 202L;
    private static final long PROFILE_ID = 800L;
    private static final long OTHER_PROFILE_ID = 801L;
    private static final long VERSION_ID = 900L;
    private static final String SOURCE_SYSTEM = "AIFULI_MALL";
    private static final String EXTERNAL_TENANT_ID = "tenant-a";
    private static final String TRANSPORT_MODE = "RPC";
    private static final String CONTRACT_VERSION = "v1";

    @Mock
    private BuyerIntegrationProfileMapper profileMapper;
    @Mock
    private BuyerIntegrationProfileVersionMapper versionMapper;
    @Mock
    private BuyerIntegrationCapabilityMapper capabilityMapper;
    @InjectMocks
    private BuyerIntegrationRouteResolver resolver;

    @Test
    void currentResolvesPublishedVersionFromActiveProjectProfile() {
        when(profileMapper.selectByProjectId(PROJECT_ID))
                .thenReturn(activeProfile(PROFILE_ID, PROJECT_ID, VERSION_ID));
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(VERSION_ID, PROFILE_ID, BuyerIntegrationProfileVersionStatus.PUBLISHED));
        when(capabilityMapper.selectByProfileVersionAndCode(VERSION_ID, MEMBER_PROFILE))
                .thenReturn(enabledCapability(MEMBER_PROFILE, SOURCE_SYSTEM, EXTERNAL_TENANT_ID));

        BuyerIntegrationRoute route = resolver.resolveCurrent(PROJECT_ID, MEMBER_PROFILE);

        assertRoute(route, VERSION_ID, MEMBER_PROFILE);
    }

    @Test
    void currentRejectsMissingProjectProfileBeforeReadingVersionOrCapability() {
        when(profileMapper.selectByProjectId(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveCurrent(PROJECT_ID, MEMBER_PROFILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile not found");
        verifyNoInteractions(versionMapper, capabilityMapper);
    }

    @Test
    void currentRejectsInactiveProjectProfileBeforeReadingVersionOrCapability() {
        when(profileMapper.selectByProjectId(PROJECT_ID)).thenReturn(BuyerIntegrationProfileDO.builder()
                .id(PROFILE_ID)
                .projectId(PROJECT_ID)
                .currentVersionId(VERSION_ID)
                .status(BuyerIntegrationProfileStatus.SUSPENDED.getCode())
                .build());

        assertThatThrownBy(() -> resolver.resolveCurrent(PROJECT_ID, MEMBER_PROFILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile is not active");
        verifyNoInteractions(versionMapper, capabilityMapper);
    }

    @ParameterizedTest
    @EnumSource(value = BuyerIntegrationProfileVersionStatus.class,
            names = {"DRAFT", "VALIDATED", "SUPERSEDED"})
    void currentRejectsNonPublishedCurrentVersion(BuyerIntegrationProfileVersionStatus status) {
        when(profileMapper.selectByProjectId(PROJECT_ID))
                .thenReturn(activeProfile(PROFILE_ID, PROJECT_ID, VERSION_ID));
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(VERSION_ID, PROFILE_ID, status));

        assertThatThrownBy(() -> resolver.resolveCurrent(PROJECT_ID, MEMBER_PROFILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not published");
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void currentRetiredVersionThrowsDedicatedExceptionBeforeReadingCapability() {
        when(profileMapper.selectByProjectId(PROJECT_ID))
                .thenReturn(activeProfile(PROFILE_ID, PROJECT_ID, VERSION_ID));
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(VERSION_ID, PROFILE_ID, BuyerIntegrationProfileVersionStatus.RETIRED));

        assertThatThrownBy(() -> resolver.resolveCurrent(PROJECT_ID, MEMBER_PROFILE))
                .isInstanceOf(BuyerIntegrationProfileRetiredException.class)
                .hasMessageContaining("retired");
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void currentRejectsMissingCurrentVersionBeforeReadingCapability() {
        when(profileMapper.selectByProjectId(PROJECT_ID))
                .thenReturn(activeProfile(PROFILE_ID, PROJECT_ID, VERSION_ID));
        when(versionMapper.selectById(VERSION_ID)).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveCurrent(PROJECT_ID, MEMBER_PROFILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version not found");
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void currentRejectsVersionOwnedByAnotherProfile() {
        when(profileMapper.selectByProjectId(PROJECT_ID))
                .thenReturn(activeProfile(PROFILE_ID, PROJECT_ID, VERSION_ID));
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(VERSION_ID, OTHER_PROFILE_ID, BuyerIntegrationProfileVersionStatus.PUBLISHED));

        assertThatThrownBy(() -> resolver.resolveCurrent(PROJECT_ID, MEMBER_PROFILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile ownership mismatch");
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void frozenResolvesPublishedVersion() {
        stubFrozenVersion(BuyerIntegrationProfileVersionStatus.PUBLISHED);
        when(capabilityMapper.selectByProfileVersionAndCode(VERSION_ID, CASHIER))
                .thenReturn(enabledCapability(CASHIER, SOURCE_SYSTEM, EXTERNAL_TENANT_ID));

        BuyerIntegrationRoute route = resolver.resolveFrozen(PROJECT_ID, VERSION_ID, CASHIER);

        assertRoute(route, VERSION_ID, CASHIER);
    }

    @Test
    void frozenResolvesSupersededVersionForInFlightOperation() {
        stubFrozenVersion(BuyerIntegrationProfileVersionStatus.SUPERSEDED);
        when(capabilityMapper.selectByProfileVersionAndCode(VERSION_ID, MEMBER_PROFILE))
                .thenReturn(enabledCapability(MEMBER_PROFILE, SOURCE_SYSTEM, EXTERNAL_TENANT_ID));

        BuyerIntegrationRoute route = resolver.resolveFrozen(PROJECT_ID, VERSION_ID, MEMBER_PROFILE);

        assertRoute(route, VERSION_ID, MEMBER_PROFILE);
    }

    @Test
    void frozenRetiredVersionThrowsDedicatedExceptionBeforeReadingCapability() {
        stubFrozenVersion(BuyerIntegrationProfileVersionStatus.RETIRED);

        assertThatThrownBy(() -> resolver.resolveFrozen(PROJECT_ID, VERSION_ID, MEMBER_PROFILE))
                .isInstanceOf(BuyerIntegrationProfileRetiredException.class)
                .hasMessageContaining("retired");
        verifyNoInteractions(capabilityMapper);
    }

    @ParameterizedTest
    @EnumSource(value = BuyerIntegrationProfileVersionStatus.class, names = {"DRAFT", "VALIDATED"})
    void frozenRejectsUnpublishedPreparatoryVersion(BuyerIntegrationProfileVersionStatus status) {
        stubFrozenVersion(status);

        assertThatThrownBy(() -> resolver.resolveFrozen(PROJECT_ID, VERSION_ID, MEMBER_PROFILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not available for frozen route");
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void frozenRejectsProfileOwnedByAnotherProject() {
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(VERSION_ID, PROFILE_ID, BuyerIntegrationProfileVersionStatus.PUBLISHED));
        when(profileMapper.selectById(PROFILE_ID))
                .thenReturn(activeProfile(PROFILE_ID, OTHER_PROJECT_ID, VERSION_ID));

        assertThatThrownBy(() -> resolver.resolveFrozen(PROJECT_ID, VERSION_ID, CASHIER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project ownership mismatch");
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void frozenRejectsInactiveOwningProfileBeforeReadingCapability() {
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(VERSION_ID, PROFILE_ID, BuyerIntegrationProfileVersionStatus.PUBLISHED));
        when(profileMapper.selectById(PROFILE_ID)).thenReturn(BuyerIntegrationProfileDO.builder()
                .id(PROFILE_ID)
                .projectId(PROJECT_ID)
                .currentVersionId(VERSION_ID)
                .status(BuyerIntegrationProfileStatus.ARCHIVED.getCode())
                .build());

        assertThatThrownBy(() -> resolver.resolveFrozen(PROJECT_ID, VERSION_ID, CASHIER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile is not active");
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void frozenRejectsMapperProfileThatDoesNotOwnVersion() {
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(VERSION_ID, PROFILE_ID, BuyerIntegrationProfileVersionStatus.PUBLISHED));
        when(profileMapper.selectById(PROFILE_ID))
                .thenReturn(activeProfile(OTHER_PROFILE_ID, PROJECT_ID, VERSION_ID));

        assertThatThrownBy(() -> resolver.resolveFrozen(PROJECT_ID, VERSION_ID, CASHIER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile ownership mismatch");
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void frozenRejectsMissingVersionBeforeReadingProfileOrCapability() {
        when(versionMapper.selectById(VERSION_ID)).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveFrozen(PROJECT_ID, VERSION_ID, CASHIER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version not found");
        verifyNoInteractions(profileMapper, capabilityMapper);
    }

    @Test
    void frozenRejectsMissingOwningProfileBeforeReadingCapability() {
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(VERSION_ID, PROFILE_ID, BuyerIntegrationProfileVersionStatus.PUBLISHED));
        when(profileMapper.selectById(PROFILE_ID)).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveFrozen(PROJECT_ID, VERSION_ID, CASHIER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile not found");
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void frozenRejectsDisabledCapability() {
        stubFrozenVersion(BuyerIntegrationProfileVersionStatus.PUBLISHED);
        when(capabilityMapper.selectByProfileVersionAndCode(VERSION_ID, CASHIER))
                .thenReturn(BuyerIntegrationCapabilityDO.builder()
                        .profileVersionId(VERSION_ID)
                        .capabilityCode(CASHIER.name())
                        .status(BuyerIntegrationCapabilityStatus.DISABLED.getCode())
                        .build());

        assertThatThrownBy(() -> resolver.resolveFrozen(PROJECT_ID, VERSION_ID, CASHIER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capability is not enabled");
    }

    private void stubFrozenVersion(BuyerIntegrationProfileVersionStatus status) {
        when(versionMapper.selectById(VERSION_ID)).thenReturn(version(VERSION_ID, PROFILE_ID, status));
        when(profileMapper.selectById(PROFILE_ID))
                .thenReturn(activeProfile(PROFILE_ID, PROJECT_ID, VERSION_ID));
    }

    private static BuyerIntegrationProfileVersionDO version(
            long id, long profileId, BuyerIntegrationProfileVersionStatus status) {
        return BuyerIntegrationProfileVersionDO.builder()
                .id(id)
                .profileId(profileId)
                .status(status.getCode())
                .build();
    }

    private static BuyerIntegrationProfileDO activeProfile(long id, long projectId, long currentVersionId) {
        return BuyerIntegrationProfileDO.builder()
                .id(id)
                .projectId(projectId)
                .currentVersionId(currentVersionId)
                .status(BuyerIntegrationProfileStatus.ACTIVE.getCode())
                .build();
    }

    private static BuyerIntegrationCapabilityDO enabledCapability(
            BuyerIntegrationCapabilityCode capability, String sourceSystem, String externalTenantId) {
        return BuyerIntegrationCapabilityDO.builder()
                .profileVersionId(VERSION_ID)
                .capabilityCode(capability.name())
                .transportMode(TRANSPORT_MODE)
                .adapterCode("SAAS_" + capability.name() + "_RPC")
                .contractVersion(CONTRACT_VERSION)
                .sourceSystem(sourceSystem)
                .externalTenantId(externalTenantId)
                .status(BuyerIntegrationCapabilityStatus.ENABLED.getCode())
                .build();
    }

    private static void assertRoute(BuyerIntegrationRoute route, long versionId,
                                    BuyerIntegrationCapabilityCode capability) {
        assertThat(route.projectId()).isEqualTo(PROJECT_ID);
        assertThat(route.profileVersionId()).isEqualTo(versionId);
        assertThat(route.capability()).isEqualTo(capability);
        assertThat(route.sourceSystem()).isEqualTo(SOURCE_SYSTEM);
        assertThat(route.externalTenantId()).isEqualTo(EXTERNAL_TENANT_ID);
        assertThat(route.transportMode()).isEqualTo(TRANSPORT_MODE);
        assertThat(route.adapterCode()).isEqualTo("SAAS_" + capability.name() + "_RPC");
        assertThat(route.contractVersion()).isEqualTo(CONTRACT_VERSION);
    }
}
