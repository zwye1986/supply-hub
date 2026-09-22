package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService.RuleDraft;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService.SuccessorPublishCommand;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService.SuccessorPublishResult;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.RoutingRuleView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.NormalizedRulePatch;
import com.aifuli.module.supplychain.routing.dal.dataobject.StandardProductRoutingCandidateDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.StandardProductRoutingRuleDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.aifuli.module.supplychain.routing.dal.mysql.StandardProductRoutingCandidateMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.StandardProductRoutingRuleMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper;
import com.aifuli.module.supplychain.routing.domain.RoutingType;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_LAST_CANNOT_REMOVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 路由规则页补丁发布命令服务单测。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#5-保存事务}、
 * {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#10-测试门禁}。
 */
class RoutingRuleAdminCommandServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 10, 30, 0);
    private static final Long OPERATOR_ID = 1016L;

    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final SupplierRoutingPolicyMapper policyMapper = mock(SupplierRoutingPolicyMapper.class);
    private final SupplierRoutingPolicyVersionMapper versionMapper =
            mock(SupplierRoutingPolicyVersionMapper.class);
    private final StandardProductRoutingRuleMapper ruleMapper = mock(StandardProductRoutingRuleMapper.class);
    private final StandardProductRoutingCandidateMapper candidateMapper =
            mock(StandardProductRoutingCandidateMapper.class);
    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private final RoutingPolicyPublicationService publicationService =
            mock(RoutingPolicyPublicationService.class);
    private final RoutingRuleAdminQueryService queryService = mock(RoutingRuleAdminQueryService.class);

    private RoutingRuleAdminCommandService service;

    @BeforeEach
    void setUp() {
        service = new RoutingRuleAdminCommandService(
                clock, policyMapper, versionMapper, ruleMapper, candidateMapper,
                buyerMapper, skuMapper, supplierMapper, publicationService, queryService);
        when(clock.now()).thenReturn(NOW);
        when(publicationService.publishSuccessorStandardProductRules(any()))
                .thenReturn(new SuccessorPublishResult(
                        RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_CODE, 99L, 1));
    }

    @Test
    void createFirstRuleBootstrapsWithNullCloseVersion() {
        when(policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code())).thenReturn(List.of());
        when(policyMapper.selectByCode(RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_CODE)).thenReturn(null);
        stubBuyerSkuSuppliersForCreate("BYR_1", "SKU_1", List.of("SPL_A"));
        RoutingRuleView view = sampleView("RR20260922001");
        when(queryService.get("RR20260922001")).thenReturn(view);

        RoutingRuleView result = service.create(fixedPatch("BYR_1", "SKU_1", "SPL_A"), OPERATOR_ID);

        assertThat(result.ruleCode()).isEqualTo("RR20260922001");
        ArgumentCaptor<SuccessorPublishCommand> captor = ArgumentCaptor.forClass(SuccessorPublishCommand.class);
        verify(publicationService).publishSuccessorStandardProductRules(captor.capture());
        SuccessorPublishCommand command = captor.getValue();
        assertThat(command.policyCode()).isEqualTo(RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_CODE);
        assertThat(command.policyName()).isEqualTo(RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_NAME);
        assertThat(command.closeVersionId()).isNull();
        assertThat(command.closeExpectedVersion()).isZero();
        assertThat(command.effectiveAt()).isEqualTo(NOW);
        assertThat(command.rules()).hasSize(1);
        RuleDraft rule = command.rules().getFirst();
        assertThat(rule.ruleCode()).isEqualTo("RR20260922001");
        assertThat(rule.buyerCode()).isEqualTo("BYR_1");
        assertThat(rule.platformSkuCode()).isEqualTo("SKU_1");
        assertThat(rule.candidates()).hasSize(1);
        assertThat(rule.candidates().getFirst().supplierCode()).isEqualTo("SPL_A");
        assertThat(rule.candidates().getFirst().candidatePriority()).isZero();
        assertThat(rule.candidates().getFirst().inventoryCheckMode())
                .isEqualTo(RoutingRuleSaveSupport.DEFAULT_INVENTORY_CHECK_MODE);
    }

    @Test
    void createBootstrapReusesExistingInactivePolicyName() {
        when(policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code())).thenReturn(List.of());
        String existingName = "历史标准路由策略名";
        when(policyMapper.selectByCode(RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_CODE))
                .thenReturn(SupplierRoutingPolicyDO.builder().id(7L)
                        .policyCode(RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_CODE)
                        .policyName(existingName)
                        .routingType(RoutingType.STANDARD_PRODUCT.code())
                        .status((short) 0).activeFlag(null).version(0L).build());
        stubBuyerSkuSuppliersForCreate("BYR_1", "SKU_1", List.of("SPL_A"));
        when(queryService.get("RR20260922001")).thenReturn(sampleView("RR20260922001"));

        service.create(fixedPatch("BYR_1", "SKU_1", "SPL_A"), OPERATOR_ID);

        ArgumentCaptor<SuccessorPublishCommand> captor = ArgumentCaptor.forClass(SuccessorPublishCommand.class);
        verify(publicationService).publishSuccessorStandardProductRules(captor.capture());
        SuccessorPublishCommand command = captor.getValue();
        assertThat(command.policyCode()).isEqualTo(RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_CODE);
        assertThat(command.policyName()).isEqualTo(existingName);
        assertThat(command.policyName()).isNotEqualTo(RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_NAME);
        assertThat(command.closeVersionId()).isNull();
    }

    @Test
    void createSecondKeepsExistingRule() {
        stubActivePolicyAndVersion();
        stubExistingRule("RR_EXIST", 1L, 1L, 5L, List.of(candidate(21L, 1L, 11L, 0, "OPTIONAL", 30)));
        when(buyerMapper.selectById(1L)).thenReturn(buyer(1L, "BYR_1"));
        when(skuMapper.selectById(5L)).thenReturn(sku(5L, "SKU_1", "DIRECT_RECHARGE"));
        when(supplierMapper.selectById(11L)).thenReturn(supplier(11L, "SPL_A"));
        stubBuyerSkuSuppliersForCreate("BYR_2", "SKU_2", List.of("SPL_B"));
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(supplier(11L, "SPL_A"));
        when(queryService.get("RR20260922001")).thenReturn(sampleView("RR20260922001"));

        service.create(fixedPatch("BYR_2", "SKU_2", "SPL_B"), OPERATOR_ID);

        ArgumentCaptor<SuccessorPublishCommand> captor = ArgumentCaptor.forClass(SuccessorPublishCommand.class);
        verify(publicationService).publishSuccessorStandardProductRules(captor.capture());
        SuccessorPublishCommand command = captor.getValue();
        assertThat(command.closeVersionId()).isEqualTo(10L);
        assertThat(command.closeExpectedVersion()).isEqualTo(3L);
        assertThat(command.policyCode()).isEqualTo("CARD_PRIMARY");
        assertThat(command.rules()).extracting(RuleDraft::ruleCode)
                .containsExactly("RR_EXIST", "RR20260922001");
        assertThat(command.rules().getFirst().candidates().getFirst().healthCheckMode())
                .isEqualTo("OPTIONAL");
        assertThat(command.rules().getFirst().candidates().getFirst().healthMaxAgeSeconds()).isEqualTo(30);
    }

    @Test
    void updateSwapsCandidateOrderKeepingRuleCode() {
        stubActivePolicyAndVersion();
        stubExistingRule("RR_AUTO", 1L, 1L, 5L, List.of(
                candidate(21L, 1L, 11L, 0, "REQUIRED", 10),
                candidate(22L, 1L, 12L, 10, "UNSUPPORTED", null)));
        when(buyerMapper.selectById(1L)).thenReturn(buyer(1L, "BYR_1"));
        when(skuMapper.selectById(5L)).thenReturn(sku(5L, "SKU_1", "DIRECT_RECHARGE"));
        when(supplierMapper.selectById(11L)).thenReturn(supplier(11L, "SPL_A"));
        when(supplierMapper.selectById(12L)).thenReturn(supplier(12L, "SPL_B"));
        when(buyerMapper.selectByBuyerCode("BYR_1")).thenReturn(buyer(1L, "BYR_1"));
        when(skuMapper.selectByCode("SKU_1")).thenReturn(sku(5L, "SKU_1", "DIRECT_RECHARGE"));
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(supplier(11L, "SPL_A"));
        when(supplierMapper.selectByCode("SPL_B")).thenReturn(supplier(12L, "SPL_B"));
        when(queryService.get("RR_AUTO")).thenReturn(sampleView("RR_AUTO"));

        NormalizedRulePatch patch = new NormalizedRulePatch(
                "双候选自动", "BYR_1", "SKU_1", "DIRECT_RECHARGE",
                RoutingRuleRouteMode.AUTO, List.of("SPL_B", "SPL_A"), "RR_AUTO");
        service.update("RR_AUTO", patch, OPERATOR_ID);

        ArgumentCaptor<SuccessorPublishCommand> captor = ArgumentCaptor.forClass(SuccessorPublishCommand.class);
        verify(publicationService).publishSuccessorStandardProductRules(captor.capture());
        RuleDraft rule = captor.getValue().rules().getFirst();
        assertThat(rule.ruleCode()).isEqualTo("RR_AUTO");
        assertThat(rule.rulePriority()).isZero();
        assertThat(rule.candidates()).extracting(c -> c.supplierCode())
                .containsExactly("SPL_B", "SPL_A");
        assertThat(rule.candidates()).extracting(c -> c.candidatePriority())
                .containsExactly(0, 1);
        assertThat(rule.candidates().getFirst().inventoryCheckMode()).isEqualTo("UNSUPPORTED");
        assertThat(rule.candidates().get(1).inventoryCheckMode()).isEqualTo("REQUIRED");
        assertThat(rule.candidates().get(1).inventoryMaxAgeSeconds()).isEqualTo(10);
    }

    @Test
    void deleteNonLastPublishesRemaining() {
        stubActivePolicyAndVersion();
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(
                rule(1L, "RR_KEEP", "保留", 1L, 5L),
                rule(2L, "RR_DROP", "删除", 2L, 6L)));
        when(candidateMapper.selectByRuleId(1L)).thenReturn(List.of(
                candidate(21L, 1L, 11L, 0, "UNSUPPORTED", null)));
        when(candidateMapper.selectByRuleId(2L)).thenReturn(List.of(
                candidate(22L, 2L, 12L, 0, "UNSUPPORTED", null)));
        when(supplierMapper.selectById(11L)).thenReturn(supplier(11L, "SPL_A"));
        when(supplierMapper.selectById(12L)).thenReturn(supplier(12L, "SPL_B"));
        when(buyerMapper.selectById(1L)).thenReturn(buyer(1L, "BYR_1"));
        when(skuMapper.selectById(5L)).thenReturn(sku(5L, "SKU_1", "DIRECT_RECHARGE"));
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(supplier(11L, "SPL_A"));

        service.delete("RR_DROP", OPERATOR_ID);

        ArgumentCaptor<SuccessorPublishCommand> captor = ArgumentCaptor.forClass(SuccessorPublishCommand.class);
        verify(publicationService).publishSuccessorStandardProductRules(captor.capture());
        assertThat(captor.getValue().rules()).extracting(RuleDraft::ruleCode).containsExactly("RR_KEEP");
        verify(queryService, never()).get(anyString());
    }

    @Test
    void deleteLastRejected() {
        stubActivePolicyAndVersion();
        stubExistingRule("RR_ONLY", 1L, 1L, 5L, List.of(
                candidate(21L, 1L, 11L, 0, "UNSUPPORTED", null)));
        when(supplierMapper.selectById(11L)).thenReturn(supplier(11L, "SPL_A"));

        assertThatThrownBy(() -> service.delete("RR_ONLY", OPERATOR_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_LAST_CANNOT_REMOVE.getCode());
        verifyNoInteractions(publicationService);
    }

    @Test
    void changeStatusEnabledDoesNotPublish() {
        RoutingRuleView view = sampleView("RR_EXIST");
        when(queryService.get("RR_EXIST")).thenReturn(view);

        RoutingRuleView result = service.changeStatus("RR_EXIST", RoutingRuleStatus.ENABLED, OPERATOR_ID);

        assertThat(result).isSameAs(view);
        verifyNoInteractions(publicationService, policyMapper);
    }

    @Test
    void changeStatusDisabledUsesDeletePath() {
        stubActivePolicyAndVersion();
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(
                rule(1L, "RR_KEEP", "保留", 1L, 5L),
                rule(2L, "RR_DROP", "删除", 2L, 6L)));
        when(candidateMapper.selectByRuleId(1L)).thenReturn(List.of(
                candidate(21L, 1L, 11L, 0, "UNSUPPORTED", null)));
        when(candidateMapper.selectByRuleId(2L)).thenReturn(List.of(
                candidate(22L, 2L, 12L, 0, "UNSUPPORTED", null)));
        when(supplierMapper.selectById(11L)).thenReturn(supplier(11L, "SPL_A"));
        when(supplierMapper.selectById(12L)).thenReturn(supplier(12L, "SPL_B"));
        when(buyerMapper.selectById(1L)).thenReturn(buyer(1L, "BYR_1"));
        when(skuMapper.selectById(5L)).thenReturn(sku(5L, "SKU_1", "DIRECT_RECHARGE"));
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(supplier(11L, "SPL_A"));

        RoutingRuleView result = service.changeStatus("RR_DROP", RoutingRuleStatus.DISABLED, OPERATOR_ID);

        assertThat(result).isNull();
        verify(publicationService).publishSuccessorStandardProductRules(any());
    }

    private void stubActivePolicyAndVersion() {
        when(policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code()))
                .thenReturn(List.of(SupplierRoutingPolicyDO.builder().id(9L)
                        .policyCode("CARD_PRIMARY").policyName("卡密主策略")
                        .routingType(RoutingType.STANDARD_PRODUCT.code())
                        .status((short) 10).activeFlag((short) 1).version(1L).build()));
        when(versionMapper.selectEffective(eq(9L), eq(NOW)))
                .thenReturn(List.of(SupplierRoutingPolicyVersionDO.builder().id(10L).policyId(9L)
                        .versionNo(1).status((short) 10).version(3L)
                        .validFrom(NOW.minusDays(1)).build()));
    }

    private void stubExistingRule(String ruleCode, long ruleId, long buyerId, long skuId,
                                  List<StandardProductRoutingCandidateDO> candidates) {
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(
                rule(ruleId, ruleCode, "已有规则", buyerId, skuId)));
        when(candidateMapper.selectByRuleId(ruleId)).thenReturn(candidates);
    }

    private void stubBuyerSkuSuppliersForCreate(String buyerCode, String skuCode, List<String> supplierCodes) {
        when(buyerMapper.selectByBuyerCode(buyerCode)).thenReturn(buyer(buyerCode.hashCode() & 0xffff, buyerCode));
        when(skuMapper.selectByCode(skuCode)).thenReturn(sku(skuCode.hashCode() & 0xffff, skuCode, "DIRECT_RECHARGE"));
        for (String supplierCode : supplierCodes) {
            when(supplierMapper.selectByCode(supplierCode))
                    .thenReturn(supplier(supplierCode.hashCode() & 0xffff, supplierCode));
        }
    }

    private static NormalizedRulePatch fixedPatch(String buyerCode, String skuCode, String supplierCode) {
        return new NormalizedRulePatch(
                "固定规则", buyerCode, skuCode, "DIRECT_RECHARGE",
                RoutingRuleRouteMode.FIXED, List.of(supplierCode), null);
    }

    private static StandardProductRoutingRuleDO rule(long id, String code, String name,
                                                     long buyerId, long skuId) {
        return StandardProductRoutingRuleDO.builder().id(id).policyVersionId(10L)
                .ruleCode(code).ruleName(name).buyerId(buyerId).platformSkuId(skuId)
                .fulfillmentType("DIRECT_RECHARGE").rulePriority(10).version(0L).build();
    }

    private static StandardProductRoutingCandidateDO candidate(
            long id, long ruleId, long supplierId, int priority,
            String healthMode, Integer healthMaxAge) {
        return StandardProductRoutingCandidateDO.builder().id(id).ruleId(ruleId)
                .supplierId(supplierId).candidatePriority(priority)
                .inventoryCheckMode(priority == 0 && healthMode.equals("REQUIRED") ? "REQUIRED" : "UNSUPPORTED")
                .inventoryMaxAgeSeconds(priority == 0 && healthMode.equals("REQUIRED") ? 10 : null)
                .balanceCheckMode("UNSUPPORTED").balanceMaxAgeSeconds(null)
                .healthCheckMode(healthMode).healthMaxAgeSeconds(healthMaxAge).build();
    }

    private static BuyerDO buyer(long id, String code) {
        return BuyerDO.builder().id(id).buyerCode(code).buyerName(code)
                .status(BuyerStatus.ACTIVE.getCode()).build();
    }

    private static PlatformSkuDO sku(long id, String code, String fulfillmentType) {
        return PlatformSkuDO.builder().id(id).skuCode(code).skuName(code)
                .fulfillmentType(fulfillmentType)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
    }

    private static SupplierDO supplier(long id, String code) {
        return SupplierDO.builder().id(id).supplierCode(code).supplierName(code)
                .supplierType("STANDARD_SUPPLIER")
                .status(SupplierStatus.ACTIVE.getCode()).build();
    }

    private static RoutingRuleView sampleView(String ruleCode) {
        return new RoutingRuleView(
                ruleCode, "规则", false, RoutingRuleScopeMode.SPECIFIED,
                List.of("BYR_1"), List.of("甲"), RoutingRuleScopeMode.ALL, List.of(), List.of(),
                RoutingRuleScopeMode.ALL, List.of(), List.of(), RoutingRuleRouteMode.FIXED,
                RoutingRuleScopeMode.SPECIFIED, List.of("SPL_A"), List.of("甲供应商"),
                "SPL_A", "甲供应商", "SKU_1", "话费", "DIRECT_RECHARGE",
                List.of(), new RoutingRuleAdminQueryService.WeightView(40, 30, 30),
                RoutingRuleStatus.ENABLED, "1016", NOW);
    }
}
