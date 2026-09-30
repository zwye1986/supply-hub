package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService.RuleDraft;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService.SuccessorPublishCommand;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService.SuccessorPublishResult;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.RoutingRuleView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.NormalizedRulePatch;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.WeightInput;
import com.aifuli.module.supplychain.routing.dal.dataobject.StdRoutingCandidateDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.StdRoutingRuleDO;
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
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_PROTECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 路由规则页补丁发布命令服务单测（PRD 四级范围模型）：
 * 引导首发、保留既有规则、候选顺序交换、真实启停与受保护规则约束。
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#55-保存校验}。
 */
class RoutingRuleAdminCommandServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 23, 10, 30, 0);
    private static final Long OPERATOR_ID = 1016L;
    /** 与 NOW 的 BASIC_ISO_DATE 对应的新规则编码。 */
    private static final String NEW_RULE_CODE = "RR20260923001";

    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final SupplierRoutingPolicyMapper policyMapper = mock(SupplierRoutingPolicyMapper.class);
    private final SupplierRoutingPolicyVersionMapper versionMapper =
            mock(SupplierRoutingPolicyVersionMapper.class);
    private final StdRoutingRuleMapper ruleMapper = mock(StdRoutingRuleMapper.class);
    private final StdRoutingRuleScopeMapper scopeMapper = mock(StdRoutingRuleScopeMapper.class);
    private final StdRoutingCandidateMapper candidateMapper = mock(StdRoutingCandidateMapper.class);
    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
    private final ProductTypeMapper productTypeMapper = mock(ProductTypeMapper.class);
    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private final RoutingPolicyPublicationService publicationService =
            mock(RoutingPolicyPublicationService.class);
    private final RoutingRuleAdminQueryService queryService = mock(RoutingRuleAdminQueryService.class);

    private RoutingRuleAdminCommandService service;

    @BeforeEach
    void setUp() {
        service = new RoutingRuleAdminCommandService(
                clock, policyMapper, versionMapper, ruleMapper, scopeMapper, candidateMapper,
                buyerMapper, projectMapper, productTypeMapper, supplierMapper,
                publicationService, queryService);
        when(clock.now()).thenReturn(NOW);
        when(publicationService.publishSuccessorStandardProductRules(any()))
                .thenReturn(new SuccessorPublishResult(
                        RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_CODE, 99L, 1));
        when(scopeMapper.selectByRuleIds(anyCollection())).thenReturn(List.of());
        when(candidateMapper.selectByRuleIds(anyCollection())).thenReturn(List.of());
    }

    @Test
    void createFirstRuleBootstrapsWithNullCloseVersion() {
        when(policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code())).thenReturn(List.of());
        when(policyMapper.selectByCode(RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_CODE)).thenReturn(null);
        when(queryService.get(NEW_RULE_CODE)).thenReturn(sampleView(NEW_RULE_CODE));

        RoutingRuleView result = service.create(
                patch("引导规则", null, null, List.of()), OPERATOR_ID);

        assertThat(result.ruleCode()).isEqualTo(NEW_RULE_CODE);
        ArgumentCaptor<SuccessorPublishCommand> captor = ArgumentCaptor.forClass(SuccessorPublishCommand.class);
        verify(publicationService).publishSuccessorStandardProductRules(captor.capture());
        SuccessorPublishCommand command = captor.getValue();
        assertThat(command.policyCode()).isEqualTo(RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_CODE);
        assertThat(command.policyName()).isEqualTo(RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_NAME);
        assertThat(command.closeVersionId()).isNull();
        assertThat(command.closeExpectedVersion()).isZero();
        assertThat(command.effectiveAt()).isEqualTo(NOW);
        assertThat(command.stabilityWeight()).isEqualTo(40);
        assertThat(command.priceWeight()).isEqualTo(30);
        assertThat(command.timelinessWeight()).isEqualTo(30);
        assertThat(command.rules()).hasSize(1);
        RuleDraft rule = command.rules().getFirst();
        assertThat(rule.ruleCode()).isEqualTo(NEW_RULE_CODE);
        assertThat(rule.routeMode()).isEqualTo("AUTO");
        assertThat(rule.distributorMode()).isEqualTo("ALL");
        assertThat(rule.supplierMode()).isEqualTo("ALL");
        assertThat(rule.candidates()).isEmpty();
        assertThat(rule.strategy1()).isEqualTo("STABLE");
        assertThat(rule.strategy2()).isEqualTo("PRICE");
        assertThat(rule.strategy3()).isEqualTo("COMPOSITE");
        assertThat(rule.status()).isEqualTo((short) 10);
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
        when(buyerMapper.selectByBuyerCode("BYR_1")).thenReturn(buyer(1L, "BYR_1"));
        when(queryService.get(NEW_RULE_CODE)).thenReturn(sampleView(NEW_RULE_CODE));

        service.create(patch("引导规则", "BYR_1", null, List.of()), OPERATOR_ID);

        ArgumentCaptor<SuccessorPublishCommand> captor = ArgumentCaptor.forClass(SuccessorPublishCommand.class);
        verify(publicationService).publishSuccessorStandardProductRules(captor.capture());
        SuccessorPublishCommand command = captor.getValue();
        assertThat(command.policyCode()).isEqualTo(RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_CODE);
        assertThat(command.policyName()).isEqualTo(existingName);
        assertThat(command.policyName()).isNotEqualTo(RoutingRuleAdminCommandService.BOOTSTRAP_POLICY_NAME);
        assertThat(command.closeVersionId()).isNull();
    }

    @Test
    void rejectsProjectOutsideSelectedDistributorBeforePublishing() {
        when(policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code())).thenReturn(List.of());
        when(buyerMapper.selectByBuyerCode("BYR_1")).thenReturn(buyer(1L, "BYR_1"));
        when(projectMapper.selectByProjectCode("PRJ_2")).thenReturn(
                BuyerProjectDO.builder().id(2L).projectCode("PRJ_2").buyerId(2L).build());

        assertThatThrownBy(() -> service.create(projectPatch("BYR_1", "PRJ_2"), OPERATOR_ID))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("项目不属于所选分销商");
        verify(publicationService, never()).publishSuccessorStandardProductRules(any());
    }

    @Test
    void publishesProjectOwnedBySelectedDistributor() {
        when(policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code())).thenReturn(List.of());
        when(buyerMapper.selectByBuyerCode("BYR_1")).thenReturn(buyer(1L, "BYR_1"));
        when(projectMapper.selectByProjectCode("PRJ_1")).thenReturn(
                BuyerProjectDO.builder().id(2L).projectCode("PRJ_1").buyerId(1L).build());
        when(queryService.get(NEW_RULE_CODE)).thenReturn(sampleView(NEW_RULE_CODE));

        service.create(projectPatch("BYR_1", "PRJ_1"), OPERATOR_ID);

        ArgumentCaptor<SuccessorPublishCommand> captor = ArgumentCaptor.forClass(SuccessorPublishCommand.class);
        verify(publicationService).publishSuccessorStandardProductRules(captor.capture());
        assertThat(captor.getValue().rules().getFirst().projectCodes()).containsExactly("PRJ_1");
        assertThat(captor.getValue().rules().getFirst().supplierMode()).isEqualTo("ALL");
    }

    @Test
    void createSecondKeepsExistingRuleAndRetainsCandidateCheckModes() {
        stubActivePolicyAndVersion();
        // 既有 AUTO+SPECIFIED 规则：两个候选，健康检查 OPTIONAL/60
        stubExistingAutoRule("RR_EXIST", List.of(
                candidate(21L, 1L, 11L, 0, "UNSUPPORTED", null, "OPTIONAL", 60),
                candidate(22L, 1L, 12L, 10, "UNSUPPORTED", null, "UNSUPPORTED", null)));
        when(supplierMapper.selectById(11L)).thenReturn(supplier(11L, "SPL_A"));
        when(supplierMapper.selectById(12L)).thenReturn(supplier(12L, "SPL_B"));
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(supplier(11L, "SPL_A"));
        when(supplierMapper.selectByCode("SPL_B")).thenReturn(supplier(12L, "SPL_B"));
        // 新规则分销商范围与既有规则不同（既有 BYR_1，新增 BYR_2）
        when(buyerMapper.selectByBuyerCode("BYR_2")).thenReturn(buyer(2L, "BYR_2"));
        when(queryService.get(NEW_RULE_CODE)).thenReturn(sampleView(NEW_RULE_CODE));

        service.create(patch("第二条规则", "BYR_2", null, List.of()), OPERATOR_ID);

        ArgumentCaptor<SuccessorPublishCommand> captor = ArgumentCaptor.forClass(SuccessorPublishCommand.class);
        verify(publicationService).publishSuccessorStandardProductRules(captor.capture());
        SuccessorPublishCommand command = captor.getValue();
        assertThat(command.closeVersionId()).isEqualTo(10L);
        assertThat(command.closeExpectedVersion()).isEqualTo(3L);
        assertThat(command.policyCode()).isEqualTo("CARD_PRIMARY");
        assertThat(command.rules()).extracting(RuleDraft::ruleCode)
                .containsExactly("RR_EXIST", NEW_RULE_CODE);
        RuleDraft existing = command.rules().getFirst();
        assertThat(existing.supplierCodes()).containsExactly("SPL_A", "SPL_B");
        assertThat(existing.candidates().getFirst().healthCheckMode()).isEqualTo("OPTIONAL");
        assertThat(existing.candidates().getFirst().healthMaxAgeSeconds()).isEqualTo(60);
        assertThat(existing.candidates().get(1).candidatePriority()).isEqualTo(1);
        // 新规则：AUTO+全部供应商，无候选行
        assertThat(command.rules().get(1).candidates()).isEmpty();
    }

    @Test
    void updateSwapsCandidateOrderKeepingRuleCode() {
        stubActivePolicyAndVersion();
        stubExistingAutoRule("RR_AUTO", List.of(
                candidate(21L, 1L, 11L, 0, "REQUIRED", 10, "UNSUPPORTED", null),
                candidate(22L, 1L, 12L, 10, "UNSUPPORTED", null, "UNSUPPORTED", null)));
        when(supplierMapper.selectById(11L)).thenReturn(supplier(11L, "SPL_A"));
        when(supplierMapper.selectById(12L)).thenReturn(supplier(12L, "SPL_B"));
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(supplier(11L, "SPL_A"));
        when(supplierMapper.selectByCode("SPL_B")).thenReturn(supplier(12L, "SPL_B"));
        when(buyerMapper.selectByBuyerCode("BYR_1")).thenReturn(buyer(1L, "BYR_1"));
        when(queryService.get("RR_AUTO")).thenReturn(sampleView("RR_AUTO"));

        service.update("RR_AUTO", patch("双候选自动", "BYR_1", null, List.of("SPL_B", "SPL_A")), OPERATOR_ID);

        ArgumentCaptor<SuccessorPublishCommand> captor = ArgumentCaptor.forClass(SuccessorPublishCommand.class);
        verify(publicationService).publishSuccessorStandardProductRules(captor.capture());
        RuleDraft rule = captor.getValue().rules().getFirst();
        assertThat(rule.ruleCode()).isEqualTo("RR_AUTO");
        assertThat(rule.candidates()).extracting(c -> c.supplierCode())
                .containsExactly("SPL_B", "SPL_A");
        assertThat(rule.candidates()).extracting(c -> c.candidatePriority())
                .containsExactly(0, 1);
        // 检查模式按供应商编码从旧行合并：SPL_B 原 UNSUPPORTED，SPL_A 原 REQUIRED
        assertThat(rule.candidates().getFirst().inventoryCheckMode()).isEqualTo("UNSUPPORTED");
        assertThat(rule.candidates().get(1).inventoryCheckMode()).isEqualTo("REQUIRED");
        assertThat(rule.candidates().get(1).inventoryMaxAgeSeconds()).isEqualTo(10);
    }

    @Test
    void updateProtectedRuleRejectsScopeChange() {
        stubActivePolicyAndVersion();
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(
                rule(1L, "RR_GLOBAL", "全局默认", "ALL", true)));
        when(queryService.get("RR_GLOBAL")).thenReturn(sampleView("RR_GLOBAL"));

        assertThatThrownBy(() -> service.update("RR_GLOBAL",
                patch("全局默认改名", "BYR_1", null, List.of()), OPERATOR_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_PROTECTED.getCode());
        verifyNoInteractions(publicationService);
    }

    @Test
    void deletePublishesRemaining() {
        stubActivePolicyAndVersion();
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(
                rule(1L, "RR_KEEP", "保留", "ALL", false),
                rule(2L, "RR_DROP", "删除", "ALL", false)));

        service.delete("RR_DROP", OPERATOR_ID);

        ArgumentCaptor<SuccessorPublishCommand> captor = ArgumentCaptor.forClass(SuccessorPublishCommand.class);
        verify(publicationService).publishSuccessorStandardProductRules(captor.capture());
        assertThat(captor.getValue().rules()).extracting(RuleDraft::ruleCode).containsExactly("RR_KEEP");
        verify(queryService, never()).get(anyString());
    }

    @Test
    void deleteLastRejected() {
        stubActivePolicyAndVersion();
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(
                rule(1L, "RR_ONLY", "唯一规则", "ALL", false)));

        assertThatThrownBy(() -> service.delete("RR_ONLY", OPERATOR_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_NOT_FOUND.getCode());
        verifyNoInteractions(publicationService);
    }

    @Test
    void deleteProtectedRejected() {
        stubActivePolicyAndVersion();
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(
                rule(1L, "RR_KEEP", "保留", "ALL", false),
                rule(2L, "RR_GLOBAL", "全局默认", "ALL", true)));

        assertThatThrownBy(() -> service.delete("RR_GLOBAL", OPERATOR_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_PROTECTED.getCode());
        verifyNoInteractions(publicationService);
    }

    @Test
    void changeStatusPublishesRealStatus() {
        stubActivePolicyAndVersion();
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(
                rule(1L, "RR_EXIST", "既有规则", "ALL", false)));
        when(queryService.get("RR_EXIST")).thenReturn(sampleView("RR_EXIST"));

        RoutingRuleView result = service.changeStatus("RR_EXIST", RoutingRuleStatus.DISABLED, OPERATOR_ID);

        assertThat(result.ruleCode()).isEqualTo("RR_EXIST");
        ArgumentCaptor<SuccessorPublishCommand> captor = ArgumentCaptor.forClass(SuccessorPublishCommand.class);
        verify(publicationService).publishSuccessorStandardProductRules(captor.capture());
        // DISABLED → status 20：真实启停，不再等同删除
        assertThat(captor.getValue().rules().getFirst().status()).isEqualTo((short) 20);
    }

    private void stubActivePolicyAndVersion() {
        when(policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code()))
                .thenReturn(List.of(SupplierRoutingPolicyDO.builder().id(9L)
                        .policyCode("CARD_PRIMARY").policyName("卡密主策略")
                        .routingType(RoutingType.STANDARD_PRODUCT.code())
                        .status((short) 10).activeFlag((short) 1).version(1L)
                        .stabilityWeight(40).priceWeight(30).timelinessWeight(30).build()));
        when(versionMapper.selectEffective(eq(9L), eq(NOW)))
                .thenReturn(List.of(SupplierRoutingPolicyVersionDO.builder().id(10L).policyId(9L)
                        .versionNo(1).status((short) 10).version(3L)
                        .validFrom(NOW.minusDays(1)).build()));
    }

    private void stubExistingAutoRule(String ruleCode, List<StdRoutingCandidateDO> candidates) {
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(
                rule(1L, ruleCode, "已有规则", "SPECIFIED", false)));
        when(candidateMapper.selectByRuleIds(anyCollection())).thenReturn(candidates);
    }

    /**
     * 构造 AUTO 规则补丁：distributorCodes 非空即 SPECIFIED；
     * supplierCodes 为空 → 供应商范围 ALL，非空 → SPECIFIED 有序候选。
     */
    private static NormalizedRulePatch patch(String name, String distributorCode,
                                             String primarySupplierCode, List<String> supplierCodes) {
        return new NormalizedRulePatch(
                null, name, RoutingRuleRouteMode.AUTO, RoutingRuleStatus.ENABLED,
                distributorCode == null ? RoutingRuleScopeMode.ALL : RoutingRuleScopeMode.SPECIFIED,
                distributorCode == null ? List.of() : List.of(distributorCode),
                RoutingRuleScopeMode.ALL, List.of(),
                RoutingRuleScopeMode.ALL, List.of(),
                supplierCodes.isEmpty() ? RoutingRuleScopeMode.ALL : RoutingRuleScopeMode.SPECIFIED,
                supplierCodes,
                primarySupplierCode,
                List.of(RoutingRuleStrategy.STABLE, RoutingRuleStrategy.PRICE,
                        RoutingRuleStrategy.COMPOSITE),
                new WeightInput(40, 30, 30), null);
    }

    private static NormalizedRulePatch projectPatch(String distributorCode, String projectCode) {
        return new NormalizedRulePatch(
                null, "项目通用路由", RoutingRuleRouteMode.AUTO, RoutingRuleStatus.ENABLED,
                RoutingRuleScopeMode.SPECIFIED, List.of(distributorCode),
                RoutingRuleScopeMode.SPECIFIED, List.of(projectCode),
                RoutingRuleScopeMode.ALL, List.of(), RoutingRuleScopeMode.ALL, List.of(),
                null, List.of(RoutingRuleStrategy.STABLE, RoutingRuleStrategy.PRICE,
                        RoutingRuleStrategy.COMPOSITE), new WeightInput(40, 30, 30), null);
    }

    private static StdRoutingRuleDO rule(long id, String code, String name, String distributorMode,
                                         boolean protectedRule) {
        return StdRoutingRuleDO.builder().id(id).policyVersionId(10L)
                .ruleCode(code).ruleName(name).routeMode("AUTO")
                .distributorMode(distributorMode).projectMode("ALL")
                .productTypeMode("ALL").supplierMode("SPECIFIED")
                .strategyPriority1("STABLE").strategyPriority2("PRICE").strategyPriority3("COMPOSITE")
                .rulePriority(100).status((short) 10).protectedRule(protectedRule).version(0L)
                .updatedBy(OPERATOR_ID).createdAt(NOW).updatedAt(NOW).build();
    }

    private static StdRoutingCandidateDO candidate(
            long id, long ruleId, long supplierId, int priority,
            String inventoryMode, Integer inventoryMaxAge, String healthMode, Integer healthMaxAge) {
        return StdRoutingCandidateDO.builder().id(id).ruleId(ruleId)
                .supplierId(supplierId).candidatePriority(priority)
                .inventoryCheckMode(inventoryMode).inventoryMaxAgeSeconds(inventoryMaxAge)
                .balanceCheckMode("UNSUPPORTED").balanceMaxAgeSeconds(null)
                .healthCheckMode(healthMode).healthMaxAgeSeconds(healthMaxAge).build();
    }

    private static BuyerDO buyer(long id, String code) {
        return BuyerDO.builder().id(id).buyerCode(code).buyerName(code)
                .status(BuyerStatus.ACTIVE.getCode()).build();
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
                RoutingRuleScopeMode.ALL, List.of(), List.of(), RoutingRuleRouteMode.AUTO,
                RoutingRuleScopeMode.SPECIFIED, List.of("SPL_A"), List.of("甲供应商"),
                null, null,
                List.of(), new RoutingRuleAdminQueryService.WeightView(40, 30, 30),
                RoutingRuleStatus.ENABLED, "1016", NOW);
    }
}
