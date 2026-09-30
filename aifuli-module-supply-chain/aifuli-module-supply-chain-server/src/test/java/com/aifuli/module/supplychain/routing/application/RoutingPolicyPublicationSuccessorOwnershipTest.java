package com.aifuli.module.supplychain.routing.application;

import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.aifuli.module.supplychain.routing.dal.mysql.StdRoutingCandidateMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.StdRoutingRuleMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.StdRoutingRuleScopeMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
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
 * 后继发布：关闭版本必须归属 command.policyCode 对应策略（PRD 四级范围模型）。
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#55-保存校验}。
 */
class RoutingPolicyPublicationSuccessorOwnershipTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 23, 10, 50);
    private static final LocalDateTime VALID_FROM = NOW.minusHours(1);
    private static final String POLICY_CODE = "STANDARD_PRODUCT_PRIMARY";
    private static final String POLICY_NAME = "标准商品供给路由";

    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private final SupplierRoutingPolicyMapper policyMapper = mock(SupplierRoutingPolicyMapper.class);
    private final SupplierRoutingPolicyVersionMapper versionMapper =
            mock(SupplierRoutingPolicyVersionMapper.class);
    private RoutingPolicyPublicationService service;

    @BeforeEach
    void setUp() {
        service = new RoutingPolicyPublicationService(
                databaseClock, policyMapper, versionMapper,
                mock(StdRoutingRuleMapper.class),
                mock(StdRoutingRuleScopeMapper.class),
                mock(StdRoutingCandidateMapper.class),
                mock(BuyerMapper.class), mock(BuyerProjectMapper.class),
                mock(ProductTypeMapper.class), mock(SupplierMapper.class),
                mock(OutboxEventRepository.class));
        when(databaseClock.now()).thenReturn(NOW);
    }

    @Test
    void rejectsCloseVersionBelongingToAnotherPolicy() {
        SupplierRoutingPolicyVersionDO closing = SupplierRoutingPolicyVersionDO.builder()
                .id(100L).policyId(9L).versionNo(1).validFrom(VALID_FROM)
                .status((short) 10).version(1L).build();
        when(versionMapper.selectByIdForUpdate(100L)).thenReturn(closing);
        when(policyMapper.selectByCodeForUpdate(POLICY_CODE))
                .thenReturn(SupplierRoutingPolicyDO.builder()
                        .id(1L).policyCode(POLICY_CODE)
                        .policyName(POLICY_NAME).status((short) 10).version(0L).build());

        assertThatThrownBy(() -> service.publishSuccessorStandardProductRules(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not belong to policy code " + POLICY_CODE);

        verify(versionMapper).selectByIdForUpdate(100L);
        verify(policyMapper).selectByCodeForUpdate(POLICY_CODE);
        verifyNoMoreInteractions(versionMapper);
    }

    @Test
    void rejectsCloseVersionWhenPolicyCodeMissing() {
        SupplierRoutingPolicyVersionDO closing = SupplierRoutingPolicyVersionDO.builder()
                .id(100L).policyId(9L).versionNo(1).validFrom(VALID_FROM)
                .status((short) 10).version(1L).build();
        when(versionMapper.selectByIdForUpdate(100L)).thenReturn(closing);
        when(policyMapper.selectByCodeForUpdate(POLICY_CODE)).thenReturn(null);

        assertThatThrownBy(() -> service.publishSuccessorStandardProductRules(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not belong to policy code " + POLICY_CODE);

        verify(versionMapper).selectByIdForUpdate(100L);
        verify(policyMapper).selectByCodeForUpdate(POLICY_CODE);
        verifyNoMoreInteractions(versionMapper);
    }

    private static RoutingPolicyPublicationService.SuccessorPublishCommand command() {
        return new RoutingPolicyPublicationService.SuccessorPublishCommand(
                POLICY_CODE, POLICY_NAME, 1L, "trace-own",
                List.of(autoAllSuppliersRule("RR_1", "规则")),
                100L, 1L, NOW,
                40, 30, 30);
    }

    /** AUTO + 全部供应商的合法规则草稿（候选动态解析，无候选行）。 */
    static RoutingPolicyPublicationService.RuleDraft autoAllSuppliersRule(String ruleCode, String ruleName) {
        return new RoutingPolicyPublicationService.RuleDraft(
                ruleCode, ruleName, "AUTO", null,
                "ALL", List.of(),
                "ALL", List.of(),
                "ALL", List.of(),
                "ALL", List.of(),
                null,
                "STABLE", "PRICE", "COMPOSITE",
                100, (short) 10, false,
                List.of());
    }
}
