package com.aifuli.module.supplychain.tradecore.buyer.domain;

import java.util.Collection;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PARENT_INVALID;

/**
 * 分销商组织树纯函数：等级 1–3、上级必须刚好高一级、禁止成环，已有子节点时新等级必须仍能当合法上级。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#61-组织树}。
 */
public final class BuyerHierarchy {
    private BuyerHierarchy() {}

    public record ParentRef(Long id, int distributionLevel) {}
    public record Result(int level, Long parentBuyerId) {}

    public static Result validate(int distributionLevel, Long parentBuyerId, ParentRef parent) {
        if (distributionLevel < 1 || distributionLevel > 3) {
            throw exception(BUYER_PARENT_INVALID);
        }
        if (distributionLevel == 1) {
            if (parentBuyerId != null) {
                throw exception(BUYER_PARENT_INVALID);
            }
            return new Result(1, null);
        }
        if (parentBuyerId == null || parent == null) {
            throw exception(BUYER_PARENT_INVALID);
        }
        if (distributionLevel == 2 && parent.distributionLevel() != 1) {
            throw exception(BUYER_PARENT_INVALID);
        }
        if (distributionLevel == 3 && parent.distributionLevel() != 2) {
            throw exception(BUYER_PARENT_INVALID);
        }
        return new Result(distributionLevel, parentBuyerId);
    }

    public static void rejectCycle(Long currentBuyerId, Long newParentId, Collection<Long> descendantIds) {
        if (newParentId == null) {
            return;
        }
        if (newParentId.equals(currentBuyerId) || descendantIds.contains(newParentId)) {
            throw exception(BUYER_PARENT_INVALID);
        }
    }

    /** 已有子节点时，新等级必须仍能当这些子节点的合法上级。 */
    public static void rejectLevelConflictWithChildren(int newLevel, List<Integer> childLevels) {
        for (int child : childLevels) {
            if (child == newLevel + 1) {
                continue;
            }
            throw exception(BUYER_PARENT_INVALID);
        }
    }
}
