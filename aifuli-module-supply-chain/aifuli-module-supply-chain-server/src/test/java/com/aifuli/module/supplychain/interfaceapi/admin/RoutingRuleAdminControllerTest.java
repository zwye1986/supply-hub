package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService;
import org.junit.jupiter.api.Test;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_WRITE_NOT_OPEN;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 路由规则写入口一律拒绝，避免页面关 mock 后误以为综合排序已生效。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md#33-写入口拒绝}。
 */
class RoutingRuleAdminControllerTest {

    private final RoutingRuleAdminController controller =
            new RoutingRuleAdminController(mock(RoutingRuleAdminQueryService.class));

    @Test
    void writesAlwaysRejected() {
        assertWriteRejected(() -> controller.create());
        assertWriteRejected(() -> controller.update("RR_1"));
        assertWriteRejected(() -> controller.delete("RR_1"));
        assertWriteRejected(() -> controller.changeStatus("RR_1"));
    }

    private static void assertWriteRejected(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_WRITE_NOT_OPEN.getCode());
    }
}
