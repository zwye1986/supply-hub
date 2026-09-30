package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelUserBindingDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelUserBindingMapper;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到店购稳定绑定必须严格落在采购项目会员三元组内。
 *
 * <p>绑定唯一键为 {@code (projectId, buyerUserId, channelCode)}；
 * {@code sourceSystem/externalTenantId} 只是最近一次签发观察到的路由快照，
 * 不参与会员业务唯一性。短期 token 已迁移到独立凭证服务。设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#6-稳定绑定}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreChannelUserBindingResolverTest {

    private static final Long BUYER_ID = 1001L;
    private static final Long PROJECT_ID = 101L;
    private static final String BUYER_USER_ID = "opaque-user";
    private static final String CHANNEL_CODE = "RT_MART";

    @Mock
    private InstoreChannelUserBindingMapper bindingMapper;
    @Mock
    private BusinessNoGenerator businessNoGenerator;
    @Mock
    private SupplyChainDatabaseClock databaseClock;

    private InstoreChannelUserBindingResolver resolver;

    @BeforeEach
    void setUp() {
        lenient().when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 11, 0));
        resolver = new InstoreChannelUserBindingResolver(databaseClock, bindingMapper, businessNoGenerator);
    }

    @Test
    void resolveOrCreateReusesBindingOnlyInsideExactProjectScope() {
        when(bindingMapper.selectByProjectUser(PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE))
                .thenReturn(activeBinding());

        InstoreChannelUserBindingResolver.BindingSnapshot snapshot =
                resolver.resolveOrCreateStableBinding(
                        BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute("tenant-a"));

        assertThat(snapshot.bindingId()).isEqualTo(11L);
        assertThat(snapshot.projectId()).isEqualTo(PROJECT_ID);
        assertThat(snapshot.sourceSystem()).isEqualTo("AIFULI_MALL");
        assertThat(snapshot.externalTenantId()).isEqualTo("tenant-a");
        assertThat(snapshot.version()).isEqualTo(0L);
        verify(bindingMapper, never()).insert(any(InstoreChannelUserBindingDO.class));
        verify(bindingMapper, never()).updateMemberRouteSnapshot(anyLong(), anyLong(), any(), any(), any());
        verify(bindingMapper, never()).selectByTenantUser(any(), any(), any(), any());
    }

    @Test
    void twoProjectsSharingTenantAndUserDoNotMerge() {
        when(bindingMapper.selectByProjectUser(PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE))
                .thenReturn(null);
        when(businessNoGenerator.next(BusinessNoPrefixes.IUB)).thenReturn("IUB_1");
        doAnswer(invocation -> {
            InstoreChannelUserBindingDO value = invocation.getArgument(0);
            value.setId(12L);
            return 1;
        }).when(bindingMapper).insert(any(InstoreChannelUserBindingDO.class));

        InstoreChannelUserBindingResolver.BindingSnapshot snapshot =
                resolver.resolveOrCreateStableBinding(
                        BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute("tenant-a"));

        assertThat(snapshot.bindingId()).isEqualTo(12L);
        ArgumentCaptor<InstoreChannelUserBindingDO> captor =
                ArgumentCaptor.forClass(InstoreChannelUserBindingDO.class);
        verify(bindingMapper).insert(captor.capture());
        assertThat(captor.getValue().getBuyerId()).isEqualTo(BUYER_ID);
        assertThat(captor.getValue().getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(captor.getValue().getBuyerUserId()).isEqualTo(BUYER_USER_ID);
        assertThat(captor.getValue().getChannelCode()).isEqualTo(CHANNEL_CODE);
        verify(bindingMapper, never()).selectByTenantUser(any(), any(), any(), any());
    }

    @Test
    void existingBindingWithDifferentBuyerFailsTenantScopeCheck() {
        InstoreChannelUserBindingDO binding = activeBinding();
        binding.setBuyerId(2002L);
        when(bindingMapper.selectByProjectUser(PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE))
                .thenReturn(binding);

        assertThatThrownBy(() -> resolver.resolveOrCreateStableBinding(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute("tenant-a")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT.getCode());
    }

    @Test
    void inactiveStableBindingCannotBeReused() {
        InstoreChannelUserBindingDO binding = activeBinding();
        binding.setStatus((short) 20);
        when(bindingMapper.selectByProjectUser(PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE))
                .thenReturn(binding);

        assertThatThrownBy(() -> resolver.resolveOrCreateStableBinding(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute("tenant-a")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CHANNEL_USER_BINDING_MISSING.getCode());
    }

    @Test
    void newStableBindingFreezesRouteSnapshotAndLeavesLegacyColumnsNull() {
        when(bindingMapper.selectByProjectUser(PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE))
                .thenReturn(null);
        when(businessNoGenerator.next(BusinessNoPrefixes.IUB)).thenReturn("IUB_1");
        doAnswer(invocation -> {
            InstoreChannelUserBindingDO value = invocation.getArgument(0);
            value.setId(99L);
            return 1;
        }).when(bindingMapper).insert(any(InstoreChannelUserBindingDO.class));

        resolver.resolveOrCreateStableBinding(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute("tenant-a"));

        ArgumentCaptor<InstoreChannelUserBindingDO> captor =
                ArgumentCaptor.forClass(InstoreChannelUserBindingDO.class);
        verify(bindingMapper).insert(captor.capture());
        assertThat(captor.getValue().getSourceSystem()).isEqualTo("AIFULI_MALL");
        assertThat(captor.getValue().getExternalTenantId()).isEqualTo("tenant-a");
        assertThat(captor.getValue().getChannelUserId()).isNull();
        assertThat(captor.getValue().getToken()).isNull();
        assertThat(captor.getValue().getTokenStatus()).isNull();
        assertThat(captor.getValue().getTokenExpiresAt()).isNull();
    }

    @Test
    void unsupportedChannelIsRejectedBeforeInsert() {
        when(bindingMapper.selectByProjectUser(PROJECT_ID, BUYER_USER_ID, "ELEME"))
                .thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveOrCreateStableBinding(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, "ELEME", memberRoute("tenant-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported instore channel code");
        verify(bindingMapper, never()).insert(any(InstoreChannelUserBindingDO.class));
    }

    @Test
    void memberRouteMustTargetMemberProfileCapability() {
        BuyerIntegrationRoute cashierRoute = new BuyerIntegrationRoute(
                PROJECT_ID, 901L, BuyerIntegrationCapabilityCode.CASHIER,
                "AIFULI_MALL", "tenant-a", "HTTP", "adapter-rtmart", "v1", null);

        assertThatThrownBy(() -> resolver.resolveOrCreateStableBinding(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, cashierRoute))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEMBER_PROFILE");
        verify(bindingMapper, never()).selectByProjectUser(any(), any(), any());
    }

    @Test
    void memberRouteMustTargetTheSameProject() {
        BuyerIntegrationRoute otherProjectRoute = memberRoute(202L, 901L, "tenant-a");

        assertThatThrownBy(() -> resolver.resolveOrCreateStableBinding(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, otherProjectRoute))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
        verify(bindingMapper, never()).selectByProjectUser(any(), any(), any());
    }

    @Test
    void concurrentCreateRereadsByTheSameProjectKey() {
        when(bindingMapper.selectByProjectUser(PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE))
                .thenReturn(null)
                .thenReturn(activeBinding());
        when(businessNoGenerator.next(BusinessNoPrefixes.IUB)).thenReturn("IUB_1");
        DuplicateKeyException duplicate = new DuplicateKeyException("uk_project_user");
        doThrow(duplicate).when(bindingMapper).insert(any(InstoreChannelUserBindingDO.class));

        InstoreChannelUserBindingResolver.BindingSnapshot snapshot =
                resolver.resolveOrCreateStableBinding(
                        BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute("tenant-a"));

        assertThat(snapshot.bindingId()).isEqualTo(11L);
        assertThat(snapshot.externalTenantId()).isEqualTo("tenant-a");
        verify(bindingMapper, never()).selectByTenantUser(any(), any(), any(), any());
    }

    @Test
    void concurrentCreatePreservesDuplicateCauseWhenRereadStillMissing() {
        when(bindingMapper.selectByProjectUser(PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE))
                .thenReturn(null);
        when(businessNoGenerator.next(BusinessNoPrefixes.IUB)).thenReturn("IUB_1");
        DuplicateKeyException duplicate = new DuplicateKeyException("uk_project_user");
        doThrow(duplicate).when(bindingMapper).insert(any(InstoreChannelUserBindingDO.class));

        assertThatThrownBy(() -> resolver.resolveOrCreateStableBinding(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute("tenant-a")))
                .isSameAs(duplicate);
    }

    @Test
    void routeSwitchTriggersVersionCasSnapshotRefresh() {
        InstoreChannelUserBindingDO existing = activeBinding();
        when(bindingMapper.selectByProjectUser(PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE))
                .thenReturn(existing);
        when(bindingMapper.updateMemberRouteSnapshot(
                eq(11L), eq(0L), eq("AIFULI_MALL"), eq("tenant-b"), any()))
                .thenReturn(1);

        InstoreChannelUserBindingResolver.BindingSnapshot snapshot =
                resolver.resolveOrCreateStableBinding(
                        BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute("tenant-b"));

        assertThat(snapshot.externalTenantId()).isEqualTo("tenant-b");
        assertThat(snapshot.version()).isEqualTo(1L);
        verify(bindingMapper).updateMemberRouteSnapshot(
                eq(11L), eq(0L), eq("AIFULI_MALL"), eq("tenant-b"), any());
    }

    @Test
    void routeSnapshotCasConflictFailsConcurrentUpdate() {
        InstoreChannelUserBindingDO existing = activeBinding();
        when(bindingMapper.selectByProjectUser(PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE))
                .thenReturn(existing);
        when(bindingMapper.updateMemberRouteSnapshot(
                eq(11L), eq(0L), eq("AIFULI_MALL"), eq("tenant-b"), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> resolver.resolveOrCreateStableBinding(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute("tenant-b")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE.getCode());
    }

    @Test
    void resolveActiveByIdReturnsProjectSnapshot() {
        when(bindingMapper.selectById(11L)).thenReturn(activeBinding());

        InstoreChannelUserBindingResolver.BindingSnapshot snapshot = resolver.resolveActiveById(11L);

        assertThat(snapshot.bindingId()).isEqualTo(11L);
        assertThat(snapshot.projectId()).isEqualTo(PROJECT_ID);
        assertThat(snapshot.sourceSystem()).isEqualTo("AIFULI_MALL");
        assertThat(snapshot.externalTenantId()).isEqualTo("tenant-a");
    }

    @Test
    void resolveActiveByIdRejectsMissingOrInactiveBinding() {
        when(bindingMapper.selectById(11L)).thenReturn(null);
        assertThatThrownBy(() -> resolver.resolveActiveById(11L))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CHANNEL_USER_BINDING_MISSING.getCode());
    }

    private static InstoreChannelUserBindingDO activeBinding() {
        return InstoreChannelUserBindingDO.builder()
                .id(11L)
                .buyerId(BUYER_ID)
                .projectId(PROJECT_ID)
                .sourceSystem("AIFULI_MALL")
                .externalTenantId("tenant-a")
                .buyerUserId(BUYER_USER_ID)
                .channelCode(CHANNEL_CODE)
                .status((short) 10)
                .version(0L)
                .build();
    }

    private static BuyerIntegrationRoute memberRoute(String externalTenantId) {
        return memberRoute(PROJECT_ID, 901L, externalTenantId);
    }

    private static BuyerIntegrationRoute memberRoute(Long projectId, Long profileVersionId,
                                                     String externalTenantId) {
        return new BuyerIntegrationRoute(
                projectId, profileVersionId, BuyerIntegrationCapabilityCode.MEMBER_PROFILE,
                "AIFULI_MALL", externalTenantId, "HTTP", "adapter-rtmart", "v1", null);
    }
}
