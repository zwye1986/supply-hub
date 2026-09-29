package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.module.supplychain.infrastructure.auth.AppKeySecretIssuer;
import com.aifuli.module.supplychain.infrastructure.auth.ProjectAppAuthProperties;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectApplicationDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAppCredentialAuditDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAppCredentialVersionDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectApplicationMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectAppCredentialVersionMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectAppCredentialAuditMapper;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 项目凭证发码、轮换、吊销状态机；见 docs/superpowers/specs/2026-09-29-access-config-backend-impl-design.md#32-管理服务。 */
class ProjectAppCredentialAdminServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 12, 0);
    private final BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
    private final ProjectApplicationMapper applicationMapper = mock(ProjectApplicationMapper.class);
    private final ProjectAppCredentialVersionMapper credentialMapper = mock(ProjectAppCredentialVersionMapper.class);
    private final ProjectAppCredentialAuditMapper auditMapper = mock(ProjectAppCredentialAuditMapper.class);
    private final AppKeySecretIssuer issuer = mock(AppKeySecretIssuer.class);
    private final UlidGenerator ulidGenerator = mock(UlidGenerator.class);
    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private ProjectAppCredentialAdminService service;

    @BeforeEach
    void setUp() {
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();
        service = new ProjectAppCredentialAdminService(projectMapper, applicationMapper,
                credentialMapper, auditMapper, issuer, ulidGenerator, databaseClock, properties);
        BuyerProjectDO project = new BuyerProjectDO();
        project.setId(20L);
        project.setProjectCode("PRJ_01");
        project.setStatus(BuyerProjectStatus.ACTIVE.getCode());
        when(projectMapper.selectByProjectCodeForUpdate("PRJ_01")).thenReturn(project);
        when(databaseClock.now()).thenReturn(NOW);
    }

    @Test
    void enableCreatesStableApplicationAndCurrentCredential() throws Exception {
        when(applicationMapper.selectByProjectId(20L)).thenReturn(null);
        when(ulidGenerator.next()).thenReturn("01TESTULID0000000000000000");
        when(issuer.issue("AP_01TESTULID0000000000000000", 1))
                .thenReturn(new AppKeySecretIssuer.IssueMaterial("only-once-key", "v1:sealed", "only****-key"));
        when(applicationMapper.insert(any(ProjectApplicationDO.class))).thenAnswer(inv -> {
            ProjectApplicationDO app = inv.getArgument(0);
            app.setId(30L);
            return 1;
        });
        when(credentialMapper.insert(any(ProjectAppCredentialVersionDO.class))).thenReturn(1);
        when(auditMapper.insert(any(ProjectAppCredentialAuditDO.class))).thenReturn(1);

        ProjectAppCredentialAdminService.IssuedCredential result = service.enable("PRJ_01", 7L);

        assertThat(result.appId()).isEqualTo("AP_01TESTULID0000000000000000");
        assertThat(result.keyVersion()).isEqualTo(1);
        assertThat(result.appKey()).isEqualTo("only-once-key");
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("v1:sealed");
        assertThat(result.toString()).doesNotContain("only-once-key");
        verify(applicationMapper).insert(any(ProjectApplicationDO.class));
        verify(credentialMapper).insert(any(ProjectAppCredentialVersionDO.class));
        verify(auditMapper).insert(any(ProjectAppCredentialAuditDO.class));
    }

    @Test
    void enableRejectsSecondApplicationWithoutIssuingNewSecret() {
        when(applicationMapper.selectByProjectId(20L)).thenReturn(application());

        assertThatThrownBy(() -> service.enable("PRJ_01", 7L)).isInstanceOf(RuntimeException.class);
        verify(issuer, never()).issue(any(), any(Integer.class));
    }

    @Test
    void rotateRevokesOldGraceAndKeepsCurrentDuringGraceWindow() {
        ProjectApplicationDO app = application();
        when(applicationMapper.selectByProjectIdForUpdate(20L)).thenReturn(app);
        ProjectAppCredentialVersionDO current = credential(2, "CURRENT", "CURRENT");
        ProjectAppCredentialVersionDO oldGrace = credential(1, "GRACE", "GRACE");
        when(credentialMapper.selectByApplicationId(30L)).thenReturn(List.of(oldGrace, current));
        when(issuer.issue("AP_FIXED", 3))
                .thenReturn(new AppKeySecretIssuer.IssueMaterial("new-key", "v1:new", "new****-key"));
        when(credentialMapper.updateById(any(ProjectAppCredentialVersionDO.class))).thenReturn(1);
        when(credentialMapper.insert(any(ProjectAppCredentialVersionDO.class))).thenReturn(1);
        when(auditMapper.insert(any(ProjectAppCredentialAuditDO.class))).thenReturn(1);

        ProjectAppCredentialAdminService.IssuedCredential result = service.rotate("PRJ_01", 7L);

        assertThat(result.keyVersion()).isEqualTo(3);
        assertThat(oldGrace.getStatus()).isEqualTo("REVOKED");
        assertThat(oldGrace.getActiveSlot()).isNull();
        assertThat(current.getStatus()).isEqualTo("GRACE");
        assertThat(current.getActiveSlot()).isEqualTo("GRACE");
        assertThat(current.getValidTo()).isEqualTo(NOW.plusHours(24));
        verify(credentialMapper).insert(any(ProjectAppCredentialVersionDO.class));
        verify(auditMapper).insert(any(ProjectAppCredentialAuditDO.class));
    }

    @Test
    void revokeClearsActiveSlotsAndRequiresExplicitConfirmation() {
        ProjectApplicationDO app = application();
        when(applicationMapper.selectByProjectIdForUpdate(20L)).thenReturn(app);
        ProjectAppCredentialVersionDO current = credential(2, "CURRENT", "CURRENT");
        ProjectAppCredentialVersionDO grace = credential(1, "GRACE", "GRACE");
        when(credentialMapper.selectByApplicationId(30L)).thenReturn(List.of(grace, current));
        when(credentialMapper.updateById(any(ProjectAppCredentialVersionDO.class))).thenReturn(1);
        when(auditMapper.insert(any(ProjectAppCredentialAuditDO.class))).thenReturn(1);

        assertThatThrownBy(() -> service.revoke("PRJ_01", false, "停用", 7L))
                .isInstanceOf(RuntimeException.class);
        service.revoke("PRJ_01", true, "停用", 7L);

        assertThat(current.getStatus()).isEqualTo("REVOKED");
        assertThat(grace.getStatus()).isEqualTo("REVOKED");
        assertThat(current.getActiveSlot()).isNull();
        assertThat(grace.getActiveSlot()).isNull();
        verify(auditMapper).insert(any(ProjectAppCredentialAuditDO.class));
    }

    private static ProjectApplicationDO application() {
        ProjectApplicationDO app = new ProjectApplicationDO();
        app.setId(30L);
        app.setAppId("AP_FIXED");
        app.setProjectId(20L);
        app.setStatus((short) 10);
        return app;
    }

    private static ProjectAppCredentialVersionDO credential(int version, String status, String slot) {
        ProjectAppCredentialVersionDO credential = new ProjectAppCredentialVersionDO();
        credential.setId((long) version);
        credential.setApplicationId(30L);
        credential.setKeyVersion(version);
        credential.setStatus(status);
        credential.setActiveSlot(slot);
        credential.setValidFrom(NOW.minusDays(1));
        return credential;
    }
}
