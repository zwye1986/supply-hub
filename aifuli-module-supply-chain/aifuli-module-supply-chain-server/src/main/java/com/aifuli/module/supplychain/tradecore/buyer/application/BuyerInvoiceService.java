package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerContactDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerInvoiceDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerContactMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerInvoiceMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.profile.application.InvoiceFieldRules;
import com.aifuli.module.supplychain.tradecore.profile.domain.InvoiceTitleType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;

/**
 * 分销商开票查询与保存。无行时用档案预填且不 insert；保存明文开票表，不回写档案。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerInvoiceService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final SupplyChainDatabaseClock databaseClock;
    private final EnvelopeCryptoPort envelopeCrypto;
    private final BuyerMapper buyerMapper;
    private final BuyerContactMapper contactMapper;
    private final BuyerInvoiceMapper invoiceMapper;

    public BuyerInvoiceView get(String buyerCode) {
        BuyerDO buyer = requireBuyer(buyerCode);
        BuyerInvoiceDO row = invoiceMapper.selectByBuyerId(buyer.getId());
        if (row != null) {
            log.info("查询分销商开票 buyerCode={} persisted=true titleType={}", buyerCode, row.getTitleType());
            return toView(buyer.getBuyerCode(), row, true);
        }
        BuyerInvoiceView prefilled = prefill(buyer);
        log.info("查询分销商开票 buyerCode={} persisted=false titleType={}",
                buyerCode, prefilled.titleType());
        return prefilled;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public BuyerInvoiceView save(String buyerCode, SaveInvoiceCommand command, Long operatorId) {
        BuyerDO buyer = buyerMapper.selectByBuyerCodeForUpdate(buyerCode);
        if (buyer == null) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        InvoiceFieldRules.Normalized fields = InvoiceFieldRules.normalizeForSave(
                command.titleType(), command.invoiceTitle(), command.phone(),
                command.socialCreditCode(), command.bankName(), command.bankAccount(),
                command.invoiceAddress());
        LocalDateTime now = databaseClock.now();
        BuyerInvoiceDO row = invoiceMapper.selectByBuyerId(buyer.getId());
        if (row == null) {
            row = BuyerInvoiceDO.builder()
                    .buyerId(buyer.getId())
                    .version(0L)
                    .createdBy(operatorId)
                    .createdAt(now)
                    .build();
        }
        row.setTitleType(fields.titleTypeName());
        row.setInvoiceTitle(fields.invoiceTitle());
        row.setSocialCreditCode(fields.socialCreditCode());
        row.setPhone(fields.phone());
        row.setBankName(fields.bankName());
        row.setBankAccount(fields.bankAccount());
        row.setInvoiceAddress(fields.invoiceAddress());
        row.setUpdatedBy(operatorId);
        row.setUpdatedAt(now);
        if (row.getId() == null) {
            invoiceMapper.insert(row);
        } else {
            invoiceMapper.updateById(row);
        }
        log.info("保存分销商开票 buyerCode={} persisted=true titleType={}", buyerCode, fields.titleTypeName());
        return toView(buyer.getBuyerCode(), invoiceMapper.selectByBuyerId(buyer.getId()), true);
    }

    private BuyerDO requireBuyer(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode);
        if (buyer == null) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        return buyer;
    }

    private BuyerInvoiceView prefill(BuyerDO buyer) {
        List<BuyerContactDO> contacts = contactMapper.selectByBuyerId(buyer.getId());
        String phone = contacts.isEmpty()
                ? InvoiceFieldRules.blankToNull(buyer.getContactMobile())
                : InvoiceFieldRules.blankToNull(contacts.getFirst().getContactMobile());
        return new BuyerInvoiceView(
                null,
                false,
                buyer.getBuyerCode(),
                InvoiceTitleType.ENTERPRISE.name(),
                InvoiceFieldRules.firstNonBlank(buyer.getLegalEntityName(), buyer.getBuyerName()),
                InvoiceFieldRules.blankToNull(buyer.getCreditCode()),
                phone,
                InvoiceFieldRules.blankToNull(buyer.getBankName()),
                decryptBankAccount(buyer),
                InvoiceFieldRules.blankToNull(buyer.getContactAddress()),
                null,
                null);
    }

    private String decryptBankAccount(BuyerDO buyer) {
        byte[] cipher = buyer.getBankAccountCipher();
        String keyVersion = buyer.getBankAccountKeyVersion();
        if (cipher == null || cipher.length == 0 || keyVersion == null) {
            return null;
        }
        byte[] aad = ("buyer:" + buyer.getId() + ":bankAccount").getBytes(StandardCharsets.UTF_8);
        return new String(envelopeCrypto.decrypt(cipher, keyVersion, aad), StandardCharsets.UTF_8);
    }

    private static BuyerInvoiceView toView(String buyerCode, BuyerInvoiceDO row, boolean persisted) {
        return new BuyerInvoiceView(
                row.getId(),
                persisted,
                buyerCode,
                row.getTitleType(),
                row.getInvoiceTitle(),
                row.getSocialCreditCode(),
                row.getPhone(),
                row.getBankName(),
                row.getBankAccount(),
                row.getInvoiceAddress(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    public record SaveInvoiceCommand(
            String titleType,
            String invoiceTitle,
            String socialCreditCode,
            String phone,
            String bankName,
            String bankAccount,
            String invoiceAddress) {
    }

    public record BuyerInvoiceView(
            @Schema(description = "分销商开票信息内部主键") Long id,
            @Schema(description = "是否已有开票行") boolean persisted,
            @Schema(description = "采购商永久业务编码") String buyerCode,
            @Schema(description = "抬头类型：ENTERPRISE企业，PERSONAL个人") String titleType,
            @Schema(description = "开票抬头") String invoiceTitle,
            @Schema(description = "统一社会信用代码") String socialCreditCode,
            @Schema(description = "电话") String phone,
            @Schema(description = "开户行") String bankName,
            @Schema(description = "开户账号") String bankAccount,
            @Schema(description = "开票地址") String invoiceAddress,
            @Schema(description = "创建时间，Asia/Shanghai") LocalDateTime createdAt,
            @Schema(description = "最后修改时间，Asia/Shanghai") LocalDateTime updatedAt) {
    }
}
