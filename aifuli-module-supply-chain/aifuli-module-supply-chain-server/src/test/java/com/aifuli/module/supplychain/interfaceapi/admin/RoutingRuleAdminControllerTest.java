package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.security.core.util.SecurityFrameworkUtils;
import com.aifuli.module.supplychain.interfaceapi.admin.vo.RoutingRuleSaveReqVO;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminCommandService;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.PriorityView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.RoutingRuleView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.WeightView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleRouteMode;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.NormalizedRulePatch;
import com.aifuli.module.supplychain.routing.application.RoutingRuleScopeMode;
import com.aifuli.module.supplychain.routing.application.RoutingRuleStatus;
import com.aifuli.module.supplychain.routing.application.RoutingRuleStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_BUYER_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 路由规则写入口接线：VO → SaveSupport → CommandService（PRD 四级范围模型）。
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#56-rest-契约}。
 */
class RoutingRuleAdminControllerTest {

    private static final Long LOGIN_USER_ID = 1001L;

    private final RoutingRuleAdminCommandService commandService = mock(RoutingRuleAdminCommandService.class);
    private final RoutingRuleAdminQueryService queryService = mock(RoutingRuleAdminQueryService.class);
    private final RoutingRuleAdminController controller =
            new RoutingRuleAdminController(commandService, queryService);

    private MockedStatic<SecurityFrameworkUtils> securityMock;

    @BeforeEach
    void setUpLoginUser() {
        securityMock = mockStatic(SecurityFrameworkUtils.class);
        securityMock.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(LOGIN_USER_ID);
    }

    @AfterEach
    void tearDownLoginUser() {
        securityMock.close();
    }

    @Test
    void createCallsCommandService() {
        RoutingRuleSaveReqVO request = fixedCreateRequest();
        RoutingRuleView view = sampleView("RR_NEW");
        when(commandService.create(any(NormalizedRulePatch.class), eq(LOGIN_USER_ID))).thenReturn(view);

        CommonResult<RoutingRuleView> result = controller.create(request);

        assertThat(result.getData().ruleCode()).isEqualTo("RR_NEW");
        verify(commandService).create(any(NormalizedRulePatch.class), eq(LOGIN_USER_ID));
    }

    @Test
    void createPropagatesBuyerRequiredFromCommand() {
        RoutingRuleSaveReqVO request = fixedCreateRequest();
        when(commandService.create(any(NormalizedRulePatch.class), eq(LOGIN_USER_ID)))
                .thenThrow(exception(ROUTING_RULE_BUYER_REQUIRED));

        assertThatThrownBy(() -> controller.create(request))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_BUYER_REQUIRED.getCode());
    }

    private static RoutingRuleSaveReqVO fixedCreateRequest() {
        RoutingRuleSaveReqVO request = new RoutingRuleSaveReqVO();
        request.setName("话费固定规则");
        request.setDistributorCodes(List.of("BYR_1"));
        request.setRouteMode("FIXED");
        request.setPrimarySupplierCode("SPL_A");
        request.setSupplierMode("SPECIFIED");
        request.setSupplierCodes(List.of("SPL_A"));
        request.setStatus("ENABLED");
        request.setPriorities(List.of(
                priority("1", "STABLE"), priority("2", "PRICE"), priority("3", "COMPOSITE")));
        RoutingRuleSaveReqVO.WeightReqVO weights = new RoutingRuleSaveReqVO.WeightReqVO();
        weights.setStability(40);
        weights.setPrice(30);
        weights.setTimeliness(30);
        request.setWeights(weights);
        return request;
    }

    private static RoutingRuleSaveReqVO.PriorityItemReqVO priority(String id, String strategy) {
        RoutingRuleSaveReqVO.PriorityItemReqVO item = new RoutingRuleSaveReqVO.PriorityItemReqVO();
        item.setId(id);
        item.setStrategy(strategy);
        return item;
    }

    private static RoutingRuleView sampleView(String ruleCode) {
        return new RoutingRuleView(
                ruleCode, "话费固定规则", false,
                RoutingRuleScopeMode.SPECIFIED, List.of("BYR_1"), List.of("分销商1"),
                RoutingRuleScopeMode.ALL, List.of(), List.of(),
                RoutingRuleScopeMode.ALL, List.of(), List.of(),
                RoutingRuleRouteMode.FIXED, RoutingRuleScopeMode.SPECIFIED,
                List.of("SPL_A"), List.of("供应商A"), "SPL_A", "供应商A",
                List.of(
                        new PriorityView("STABLE", RoutingRuleStrategy.STABLE),
                        new PriorityView("PRICE", RoutingRuleStrategy.PRICE),
                        new PriorityView("COMPOSITE", RoutingRuleStrategy.COMPOSITE)),
                new WeightView(40, 30, 30),
                RoutingRuleStatus.ENABLED, "1001", LocalDateTime.now());
    }
}
