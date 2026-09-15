package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND;

/**
 * 采购项目只读访问校验器，服务于 HOME/DETAIL 入口等只读边界。
 *
 * <p>不变量：只放行 {@link BuyerProjectStatus#ACTIVE} 且归属当前采购商的项目。
 * 按项目主键访问时，缺失、非启用或归属不符统一映射 {@code BUYER_AUTH_INVALID}，
 * 避免向到店购入口泄露项目存在性。按项目编码访问时映射 {@code PROJECT_NOT_FOUND}，
 * 供标准履约等编码契约区分“项目不可用”。
 * 详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#9-业务流程}
 * 与 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerProjectAccessResolver {

    private final BuyerProjectMapper projectMapper;

    /**
     * 校验项目存在、启用且属于指定采购商，返回项目快照。
     *
     * <p>只读操作，不推进项目状态；projectId/buyerId 缺失同样返回 001，
     * 避免空主键触发数据库查询。</p>
     */
    public ProjectSnapshot requireActive(Long projectId, Long buyerId) {
        log.info("[SUPPLY_CHAIN][projectAccess] requireActive 入口 projectId={}, buyerId={}",
                projectId, buyerId);
        if (projectId == null || buyerId == null) {
            throw exception(BUYER_AUTH_INVALID);
        }
        BuyerProjectDO project = projectMapper.selectById(projectId);
        if (!isActiveAndMatchesBuyer(project, buyerId)) {
            log.warn("[SUPPLY_CHAIN][projectAccess] 项目不可用或归属不符 projectId={}, buyerId={}",
                    projectId, buyerId);
            throw exception(BUYER_AUTH_INVALID);
        }
        return new ProjectSnapshot(project.getId(), project.getProjectCode(), project.getBuyerId());
    }

    /**
     * 按项目编码校验项目存在、启用且属于指定采购商，供标准履约等编码契约使用。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。
     */
    public ProjectSnapshot requireActiveByCode(String projectCode, Long buyerId) {
        log.info("[SUPPLY_CHAIN][projectAccess] requireActiveByCode 入口 projectCode={}, buyerId={}",
                projectCode, buyerId);
        if (projectCode == null || projectCode.isBlank() || buyerId == null) {
            throw exception(PROJECT_NOT_FOUND);
        }
        BuyerProjectDO project = projectMapper.selectByProjectCode(projectCode);
        if (!isActiveAndMatchesBuyer(project, buyerId)) {
            log.warn("[SUPPLY_CHAIN][projectAccess] 项目不可用或归属不符 projectCode={}, buyerId={}",
                    projectCode, buyerId);
            throw exception(PROJECT_NOT_FOUND);
        }
        return new ProjectSnapshot(project.getId(), project.getProjectCode(), project.getBuyerId());
    }

    private static boolean isActiveAndMatchesBuyer(BuyerProjectDO project, Long buyerId) {
        return project != null
                && project.getStatus() != null
                && project.getStatus() == BuyerProjectStatus.ACTIVE.getCode()
                && Objects.equals(project.getBuyerId(), buyerId);
    }

    /** 项目只读快照；仅暴露入口编排所需的最小字段。 */
    public record ProjectSnapshot(Long projectId, String projectCode, Long buyerId) {
    }
}
