package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.dal.dataobject.StdRoutingRuleDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.StdRoutingRuleScopeDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.aifuli.module.supplychain.routing.dal.mysql.StdRoutingCandidateMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.StdRoutingRuleMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.StdRoutingRuleScopeMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper;
import com.aifuli.module.supplychain.routing.domain.RoutingType;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_PROJECT_BUYER_MISMATCH;
import static org.assertj.core.api.Assertions.assertThat;
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
    private final StdRoutingRuleMapper ruleMapper = mock(StdRoutingRuleMapper.class);
    private final StdRoutingRuleScopeMapper scopeMapper = mock(StdRoutingRuleScopeMapper.class);
    private final StdRoutingCandidateMapper candidateMapper = mock(StdRoutingCandidateMapper.class);
    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
    private RoutingPolicyPublicationService service;

    @BeforeEach
    void setUp() {
        service = new RoutingPolicyPublicationService(
                databaseClock, policyMapper, versionMapper,
                ruleMapper, scopeMapper, candidateMapper,
                buyerMapper, projectMapper,
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

    @Test
    void rejectsExistingDraftWhenStoredProjectBelongsToAnotherBuyer() {
        SupplierRoutingPolicyVersionDO draft = SupplierRoutingPolicyVersionDO.builder()
                .id(200L).policyId(1L).versionNo(2).status((short) 0).version(0L).build();
        SupplierRoutingPolicyDO policy = SupplierRoutingPolicyDO.builder()
                .id(1L).policyCode(POLICY_CODE).routingType(RoutingType.STANDARD_PRODUCT.code())
                .status((short) 20).build();
        StdRoutingRuleDO rule = StdRoutingRuleDO.builder()
                .id(300L).ruleCode("RR_OLD_DRAFT").routeMode("AUTO")
                .distributorMode("SPECIFIED").projectMode("SPECIFIED")
                .productTypeMode("ALL").supplierMode("ALL").status((short) 10).build();
        when(versionMapper.selectByIdForUpdate(200L)).thenReturn(draft);
        when(policyMapper.selectByIdForUpdate(1L)).thenReturn(policy);
        when(versionMapper.selectPublished(1L)).thenReturn(List.of());
        when(policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code())).thenReturn(List.of());
        when(ruleMapper.selectByPolicyVersionId(200L)).thenReturn(List.of(rule));
        when(scopeMapper.selectByRuleIds(List.of(300L))).thenReturn(List.of(
                StdRoutingRuleScopeDO.builder().scopeKind("DISTRIBUTOR").scopeCode("BUYER_A").build(),
                StdRoutingRuleScopeDO.builder().scopeKind("PROJECT").scopeCode("PROJECT_B").build()));
        when(candidateMapper.selectByRuleId(300L)).thenReturn(List.of());
        when(buyerMapper.selectByBuyerCode("BUYER_A")).thenReturn(BuyerDO.builder()
                .id(11L).status(BuyerStatus.ACTIVE.getCode()).build());
        when(projectMapper.selectByProjectCode("PROJECT_B")).thenReturn(BuyerProjectDO.builder()
                .buyerId(12L).build());

        assertThatThrownBy(() -> service.publish(new RoutingPolicyPublicationService.PublishCommand(
                200L, 0L, "发布旧草稿", 1L, "trace-old-draft", null, null)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ROUTING_RULE_PROJECT_BUYER_MISMATCH.getCode()));
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
