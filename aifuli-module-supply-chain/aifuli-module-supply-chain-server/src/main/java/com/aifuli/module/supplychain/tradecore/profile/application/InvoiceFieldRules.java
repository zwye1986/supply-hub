package com.aifuli.module.supplychain.tradecore.profile.application;

import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.tradecore.profile.domain.InvoiceTitleType;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INVOICE_FIELD_INVALID;

/**
 * 开票保存校验与个人抬头清空规则。GET 预填不走本类。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md#61-校验}。
 */
public final class InvoiceFieldRules {

    private InvoiceFieldRules() {
    }

    public static InvoiceTitleType requireTitleType(String titleType) {
        return ClosedEnums.parseRequired(InvoiceTitleType.class, titleType);
    }

    public static Normalized normalizeForSave(String titleType, String invoiceTitle, String phone,
                                               String socialCreditCode, String bankName,
                                               String bankAccount, String invoiceAddress) {
        InvoiceTitleType type = requireTitleType(titleType);
        String title = blankToNull(invoiceTitle);
        String phoneValue = blankToNull(phone);
        if (title == null || phoneValue == null) {
            throw exception(INVOICE_FIELD_INVALID);
        }
        if (type == InvoiceTitleType.PERSONAL) {
            return new Normalized(type, title, phoneValue, null, null, null, blankToNull(invoiceAddress));
        }
        String credit = blankToNull(socialCreditCode);
        String bank = blankToNull(bankName);
        String account = blankToNull(bankAccount);
        if (credit == null || bank == null || account == null) {
            throw exception(INVOICE_FIELD_INVALID);
        }
        return new Normalized(type, title, phoneValue, credit, bank, account, blankToNull(invoiceAddress));
    }

    public static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    public record Normalized(
            InvoiceTitleType titleType,
            String invoiceTitle,
            String phone,
            String socialCreditCode,
            String bankName,
            String bankAccount,
            String invoiceAddress) {
        public String titleTypeName() {
            return titleType.name();
        }
    }
}
