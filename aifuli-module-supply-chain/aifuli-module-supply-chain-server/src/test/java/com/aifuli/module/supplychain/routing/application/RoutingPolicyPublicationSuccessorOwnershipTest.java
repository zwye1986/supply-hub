package com.aifuli.module.supplychain.routing.application;

import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.aifuli.module.supplychain.routing.dal.mysql.StandardProductRoutingCandidateMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.StandardProductRoutingRuleMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 后继发布：关闭版本必须归属 command.policyCode 对应策略。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#5-保存事务}。
 */
class RoutingPolicyPublicationSuccessorOwnershipTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 10, 50);
    private static final LocalDateTime VALID_FROM = NOW.minusHours(1);

    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private final SupplierRoutingPolicyMapper policyMapper = mock(SupplierRoutingPolicyMapper.class);
    private final SupplierRoutingPolicyVersionMapper versionMapper =
            mock(SupplierRoutingPolicyVersionMapper.class);
    private RoutingPolicyPublicationService service;

    @BeforeEach
    void setUp() {
        service = new RoutingPolicyPublicationService(
                databaseClock, policyMapper, versionMapper,
                mock(StandardProductRoutingRuleMapper.class),
                mock(StandardProductRoutingCandidateMapper.class),
                mock(BuyerMapper.class), mock(PlatformSkuMapper.class), mock(SupplierMapper.class),
                mock(OutboxEventRepository.class));
        when(databaseClock.now()).thenReturn(NOW);
    }

    @Test
    void rejectsCloseVersionBelongingToAnotherPolicy() {
        SupplierRoutingPolicyVersionDO closing = SupplierRoutingPolicyVersionDO.builder()
                .id(100L).policyId(9L).versionNo(1).validFrom(VALID_FROM)
                .status((short) 10).version(1L).build();
        when(versionMapper.selectByIdForUpdate(100L)).thenReturn(closing);
        when(policyMapper.selectByCodeForUpdate("STANDARD_PRODUCT_PRIMARY"))
                .thenReturn(SupplierRoutingPolicyDO.builder()
                        .id(1L).policyCode("STANDARD_PRODUCT_PRIMARY")
                        .policyName("标准商品供给路由").status((short) 10).version(0L).build());

        assertThatThrownBy(() -> service.publishSuccessorStandardProductRules(
                new RoutingPolicyPublicationService.SuccessorPublishCommand(
                        "STANDARD_PRODUCT_PRIMARY", "标准商品供给路由", 1L, "trace-own",
                        List.of(new RoutingPolicyPublicationService.RuleDraft(
                                "RR_1", "规则", "BYR", "SKU", "DIRECT_RECHARGE", 0,
                                List.of(new RoutingPolicyPublicationService.CandidateDraft(
                                        "SPL", 0, "UNSUPPORTED", null,
                                        "UNSUPPORTED", null, "UNSUPPORTED", null)))),
                        100L, 1L, NOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not belong to policy code STANDARD_PRODUCT_PRIMARY");

        verify(versionMapper).selectByIdForUpdate(100L);
        verify(policyMapper).selectByCodeForUpdate("STANDARD_PRODUCT_PRIMARY");
        verifyNoMoreInteractions(versionMapper);
    }

    @Test
    void rejectsCloseVersionWhenPolicyCodeMissing() {
        SupplierRoutingPolicyVersionDO closing = SupplierRoutingPolicyVersionDO.builder()
                .id(100L).policyId(9L).versionNo(1).validFrom(VALID_FROM)
                .status((short) 10).version(1L).build();
        when(versionMapper.selectByIdForUpdate(100L)).thenReturn(closing);
        when(policyMapper.selectByCodeForUpdate("STANDARD_PRODUCT_PRIMARY")).thenReturn(null);

        assertThatThrownBy(() -> service.publishSuccessorStandardProductRules(
                new RoutingPolicyPublicationService.SuccessorPublishCommand(
                        "STANDARD_PRODUCT_PRIMARY", "标准商品供给路由", 1L, "trace-missing",
                        List.of(new RoutingPolicyPublicationService.RuleDraft(
                                "RR_1", "规则", "BYR", "SKU", "DIRECT_RECHARGE", 0,
                                List.of(new RoutingPolicyPublicationService.CandidateDraft(
                                        "SPL", 0, "UNSUPPORTED", null,
                                        "UNSUPPORTED", null, "UNSUPPORTED", null)))),
                        100L, 1L, NOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not belong to policy code STANDARD_PRODUCT_PRIMARY");

        verify(versionMapper).selectByIdForUpdate(100L);
        verify(policyMapper).selectByCodeForUpdate("STANDARD_PRODUCT_PRIMARY");
        verifyNoMoreInteractions(versionMapper);
    }
}
