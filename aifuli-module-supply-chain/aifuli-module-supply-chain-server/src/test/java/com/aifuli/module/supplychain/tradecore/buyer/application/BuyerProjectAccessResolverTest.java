package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver.ProjectSnapshot;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 采购项目只读校验单元测试：按主键访问只放行启用且归属当前采购商的项目，
 * 项目缺失、停用、归属不符统一返回 001；按编码访问时映射 PROJECT_NOT_FOUND。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#9-业务流程}。</p>
 */
@ExtendWith(MockitoExtension.class)
class BuyerProjectAccessResolverTest {

    private static final Long PROJECT_ID = 101L;
    private static final Long BUYER_ID = 1001L;
    private static final String PROJECT_CODE = "PRJ_101";

    @Mock
    private BuyerProjectMapper projectMapper;

    private BuyerProjectAccessResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new BuyerProjectAccessResolver(projectMapper);
    }

    @Test
    void requireActiveReturnsSnapshotForActiveProjectOfBuyer() {
        when(projectMapper.selectById(PROJECT_ID))
                .thenReturn(project(BuyerProjectStatus.ACTIVE.getCode()));

        ProjectSnapshot snapshot = resolver.requireActive(PROJECT_ID, BUYER_ID);

        assertThat(snapshot).isEqualTo(new ProjectSnapshot(PROJECT_ID, PROJECT_CODE, BUYER_ID));
    }

    @Test
    void requireActiveByCodeReturnsSnapshotForActiveProjectOfBuyer() {
        when(projectMapper.selectByProjectCode(PROJECT_CODE))
                .thenReturn(project(BuyerProjectStatus.ACTIVE.getCode()));

        ProjectSnapshot snapshot = resolver.requireActiveByCode(PROJECT_CODE, BUYER_ID);

        assertThat(snapshot).isEqualTo(new ProjectSnapshot(PROJECT_ID, PROJECT_CODE, BUYER_ID));
    }

    @Test
    void requireActiveByCodeRejectsProjectOfAnotherBuyer() {
        BuyerProjectDO otherBuyerProject = project(BuyerProjectStatus.ACTIVE.getCode());
        otherBuyerProject.setBuyerId(9999L);
        when(projectMapper.selectByProjectCode(PROJECT_CODE)).thenReturn(otherBuyerProject);

        assertThatThrownBy(() -> resolver.requireActiveByCode(PROJECT_CODE, BUYER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND.getCode());
    }

    @Test
    void requireActiveByCodeRejectsBlankCodeWithoutDatabaseAccess() {
        assertThatThrownBy(() -> resolver.requireActiveByCode("  ", BUYER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND.getCode());

        verifyNoInteractions(projectMapper);
    }

    @Test
    void requireActiveByCodeRejectsSuspendedProject() {
        when(projectMapper.selectByProjectCode(PROJECT_CODE))
                .thenReturn(project(BuyerProjectStatus.SUSPENDED.getCode()));

        assertThatThrownBy(() -> resolver.requireActiveByCode(PROJECT_CODE, BUYER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND.getCode());
    }

    @Test
    void requireActiveRejectsMissingProjectWithoutLeakingFact() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> resolver.requireActive(PROJECT_ID, BUYER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID.getCode());
    }

    @Test
    void requireActiveRejectsDraftProject() {
        when(projectMapper.selectById(PROJECT_ID))
                .thenReturn(project(BuyerProjectStatus.DRAFT.getCode()));

        assertThatThrownBy(() -> resolver.requireActive(PROJECT_ID, BUYER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID.getCode());
    }

    @Test
    void requireActiveRejectsSuspendedProject() {
        when(projectMapper.selectById(PROJECT_ID))
                .thenReturn(project(BuyerProjectStatus.SUSPENDED.getCode()));

        assertThatThrownBy(() -> resolver.requireActive(PROJECT_ID, BUYER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID.getCode());
    }

    @Test
    void requireActiveRejectsArchivedProject() {
        when(projectMapper.selectById(PROJECT_ID))
                .thenReturn(project(BuyerProjectStatus.ARCHIVED.getCode()));

        assertThatThrownBy(() -> resolver.requireActive(PROJECT_ID, BUYER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID.getCode());
    }

    @Test
    void requireActiveRejectsProjectOfAnotherBuyer() {
        BuyerProjectDO otherBuyerProject = project(BuyerProjectStatus.ACTIVE.getCode());
        otherBuyerProject.setBuyerId(9999L);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(otherBuyerProject);

        assertThatThrownBy(() -> resolver.requireActive(PROJECT_ID, BUYER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID.getCode());
    }

    @Test
    void requireActiveRejectsNullIdsWithoutDatabaseAccess() {
        assertThatThrownBy(() -> resolver.requireActive(null, BUYER_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID.getCode());
        assertThatThrownBy(() -> resolver.requireActive(PROJECT_ID, null))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID.getCode());

        verifyNoInteractions(projectMapper);
    }

    private BuyerProjectDO project(short status) {
        return BuyerProjectDO.builder()
                .id(PROJECT_ID)
                .projectCode(PROJECT_CODE)
                .projectName("测试采购项目")
                .buyerId(BUYER_ID)
                .status(status)
                .build();
    }
}
