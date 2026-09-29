package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.AuthBuyerProjectDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAppCredentialVersionDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectApplicationDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectCapabilityDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.AuthBuyerProjectMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectAppCredentialVersionMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectApplicationMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectCapabilityMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭证链与按操作能力授权单测。
 * 详见 docs/superpowers/specs/2026-09-29-access-config-backend-impl-design.md#3-a-项目应用发码。
 */
@ExtendWith(MockitoExtension.class)
class ProjectAppCredentialServiceTest {

    private static final String APP_ID = "app-credential-test";
    private static final String APP_KEY_SENTINEL = "app-key-sentinel-never-serialize";
    private static final String SECRET_REF_SENTINEL = "secret-ref-sentinel-never-log";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(FIXED_CLOCK.instant(), ZoneId.of("Asia/Shanghai"));

    @Mock
    private ProjectApplicationMapper applicationMapper;
    @Mock
    private AuthBuyerProjectMapper buyerProjectMapper;
    @Mock
    private ProjectAppCredentialVersionMapper credentialMapper;
    @Mock
    private ProjectCapabilityMapper capabilityMapper;
    @Mock
    private AppKeySecretResolver secretResolver;

    private ProjectAppCredentialService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, ProjectApplicationDO.class);
        TableInfoHelper.initTableInfo(assistant, AuthBuyerProjectDO.class);
        TableInfoHelper.initTableInfo(assistant, ProjectAppCredentialVersionDO.class);
        TableInfoHelper.initTableInfo(assistant, ProjectCapabilityDO.class);
    }

    @BeforeEach
    void setUp() {
        service = new ProjectAppCredentialService(applicationMapper, buyerProjectMapper,
                credentialMapper, capabilityMapper, secretResolver, FIXED_CLOCK);
    }

    @Test
    void authenticateResolvesCurrentCredentialChain() {
        stubFullChain(ProjectAppCredentialStatus.CURRENT);

        ProjectAppCredentialService.AuthenticatedApp app = service.authenticate(APP_ID, 1);

        assertThat(app.projectId()).isEqualTo(20L);
        assertThat(app.buyerId()).isEqualTo(10L);
        assertThat(app.appId()).isEqualTo(APP_ID);
        assertThat(app.keyVersion()).isEqualTo(1);
        assertThat(app.appKey()).isEqualTo(APP_KEY_SENTINEL);
    }

    @Test
    void authenticateAcceptsGraceCredential() {
        stubFullChain(ProjectAppCredentialStatus.GRACE);

        assertThat(service.authenticate(APP_ID, 1).appKey()).isEqualTo(APP_KEY_SENTINEL);
    }

    @Test
    void authenticateDoesNotRequireInstoreBeforeOperationAuthorization() {
        stubUpToCredential(ProjectAppCredentialStatus.CURRENT);
        when(secretResolver.resolveAppKey(APP_ID, 1, SECRET_REF_SENTINEL)).thenReturn(APP_KEY_SENTINEL);

        assertThat(service.authenticate(APP_ID, 1).appKey()).isEqualTo(APP_KEY_SENTINEL);
        verify(capabilityMapper, never()).selectByProjectIdAndCode(20L,
                ProjectAppCredentialService.CAPABILITY_INSTORE);
    }

    @Test
    void authenticateRejectsUnknownApplication() {
        when(applicationMapper.selectByAppId(APP_ID)).thenReturn(null);

        assertAuthInvalid();
    }

    @ParameterizedTest
    @ValueSource(shorts = {20, 90})
    void authenticateRejectsNonEnabledApplication(short status) {
        when(applicationMapper.selectByAppId(APP_ID)).thenReturn(application(status));

        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsMissingProject() {
        when(applicationMapper.selectByAppId(APP_ID)).thenReturn(application((short) 10));
        when(buyerProjectMapper.selectById(20L)).thenReturn(null);

        assertAuthInvalid();
    }

    @ParameterizedTest
    @ValueSource(shorts = {0, 20, 90})
    void authenticateRejectsNonEnabledProject(short status) {
        when(applicationMapper.selectByAppId(APP_ID)).thenReturn(application((short) 10));
        when(buyerProjectMapper.selectById(20L)).thenReturn(project(status));

        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsMissingCredential() {
        stubApplicationAndProject();
        when(credentialMapper.selectByApplicationIdAndKeyVersion(30L, 1)).thenReturn(null);

        assertAuthInvalid();
    }

    @ParameterizedTest
    @EnumSource(value = ProjectAppCredentialStatus.class, names = {"REVOKED", "EXPIRED"})
    void authenticateRejectsUnavailableCredentialStatus(ProjectAppCredentialStatus status) {
        stubUpToCredential(status);

        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsCredentialWithoutValidFrom() {
        ProjectAppCredentialVersionDO credential = credential(ProjectAppCredentialStatus.CURRENT);
        credential.setValidFrom(null);
        stubCredential(credential);

        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsCredentialNotYetValid() {
        ProjectAppCredentialVersionDO credential = credential(ProjectAppCredentialStatus.CURRENT);
        credential.setValidFrom(NOW.plusNanos(1));
        stubCredential(credential);

        assertAuthInvalid();
    }

    @Test
    void authenticateAcceptsCredentialAtValidFromBoundary() {
        ProjectAppCredentialVersionDO credential = credential(ProjectAppCredentialStatus.CURRENT);
        credential.setValidFrom(NOW);
        stubCredentialAndCapability(credential);
        when(secretResolver.resolveAppKey(APP_ID, 1, SECRET_REF_SENTINEL)).thenReturn(APP_KEY_SENTINEL);

        assertThat(service.authenticate(APP_ID, 1).appKey()).isEqualTo(APP_KEY_SENTINEL);
    }

    @Test
    void authenticateRejectsCredentialAtValidToBoundary() {
        ProjectAppCredentialVersionDO credential = credential(ProjectAppCredentialStatus.CURRENT);
        credential.setValidTo(NOW);
        stubCredential(credential);

        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsCredentialPastValidTo() {
        ProjectAppCredentialVersionDO credential = credential(ProjectAppCredentialStatus.CURRENT);
        credential.setValidTo(NOW.minusNanos(1));
        stubCredential(credential);

        assertAuthInvalid();
    }

    @Test
    void authenticateAcceptsCredentialBeforeValidToBoundary() {
        ProjectAppCredentialVersionDO credential = credential(ProjectAppCredentialStatus.CURRENT);
        credential.setValidTo(NOW.plusNanos(1));
        stubCredentialAndCapability(credential);
        when(secretResolver.resolveAppKey(APP_ID, 1, SECRET_REF_SENTINEL)).thenReturn(APP_KEY_SENTINEL);

        assertThat(service.authenticate(APP_ID, 1).appKey()).isEqualTo(APP_KEY_SENTINEL);
    }

    @Test
    void operationAuthorizationRejectsMissingCapability() {
        when(capabilityMapper.selectByProjectIdAndCode(20L, ProjectAppCredentialService.CAPABILITY_INSTORE))
                .thenReturn(null);

        ServiceException failure = catchThrowableOfType(
                () -> service.requireCapability(20L, ProjectAppCredentialService.CAPABILITY_INSTORE),
                ServiceException.class);
        assertThat(failure.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        verify(secretResolver, never()).resolveAppKey(APP_ID, 1, SECRET_REF_SENTINEL);
    }

    @Test
    void operationAuthorizationRejectsDisabledCapability() {
        when(capabilityMapper.selectByProjectIdAndCode(20L, ProjectAppCredentialService.CAPABILITY_INSTORE))
                .thenReturn(capability((short) 20));

        ServiceException failure = catchThrowableOfType(
                () -> service.requireCapability(20L, ProjectAppCredentialService.CAPABILITY_INSTORE),
                ServiceException.class);
        assertThat(failure.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
    }

    @Test
    void operationAuthorizationAcceptsEnabledCapability() {
        when(capabilityMapper.selectByProjectIdAndCode(20L, ProjectAppCredentialService.CAPABILITY_INSTORE))
                .thenReturn(capability((short) 10));

        service.requireCapability(20L, ProjectAppCredentialService.CAPABILITY_INSTORE);
    }

    @Test
    void authenticateMapsDecryptFailureAndPreservesCause() {
        stubUpToCredential(ProjectAppCredentialStatus.CURRENT);
        IllegalArgumentException resolverFailure = new IllegalArgumentException("secret_ref 解密失败");
        when(secretResolver.resolveAppKey(APP_ID, 1, SECRET_REF_SENTINEL)).thenThrow(resolverFailure);

        ServiceException failure = catchThrowableOfType(
                () -> service.authenticate(APP_ID, 1), ServiceException.class);

        assertThat(failure.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertThat(failure.getCause()).isSameAs(resolverFailure);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void authenticateRejectsEmptyResolvedAppKey(String resolvedAppKey) {
        stubCredentialAndCapability(credential(ProjectAppCredentialStatus.CURRENT));
        when(secretResolver.resolveAppKey(APP_ID, 1, SECRET_REF_SENTINEL)).thenReturn(resolvedAppKey);

        assertAuthInvalid();
        verify(secretResolver).resolveAppKey(APP_ID, 1, SECRET_REF_SENTINEL);
    }

    @Test
    void authenticatedAppKeepsAccessorButDoesNotSerializeOrLogAppKey() throws Exception {
        ProjectAppCredentialService.AuthenticatedApp app = new ProjectAppCredentialService.AuthenticatedApp(
                20L, 10L, APP_ID, 1, APP_KEY_SENTINEL);

        String json = new ObjectMapper().writeValueAsString(app);

        assertThat(app.appKey()).isEqualTo(APP_KEY_SENTINEL);
        assertThat(json).doesNotContain("appKey", APP_KEY_SENTINEL);
        assertThat(app.toString()).doesNotContain("appKey", APP_KEY_SENTINEL);
    }

    @Test
    void credentialToStringDoesNotExposeSecretRef() {
        ProjectAppCredentialVersionDO credential = credential(ProjectAppCredentialStatus.CURRENT);

        assertThat(credential.getSecretRef()).isEqualTo(SECRET_REF_SENTINEL);
        assertThat(credential.toString()).doesNotContain("secretRef", SECRET_REF_SENTINEL);
    }

    private void stubFullChain(ProjectAppCredentialStatus status) {
        ProjectAppCredentialVersionDO credential = credential(status);
        stubCredentialAndCapability(credential);
        when(secretResolver.resolveAppKey(APP_ID, 1, SECRET_REF_SENTINEL)).thenReturn(APP_KEY_SENTINEL);
    }

    private void stubUpToCredential(ProjectAppCredentialStatus status) {
        stubCredential(credential(status));
    }

    private void stubCredential(ProjectAppCredentialVersionDO credential) {
        stubApplicationAndProject();
        when(credentialMapper.selectByApplicationIdAndKeyVersion(30L, 1)).thenReturn(credential);
    }

    private void stubCredentialAndCapability(ProjectAppCredentialVersionDO credential) {
        stubCredential(credential);
    }

    private void stubApplicationAndProject() {
        when(applicationMapper.selectByAppId(APP_ID)).thenReturn(application((short) 10));
        when(buyerProjectMapper.selectById(20L)).thenReturn(project((short) 10));
    }

    private static ProjectApplicationDO application(short status) {
        ProjectApplicationDO application = new ProjectApplicationDO();
        application.setId(30L);
        application.setProjectId(20L);
        application.setAppId(APP_ID);
        application.setStatus(status);
        return application;
    }

    private static AuthBuyerProjectDO project(short status) {
        AuthBuyerProjectDO project = new AuthBuyerProjectDO();
        project.setId(20L);
        project.setBuyerId(10L);
        project.setStatus(status);
        return project;
    }

    private static ProjectAppCredentialVersionDO credential(ProjectAppCredentialStatus status) {
        ProjectAppCredentialVersionDO credential = new ProjectAppCredentialVersionDO();
        credential.setId(40L);
        credential.setApplicationId(30L);
        credential.setKeyVersion(1);
        credential.setSecretRef(SECRET_REF_SENTINEL);
        credential.setStatus(status.name());
        credential.setValidFrom(NOW.minusDays(1));
        credential.setValidTo(null);
        return credential;
    }

    private static ProjectCapabilityDO capability(short status) {
        ProjectCapabilityDO capability = new ProjectCapabilityDO();
        capability.setId(50L);
        capability.setProjectId(20L);
        capability.setCapabilityCode(ProjectAppCredentialService.CAPABILITY_INSTORE);
        capability.setStatus(status);
        return capability;
    }

    private void assertAuthInvalid() {
        ServiceException failure = catchThrowableOfType(
                () -> service.authenticate(APP_ID, 1), ServiceException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
    }
}
