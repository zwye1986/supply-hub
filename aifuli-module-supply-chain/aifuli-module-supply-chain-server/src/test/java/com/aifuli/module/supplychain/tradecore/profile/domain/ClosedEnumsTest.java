package com.aifuli.module.supplychain.tradecore.profile.domain;

import com.aifuli.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClosedEnumsTest {

    @Test
    void blankIsNullAndUnknownThrowsEnumNotOpen() {
        assertThat(ClosedEnums.parse(ShippingSlaCode.class, null)).isNull();
        assertThat(ClosedEnums.parse(ShippingSlaCode.class, "  ")).isNull();
        assertThat(ClosedEnums.parse(ShippingSlaCode.class, "NEXT_DAY"))
                .isEqualTo(ShippingSlaCode.NEXT_DAY);
        assertThatThrownBy(() -> ClosedEnums.parse(ShippingSlaCode.class, "same-day"))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException se = (ServiceException) ex;
                    assertThat(se.getCode()).isEqualTo(ENUM_NOT_OPEN.getCode());
                    assertThat(se.getMessage()).isEqualTo(
                            "未知枚举值：ShippingSlaCode 不支持 same-day，允许值：SAME_DAY、NEXT_DAY、HOURS_48、HOURS_72、CUSTOM");
                });
        assertThat(ClosedEnums.parseRequired(L2AdjustMode.class, "EQUAL_L1"))
                .isEqualTo(L2AdjustMode.EQUAL_L1);
        assertThatThrownBy(() -> ClosedEnums.parseRequired(L2AdjustMode.class, null))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException se = (ServiceException) ex;
                    assertThat(se.getCode()).isEqualTo(ENUM_NOT_OPEN.getCode());
                    assertThat(se.getMessage()).isEqualTo(
                            "未知枚举值：L2AdjustMode 不能为空，允许值：FOLLOW_TEMPLATE、EQUAL_L1、CUSTOM");
                });
    }

    /**
     * 复现管理端把类目树编码写入 productClassification 时，必须回显类型、提交值与允许值。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#413-spusku-复合创建postsupply-chainproductsspus}。
     */
    @Test
    void unknownProductClassificationIncludesTypeValueAndAllowed() {
        assertThatThrownBy(() -> ClosedEnums.parseRequired(ProductClassification.class, "E2E_L1_20260903"))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException se = (ServiceException) ex;
                    assertThat(se.getCode()).isEqualTo(ENUM_NOT_OPEN.getCode());
                    assertThat(se.getMessage()).isEqualTo(
                            "未知枚举值：ProductClassification 不支持 E2E_L1_20260903，允许值：ELECTRONICS、VIRTUAL_CARD、MOVIE_TICKET");
                });
    }
}
