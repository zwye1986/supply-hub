package com.aifuli.module.supplychain.tradecore.profile.application;

import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.tradecore.profile.domain.InvoiceTitleType;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INVOICE_FIELD_INVALID;

/**
 * 供应商与分销商共用的开票保存校验。电话选填，个人仅清空信用代码，银行信息按提交值保留。
 * GET 预填不走本类；保存统一将空白字符串归一化为 null，不回写主体档案。
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
        if (title == null) {
            throw exception(INVOICE_FIELD_INVALID);
        }
        if (type == InvoiceTitleType.PERSONAL) {
            return new Normalized(type, title, phoneValue, null,
                    blankToNull(bankName), blankToNull(bankAccount), blankToNull(invoiceAddress));
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
