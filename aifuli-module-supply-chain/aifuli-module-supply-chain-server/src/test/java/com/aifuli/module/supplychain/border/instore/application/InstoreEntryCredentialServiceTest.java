package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.instore.application.InstoreChannelUserBindingResolver.BindingSnapshot;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService.ClaimedCredential;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService.IssuedCredential;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreEntryCredentialDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreEntryCredentialMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreCredentialRevokeReason;
import com.aifuli.module.supplychain.border.instore.domain.InstoreEntryCredentialPurpose;
import com.aifuli.module.supplychain.border.instore.domain.InstoreEntryCredentialStatus;
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

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到店购入口凭证单元测试：签发冻结项目/会员档案版本/企业组快照，明文仅签发时返回，
 * 持久化仅保存 SHA-256 摘要，消费严格执行 ACTIVE→PROCESSING→USED 状态机与 REVOKED 终态。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#8-凭证状态机}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreEntryCredentialServiceTest {

    private static final Long BUYER_ID = 1001L;
    private static final Long PROJECT_ID = 101L;
    private static final Long PROFILE_VERSION_ID = 901L;
    private static final String BUYER_USER_ID = "opaque-user";
    private static final String CHANNEL_CODE = "RT_MART";
    private static final String COMPANY_CODE = "COMPANY_A";
    private static final LocalDateTime DATABASE_NOW = LocalDateTime.of(2026, 9, 4, 11, 0);

    @Mock
    private InstoreEntryCredentialMapper credentialMapper;
    @Mock
    private InstoreChannelUserBindingResolver bindingResolver;
    @Mock
    private BusinessNoGenerator businessNoGenerator;
    @Mock
    private SupplyChainDatabaseClock databaseClock;

    private InstoreEntryCredentialService service;

    @BeforeEach
    void setUp() {
        InstoreEntryCredentialProperties properties = new InstoreEntryCredentialProperties();
        properties.setTtl(Duration.ofMinutes(10));
        lenient().when(databaseClock.now()).thenReturn(DATABASE_NOW);
        service = new InstoreEntryCredentialService(
                databaseClock, credentialMapper, bindingResolver, businessNoGenerator, properties);
    }

    @Test
    void issueHomeFreezesSnapshotAndReturnsPlaintextOnce() throws Exception {
        when(bindingResolver.resolveOrCreateStableBinding(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute()))
                .thenReturn(bindingSnapshot());
        when(businessNoGenerator.next(BusinessNoPrefixes.IEC)).thenReturn("IEC_1");
        doAnswer(invocation -> {
            InstoreEntryCredentialDO value = invocation.getArgument(0);
            value.setId(77L);
            return 1;
        }).when(credentialMapper).insert(any(InstoreEntryCredentialDO.class));

        IssuedCredential issued = service.issueHome(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute(), COMPANY_CODE);

        ArgumentCaptor<InstoreEntryCredentialDO> captor =
                ArgumentCaptor.forClass(InstoreEntryCredentialDO.class);
        verify(credentialMapper).insert(captor.capture());
        InstoreEntryCredentialDO persisted = captor.getValue();
        assertThat(persisted.getBindingId()).isEqualTo(11L);
        assertThat(persisted.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(persisted.getMemberProfileVersionId()).isEqualTo(PROFILE_VERSION_ID);
        assertThat(persisted.getCompanyCode()).isEqualTo(COMPANY_CODE);
        assertThat(persisted.getStatus())
                .isEqualTo(InstoreEntryCredentialStatus.ACTIVE.getCode());
        assertThat(persisted.getTokenDigest())
                .isEqualTo(MessageDigest.getInstance("SHA-256")
                        .digest(issued.token().getBytes(StandardCharsets.UTF_8)));
        assertThat(InstoreEntryCredentialDO.class.getDeclaredFields())
                .extracting(Field::getName)
                .doesNotContain("token");

        assertThat(issued.credentialId()).isEqualTo(77L);
        assertThat(issued.credentialNo()).isEqualTo("IEC_1");
        assertThat(issued.credentialVersion()).isZero();
        assertThat(issued.token()).startsWith("tk-").hasSize(67);
        assertThat(issued.purpose()).isEqualTo(InstoreEntryCredentialPurpose.HOME);
        assertThat(issued.instoreOrderId()).isNull();
        assertThat(issued.expiresAt()).isEqualTo(DATABASE_NOW.plusMinutes(10));
    }

    @Test
    void issueDetailFreezesOrderBinding() {
        when(bindingResolver.resolveOrCreateStableBinding(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute()))
                .thenReturn(bindingSnapshot());
        when(businessNoGenerator.next(BusinessNoPrefixes.IEC)).thenReturn("IEC_2");
        doAnswer(invocation -> {
            InstoreEntryCredentialDO value = invocation.getArgument(0);
            value.setId(78L);
            return 1;
        }).when(credentialMapper).insert(any(InstoreEntryCredentialDO.class));

        IssuedCredential issued = service.issueDetail(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute(),
                COMPANY_CODE, 55L);

        ArgumentCaptor<InstoreEntryCredentialDO> captor =
                ArgumentCaptor.forClass(InstoreEntryCredentialDO.class);
        verify(credentialMapper).insert(captor.capture());
        assertThat(captor.getValue().getPurpose())
                .isEqualTo(InstoreEntryCredentialPurpose.DETAIL.name());
        assertThat(captor.getValue().getInstoreOrderId()).isEqualTo(55L);
        assertThat(issued.instoreOrderId()).isEqualTo(55L);
    }

    @Test
    void issueDetailRequiresOrder() {
        assertThatThrownBy(() -> service.issueDetail(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute(),
                COMPANY_CODE, null))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_INVALID.getCode());
        verify(bindingResolver, never()).resolveOrCreateStableBinding(
                anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void issueRequiresCompanyCode() {
        assertThatThrownBy(() -> service.issueHome(
                BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE, memberRoute(), " "))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_INVALID.getCode());
        verify(bindingResolver, never()).resolveOrCreateStableBinding(
                anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void claimRejectsBlankOrUnknownToken() {
        assertThatThrownBy(() -> service.claimForMemberLookup(PROJECT_ID, CHANNEL_CODE, " "))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_INVALID.getCode());

        when(credentialMapper.selectByTokenDigest(any())).thenReturn(null);
        assertThatThrownBy(() -> service.claimForMemberLookup(PROJECT_ID, CHANNEL_CODE, "tk-unknown"))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_INVALID.getCode());
        verify(bindingResolver, never()).resolveActiveById(anyLong());
    }

    @Test
    void claimFromAnotherProjectFailsBeforeCas() {
        InstoreEntryCredentialDO active = activeCredential(
                InstoreEntryCredentialPurpose.HOME, null, future());
        when(credentialMapper.selectByTokenDigest(any())).thenReturn(active);

        assertThatThrownBy(() -> service.claimForMemberLookup(202L, CHANNEL_CODE, "tk-value"))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT.getCode());
        verify(bindingResolver, never()).resolveActiveById(anyLong());
        verify(credentialMapper, never()).claimProcessing(anyLong(), anyLong(), any());
    }

    @Test
    void claimFromAnotherChannelFailsBeforeCas() {
        InstoreEntryCredentialDO active = activeCredential(
                InstoreEntryCredentialPurpose.HOME, null, future());
        when(credentialMapper.selectByTokenDigest(any())).thenReturn(active);
        when(bindingResolver.resolveActiveById(active.getBindingId()))
                .thenReturn(bindingSnapshot());

        assertThatThrownBy(() -> service.claimForMemberLookup(PROJECT_ID, "O2O", "tk-value"))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT.getCode());
        verify(credentialMapper, never()).claimProcessing(anyLong(), anyLong(), any());
    }

    @Test
    void claimWithDirtyPurposeFailsBeforeCas() {
        InstoreEntryCredentialDO dirty = activeCredential(
                InstoreEntryCredentialPurpose.HOME, null, future());
        dirty.setPurpose("GARBAGE");
        when(credentialMapper.selectByTokenDigest(any())).thenReturn(dirty);
        when(bindingResolver.resolveActiveById(dirty.getBindingId()))
                .thenReturn(bindingSnapshot());

        assertThatThrownBy(() -> service.claimForMemberLookup(PROJECT_ID, CHANNEL_CODE, "tk-value"))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_INVALID.getCode());
        verify(credentialMapper, never()).claimProcessing(anyLong(), anyLong(), any());
    }

    @Test
    void revokedCredentialReturnsRevokedErrorBeforeCas() {
        InstoreEntryCredentialDO revoked = activeCredential(
                InstoreEntryCredentialPurpose.HOME, null, future());
        revoked.setStatus(InstoreEntryCredentialStatus.REVOKED.getCode());
        when(credentialMapper.selectByTokenDigest(any())).thenReturn(revoked);
        when(bindingResolver.resolveActiveById(revoked.getBindingId()))
                .thenReturn(bindingSnapshot());

        assertThatThrownBy(() -> service.claimForMemberLookup(PROJECT_ID, CHANNEL_CODE, "tk-value"))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_REVOKED.getCode());
        verify(credentialMapper, never()).claimProcessing(anyLong(), anyLong(), any());
    }

    @Test
    void expiredCredentialReturnsExpiredError() {
        InstoreEntryCredentialDO expired = activeCredential(
                InstoreEntryCredentialPurpose.HOME, null,
                DATABASE_NOW.minusSeconds(1));
        when(credentialMapper.selectByTokenDigest(any())).thenReturn(expired);
        when(bindingResolver.resolveActiveById(expired.getBindingId()))
                .thenReturn(bindingSnapshot());
        when(credentialMapper.markExpired(eq(expired.getId()), eq(0L), any())).thenReturn(1);

        assertThatThrownBy(() -> service.claimForMemberLookup(PROJECT_ID, CHANNEL_CODE, "tk-expired"))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_EXPIRED.getCode());
    }

    @Test
    void usedCredentialReturnsAlreadyUsedError() {
        InstoreEntryCredentialDO used = activeCredential(
                InstoreEntryCredentialPurpose.HOME, null, future());
        used.setStatus(InstoreEntryCredentialStatus.USED.getCode());
        when(credentialMapper.selectByTokenDigest(any())).thenReturn(used);
        when(bindingResolver.resolveActiveById(used.getBindingId()))
                .thenReturn(bindingSnapshot());

        assertThatThrownBy(() -> service.claimForMemberLookup(PROJECT_ID, CHANNEL_CODE, "tk-used"))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_ALREADY_USED.getCode());
    }

    @Test
    void processingCredentialReturnsConcurrentUpdate() {
        InstoreEntryCredentialDO processing = activeCredential(
                InstoreEntryCredentialPurpose.HOME, null, future());
        processing.setStatus(InstoreEntryCredentialStatus.PROCESSING.getCode());
        when(credentialMapper.selectByTokenDigest(any())).thenReturn(processing);
        when(bindingResolver.resolveActiveById(processing.getBindingId()))
                .thenReturn(bindingSnapshot());

        assertThatThrownBy(() -> service.claimForMemberLookup(PROJECT_ID, CHANNEL_CODE, "tk-processing"))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE.getCode());
    }

    @Test
    void successfulClaimCarriesFrozenSnapshot() {
        InstoreEntryCredentialDO active = activeCredential(
                InstoreEntryCredentialPurpose.HOME, null, future());
        when(credentialMapper.selectByTokenDigest(any())).thenReturn(active);
        when(bindingResolver.resolveActiveById(active.getBindingId()))
                .thenReturn(bindingSnapshot());
        when(credentialMapper.claimProcessing(eq(77L), eq(0L), any())).thenReturn(1);

        ClaimedCredential claimed = service.claimForMemberLookup(
                PROJECT_ID, CHANNEL_CODE, "tk-value");

        assertThat(claimed.credentialId()).isEqualTo(77L);
        assertThat(claimed.processingVersion()).isEqualTo(1L);
        assertThat(claimed.projectId()).isEqualTo(PROJECT_ID);
        assertThat(claimed.memberProfileVersionId()).isEqualTo(PROFILE_VERSION_ID);
        assertThat(claimed.companyCode()).isEqualTo(COMPANY_CODE);
        assertThat(claimed.buyerUserId()).isEqualTo(BUYER_USER_ID);
        assertThat(claimed.channelCode()).isEqualTo(CHANNEL_CODE);
        assertThat(claimed.purpose()).isEqualTo(InstoreEntryCredentialPurpose.HOME);
        assertThat(claimed.instoreOrderId()).isNull();
    }

    @Test
    void detailClaimCarriesInstoreOrderId() {
        InstoreEntryCredentialDO detail = activeCredential(
                InstoreEntryCredentialPurpose.DETAIL, 55L, future());
        when(credentialMapper.selectByTokenDigest(any())).thenReturn(detail);
        when(bindingResolver.resolveActiveById(detail.getBindingId()))
                .thenReturn(bindingSnapshot());
        when(credentialMapper.claimProcessing(eq(77L), eq(0L), any())).thenReturn(1);

        ClaimedCredential claimed = service.claimForMemberLookup(
                PROJECT_ID, CHANNEL_CODE, "tk-detail");

        assertThat(claimed.purpose()).isEqualTo(InstoreEntryCredentialPurpose.DETAIL);
        assertThat(claimed.instoreOrderId()).isEqualTo(55L);
    }

    @Test
    void claimCasConflictRereadsAndThrowsCurrentState() {
        InstoreEntryCredentialDO active = activeCredential(
                InstoreEntryCredentialPurpose.HOME, null, future());
        when(credentialMapper.selectByTokenDigest(any())).thenReturn(active);
        when(bindingResolver.resolveActiveById(active.getBindingId()))
                .thenReturn(bindingSnapshot());
        when(credentialMapper.claimProcessing(eq(77L), eq(0L), any())).thenReturn(0);
        when(credentialMapper.selectById(77L)).thenReturn(active);

        assertThatThrownBy(() -> service.claimForMemberLookup(PROJECT_ID, CHANNEL_CODE, "tk-value"))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE.getCode());
    }

    @Test
    void successfulClaimCanBeMarkedUsed() {
        when(credentialMapper.markUsed(eq(77L), eq(1L), any())).thenReturn(1);
        service.markLookupSucceeded(77L, 1L);
    }

    @Test
    void markUsedCasFailureThrowsConcurrentUpdate() {
        when(credentialMapper.markUsed(eq(77L), eq(1L), any())).thenReturn(0);
        assertThatThrownBy(() -> service.markLookupSucceeded(77L, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE.getCode());
    }

    @Test
    void failedLookupReleasesUnexpiredProcessingCredential() {
        InstoreEntryCredentialDO processing = activeCredential(
                InstoreEntryCredentialPurpose.HOME, null, future());
        processing.setStatus(InstoreEntryCredentialStatus.PROCESSING.getCode());
        processing.setVersion(1L);
        when(credentialMapper.selectById(77L)).thenReturn(processing);
        when(credentialMapper.releaseProcessing(eq(77L), eq(1L), any())).thenReturn(1);

        service.releaseAfterLookupFailure(77L, 1L);
    }

    @Test
    void failedLookupExpiresCredentialInsteadOfReleasingAfterDeadline() {
        InstoreEntryCredentialDO processing = activeCredential(
                InstoreEntryCredentialPurpose.HOME, null,
                DATABASE_NOW.minusSeconds(1));
        processing.setStatus(InstoreEntryCredentialStatus.PROCESSING.getCode());
        processing.setVersion(1L);
        when(credentialMapper.selectById(77L)).thenReturn(processing);
        when(credentialMapper.markExpired(eq(77L), eq(1L), any())).thenReturn(1);

        service.releaseAfterLookupFailure(77L, 1L);

        verify(credentialMapper, never()).releaseProcessing(anyLong(), anyLong(), any());
    }

    @Test
    void releaseCasFailureThrowsConcurrentUpdate() {
        InstoreEntryCredentialDO processing = activeCredential(
                InstoreEntryCredentialPurpose.HOME, null, future());
        processing.setStatus(InstoreEntryCredentialStatus.PROCESSING.getCode());
        processing.setVersion(1L);
        when(credentialMapper.selectById(77L)).thenReturn(processing);
        when(credentialMapper.releaseProcessing(eq(77L), eq(1L), any())).thenReturn(0);

        assertThatThrownBy(() -> service.releaseAfterLookupFailure(77L, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE.getCode());
    }

    @Test
    void revokeFromActiveSucceeds() {
        when(credentialMapper.revoke(
                eq(77L), eq(0L), eq(InstoreCredentialRevokeReason.ENTRY_URL_CALL_FAILED), any()))
                .thenReturn(1);

        service.revoke(77L, 0L, InstoreCredentialRevokeReason.ENTRY_URL_CALL_FAILED);
    }

    @Test
    void revokeCasFailureThrowsConcurrentUpdate() {
        when(credentialMapper.revoke(
                eq(77L), eq(0L), eq(InstoreCredentialRevokeReason.MEMBER_PROFILE_RETIRED), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.revoke(
                77L, 0L, InstoreCredentialRevokeReason.MEMBER_PROFILE_RETIRED))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE.getCode());
    }

    private static InstoreEntryCredentialDO activeCredential(
            InstoreEntryCredentialPurpose purpose, Long instoreOrderId,
            LocalDateTime expiresAt) {
        return InstoreEntryCredentialDO.builder()
                .id(77L)
                .credentialNo("IEC_1")
                .bindingId(11L)
                .projectId(PROJECT_ID)
                .memberProfileVersionId(PROFILE_VERSION_ID)
                .companyCode(COMPANY_CODE)
                .tokenDigest(new byte[32])
                .purpose(purpose.name())
                .instoreOrderId(instoreOrderId)
                .status(InstoreEntryCredentialStatus.ACTIVE.getCode())
                .expiresAt(expiresAt)
                .version(0L)
                .createdAt(DATABASE_NOW)
                .updatedAt(DATABASE_NOW)
                .build();
    }

    private static LocalDateTime future() {
        return DATABASE_NOW.plusMinutes(10);
    }

    private static BindingSnapshot bindingSnapshot() {
        return new BindingSnapshot(
                11L, BUYER_ID, PROJECT_ID, "AIFULI_MALL", "tenant-a",
                BUYER_USER_ID, CHANNEL_CODE, 0L);
    }

    private static BuyerIntegrationRoute memberRoute() {
        return new BuyerIntegrationRoute(
                PROJECT_ID, PROFILE_VERSION_ID, BuyerIntegrationCapabilityCode.MEMBER_PROFILE,
                "AIFULI_MALL", "tenant-a", "HTTP", "adapter-rtmart", "v1", null);
    }
}
