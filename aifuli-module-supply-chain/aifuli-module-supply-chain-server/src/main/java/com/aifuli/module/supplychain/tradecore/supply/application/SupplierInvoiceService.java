package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.profile.application.InvoiceFieldRules;
import com.aifuli.module.supplychain.tradecore.profile.domain.InvoiceTitleType;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierContactDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierInvoiceDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierContactMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierInvoiceMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;

/**
 * 供应商开票查询与保存。无行时用档案预填且不 insert；保存明文开票表，不回写档案。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierInvoiceService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final SupplyChainDatabaseClock databaseClock;
    private final EnvelopeCryptoPort envelopeCrypto;
    private final SupplierMapper supplierMapper;
    private final SupplierContactMapper contactMapper;
    private final SupplierInvoiceMapper invoiceMapper;

    public SupplierInvoiceView get(String supplierCode) {
        SupplierDO supplier = requireSupplier(supplierCode);
        SupplierInvoiceDO row = invoiceMapper.selectBySupplierId(supplier.getId());
        if (row != null) {
            log.info("查询供应商开票 supplierCode={} persisted=true titleType={}",
                    supplierCode, row.getTitleType());
            return toView(supplier.getSupplierCode(), row, true);
        }
        SupplierInvoiceView prefilled = prefill(supplier);
        log.info("查询供应商开票 supplierCode={} persisted=false titleType={}",
                supplierCode, prefilled.titleType());
        return prefilled;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public SupplierInvoiceView save(String supplierCode, SaveInvoiceCommand command, Long operatorId) {
        SupplierDO supplier = supplierMapper.selectByCodeForUpdate(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        InvoiceFieldRules.Normalized fields = InvoiceFieldRules.normalizeForSave(
                command.titleType(), command.invoiceTitle(), command.phone(),
                command.socialCreditCode(), command.bankName(), command.bankAccount(),
                command.invoiceAddress());
        LocalDateTime now = databaseClock.now();
        SupplierInvoiceDO row = invoiceMapper.selectBySupplierId(supplier.getId());
        if (row == null) {
            row = SupplierInvoiceDO.builder()
                    .supplierId(supplier.getId())
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
        log.info("保存供应商开票 supplierCode={} persisted=true titleType={}",
                supplierCode, fields.titleTypeName());
        return toView(supplier.getSupplierCode(), invoiceMapper.selectBySupplierId(supplier.getId()), true);
    }

    private SupplierDO requireSupplier(String supplierCode) {
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        return supplier;
    }

    private SupplierInvoiceView prefill(SupplierDO supplier) {
        List<SupplierContactDO> contacts = contactMapper.selectBySupplierId(supplier.getId());
        String phone = contacts.isEmpty() ? null : InvoiceFieldRules.blankToNull(contacts.getFirst().getContactMobile());
        return new SupplierInvoiceView(
                null,
                false,
                supplier.getSupplierCode(),
                InvoiceTitleType.ENTERPRISE.name(),
                InvoiceFieldRules.firstNonBlank(supplier.getLegalEntityName(), supplier.getSupplierName()),
                InvoiceFieldRules.blankToNull(supplier.getCreditCode()),
                phone,
                InvoiceFieldRules.blankToNull(supplier.getBankName()),
                decryptBankAccount(supplier),
                InvoiceFieldRules.blankToNull(supplier.getContactAddress()),
                null,
                null);
    }

    private String decryptBankAccount(SupplierDO supplier) {
        byte[] cipher = supplier.getBankAccountCipher();
        String keyVersion = supplier.getBankAccountKeyVersion();
        if (cipher == null || cipher.length == 0 || keyVersion == null) {
            return null;
        }
        byte[] aad = ("supplier:" + supplier.getId() + ":bankAccount").getBytes(StandardCharsets.UTF_8);
        return new String(envelopeCrypto.decrypt(cipher, keyVersion, aad), StandardCharsets.UTF_8);
    }

    private static SupplierInvoiceView toView(String supplierCode, SupplierInvoiceDO row, boolean persisted) {
        return new SupplierInvoiceView(
                row.getId(),
                persisted,
                supplierCode,
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

    public record SupplierInvoiceView(
            @Schema(description = "供应商开票信息内部主键") Long id,
            @Schema(description = "是否已有开票行") boolean persisted,
            @Schema(description = "供应商永久业务编码") String supplierCode,
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
