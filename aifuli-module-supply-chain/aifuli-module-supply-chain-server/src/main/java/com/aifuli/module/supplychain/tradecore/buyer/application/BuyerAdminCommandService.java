package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort.EncryptedPayload;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService.OpeningBalanceCommand;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerCategoryScopeDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerContactDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerCooperationModeDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerFileRefDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerIntegrationModeDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerCategoryScopeMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerContactMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerCooperationModeMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerFileRefMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerIntegrationModeMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerHierarchy;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerHierarchy.ParentRef;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerScopeKind;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductTypeValidationService;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.tradecore.profile.domain.CooperationMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.DistributorType;
import com.aifuli.module.supplychain.tradecore.profile.domain.IntegrationMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.SellableCategory;
import com.aifuli.module.supplychain.tradecore.profile.domain.SettlementMethod;
import com.aifuli.module.supplychain.tradecore.profile.domain.SupplyPreference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_CREDIT_CODE_DUPLICATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_OPENING_ALREADY_DONE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PARENT_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_STATUS_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_CONTACT_REQUIRED;

/**
 * 分销商管理端档案命令：建档走采购商账本期初，不写供应商结算账户、不插入租户绑定。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerAdminCommandService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final int NAME_SHORT_LIMIT = 64;
    private static final String BUYER_TYPE_SAAS_MALL = "SAAS_MALL";
    private static final int MAX_FILE_KEYS = 5;
    private static final String FILE_LIMIT_MESSAGE = "最多5个文件";
    private static final String OPENING_AUDIT_NOTE = "管理端分销商期初";
    private static final String DEFAULT_OPENING_TRACE = "buyer-opening";
    private static final int DEFAULT_DISTRIBUTION_LEVEL = 1;

    private final BuyerMapper buyerMapper;
    private final BuyerContactMapper contactMapper;
    private final BuyerIntegrationModeMapper integrationModeMapper;
    private final BuyerCooperationModeMapper cooperationModeMapper;
    private final BuyerCategoryScopeMapper categoryScopeMapper;
    private final BuyerFileRefMapper fileRefMapper;
    private final BuyerAccountOpeningService accountOpeningService;
    private final BusinessNoGenerator businessNoGenerator;
    private final EnvelopeCryptoPort envelopeCrypto;
    private final ProductTypeValidationService productTypeValidationService;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String create(CreateBuyerCommand command) {
        log.info("创建分销商档案 buyerName={} openingBalanceCents={} operatorId={}",
                command.buyerName(), command.openingBalanceCents(), command.operatorId());
        validateClosedEnums(command.distributorType(), command.supplyPreference(),
                command.settlementMethod(), command.cooperationModes(), command.integrationModes());
        List<ContactLine> contacts = requireContacts(command.contacts());
        BuyerHierarchy.Result tree = resolveTree(null, command.distributionLevel(), command.parentBuyerCode());
        long cents = command.openingBalanceCents() == null ? 0L : command.openingBalanceCents();
        if (cents < 0) {
            throw new IllegalArgumentException("Opening balance cannot be negative");
        }
        LocalDateTime now = databaseClock.now();
        String buyerCode = businessNoGenerator.next(BusinessNoPrefixes.BYR);
        ContactLine primary = contacts.getFirst();
        BuyerDO buyer = BuyerDO.builder()
                .buyerCode(buyerCode)
                .buyerName(command.buyerName())
                .shortName(truncate(command.buyerName(), NAME_SHORT_LIMIT))
                .buyerType(BUYER_TYPE_SAAS_MALL)
                .status(BuyerStatus.ACTIVE.getCode())
                .parentBuyerId(tree.parentBuyerId())
                .distributionLevel((short) tree.level())
                .distributorType(blankToNull(command.distributorType()))
                .supplyPreference(blankToNull(command.supplyPreference()))
                .settlementMethod(blankToNull(command.settlementMethod()))
                .settlementCycleDays(command.settlementCycleDays())
                .cooperationStartedAt(command.cooperationStartedAt())
                .cooperationNote(command.cooperationNote())
                .legalEntityName(command.legalEntityName())
                .creditCode(command.creditCode())
                .legalPersonName(command.legalPersonName())
                .contactAddress(command.contactAddress())
                .bankName(command.bankName())
                .contactName(primary.name())
                .contactMobile(primary.mobile())
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        try {
            buyerMapper.insert(buyer);
        } catch (DuplicateKeyException duplicate) {
            throw exception(BUYER_CREDIT_CODE_DUPLICATE);
        }
        encryptSensitive(buyer, command);
        buyerMapper.updateById(buyer);
        replaceContacts(buyer.getId(), contacts, command.operatorId(), now);
        replaceIntegrationModes(buyer.getId(), command.integrationModes(), now);
        replaceCooperationModes(buyer.getId(), command.cooperationModes(), now);
        replaceCategoryScopes(buyer.getId(), command.sellableCategories(), command.productTypes(), now);
        replaceFileRefs(buyer.getId(), requireFileKeys(command.licenseFileKeys(), command.licenseFileKey()),
                requireFileKeys(command.qualificationFileKeys(), command.qualificationFileKey()), now);
        openBuyerAccount(buyer, cents, command.operatorId(), command.traceId());
        return buyerCode;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void update(UpdateBuyerCommand command) {
        log.info("更新分销商档案 buyerCode={} openingBalanceCents={} operatorId={}",
                command.buyerCode(), command.openingBalanceCents(), command.operatorId());
        if (command.openingBalanceCents() != null) {
            throw exception(BUYER_OPENING_ALREADY_DONE);
        }
        validateClosedEnums(command.distributorType(), command.supplyPreference(),
                command.settlementMethod(), command.cooperationModes(), command.integrationModes());
        List<ContactLine> contacts = requireContacts(command.contacts());
        BuyerDO buyer = requireForUpdate(command.buyerCode());
        BuyerHierarchy.Result tree = resolveTree(buyer.getId(), command.distributionLevel(),
                command.parentBuyerCode());
        LocalDateTime now = databaseClock.now();
        ContactLine primary = contacts.getFirst();
        buyer.setBuyerName(command.buyerName());
        buyer.setShortName(truncate(command.buyerName(), NAME_SHORT_LIMIT));
        buyer.setParentBuyerId(tree.parentBuyerId());
        buyer.setDistributionLevel((short) tree.level());
        buyer.setDistributorType(blankToNull(command.distributorType()));
        buyer.setSupplyPreference(blankToNull(command.supplyPreference()));
        buyer.setSettlementMethod(blankToNull(command.settlementMethod()));
        buyer.setSettlementCycleDays(command.settlementCycleDays());
        if (command.cooperationStartedAt() != null) {
            buyer.setCooperationStartedAt(command.cooperationStartedAt());
        }
        buyer.setCooperationNote(command.cooperationNote());
        buyer.setLegalEntityName(command.legalEntityName());
        buyer.setCreditCode(command.creditCode());
        buyer.setLegalPersonName(command.legalPersonName());
        buyer.setContactAddress(command.contactAddress());
        buyer.setBankName(command.bankName());
        buyer.setContactName(primary.name());
        buyer.setContactMobile(primary.mobile());
        buyer.setUpdatedBy(command.operatorId());
        buyer.setUpdatedAt(now);
        encryptSensitive(buyer, command);
        try {
            if (buyerMapper.updateById(buyer) != 1) {
                throw exception(BUYER_STATUS_CONFLICT);
            }
        } catch (DuplicateKeyException duplicate) {
            throw exception(BUYER_CREDIT_CODE_DUPLICATE);
        }
        replaceContacts(buyer.getId(), contacts, command.operatorId(), now);
        replaceIntegrationModes(buyer.getId(), command.integrationModes(), now);
        replaceCooperationModes(buyer.getId(), command.cooperationModes(), now);
        replaceCategoryScopes(buyer.getId(), command.sellableCategories(), command.productTypes(), now);
        replaceFileRefs(buyer.getId(), requireFileKeys(command.licenseFileKeys(), command.licenseFileKey()),
                requireFileKeys(command.qualificationFileKeys(), command.qualificationFileKey()), now);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeStatus(String buyerCode, BuyerStatus target, Long operatorId) {
        log.info("变更分销商状态 buyerCode={} target={} operatorId={}", buyerCode, target, operatorId);
        if (target == null) {
            throw exception(ENUM_NOT_OPEN);
        }
        BuyerDO buyer = requireForUpdate(buyerCode);
        BuyerStatus current = BuyerStatus.fromCode(buyer.getStatus());
        if (!current.canTransitionTo(target)) {
            throw exception(BUYER_STATUS_CONFLICT);
        }
        buyer.setStatus(target.getCode());
        buyer.setUpdatedBy(operatorId);
        buyer.setUpdatedAt(databaseClock.now());
        if (buyerMapper.updateById(buyer) != 1) {
            throw exception(BUYER_STATUS_CONFLICT);
        }
    }

    /**
     * 解析组织树：加载上级、收集直接子等级与最多两层子孙 id，再交给 {@link BuyerHierarchy}。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#61-组织树}。
     */
    private BuyerHierarchy.Result resolveTree(Long currentBuyerId, Integer distributionLevel, String parentBuyerCode) {
        int level = distributionLevel == null ? DEFAULT_DISTRIBUTION_LEVEL : distributionLevel;
        ParentRef parent = loadParent(parentBuyerCode);
        Long parentId = parent == null ? null : parent.id();
        if (currentBuyerId != null) {
            List<BuyerDO> children = selectDirectChildren(currentBuyerId);
            List<Integer> childLevels = new ArrayList<>();
            for (BuyerDO child : children) {
                childLevels.add(child.getDistributionLevel() == null
                        ? DEFAULT_DISTRIBUTION_LEVEL : child.getDistributionLevel().intValue());
            }
            BuyerHierarchy.rejectCycle(currentBuyerId, parentId, collectDescendantIds(children));
            BuyerHierarchy.rejectLevelConflictWithChildren(level, childLevels);
        }
        return BuyerHierarchy.validate(level, parentId, parent);
    }

    private ParentRef loadParent(String parentBuyerCode) {
        if (isBlank(parentBuyerCode)) {
            return null;
        }
        BuyerDO parent = buyerMapper.selectByBuyerCode(parentBuyerCode.trim());
        if (parent == null || parent.getDistributionLevel() == null) {
            throw exception(BUYER_PARENT_INVALID);
        }
        return new ParentRef(parent.getId(), parent.getDistributionLevel().intValue());
    }

    private List<BuyerDO> selectDirectChildren(Long buyerId) {
        return buyerMapper.selectList(new LambdaQueryWrapper<BuyerDO>()
                .eq(BuyerDO::getParentBuyerId, buyerId));
    }

    /** 最多再查两层，覆盖三级树的全部子孙。 */
    private List<Long> collectDescendantIds(List<BuyerDO> children) {
        List<Long> ids = new ArrayList<>();
        for (BuyerDO child : children) {
            ids.add(child.getId());
        }
        if (ids.isEmpty()) {
            return ids;
        }
        List<BuyerDO> grandchildren = buyerMapper.selectList(new LambdaQueryWrapper<BuyerDO>()
                .in(BuyerDO::getParentBuyerId, ids));
        for (BuyerDO row : grandchildren) {
            ids.add(row.getId());
        }
        return ids;
    }

    private BuyerDO requireForUpdate(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCodeForUpdate(buyerCode);
        if (buyer == null) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        return buyer;
    }

    private void openBuyerAccount(BuyerDO buyer, long cents, Long operatorId, String traceId) {
        byte[] digest = sha256("OPENING_BALANCE:" + buyer.getId() + ":CNY");
        String resolvedTrace = isBlank(traceId) ? DEFAULT_OPENING_TRACE : traceId.trim();
        accountOpeningService.open(new OpeningBalanceCommand(
                buyer.getBuyerCode(), Money.cny(cents), operatorId,
                "ADMIN_OPENING:" + buyer.getId() + ":CNY", digest,
                OPENING_AUDIT_NOTE, resolvedTrace, buyer.getBuyerCode()));
    }

    private void encryptSensitive(BuyerDO buyer, SensitiveFields fields) {
        Long id = buyer.getId();
        EncryptedPayload mobile = encryptOptional(fields.legalPersonMobile(), id, "legalPersonMobile");
        EncryptedPayload account = encryptOptional(fields.bankAccount(), id, "bankAccount");
        EncryptedPayload idFront = encryptOptional(fields.idFrontFileKey(), id, "idFront");
        EncryptedPayload idBack = encryptOptional(fields.idBackFileKey(), id, "idBack");
        buyer.setLegalPersonMobileCipher(mobile == null ? null : mobile.ciphertext());
        buyer.setBankAccountCipher(account == null ? null : account.ciphertext());
        String sensitiveVersion = firstKeyVersion(mobile, account, idFront, idBack);
        buyer.setBankAccountKeyVersion(account == null ? sensitiveVersion : account.keyVersion());
        if (buyer.getBankAccountKeyVersion() == null) {
            buyer.setBankAccountKeyVersion(sensitiveVersion);
        }
        buyer.setIdFrontCipher(idFront == null ? null : idFront.ciphertext());
        buyer.setIdBackCipher(idBack == null ? null : idBack.ciphertext());
        buyer.setDocumentKeyVersion(firstKeyVersion(idFront, idBack));
    }

    private EncryptedPayload encryptOptional(String plaintext, Long buyerId, String field) {
        if (isBlank(plaintext)) {
            return null;
        }
        byte[] aad = ("buyer:" + buyerId + ":" + field).getBytes(StandardCharsets.UTF_8);
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

    private void replaceContacts(Long buyerId, List<ContactLine> contacts, Long operatorId, LocalDateTime now) {
        contactMapper.deleteByBuyerId(buyerId);
        int sort = 0;
        for (ContactLine contact : contacts) {
            contactMapper.insert(BuyerContactDO.builder()
                    .buyerId(buyerId)
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

    private void replaceIntegrationModes(Long buyerId, List<String> integrationModes, LocalDateTime now) {
        integrationModeMapper.deleteByBuyerId(buyerId);
        if (integrationModes == null || integrationModes.isEmpty()) {
            return;
        }
        LinkedHashSet<String> modes = new LinkedHashSet<>();
        for (String mode : integrationModes) {
            modes.add(ClosedEnums.parseRequired(IntegrationMode.class, mode).name());
        }
        for (String mode : modes) {
            integrationModeMapper.insert(BuyerIntegrationModeDO.builder()
                    .buyerId(buyerId)
                    .integrationMode(mode)
                    .createdAt(now)
                    .build());
        }
    }

    private void replaceCooperationModes(Long buyerId, List<String> cooperationModes, LocalDateTime now) {
        cooperationModeMapper.deleteByBuyerId(buyerId);
        if (cooperationModes == null || cooperationModes.isEmpty()) {
            return;
        }
        LinkedHashSet<String> modes = new LinkedHashSet<>();
        for (String mode : cooperationModes) {
            modes.add(ClosedEnums.parseRequired(CooperationMode.class, mode).name());
        }
        for (String mode : modes) {
            cooperationModeMapper.insert(BuyerCooperationModeDO.builder()
                    .buyerId(buyerId)
                    .cooperationMode(mode)
                    .createdAt(now)
                    .build());
        }
    }

    private void replaceCategoryScopes(Long buyerId, List<String> sellableCategories, List<String> productTypes,
                                       LocalDateTime now) {
        categoryScopeMapper.deleteByBuyerId(buyerId);
        insertCategoryScopes(buyerId, BuyerScopeKind.SELLABLE, SellableCategory.class, sellableCategories, now);
        insertProductTypeScopes(buyerId, productTypes, now);
    }

    private void insertProductTypeScopes(Long buyerId, List<String> productTypes, LocalDateTime now) {
        if (productTypes == null) {
            return;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String productType : productTypes) {
            unique.add(productTypeValidationService.requireActiveForUpdate(productType).getTypeCode());
        }
        for (String code : unique) {
            categoryScopeMapper.insert(BuyerCategoryScopeDO.builder()
                    .buyerId(buyerId)
                    .scopeKind(BuyerScopeKind.PRODUCT_TYPE.name())
                    .scopeCode(code)
                    .createdAt(now)
                    .build());
        }
    }

    private <E extends Enum<E>> void insertCategoryScopes(Long buyerId, BuyerScopeKind kind, Class<E> enumType,
                                                           List<String> codes, LocalDateTime now) {
        if (codes == null) {
            return;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String code : codes) {
            unique.add(ClosedEnums.parseRequired(enumType, code).name());
        }
        for (String code : unique) {
            categoryScopeMapper.insert(BuyerCategoryScopeDO.builder()
                    .buyerId(buyerId)
                    .scopeKind(kind.name())
                    .scopeCode(code)
                    .createdAt(now)
                    .build());
        }
    }

    /**
     * 全量替换执照/资质文件引用，最多 5 个。AAD 为 {@code buyer:{id}:LICENSE:{sort}}。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#52-详情--新增--编辑}。
     */
    private void replaceFileRefs(Long buyerId, List<String> licenseFileKeys,
                                  List<String> qualificationFileKeys, LocalDateTime now) {
        fileRefMapper.deleteByBuyerId(buyerId);
        insertFileRefs(buyerId, BuyerFileRefDO.FILE_ROLE_LICENSE, licenseFileKeys, now);
        insertFileRefs(buyerId, BuyerFileRefDO.FILE_ROLE_QUALIFICATION, qualificationFileKeys, now);
    }

    private void insertFileRefs(Long buyerId, String fileRole, List<String> fileKeys, LocalDateTime now) {
        int sort = 0;
        for (String fileKey : fileKeys) {
            EncryptedPayload payload = encryptFileKey(fileKey, buyerId, fileRole, sort);
            fileRefMapper.insert(BuyerFileRefDO.builder()
                    .buyerId(buyerId)
                    .fileRole(fileRole)
                    .sortNo(sort)
                    .fileCipher(payload.ciphertext())
                    .keyVersion(payload.keyVersion())
                    .createdAt(now)
                    .build());
            sort++;
        }
    }

    private EncryptedPayload encryptFileKey(String plaintext, Long buyerId, String fileRole, int sort) {
        byte[] aad = ("buyer:" + buyerId + ":" + fileRole + ":" + sort).getBytes(StandardCharsets.UTF_8);
        return envelopeCrypto.encrypt(plaintext.getBytes(StandardCharsets.UTF_8), aad);
    }

    static void validateClosedEnums(String distributorType, String supplyPreference, String settlementMethod,
                                    List<String> cooperationModes, List<String> integrationModes) {
        ClosedEnums.parse(DistributorType.class, distributorType);
        ClosedEnums.parse(SupplyPreference.class, supplyPreference);
        ClosedEnums.parse(SettlementMethod.class, settlementMethod);
        if (cooperationModes != null) {
            for (String mode : cooperationModes) {
                ClosedEnums.parseRequired(CooperationMode.class, mode);
            }
        }
        if (integrationModes != null) {
            for (String mode : integrationModes) {
                ClosedEnums.parseRequired(IntegrationMode.class, mode);
            }
        }
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

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
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

    public record ContactLine(String name, String mobile, String email) {
    }

    public record CreateBuyerCommand(
            String buyerName,
            List<ContactLine> contacts,
            String parentBuyerCode,
            Integer distributionLevel,
            String distributorType,
            String supplyPreference,
            String settlementMethod,
            Integer settlementCycleDays,
            List<String> cooperationModes,
            List<String> integrationModes,
            LocalDateTime cooperationStartedAt,
            String cooperationNote,
            String legalEntityName,
            String creditCode,
            String legalPersonName,
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
            List<String> sellableCategories,
            List<String> productTypes,
            Long openingBalanceCents,
            Long operatorId,
            String traceId) implements SensitiveFields {
    }

    public record UpdateBuyerCommand(
            String buyerCode,
            String buyerName,
            List<ContactLine> contacts,
            String parentBuyerCode,
            Integer distributionLevel,
            String distributorType,
            String supplyPreference,
            String settlementMethod,
            Integer settlementCycleDays,
            List<String> cooperationModes,
            List<String> integrationModes,
            LocalDateTime cooperationStartedAt,
            String cooperationNote,
            String legalEntityName,
            String creditCode,
            String legalPersonName,
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
            List<String> sellableCategories,
            List<String> productTypes,
            Long openingBalanceCents,
            Long operatorId) implements SensitiveFields {
    }
}
