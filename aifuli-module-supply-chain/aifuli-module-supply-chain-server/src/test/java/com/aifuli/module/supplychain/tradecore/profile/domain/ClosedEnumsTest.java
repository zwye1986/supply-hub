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
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ENUM_NOT_OPEN.getCode());
        assertThat(ClosedEnums.parseRequired(L2AdjustMode.class, "EQUAL_L1"))
                .isEqualTo(L2AdjustMode.EQUAL_L1);
        assertThatThrownBy(() -> ClosedEnums.parseRequired(L2AdjustMode.class, null))
                .isInstanceOf(ServiceException.class);
    }
}
