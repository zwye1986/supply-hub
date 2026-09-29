package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.module.supplychain.infrastructure.auth.AppKeySecretIssuer;
import com.aifuli.module.supplychain.infrastructure.auth.ProjectAppAuthProperties;
import com.aifuli.module.supplychain.infrastructure.auth.ProjectAppCredentialStatus;
import com.aifuli.module.supplychain.infrastructure.auth.ProjectAuthStatus;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectApplicationDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAppCredentialAuditDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAppCredentialVersionDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectAppCredentialAuditMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectApplicationMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectAppCredentialVersionMapper;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_APP_ALREADY_ENABLED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_APP_CONFIRMATION_REQUIRED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_APP_CREDENTIAL_STATE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_APP_NOT_ENABLED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_STATUS_INVALID;

/**
 * 项目应用发码与凭证版本状态机。始终按项目→应用→凭证顺序加锁；明文仅在一次性响应中出现。
 * 设计见 docs/superpowers/specs/2026-09-29-access-config-backend-impl-design.md#32-管理服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAppCredentialAdminService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String APP_ID_PREFIX = "AP_";

    private final BuyerProjectMapper projectMapper;
    private final ProjectApplicationMapper applicationMapper;
    private final ProjectAppCredentialVersionMapper credentialMapper;
    private final ProjectAppCredentialAuditMapper auditMapper;
    private final AppKeySecretIssuer issuer;
    private final UlidGenerator ulidGenerator;
    private final SupplyChainDatabaseClock databaseClock;
    private final ProjectAppAuthProperties properties;

    /** 首次开通项目唯一应用；重复开通必须失败，不得再次回显旧密钥。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public IssuedCredential enable(String projectCode, Long operatorId) {
        log.info("[project-app] 开通入口 projectCode={}, operatorId={}", projectCode, operatorId);
        BuyerProjectDO project = requireActiveProjectForUpdate(projectCode);
        if (applicationMapper.selectByProjectId(project.getId()) != null) {
            throw exception(PROJECT_APP_ALREADY_ENABLED);
        }
        LocalDateTime now = databaseClock.now();
        String appId = APP_ID_PREFIX + ulidGenerator.next();
        ProjectApplicationDO app = new ProjectApplicationDO();
        app.setProjectId(project.getId());
        app.setAppId(appId);
        app.setStatus(ProjectAuthStatus.ENABLED.code());
        app.setVersion(0L);
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
        requireOne(applicationMapper.insert(app));
        AppKeySecretIssuer.IssueMaterial material = issuer.issue(appId, 1);
        insertCredential(app.getId(), 1, material, now);
        appendAudit(project.getId(), app.getId(), CredentialAction.ENABLE, 1, operatorId, null, now);
        log.info("[project-app] 开通完成 projectCode={}, appId={}, keyVersion={}, operatorId={}",
                projectCode, appId, 1, operatorId);
        return new IssuedCredential(appId, 1, material.appKey(), material.maskedHint());
    }

    /** 旧 GRACE 先吊销，再将 CURRENT 降为 GRACE，最后插入新 CURRENT，满足活动槽唯一约束。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public IssuedCredential rotate(String projectCode, Long operatorId) {
        log.info("[project-app] 轮换入口 projectCode={}, operatorId={}", projectCode, operatorId);
        BuyerProjectDO project = requireActiveProjectForUpdate(projectCode);
        ProjectApplicationDO app = requireApplicationForUpdate(project.getId());
        List<ProjectAppCredentialVersionDO> credentials = credentialMapper.selectByApplicationId(app.getId());
        LocalDateTime now = databaseClock.now();
        Duration graceWindow = properties.getGraceWindow();
        if (graceWindow == null || graceWindow.isNegative() || graceWindow.isZero()) {
            throw new IllegalStateException("aifuli.supply-chain.auth.grace-window 必须为正数");
        }
        int maxVersion = credentials.stream().map(ProjectAppCredentialVersionDO::getKeyVersion)
                .max(Comparator.naturalOrder()).orElse(0);
        if (maxVersion == Integer.MAX_VALUE) {
            throw exception(PROJECT_APP_CREDENTIAL_STATE_INVALID);
        }
        ProjectAppCredentialVersionDO current = uniqueActive(credentials, ProjectAppCredentialStatus.CURRENT);
        ProjectAppCredentialVersionDO oldGrace = uniqueActive(credentials, ProjectAppCredentialStatus.GRACE);
        if (oldGrace != null) {
            oldGrace.setStatus(ProjectAppCredentialStatus.REVOKED.name());
            oldGrace.setActiveSlot(null);
            requireOne(credentialMapper.updateById(oldGrace));
        }
        if (current != null) {
            current.setStatus(ProjectAppCredentialStatus.GRACE.name());
            current.setActiveSlot(ProjectAppCredentialStatus.GRACE.name());
            current.setValidTo(now.plus(graceWindow));
            requireOne(credentialMapper.updateById(current));
        }
        int nextVersion = maxVersion + 1;
        AppKeySecretIssuer.IssueMaterial material = issuer.issue(app.getAppId(), nextVersion);
        insertCredential(app.getId(), nextVersion, material, now);
        appendAudit(project.getId(), app.getId(), CredentialAction.ROTATE, nextVersion, operatorId, null, now);
        log.info("[project-app] 轮换完成 projectCode={}, appId={}, keyVersion={}, operatorId={}",
                projectCode, app.getAppId(), nextVersion, operatorId);
        return new IssuedCredential(app.getAppId(), nextVersion, material.appKey(), material.maskedHint());
    }

    /** 吊销全部活动凭证；保留 appId 与版本行，后续由管理员轮换恢复。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void revoke(String projectCode, boolean confirmed, String reason, Long operatorId) {
        log.info("[project-app] 吊销入口 projectCode={}, confirmed={}, operatorId={}",
                projectCode, confirmed, operatorId);
        if (!confirmed || reason == null || reason.isBlank() || reason.trim().length() > 256) {
            throw exception(PROJECT_APP_CONFIRMATION_REQUIRED);
        }
        BuyerProjectDO project = requireActiveProjectForUpdate(projectCode);
        ProjectApplicationDO app = requireApplicationForUpdate(project.getId());
        for (ProjectAppCredentialVersionDO credential : credentialMapper.selectByApplicationId(app.getId())) {
            if (ProjectAppCredentialStatus.isUsable(credential.getStatus())) {
                credential.setStatus(ProjectAppCredentialStatus.REVOKED.name());
                credential.setActiveSlot(null);
                requireOne(credentialMapper.updateById(credential));
            }
        }
        appendAudit(project.getId(), app.getId(), CredentialAction.REVOKE, null,
                operatorId, reason.trim(), databaseClock.now());
        log.info("[project-app] 吊销完成 projectCode={}, appId={}, operatorId={}, reasonLength={}",
                projectCode, app.getAppId(), operatorId, reason.length());
    }

    /** 列表只返回脱敏提示和状态，不查询或序列化 secretRef。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public ApplicationCredentials listCredentials(String projectCode) {
        BuyerProjectDO project = projectMapper.selectByProjectCode(projectCode);
        if (project == null) {
            throw exception(PROJECT_NOT_FOUND);
        }
        ProjectApplicationDO app = applicationMapper.selectByProjectId(project.getId());
        if (app == null) {
            return new ApplicationCredentials(null, List.of());
        }
        List<CredentialView> credentials = credentialMapper.selectByApplicationId(app.getId()).stream()
                .map(row -> new CredentialView(row.getKeyVersion(), row.getStatus(), row.getMaskedHint(),
                        row.getValidFrom(), row.getValidTo())).toList();
        return new ApplicationCredentials(app.getAppId(), credentials);
    }

    private BuyerProjectDO requireActiveProjectForUpdate(String projectCode) {
        BuyerProjectDO project = projectMapper.selectByProjectCodeForUpdate(projectCode);
        if (project == null) {
            throw exception(PROJECT_NOT_FOUND);
        }
        if (project.getStatus() == null || project.getStatus() != BuyerProjectStatus.ACTIVE.getCode()) {
            throw exception(PROJECT_STATUS_INVALID);
        }
        return project;
    }

    private ProjectApplicationDO requireApplicationForUpdate(Long projectId) {
        ProjectApplicationDO app = applicationMapper.selectByProjectIdForUpdate(projectId);
        if (app == null || !ProjectAuthStatus.ENABLED.matches(app.getStatus())) {
            throw exception(PROJECT_APP_NOT_ENABLED);
        }
        return app;
    }

    private static ProjectAppCredentialVersionDO uniqueActive(
            List<ProjectAppCredentialVersionDO> credentials, ProjectAppCredentialStatus expected) {
        List<ProjectAppCredentialVersionDO> matches = credentials.stream()
                .filter(row -> expected.name().equals(row.getStatus())).toList();
        if (matches.size() > 1 || matches.stream().anyMatch(row -> !expected.name().equals(row.getActiveSlot()))) {
            throw exception(PROJECT_APP_CREDENTIAL_STATE_INVALID);
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private void insertCredential(Long applicationId, int version,
                                  AppKeySecretIssuer.IssueMaterial material, LocalDateTime now) {
        ProjectAppCredentialVersionDO credential = new ProjectAppCredentialVersionDO();
        credential.setApplicationId(applicationId);
        credential.setKeyVersion(version);
        credential.setSecretRef(material.secretRef());
        credential.setMaskedHint(material.maskedHint());
        credential.setStatus(ProjectAppCredentialStatus.CURRENT.name());
        credential.setActiveSlot(ProjectAppCredentialStatus.CURRENT.name());
        credential.setValidFrom(now);
        credential.setCreatedAt(now);
        requireOne(credentialMapper.insert(credential));
    }

    private void appendAudit(Long projectId, Long applicationId, CredentialAction action,
                             Integer keyVersion, Long operatorId, String reason, LocalDateTime now) {
        if (operatorId == null) {
            throw new IllegalArgumentException("项目凭证操作人不能为空");
        }
        ProjectAppCredentialAuditDO audit = new ProjectAppCredentialAuditDO();
        audit.setProjectId(projectId);
        audit.setApplicationId(applicationId);
        audit.setAction(action.name());
        audit.setKeyVersion(keyVersion);
        audit.setOperatorId(operatorId);
        audit.setReason(reason);
        audit.setOccurredAt(now);
        requireOne(auditMapper.insert(audit));
    }

    private enum CredentialAction {
        ENABLE, ROTATE, REVOKE
    }

    private static void requireOne(int affected) {
        if (affected != 1) {
            throw exception(PROJECT_APP_CREDENTIAL_STATE_INVALID);
        }
    }

    /** 一次性响应只含明文与脱敏提示，日志输出隐藏 appKey。 */
    public record IssuedCredential(String appId, int keyVersion, String appKey, String maskedHint) {
        @Override
        public String toString() {
            return "IssuedCredential[appId=" + appId + ", keyVersion=" + keyVersion
                    + ", maskedHint=" + maskedHint + ']';
        }
    }

    public record CredentialView(Integer keyVersion, String status, String maskedHint,
                                 LocalDateTime validFrom, LocalDateTime validTo) {
    }

    public record ApplicationCredentials(String appId, List<CredentialView> credentials) {
    }
}
