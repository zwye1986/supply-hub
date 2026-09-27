package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort.EncryptedPayload;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.tradecore.profile.domain.IntegrationMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.SettlementMethod;
import com.aifuli.module.supplychain.tradecore.profile.domain.ShippingSlaCode;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierContactDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDeliveryRegionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierFileRefDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierIntegrationModeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSettlementAccountDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSettlementLedgerDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierContactMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierDeliveryRegionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierFileRefMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierIntegrationModeMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingDependencyChangedEvent;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSettlementAccountMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSettlementLedgerMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.DeliveryRegionType;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_CONTACT_REQUIRED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_CREDIT_CODE_DUPLICATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_OPENING_ALREADY_DONE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_REGION_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_STATUS_CONFLICT;

/**
 * 供应商管理端档案命令。发货时效/结算/对接方式按封闭枚举落库；证件最多 5 个写入 file_ref。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#52-详情--新增--编辑}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierProfileCommandService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final Pattern ADCODE = Pattern.compile("^[0-9]{6}$");
    private static final int NAME_SHORT_LIMIT = 64;
    private static final String SUPPLIER_TYPE_STANDARD = "STANDARD_SUPPLIER";
    private static final String ENTRY_TYPE_DEPOSIT = "DEPOSIT";
    private static final String OPENING_ENTRY_KEY_PREFIX = "OPENING_BALANCE:";
    private static final String OPENING_AUDIT_NOTE = "供应商建档期初";
    private static final String OPENING_EVIDENCE_PREFIX = "supplier-opening:";
    private static final short SETTLEMENT_ACCOUNT_NORMAL = 10;
    private static final int MAX_FILE_KEYS = 5;
    private static final String FILE_LIMIT_MESSAGE = "最多5个文件";

    private final SupplierMapper supplierMapper;
    private final SupplierOfferMapper supplierOfferMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final SupplierContactMapper contactMapper;
    private final SupplierDeliveryRegionMapper regionMapper;
    private final SupplierSettlementAccountMapper settlementAccountMapper;
    private final SupplierSettlementLedgerMapper settlementLedgerMapper;
    private final SupplierIntegrationModeMapper integrationModeMapper;
    private final SupplierFileRefMapper fileRefMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final ScopedBusinessCodeService scopedBusinessCodeService;
    private final EnvelopeCryptoPort envelopeCrypto;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String create(CreateSupplierProfileCommand command) {
        log.info("创建供应商档案 supplierName={} openingBalanceCents={} shippingSlaCode={} settlementMethod={} "
                        + "licenseFileCount={} qualificationFileCount={} operatorId={}",
                command.supplierName(), command.openingBalanceCents(), command.shippingSlaCode(),
                command.settlementMethod(),
                command.licenseFileKeys() == null ? 0 : command.licenseFileKeys().size(),
                command.qualificationFileKeys() == null ? 0 : command.qualificationFileKeys().size(),
                command.operatorId());
        validateClosedEnums(command.shippingSlaCode(), command.settlementMethod(), command.integrationModes());
        validateShippingSla(command.shippingSlaCode(), command.shippingSlaCustom());
        List<ContactLine> contacts = requireContacts(command.contacts());
        DeliveryRegionType regionType = DeliveryRegionType.fromCode(command.deliveryRegionType());
        List<String> adcodes = normalizeAdcodes(regionType, command.deliveryRegionAdcodes());
        if (command.openingBalanceCents() < 0) {
            throw new IllegalArgumentException("Opening balance cannot be negative");
        }
        LocalDateTime now = databaseClock.now();
        LocalDateTime onboardedAt = normalizeOnboardedAt(command.onboardedAt());
        if (onboardedAt == null) {
            onboardedAt = now;
        }
        String supplierCode = scopedBusinessCodeService.allocateRoot(ScopedBusinessCodeService.Kind.SPL,
                null, command.operatorId()).code();
        String shortName = truncate(command.supplierName(), NAME_SHORT_LIMIT);
        SupplierDO supplier = SupplierDO.builder()
                .supplierCode(supplierCode)
                .supplierName(command.supplierName())
                .shortName(shortName)
                .supplierType(SUPPLIER_TYPE_STANDARD)
                .legalEntityName(command.legalEntityName())
                .creditCode(command.creditCode())
                .status(command.status() == null
                        ? SupplierStatus.ACTIVE.getCode() : command.status().getCode())
                .deliveryRegionType(regionType.name())
                .aftersalesSupported(Boolean.TRUE.equals(command.aftersalesSupported()) ? (short) 1 : (short) 0)
                .shippingSlaCode(blankToNull(command.shippingSlaCode()))
                .shippingSlaCustom(customShippingSlaText(command.shippingSlaCode(), command.shippingSlaCustom()))
                .settlementMethod(blankToNull(command.settlementMethod()))
                .settlementCycleDays(command.settlementCycleDays())
                .onboardedAt(onboardedAt)
                .cooperationNote(command.cooperationNote())
                .legalPersonName(command.legalPersonName())
                .legalPersonIdNo(blankToNull(command.legalPersonIdNo()))
                .contactAddress(command.contactAddress())
                .bankName(command.bankName())
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        try {
            supplierMapper.insert(supplier);
        } catch (DuplicateKeyException duplicate) {
            throw exception(SUPPLIER_CREDIT_CODE_DUPLICATE);
        }
        encryptSensitive(supplier, command);
        supplierMapper.updateById(supplier);
        replaceContacts(supplier.getId(), contacts, command.operatorId(), now);
        replaceRegions(supplier.getId(), adcodes, now);
        replaceIntegrationModes(supplier.getId(), command.integrationModes(), now);
        replaceFileRefs(supplier.getId(), requireFileKeys(command.licenseFileKeys(), command.licenseFileKey()),
                requireFileKeys(command.qualificationFileKeys(), command.qualificationFileKey()), now);
        openSettlementAccount(supplier, command.openingBalanceCents(), command.operatorId(),
                command.traceId(), now);
        return supplierCode;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void update(UpdateSupplierProfileCommand command) {
        log.info("更新供应商档案 supplierCode={} shippingSlaCode={} settlementMethod={} operatorId={}",
                command.supplierCode(), command.shippingSlaCode(), command.settlementMethod(), command.operatorId());
        if (command.openingBalanceCents() != null) {
            throw exception(SUPPLIER_OPENING_ALREADY_DONE);
        }
        validateClosedEnums(command.shippingSlaCode(), command.settlementMethod(), command.integrationModes());
        validateShippingSla(command.shippingSlaCode(), command.shippingSlaCustom());
        List<ContactLine> contacts = requireContacts(command.contacts());
        DeliveryRegionType regionType = DeliveryRegionType.fromCode(command.deliveryRegionType());
        List<String> adcodes = normalizeAdcodes(regionType, command.deliveryRegionAdcodes());
        SupplierDO supplier = requireForUpdate(command.supplierCode());
        LocalDateTime now = databaseClock.now();
        supplier.setSupplierName(command.supplierName());
        supplier.setShortName(truncate(command.supplierName(), NAME_SHORT_LIMIT));
        supplier.setLegalEntityName(command.legalEntityName());
        supplier.setCreditCode(command.creditCode());
        supplier.setDeliveryRegionType(regionType.name());
        supplier.setAftersalesSupported(Boolean.TRUE.equals(command.aftersalesSupported()) ? (short) 1 : (short) 0);
        supplier.setShippingSlaCode(blankToNull(command.shippingSlaCode()));
        supplier.setShippingSlaCustom(customShippingSlaText(command.shippingSlaCode(), command.shippingSlaCustom()));
        supplier.setSettlementMethod(blankToNull(command.settlementMethod()));
        supplier.setSettlementCycleDays(command.settlementCycleDays());
        LocalDateTime onboardedAt = normalizeOnboardedAt(command.onboardedAt());
        if (onboardedAt != null) {
            supplier.setOnboardedAt(onboardedAt);
        }
        supplier.setCooperationNote(command.cooperationNote());
        supplier.setLegalPersonName(command.legalPersonName());
        supplier.setLegalPersonIdNo(blankToNull(command.legalPersonIdNo()));
        supplier.setContactAddress(command.contactAddress());
        supplier.setBankName(command.bankName());
        supplier.setUpdatedBy(command.operatorId());
        supplier.setUpdatedAt(now);
        encryptSensitive(supplier, command);
        try {
            if (supplierMapper.updateById(supplier) != 1) {
                throw exception(SUPPLIER_STATUS_CONFLICT);
            }
        } catch (DuplicateKeyException duplicate) {
            throw exception(SUPPLIER_CREDIT_CODE_DUPLICATE);
        }
        replaceContacts(supplier.getId(), contacts, command.operatorId(), now);
        replaceRegions(supplier.getId(), adcodes, now);
        replaceIntegrationModes(supplier.getId(), command.integrationModes(), now);
        replaceFileRefs(supplier.getId(), requireFileKeys(command.licenseFileKeys(), command.licenseFileKey()),
                requireFileKeys(command.qualificationFileKeys(), command.qualificationFileKey()), now);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeStatus(String supplierCode, SupplierStatus target, Long operatorId) {
        log.info("变更供应商状态 supplierCode={} target={} operatorId={}", supplierCode, target, operatorId);
        SupplierDO supplier = requireForUpdate(supplierCode);
        SupplierStatus current = SupplierStatus.fromCode(supplier.getStatus());
        if (!current.canTransitionTo(target)) {
            throw exception(SUPPLIER_STATUS_CONFLICT);
        }
        supplier.setStatus(target.getCode());
        supplier.setUpdatedBy(operatorId);
        supplier.setUpdatedAt(databaseClock.now());
        if (supplierMapper.updateById(supplier) != 1) {
            throw exception(SUPPLIER_STATUS_CONFLICT);
        }
        // 供应商状态属于“最低有效供货价”的有效性条件；按SKU去重后同步切换价格版本与可售状态。
        // 设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#3-供货价与利润隐藏。
        supplierOfferMapper.selectPublishedBySupplier(supplier.getId()).stream()
                .map(offer -> offer.getPlatformSkuId())
                .distinct()
                .forEach(id -> eventPublisher.publishEvent(new PricingDependencyChangedEvent(id)));
    }

    private SupplierDO requireForUpdate(String supplierCode) {
        SupplierDO supplier = supplierMapper.selectByCodeForUpdate(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        return supplier;
    }

    private void encryptSensitive(SupplierDO supplier, SensitiveFields fields) {
        Long id = supplier.getId();
        EncryptedPayload mobile = encryptOptional(fields.legalPersonMobile(), id, "legalMobile");
        EncryptedPayload account = encryptOptional(fields.bankAccount(), id, "bankAccount");
        EncryptedPayload idFront = encryptOptional(fields.idFrontFileKey(), id, "idFront");
        EncryptedPayload idBack = encryptOptional(fields.idBackFileKey(), id, "idBack");
        supplier.setLegalPersonMobileCipher(mobile == null ? null : mobile.ciphertext());
        supplier.setBankAccountCipher(account == null ? null : account.ciphertext());
        String sensitiveVersion = firstKeyVersion(mobile, account, idFront, idBack);
        supplier.setBankAccountKeyVersion(account == null ? sensitiveVersion : account.keyVersion());
        if (supplier.getBankAccountKeyVersion() == null) {
            supplier.setBankAccountKeyVersion(sensitiveVersion);
        }
        supplier.setIdFrontCipher(idFront == null ? null : idFront.ciphertext());
        supplier.setIdBackCipher(idBack == null ? null : idBack.ciphertext());
        supplier.setDocumentKeyVersion(firstKeyVersion(idFront, idBack));
    }

    private EncryptedPayload encryptOptional(String plaintext, Long supplierId, String field) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        byte[] aad = ("supplier:" + supplierId + ":" + field).getBytes(StandardCharsets.UTF_8);
        return envelopeCrypto.encrypt(plaintext.getBytes(StandardCharsets.UTF_8), aad);
    }

    private static String firstKeyVersion(EncryptedPayload... payloads) {
        for (EncryptedPayload payload : payloads) {
            if (payload != null) {
                return payload.keyVersion();
            }
        }
        return null;
    }

    private void replaceContacts(Long supplierId, List<ContactLine> contacts, Long operatorId, LocalDateTime now) {
        contactMapper.deleteBySupplierId(supplierId);
        int sort = 0;
        for (ContactLine contact : contacts) {
            contactMapper.insert(SupplierContactDO.builder()
                    .supplierId(supplierId)
                    .sortNo(sort++)
                    .contactName(contact.name())
                    .contactMobile(contact.mobile())
                    .contactEmail(contact.email())
                    .version(0L)
                    .createdBy(operatorId)
                    .createdAt(now)
                    .updatedBy(operatorId)
                    .updatedAt(now)
                    .build());
        }
    }

    private void replaceRegions(Long supplierId, List<String> adcodes, LocalDateTime now) {
        regionMapper.deleteBySupplierId(supplierId);
        for (String adcode : adcodes) {
            regionMapper.insert(SupplierDeliveryRegionDO.builder()
                    .supplierId(supplierId)
                    .adcode(adcode)
                    .createdAt(now)
                    .build());
        }
    }

    /**
     * 全量替换对接方式。先删后插，空列表表示清空。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#42-其它封闭枚举}。
     */
    private void replaceIntegrationModes(Long supplierId, List<String> integrationModes, LocalDateTime now) {
        integrationModeMapper.deleteBySupplierId(supplierId);
        if (integrationModes == null || integrationModes.isEmpty()) {
            return;
        }
        LinkedHashSet<String> modes = new LinkedHashSet<>();
        for (String mode : integrationModes) {
            IntegrationMode parsed = ClosedEnums.parseRequired(IntegrationMode.class, mode);
            modes.add(parsed.name());
        }
        for (String mode : modes) {
            integrationModeMapper.insert(SupplierIntegrationModeDO.builder()
                    .supplierId(supplierId)
                    .integrationMode(mode)
                    .createdAt(now)
                    .build());
        }
    }

    /**
     * 全量替换执照/资质文件引用，最多 5 个。新数据不写旧单列 {@code license_file_cipher}。
     * AAD 为 {@code supplier:{id}:LICENSE:{sort}} / {@code QUALIFICATION:{sort}}。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#52-详情--新增--编辑}。
     */
    private void replaceFileRefs(Long supplierId, List<String> licenseFileKeys,
                                  List<String> qualificationFileKeys, LocalDateTime now) {
        fileRefMapper.deleteBySupplierId(supplierId);
        insertFileRefs(supplierId, SupplierFileRefDO.FILE_ROLE_LICENSE, licenseFileKeys, now);
        insertFileRefs(supplierId, SupplierFileRefDO.FILE_ROLE_QUALIFICATION, qualificationFileKeys, now);
    }

    private void insertFileRefs(Long supplierId, String fileRole, List<String> fileKeys, LocalDateTime now) {
        int sort = 0;
        for (String fileKey : fileKeys) {
            EncryptedPayload payload = encryptFileKey(fileKey, supplierId, fileRole, sort);
            fileRefMapper.insert(SupplierFileRefDO.builder()
                    .supplierId(supplierId)
                    .fileRole(fileRole)
                    .sortNo(sort)
                    .fileCipher(payload.ciphertext())
                    .keyVersion(payload.keyVersion())
                    .createdAt(now)
                    .build());
            sort++;
        }
    }

    private EncryptedPayload encryptFileKey(String plaintext, Long supplierId, String fileRole, int sort) {
        byte[] aad = ("supplier:" + supplierId + ":" + fileRole + ":" + sort).getBytes(StandardCharsets.UTF_8);
        return envelopeCrypto.encrypt(plaintext.getBytes(StandardCharsets.UTF_8), aad);
    }

    private void openSettlementAccount(SupplierDO supplier, long openingBalanceCents, Long operatorId,
                                         String traceId, LocalDateTime now) {
        SupplierSettlementAccountDO account = SupplierSettlementAccountDO.builder()
                .accountNo(businessNoGenerator.next(BusinessNoPrefixes.SSA))
                .supplierId(supplier.getId())
                .currency(Money.CURRENCY_CNY)
                .balance(openingBalanceCents)
                .frozen(0L)
                .status(SETTLEMENT_ACCOUNT_NORMAL)
                .version(0L)
                .createdBy(operatorId)
                .createdAt(now)
                .updatedBy(operatorId)
                .updatedAt(now)
                .build();
        settlementAccountMapper.insert(account);
        String evidenceRef = OPENING_EVIDENCE_PREFIX + supplier.getSupplierCode();
        settlementLedgerMapper.insert(SupplierSettlementLedgerDO.builder()
                .ledgerNo(businessNoGenerator.next(BusinessNoPrefixes.SSL))
                .accountId(account.getId())
                .entryKey(OPENING_ENTRY_KEY_PREFIX + supplier.getId() + ":" + Money.CURRENCY_CNY)
                .entryType(ENTRY_TYPE_DEPOSIT)
                .balanceDelta(openingBalanceCents)
                .frozenDelta(0L)
                .balanceBefore(0L)
                .balanceAfter(openingBalanceCents)
                .frozenBefore(0L)
                .frozenAfter(0L)
                .operatorId(operatorId)
                .evidenceRef(evidenceRef)
                .evidenceDigest(sha256(evidenceRef))
                .auditNote(OPENING_AUDIT_NOTE)
                .traceId(traceId == null || traceId.isBlank() ? "supplier-opening" : traceId)
                .causationId(supplier.getSupplierCode())
                .occurredAt(now)
                .createdAt(now)
                .build());
    }

    static void validateClosedEnums(String shippingSlaCode, String settlementMethod, List<String> integrationModes) {
        ClosedEnums.parse(ShippingSlaCode.class, shippingSlaCode);
        ClosedEnums.parse(SettlementMethod.class, settlementMethod);
        if (integrationModes != null) {
            for (String mode : integrationModes) {
                ClosedEnums.parseRequired(IntegrationMode.class, mode);
            }
        }
    }

    static void validateShippingSla(String shippingSlaCode, String shippingSlaCustom) {
        ShippingSlaCode parsed = ClosedEnums.parse(ShippingSlaCode.class, shippingSlaCode);
        if (parsed == ShippingSlaCode.CUSTOM && (shippingSlaCustom == null || shippingSlaCustom.isBlank())) {
            throw exception(ENUM_NOT_OPEN);
        }
    }

    private static String customShippingSlaText(String shippingSlaCode, String shippingSlaCustom) {
        ShippingSlaCode parsed = ClosedEnums.parse(ShippingSlaCode.class, shippingSlaCode);
        if (parsed != ShippingSlaCode.CUSTOM) {
            return null;
        }
        return shippingSlaCustom.trim();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    static List<String> requireFileKeys(List<String> keys, String fallback) {
        List<String> merged = new ArrayList<>();
        if (keys != null) {
            for (String key : keys) {
                if (key == null || key.isBlank()) {
                    continue;
                }
                merged.add(key.trim());
            }
        }
        if (merged.isEmpty() && notBlank(fallback)) {
            merged.add(fallback.trim());
        }
        if (merged.size() > MAX_FILE_KEYS) {
            throw new IllegalArgumentException(FILE_LIMIT_MESSAGE);
        }
        return merged;
    }

    static List<ContactLine> requireContacts(List<ContactLine> raw) {
        List<ContactLine> cleaned = new ArrayList<>();
        if (raw != null) {
            for (ContactLine line : raw) {
                if (line == null) {
                    continue;
                }
                boolean empty = isBlank(line.name()) && isBlank(line.mobile()) && isBlank(line.email());
                if (empty) {
                    continue;
                }
                if (isBlank(line.name()) || isBlank(line.mobile())) {
                    throw exception(SUPPLIER_CONTACT_REQUIRED);
                }
                cleaned.add(new ContactLine(line.name().trim(), line.mobile().trim(),
                        isBlank(line.email()) ? null : line.email().trim()));
            }
        }
        if (cleaned.isEmpty()) {
            throw exception(SUPPLIER_CONTACT_REQUIRED);
        }
        return cleaned;
    }

    static List<String> normalizeAdcodes(DeliveryRegionType regionType, List<String> raw) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (raw != null) {
            for (String item : raw) {
                if (item == null || item.isBlank()) {
                    continue;
                }
                String adcode = item.trim();
                if (!ADCODE.matcher(adcode).matches()) {
                    throw exception(SUPPLIER_REGION_INVALID);
                }
                codes.add(adcode);
            }
        }
        if (regionType == DeliveryRegionType.NATIONWIDE && !codes.isEmpty()) {
            throw exception(SUPPLIER_REGION_INVALID);
        }
        if (regionType == DeliveryRegionType.DESIGNATED && codes.isEmpty()) {
            throw exception(SUPPLIER_REGION_INVALID);
        }
        return List.copyOf(codes);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    /**
     * 管理端日期选择器空值常序列化为 Unix 0，不能当成真实接入时间。
     * 设计见 {@code docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md#1511-管理端档案原值回显}。
     */
    static LocalDateTime normalizeOnboardedAt(LocalDateTime value) {
        if (value == null || value.getYear() <= 1970) {
            return null;
        }
        return value;
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public interface SensitiveFields {
        String legalPersonMobile();
        String bankAccount();
        String licenseFileKey();
        String idFrontFileKey();
        String idBackFileKey();
        String qualificationFileKey();
    }

    /**
     * 供应商运营联系人。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#52-详情--新增--编辑}。
     */
    @Schema(description = "供应商运营联系人")
    public record ContactLine(
            @Schema(description = "联系人姓名") String name,
            @Schema(description = "联系人手机号") String mobile,
            @Schema(description = "联系人邮箱") String email) {
    }

    public record CreateSupplierProfileCommand(
            String supplierName,
            List<ContactLine> contacts,
            String deliveryRegionType,
            List<String> deliveryRegionAdcodes,
            Boolean aftersalesSupported,
            String shippingSlaCode,
            String shippingSlaCustom,
            String settlementMethod,
            Integer settlementCycleDays,
            List<String> integrationModes,
            LocalDateTime onboardedAt,
            SupplierStatus status,
            String cooperationNote,
            String legalEntityName,
            String creditCode,
            String legalPersonName,
            String legalPersonIdNo,
            String legalPersonMobile,
            String contactAddress,
            String bankName,
            String bankAccount,
            String licenseFileKey,
            String idFrontFileKey,
            String idBackFileKey,
            String qualificationFileKey,
            List<String> licenseFileKeys,
            List<String> qualificationFileKeys,
            long openingBalanceCents,
            Long operatorId,
            String traceId) implements SensitiveFields {
    }

    public record UpdateSupplierProfileCommand(
            String supplierCode,
            String supplierName,
            List<ContactLine> contacts,
            String deliveryRegionType,
            List<String> deliveryRegionAdcodes,
            Boolean aftersalesSupported,
            String shippingSlaCode,
            String shippingSlaCustom,
            String settlementMethod,
            Integer settlementCycleDays,
            List<String> integrationModes,
            LocalDateTime onboardedAt,
            String cooperationNote,
            String legalEntityName,
            String creditCode,
            String legalPersonName,
            String legalPersonIdNo,
            String legalPersonMobile,
            String contactAddress,
            String bankName,
            String bankAccount,
            String licenseFileKey,
            String idFrontFileKey,
            String idBackFileKey,
            String qualificationFileKey,
            List<String> licenseFileKeys,
            List<String> qualificationFileKeys,
            Long openingBalanceCents,
            Long operatorId) implements SensitiveFields {
    }
}
