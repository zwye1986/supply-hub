package com.aifuli.module.supplychain.tradecore.profile.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.profile.domain.InvoiceTitleType;
import org.junit.jupiter.api.Test;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INVOICE_FIELD_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 开票保存校验：企业必填银行字段，个人清空信用代码与账号。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md#61-校验}。
 */
class InvoiceFieldRulesTest {

    @Test
    void enterpriseRequiresBankFields() {
        InvoiceFieldRules.Normalized saved = InvoiceFieldRules.normalizeForSave(
                "ENTERPRISE", "甲公司", "13800000000", "91110000MA1K3ABC0X",
                "工商银行", "6222021001001234567", "上海");
        assertThat(saved.titleType()).isEqualTo(InvoiceTitleType.ENTERPRISE);
        assertThat(saved.socialCreditCode()).isEqualTo("91110000MA1K3ABC0X");
        assertThat(saved.bankAccount()).isEqualTo("6222021001001234567");
        assertThat(saved.invoiceAddress()).isEqualTo("上海");
    }

    @Test
    void personalClearsBankFieldsEvenIfSubmitted() {
        InvoiceFieldRules.Normalized saved = InvoiceFieldRules.normalizeForSave(
                "PERSONAL", "张三", "13800000000", "91110000MA1K3ABC0X",
                "工商银行", "6222021001001234567", " ");
        assertThat(saved.titleType()).isEqualTo(InvoiceTitleType.PERSONAL);
        assertThat(saved.socialCreditCode()).isNull();
        assertThat(saved.bankName()).isNull();
        assertThat(saved.bankAccount()).isNull();
        assertThat(saved.invoiceAddress()).isNull();
    }

    @Test
    void missingTitleOrPhoneIsInvalid() {
        assertThatThrownBy(() -> InvoiceFieldRules.normalizeForSave(
                "ENTERPRISE", " ", "13800000000", "91110000MA1K3ABC0X",
                "工商银行", "6222021001001234567", null))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(INVOICE_FIELD_INVALID.getCode());
        assertThatThrownBy(() -> InvoiceFieldRules.normalizeForSave(
                "PERSONAL", "张三", null, null, null, null, null))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(INVOICE_FIELD_INVALID.getCode());
    }

    @Test
    void enterpriseMissingBankAccountIsInvalid() {
        assertThatThrownBy(() -> InvoiceFieldRules.normalizeForSave(
                "ENTERPRISE", "甲公司", "13800000000", "91110000MA1K3ABC0X",
                "工商银行", "  ", null))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(INVOICE_FIELD_INVALID.getCode());
    }

    @Test
    void unknownTitleTypeIsEnumNotOpen() {
        assertThatThrownBy(() -> InvoiceFieldRules.normalizeForSave(
                "COMPANY", "甲公司", "13800000000", "91110000MA1K3ABC0X",
                "工商银行", "6222021001001234567", null))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ENUM_NOT_OPEN.getCode());
    }
}
