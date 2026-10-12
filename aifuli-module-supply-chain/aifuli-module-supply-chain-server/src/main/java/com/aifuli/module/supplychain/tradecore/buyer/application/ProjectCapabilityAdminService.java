package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.infrastructure.auth.ProjectAuthStatus;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectCapabilityDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectCapabilityMapper;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectCapabilityAuditDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectCapabilityAuditMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.*;

/**
 * 项目业务接入授权，与应用凭证分离。只写标准商品能力；关闭保留历史行供摘要查询授权。
 * 项目→能力当前读锁序、版本 CAS 与追加审计同事务，不调用供应商、不改订单或资金。
 * 设计见 docs/superpowers/specs/2026-10-12-project-standard-product-capability-design.md#2-持久化与并发。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectCapabilityAdminService {
    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String STANDARD_PRODUCT = ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT;
    private static final String O2O = "O2O";
    private static final String INSTORE = "INSTORE";
    private static final List<String> CAPABILITIES = List.of(STANDARD_PRODUCT, O2O, INSTORE);
    private final BuyerProjectMapper projectMapper;
    private final ProjectCapabilityMapper capabilityMapper;
    private final ProjectCapabilityAuditMapper auditMapper;
    private final SupplyChainDatabaseClock databaseClock;

    /** 三类能力只读展示；旧能力无审计时不编造开通人。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public List<CapabilityView> list(String projectCode) {
        BuyerProjectDO project = projectMapper.selectByProjectCode(projectCode);
        if (project == null) throw exception(PROJECT_NOT_FOUND);
        return CAPABILITIES.stream().map(code -> {
            ProjectCapabilityDO row = capabilityMapper.selectByProjectIdAndCode(project.getId(), code);
            ProjectCapabilityAuditDO audit = auditMapper.selectLatest(project.getId(), code);
            CapabilityState state = row == null ? CapabilityState.NOT_ENABLED : stateOf(row.getStatus());
            return new CapabilityView(code, state.name(), row == null ? null : row.getVersion(),
                    audit == null ? null : audit.getOperatorId(), audit == null ? null : audit.getOccurredAt());
        }).toList();
    }

    /** 开启只要求项目 ACTIVE；商品、价格、路由与凭证准入继续由各自边界负责。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ChangeResult enable(String projectCode, Long expectedVersion, Long operatorId) {
        return change(projectCode, expectedVersion, ProjectAuthStatus.ENABLED, null, operatorId);
    }

    /** 关闭不限项目状态，但必须确认并说明原因；无能力行时不制造历史查单权。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ChangeResult disable(String projectCode, Long expectedVersion, boolean confirmed, String reason, Long operatorId) {
        if (!confirmed || reason == null || reason.isBlank() || reason.trim().length() > 256) {
            throw exception(PROJECT_CAPABILITY_CONFIRMATION_REQUIRED);
        }
        return change(projectCode, expectedVersion, ProjectAuthStatus.SUSPENDED, reason.trim(), operatorId);
    }

    private ChangeResult change(String projectCode, Long expectedVersion, ProjectAuthStatus target,
                                String reason, Long operatorId) {
        Objects.requireNonNull(operatorId, "项目能力操作人不能为空");
        BuyerProjectDO project = projectMapper.selectByProjectCodeForUpdate(projectCode);
        if (project == null) throw exception(PROJECT_NOT_FOUND);
        if (target == ProjectAuthStatus.ENABLED && !Objects.equals(project.getStatus(), BuyerProjectStatus.ACTIVE.getCode())) {
            throw exception(PROJECT_STATUS_INVALID);
        }
        ProjectCapabilityDO row = capabilityMapper.selectByProjectIdAndCodeForUpdate(project.getId(), STANDARD_PRODUCT);
        if (expectedVersion != null && expectedVersion < 0
                || !Objects.equals(expectedVersion, row == null ? null : row.getVersion())) {
            throw exception(PROJECT_CAPABILITY_VERSION_CONFLICT);
        }
        if ((row == null && target == ProjectAuthStatus.SUSPENDED)
                || (row != null && target.matches(row.getStatus()))) {
            log.debug("[projectCapabilityChange][项目接入能力无变化] projectCode={}, target={}, operatorId={}",
                    projectCode, target, operatorId);
            return new ChangeResult(false);
        }
        if (row != null) stateOf(row.getStatus());
        Short before = row == null ? null : row.getStatus();
        long version = row == null ? 0L : Math.addExact(row.getVersion(), 1L);
        LocalDateTime now = databaseClock.now();
        if (row == null) {
            row = new ProjectCapabilityDO();
            row.setProjectId(project.getId());
            row.setCapabilityCode(STANDARD_PRODUCT);
            row.setStatus(target.code());
            row.setVersion(version);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            requireOne(capabilityMapper.insert(row));
        } else {
            requireOne(capabilityMapper.update(null, new LambdaUpdateWrapper<ProjectCapabilityDO>()
                    .eq(ProjectCapabilityDO::getId, row.getId()).eq(ProjectCapabilityDO::getVersion, expectedVersion)
                    .set(ProjectCapabilityDO::getStatus, target.code()).set(ProjectCapabilityDO::getVersion, version)
                    .set(ProjectCapabilityDO::getUpdatedAt, now)));
        }
        ProjectCapabilityAuditDO audit = new ProjectCapabilityAuditDO();
        audit.setProjectId(project.getId());
        audit.setCapabilityCode(STANDARD_PRODUCT);
        audit.setBeforeStatus(before);
        audit.setAfterStatus(target.code());
        audit.setCapabilityVersion(version);
        audit.setOperatorId(operatorId);
        audit.setReason(reason);
        audit.setOccurredAt(now);
        requireOne(auditMapper.insert(audit));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("[projectCapabilityChange][项目接入能力变更已提交] projectCode={}, capabilityCode={}, status={}, version={}, operatorId={}",
                        projectCode, STANDARD_PRODUCT, target.code(), version, operatorId);
            }
        });
        return new ChangeResult(true);
    }

    private static CapabilityState stateOf(Short status) {
        if (ProjectAuthStatus.ENABLED.matches(status)) return CapabilityState.ENABLED;
        if (ProjectAuthStatus.SUSPENDED.matches(status)) return CapabilityState.DISABLED;
        throw new IllegalStateException("项目能力状态不符合数据库契约");
    }

    private static void requireOne(int affected) {
        if (affected != 1) throw exception(PROJECT_CAPABILITY_VERSION_CONFLICT);
    }

    private enum CapabilityState { NOT_ENABLED, ENABLED, DISABLED }

    /** 管理展示状态不等于商品可售；version 无行为空，用于首次开通的并发比较。 */
    public record CapabilityView(String capabilityCode, String status, Long version,
                                 Long lastOperatorId,
                                 @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime lastChangedAt) { }
    public record ChangeResult(boolean changed) { }
}
