package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminCommandService.CreateProjectCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminCommandService.UpdateProjectCommand;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.profile.domain.SettlementPartyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_STATUS_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_BUYER_IMMUTABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_PERIOD_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_PLATFORM_SETTLEMENT_NOT_ENABLED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_STATUS_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目管理端档案命令：草稿可改归属、CLOSED 状态机、仅 DISTRIBUTOR 结算。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#71-档案}。
 */
class ProjectAdminCommandServiceTest {

    private static final String PROJECT_CODE = "PRJ_TESTCODE000000000000001";
    private static final String BUYER_A = "BYR_A";
    private static final String BUYER_B = "BYR_B";

    private final BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BusinessNoGenerator businessNoGenerator = mock(BusinessNoGenerator.class);
    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private ProjectAdminCommandService service;
    private BuyerProjectDO stored;

    @BeforeEach
    void setUp() {
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 11, 0));
        service = new ProjectAdminCommandService(databaseClock, projectMapper, buyerMapper, businessNoGenerator);
        when(businessNoGenerator.next(BusinessNoPrefixes.PRJ)).thenReturn(PROJECT_CODE);
        when(buyerMapper.selectByBuyerCode(BUYER_A)).thenReturn(activeBuyer(1L, BUYER_A));
        when(buyerMapper.selectByBuyerCode(BUYER_B)).thenReturn(activeBuyer(2L, BUYER_B));
        when(projectMapper.insert(any(BuyerProjectDO.class))).thenAnswer(inv -> {
            stored = inv.getArgument(0);
            stored.setId(9L);
            return 1;
        });
        when(projectMapper.selectByProjectCodeForUpdate(PROJECT_CODE)).thenAnswer(inv -> stored);
        when(projectMapper.updateById(any(BuyerProjectDO.class))).thenAnswer(inv -> {
            stored = inv.getArgument(0);
            return 1;
        });
    }

    @Test
    void draftMayChangeBuyerAndActiveMayNot() {
        service.create(create(BUYER_A, null, null, null));
        service.update(update(BUYER_B));
        assertThat(stored.getBuyerId()).isEqualTo(2L);

        service.changeStatus(PROJECT_CODE, BuyerProjectStatus.ACTIVE, 1L);
        assertThatThrownBy(() -> service.update(update(BUYER_A)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PROJECT_BUYER_IMMUTABLE.getCode());

        stored.setStatus(BuyerProjectStatus.SUSPENDED.getCode());
        assertThatThrownBy(() -> service.update(update(BUYER_A)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PROJECT_BUYER_IMMUTABLE.getCode());

        stored.setStatus(BuyerProjectStatus.CLOSED.getCode());
        assertThatThrownBy(() -> service.update(update(BUYER_A)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PROJECT_BUYER_IMMUTABLE.getCode());
    }

    @Test
    void platformSettlementRejected() {
        assertThatThrownBy(() -> service.create(create(BUYER_A, null, SettlementPartyType.PLATFORM.name(), null)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PROJECT_PLATFORM_SETTLEMENT_NOT_ENABLED.getCode());
        assertThatThrownBy(() -> service.create(create(BUYER_A, null, "UNKNOWN_PARTY", null)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ENUM_NOT_OPEN.getCode());
    }

    @Test
    void statusMachineUsesClosed() {
        service.create(create(BUYER_A, null, null, null));
        assertThatThrownBy(() -> service.changeStatus(PROJECT_CODE, BuyerProjectStatus.CLOSED, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PROJECT_STATUS_INVALID.getCode());

        stored.setStatus(BuyerProjectStatus.ACTIVE.getCode());
        service.changeStatus(PROJECT_CODE, BuyerProjectStatus.CLOSED, 1L);
        assertThat(stored.getStatus()).isEqualTo(BuyerProjectStatus.CLOSED.getCode());

        service.changeStatus(PROJECT_CODE, BuyerProjectStatus.ACTIVE, 1L);
        assertThat(stored.getStatus()).isEqualTo(BuyerProjectStatus.ACTIVE.getCode());

        when(projectMapper.selectByProjectCodeForUpdate("PRJ_MISSING")).thenReturn(null);
        assertThatThrownBy(() -> service.changeStatus("PRJ_MISSING", BuyerProjectStatus.ACTIVE, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PROJECT_NOT_FOUND.getCode());
    }

    @Test
    void fullUpdateMayChangeStatusThroughSameStateMachine() {
        service.create(create(BUYER_A, null, null, null));

        service.update(update(BUYER_A, BuyerProjectStatus.ACTIVE.name()));

        assertThat(stored.getStatus()).isEqualTo(BuyerProjectStatus.ACTIVE.getCode());
    }

    @Test
    void createAllowsDraftOrActiveAndGeneratesPrjCode() {
        String draftCode = service.create(create(BUYER_A, null, null, null));
        assertThat(draftCode).isEqualTo(PROJECT_CODE);
        ArgumentCaptor<BuyerProjectDO> inserted = ArgumentCaptor.forClass(BuyerProjectDO.class);
        verify(projectMapper).insert(inserted.capture());
        BuyerProjectDO draft = inserted.getValue();
        assertThat(draft.getProjectCode()).isEqualTo(PROJECT_CODE);
        assertThat(draft.getStatus()).isEqualTo(BuyerProjectStatus.DRAFT.getCode());
        assertThat(draft.getCatalogMode()).isEqualTo("MANAGED");
        assertThat(draft.getSettlementPartyType()).isEqualTo(SettlementPartyType.DISTRIBUTOR.name());
        assertThat(draft.getStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(draft.getEndDate()).isEqualTo(LocalDate.of(2026, 9, 30));

        String activeCode = service.create(create(BUYER_A, BuyerProjectStatus.ACTIVE.name(), null, null));
        assertThat(activeCode).isEqualTo(PROJECT_CODE);
        assertThat(stored.getStatus()).isEqualTo(BuyerProjectStatus.ACTIVE.getCode());
    }

    @Test
    void createStoresPrdProjectType() {
        service.create(create(BUYER_A, null, null, "POINTS_REDEMPTION"));

        assertThat(stored.getProjectType()).isEqualTo("POINTS_REDEMPTION");
    }

    @Test
    void endBeforeStartRejected() {
        assertThatThrownBy(() -> service.create(cmdPeriod(
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 1))))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PROJECT_PERIOD_INVALID.getCode());
    }

    @Test
    void blacklistedBuyerCannotOwnNewProject() {
        when(buyerMapper.selectByBuyerCode(BUYER_A)).thenReturn(BuyerDO.builder()
                .id(1L).buyerCode(BUYER_A).status(BuyerStatus.BLACKLIST.getCode()).build());
        assertThatThrownBy(() -> service.create(create(BUYER_A, null, null, null)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_STATUS_CONFLICT.getCode());

        when(buyerMapper.selectByBuyerCode("BYR_MISSING")).thenReturn(null);
        assertThatThrownBy(() -> service.create(create("BYR_MISSING", null, null, null)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_PROFILE_NOT_FOUND.getCode());
    }

    private static CreateProjectCommand create(String buyerCode, String status,
                                               String settlementPartyType, String projectType) {
        return new CreateProjectCommand(
                "福利项目", buyerCode, projectType,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                settlementPartyType, "说明", status, 1L);
    }

    private static CreateProjectCommand cmdPeriod(LocalDate start, LocalDate end) {
        return new CreateProjectCommand(
                "福利项目", BUYER_A, null, start, end, null, null, null, 1L);
    }

    private static UpdateProjectCommand update(String buyerCode) {
        return update(buyerCode, null);
    }

    private static UpdateProjectCommand update(String buyerCode, String status) {
        return new UpdateProjectCommand(
                PROJECT_CODE, "福利项目", buyerCode, null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                null, "说明", status, 1L);
    }

    private static BuyerDO activeBuyer(Long id, String code) {
        return BuyerDO.builder()
                .id(id)
                .buyerCode(code)
                .status(BuyerStatus.ACTIVE.getCode())
                .build();
    }
}
