package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.tradecore.profile.domain.ProjectType;
import com.aifuli.module.supplychain.tradecore.profile.domain.SettlementPartyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_STATUS_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_BUYER_IMMUTABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_PERIOD_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_PLATFORM_SETTLEMENT_NOT_ENABLED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_STATUS_INVALID;

/**
 * 项目管理端档案命令：项目创建后分销商永久不变；结算方仅 DISTRIBUTOR；
 * 档案整档更新可选变更状态，但仍必须经过项目状态机。不返回 app 密钥。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#71-档案}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAdminCommandService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    /** 目录模式：MANAGED中台管理，INDEPENDENT项目独立。本批恒为 MANAGED。 */
    private static final String CATALOG_MODE_MANAGED = "MANAGED";

    private final BuyerProjectMapper projectMapper;
    private final BuyerMapper buyerMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final ScopedBusinessCodeService scopedBusinessCodeService;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String create(CreateProjectCommand command) {
        log.info("创建项目档案 projectName={} buyerCode={} status={} operatorId={}",
                command.projectName(), command.buyerCode(), command.status(), command.operatorId());
        validatePeriod(command.startDate(), command.endDate());
        String settlementParty = resolveSettlementParty(command.settlementPartyType());
        ProjectType projectType = ClosedEnums.parse(ProjectType.class, command.projectType());
        BuyerDO buyer = requireActiveBuyer(command.buyerCode());
        BuyerProjectStatus status = resolveCreateStatus(command.status());
        LocalDateTime now = databaseClock.now();
        String projectCode = scopedBusinessCodeService.allocateChild(ScopedBusinessCodeService.Kind.PRJ,
                buyer.getId(), buyer.getCodeScopeNo(), null, command.operatorId()).code();
        BuyerProjectDO project = BuyerProjectDO.builder()
                .projectCode(projectCode)
                .projectName(command.projectName())
                .buyerId(buyer.getId())
                .catalogMode(CATALOG_MODE_MANAGED)
                .projectType(projectType == null ? null : projectType.name())
                .startDate(command.startDate())
                .endDate(command.endDate())
                .settlementPartyType(settlementParty)
                .description(command.description())
                .status(status.getCode())
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        projectMapper.insert(project);
        return projectCode;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void update(UpdateProjectCommand command) {
        validatePeriod(command.startDate(), command.endDate());
        String settlementParty = resolveSettlementParty(command.settlementPartyType());
        ProjectType projectType = ClosedEnums.parse(ProjectType.class, command.projectType());
        BuyerProjectDO project = requireForUpdate(command.projectCode());
        BuyerProjectStatus currentStatus = BuyerProjectStatus.fromCode(project.getStatus());
        BuyerProjectStatus targetStatus = resolveUpdateStatus(command.status(), currentStatus);
        log.info("更新项目档案 projectName={} buyerCode={} status={} operatorId={}",
                command.projectName(), command.buyerCode(),
                targetStatus, command.operatorId());
        if (command.buyerCode() != null) {
            BuyerDO targetBuyer = requireBuyer(command.buyerCode());
            if (!targetBuyer.getId().equals(project.getBuyerId())) {
                throw exception(PROJECT_BUYER_IMMUTABLE);
            }
        }
        LocalDateTime now = databaseClock.now();
        project.setProjectName(command.projectName());
        project.setProjectType(projectType == null ? null : projectType.name());
        project.setStartDate(command.startDate());
        project.setEndDate(command.endDate());
        project.setSettlementPartyType(settlementParty);
        project.setDescription(command.description());
        project.setStatus(targetStatus.getCode());
        project.setUpdatedBy(command.operatorId());
        project.setUpdatedAt(now);
        if (projectMapper.updateById(project) != 1) {
            throw exception(PROJECT_NOT_FOUND);
        }
    }

    /**
     * PATCH 状态：走 {@link BuyerProjectStatus#canTransitionTo}；草稿不能直接结束。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#42-其它封闭枚举}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeStatus(String projectCode, BuyerProjectStatus target, Long operatorId) {
        if (target == null) {
            throw exception(ENUM_NOT_OPEN);
        }
        BuyerProjectDO project = requireForUpdate(projectCode);
        BuyerDO owner = project.getBuyerId() == null ? null : buyerMapper.selectById(project.getBuyerId());
        log.info("变更项目状态 projectName={} buyerCode={} status={} operatorId={}",
                project.getProjectName(), owner == null ? null : owner.getBuyerCode(), target, operatorId);
        BuyerProjectStatus current = BuyerProjectStatus.fromCode(project.getStatus());
        if (!current.canTransitionTo(target)) {
            throw exception(PROJECT_STATUS_INVALID);
        }
        project.setStatus(target.getCode());
        project.setUpdatedBy(operatorId);
        project.setUpdatedAt(databaseClock.now());
        if (projectMapper.updateById(project) != 1) {
            throw exception(PROJECT_STATUS_INVALID);
        }
    }

    private BuyerProjectDO requireForUpdate(String projectCode) {
        BuyerProjectDO project = projectMapper.selectByProjectCodeForUpdate(projectCode);
        if (project == null) {
            throw exception(PROJECT_NOT_FOUND);
        }
        return project;
    }

    /**
     * 整档更新的状态字段为空时保持当前状态；显式状态仍必须经过同一套项目状态机。
     * 相同状态视为幂等保存，避免编辑页面回显当前状态后无法再次保存档案。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#71-档案}。
     */
    private static BuyerProjectStatus resolveUpdateStatus(String rawStatus, BuyerProjectStatus currentStatus) {
        if (isBlank(rawStatus)) {
            return currentStatus;
        }
        BuyerProjectStatus targetStatus;
        try {
            targetStatus = BuyerProjectStatus.valueOf(rawStatus.trim());
        } catch (IllegalArgumentException ex) {
            ServiceException invalid = exception(ENUM_NOT_OPEN);
            invalid.initCause(ex);
            throw invalid;
        }
        if (targetStatus != currentStatus && !currentStatus.canTransitionTo(targetStatus)) {
            throw exception(PROJECT_STATUS_INVALID);
        }
        return targetStatus;
    }

    private BuyerDO requireActiveBuyer(String buyerCode) {
        BuyerDO buyer = requireBuyer(buyerCode);
        requireActive(buyer);
        return buyer;
    }

    private BuyerDO requireBuyer(String buyerCode) {
        if (isBlank(buyerCode)) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode.trim());
        if (buyer == null) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        return buyer;
    }

    private static void requireActive(BuyerDO buyer) {
        if (buyer.getStatus() == null || BuyerStatus.fromCode(buyer.getStatus()) != BuyerStatus.ACTIVE) {
            throw exception(BUYER_STATUS_CONFLICT);
        }
    }

    /**
     * 创建只允许草稿或启用；空视为草稿。启用时 Buyer 必须已是 ACTIVE（由 {@link #requireActiveBuyer} 保证）。
     */
    private static BuyerProjectStatus resolveCreateStatus(String raw) {
        if (isBlank(raw)) {
            return BuyerProjectStatus.DRAFT;
        }
        BuyerProjectStatus status;
        try {
            status = BuyerProjectStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            ServiceException invalid = exception(ENUM_NOT_OPEN);
            invalid.initCause(ex);
            throw invalid;
        }
        if (status != BuyerProjectStatus.DRAFT && status != BuyerProjectStatus.ACTIVE) {
            throw exception(PROJECT_STATUS_INVALID);
        }
        return status;
    }

    /**
     * 结算方缺省 DISTRIBUTOR；PLATFORM 未开放；未知走 ENUM_NOT_OPEN。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#71-档案}。
     */
    static String resolveSettlementParty(String raw) {
        if (isBlank(raw)) {
            return SettlementPartyType.DISTRIBUTOR.name();
        }
        SettlementPartyType party = ClosedEnums.parseRequired(SettlementPartyType.class, raw);
        if (party == SettlementPartyType.PLATFORM) {
            throw exception(PROJECT_PLATFORM_SETTLEMENT_NOT_ENABLED);
        }
        return party.name();
    }

    static void validatePeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw exception(PROJECT_PERIOD_INVALID);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record CreateProjectCommand(
            String projectName,
            String buyerCode,
            String projectType,
            LocalDate startDate,
            LocalDate endDate,
            String settlementPartyType,
            String description,
            String status,
            Long operatorId) {
    }

    public record UpdateProjectCommand(
            String projectCode,
            String projectName,
            String buyerCode,
            String projectType,
            LocalDate startDate,
            LocalDate endDate,
            String settlementPartyType,
            String description,
            String status,
            Long operatorId) {
    }
}
