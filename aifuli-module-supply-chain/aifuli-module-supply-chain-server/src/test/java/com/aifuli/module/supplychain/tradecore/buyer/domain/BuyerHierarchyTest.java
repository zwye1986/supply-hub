package com.aifuli.module.supplychain.tradecore.buyer.domain;

import com.aifuli.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PARENT_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分销商组织树：一级无上级、二级挂一级、三级挂二级、禁止成环与子节点等级冲突。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#61-组织树}。
 */
class BuyerHierarchyTest {
    @Test
    void levelOneMustHaveNoParent() {
        assertThat(BuyerHierarchy.validate(1, null, null)).isEqualTo(new BuyerHierarchy.Result(1, null));
        assertThatThrownBy(() -> BuyerHierarchy.validate(1, 9L, parent(1)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_PARENT_INVALID.getCode());
    }

    @Test
    void levelTwoRequiresLevelOneParent() {
        BuyerHierarchy.validate(2, 9L, parent(1));
        assertThatThrownBy(() -> BuyerHierarchy.validate(2, 9L, parent(2)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BuyerHierarchy.validate(2, null, null))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void levelThreeRequiresLevelTwoParentAndForbidsFour() {
        BuyerHierarchy.validate(3, 8L, parent(2));
        assertThatThrownBy(() -> BuyerHierarchy.validate(3, 8L, parent(1)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BuyerHierarchy.validate(4, 8L, parent(3)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void rejectsSelfParent() {
        assertThatThrownBy(() -> BuyerHierarchy.rejectCycle(10L, 10L, List.of()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void rejectsParentThatIsOwnDescendant() {
        // current=10, newParent=11, descendantsOf10 contains 11
        assertThatThrownBy(() -> BuyerHierarchy.rejectCycle(10L, 11L, List.of(11L)))
                .isInstanceOf(ServiceException.class);
        BuyerHierarchy.rejectCycle(10L, 12L, List.of(11L));
    }

    @Test
    void rejectsLevelConflictWithChildren() {
        BuyerHierarchy.rejectLevelConflictWithChildren(1, List.of());
        BuyerHierarchy.rejectLevelConflictWithChildren(1, List.of(2));
        BuyerHierarchy.rejectLevelConflictWithChildren(2, List.of(3));
        assertThatThrownBy(() -> BuyerHierarchy.rejectLevelConflictWithChildren(2, List.of(2)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_PARENT_INVALID.getCode());
    }

    private static BuyerHierarchy.ParentRef parent(int level) {
        return new BuyerHierarchy.ParentRef(1L, level);
    }
}
