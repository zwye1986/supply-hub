package com.aifuli.module.supplychain.tradecore.buyer.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 采购项目状态机测试，覆盖设计允许的全部生命周期边。 */
class BuyerProjectStatusTest {

    @Test
    void allowsOnlyDeclaredProjectLifecycleTransitions() {
        assertThat(BuyerProjectStatus.DRAFT.canTransitionTo(BuyerProjectStatus.ACTIVE)).isTrue();
        assertThat(BuyerProjectStatus.DRAFT.canTransitionTo(BuyerProjectStatus.ARCHIVED)).isTrue();
        assertThat(BuyerProjectStatus.ACTIVE.canTransitionTo(BuyerProjectStatus.SUSPENDED)).isTrue();
        assertThat(BuyerProjectStatus.SUSPENDED.canTransitionTo(BuyerProjectStatus.ACTIVE)).isTrue();
        assertThat(BuyerProjectStatus.SUSPENDED.canTransitionTo(BuyerProjectStatus.ARCHIVED)).isTrue();

        assertThat(BuyerProjectStatus.DRAFT.canTransitionTo(BuyerProjectStatus.SUSPENDED)).isFalse();
        assertThat(BuyerProjectStatus.ACTIVE.canTransitionTo(BuyerProjectStatus.ARCHIVED)).isFalse();
        assertThat(BuyerProjectStatus.ARCHIVED.canTransitionTo(BuyerProjectStatus.ACTIVE)).isFalse();
        assertThat(BuyerProjectStatus.ACTIVE.canTransitionTo(BuyerProjectStatus.ACTIVE)).isFalse();
    }

    @Test
    void closedIsRecoverableAndDraftCannotClose() {
        assertThat(BuyerProjectStatus.fromCode((short) 30)).isEqualTo(BuyerProjectStatus.CLOSED);
        assertThat(BuyerProjectStatus.ACTIVE.canTransitionTo(BuyerProjectStatus.CLOSED)).isTrue();
        assertThat(BuyerProjectStatus.SUSPENDED.canTransitionTo(BuyerProjectStatus.CLOSED)).isTrue();
        assertThat(BuyerProjectStatus.CLOSED.canTransitionTo(BuyerProjectStatus.ACTIVE)).isTrue();
        assertThat(BuyerProjectStatus.CLOSED.canTransitionTo(BuyerProjectStatus.SUSPENDED)).isTrue();
        assertThat(BuyerProjectStatus.CLOSED.canTransitionTo(BuyerProjectStatus.ARCHIVED)).isFalse();
        assertThat(BuyerProjectStatus.DRAFT.canTransitionTo(BuyerProjectStatus.CLOSED)).isFalse();
        assertThat(BuyerProjectStatus.DRAFT.canTransitionTo(BuyerProjectStatus.ACTIVE)).isTrue();
        assertThat(BuyerProjectStatus.DRAFT.canTransitionTo(BuyerProjectStatus.ARCHIVED)).isTrue();
    }
}
