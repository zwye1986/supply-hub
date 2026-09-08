package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService.ContactLine;
import com.aifuli.module.supplychain.tradecore.supply.domain.DeliveryRegionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_CONTACT_REQUIRED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_REGION_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 供应商档案校验。设计见
 * {@code docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md#159-详情--编辑--基础信息}。
 */
class SupplierProfileCommandServiceTest {

    @Test
    void rejectsEmptyContactsAndBlankRowsWithoutName() {
        assertThatThrownBy(() -> SupplierProfileCommandService.requireContacts(List.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_CONTACT_REQUIRED.getCode());
        assertThatThrownBy(() -> SupplierProfileCommandService.requireContacts(
                List.of(new ContactLine(null, null, null))))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> SupplierProfileCommandService.requireContacts(
                List.of(new ContactLine("张三", null, null))))
                .isInstanceOf(ServiceException.class);
        assertThat(SupplierProfileCommandService.requireContacts(
                List.of(new ContactLine(" ", " ", " "), new ContactLine("张三", "13800138000", "a@b.c"))))
                .containsExactly(new ContactLine("张三", "13800138000", "a@b.c"));
    }

    @Test
    void nationwideForbidsAdcodesAndDesignatedRequiresThem() {
        assertThat(SupplierProfileCommandService.normalizeAdcodes(DeliveryRegionType.NATIONWIDE, List.of()))
                .isEmpty();
        assertThatThrownBy(() -> SupplierProfileCommandService.normalizeAdcodes(
                DeliveryRegionType.NATIONWIDE, List.of("110000")))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_REGION_INVALID.getCode());
        assertThatThrownBy(() -> SupplierProfileCommandService.normalizeAdcodes(
                DeliveryRegionType.DESIGNATED, List.of()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> SupplierProfileCommandService.normalizeAdcodes(
                DeliveryRegionType.DESIGNATED, List.of("11")))
                .isInstanceOf(ServiceException.class);
        assertThat(SupplierProfileCommandService.normalizeAdcodes(
                DeliveryRegionType.DESIGNATED, List.of("110000", "110000")))
                .containsExactly("110000");
    }

    @Test
    void closedEnumsPersistKnownValuesAndRejectUnknown() {
        SupplierProfileCommandService.validateClosedEnums("NEXT_DAY", "MONTHLY", List.of("API", "H5"));
        SupplierProfileCommandService.validateClosedEnums(null, null, null);
        assertThatThrownBy(() -> SupplierProfileCommandService.validateClosedEnums("same-day", null, null))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ENUM_NOT_OPEN.getCode());
    }

    @Test
    void customShippingSlaRequiresText() {
        assertThatThrownBy(() -> SupplierProfileCommandService.validateShippingSla("CUSTOM", null))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ENUM_NOT_OPEN.getCode());
        SupplierProfileCommandService.validateShippingSla("CUSTOM", "工作日18点前");
        SupplierProfileCommandService.validateShippingSla("NEXT_DAY", null);
    }

    @Test
    void requireFileKeysRejectsMoreThanFive() {
        assertThatThrownBy(() -> SupplierProfileCommandService.requireFileKeys(
                List.of("k1", "k2", "k3", "k4", "k5", "k6"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("最多5个文件");
    }

    @Test
    void requireFileKeysFallsBackToSingleFieldWhenArrayEmpty() {
        assertThat(SupplierProfileCommandService.requireFileKeys(List.of(), "legacy-key"))
                .containsExactly("legacy-key");
        assertThat(SupplierProfileCommandService.requireFileKeys(List.of("k1", "k2"), "legacy-key"))
                .containsExactly("k1", "k2");
        assertThat(SupplierProfileCommandService.requireFileKeys(null, null)).isEmpty();
    }

    @Test
    void treatsUnixEpochOnboardedAtAsMissing() {
        assertThat(SupplierProfileCommandService.normalizeOnboardedAt(null)).isNull();
        assertThat(SupplierProfileCommandService.normalizeOnboardedAt(
                java.time.LocalDateTime.of(1970, 1, 1, 0, 0))).isNull();
        assertThat(SupplierProfileCommandService.normalizeOnboardedAt(
                java.time.LocalDateTime.of(1969, 12, 31, 16, 0))).isNull();
        java.time.LocalDateTime onboarded = java.time.LocalDateTime.of(2026, 9, 3, 10, 0);
        assertThat(SupplierProfileCommandService.normalizeOnboardedAt(onboarded)).isEqualTo(onboarded);
    }
}
