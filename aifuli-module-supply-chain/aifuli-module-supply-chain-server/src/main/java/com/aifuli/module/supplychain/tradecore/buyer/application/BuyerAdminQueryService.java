package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.ContactLine;
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
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerScopeKind;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PARENT_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;

/**
 * 分销商管理端查询：列表多值筛选、simple / parent-options 投影、详情脱敏、直接下级。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerAdminQueryService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int PARENT_OPTION_LEVEL_TWO = 2;
    private static final int PARENT_OPTION_LEVEL_THREE = 3;

    private final BuyerMapper buyerMapper;
    private final BuyerContactMapper contactMapper;
    private final BuyerIntegrationModeMapper integrationModeMapper;
    private final BuyerCooperationModeMapper cooperationModeMapper;
    private final BuyerCategoryScopeMapper categoryScopeMapper;
    private final BuyerFileRefMapper fileRefMapper;
    private final BuyerAccountMapper accountMapper;
    private final EnvelopeCryptoPort envelopeCrypto;

    /**
     * 分页查询分销商。按更新时间倒序；归档行永不出现。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。
     */
    public PageResult<BuyerListItem> page(BuyerPageQuery query) {
        log.info("分页查询分销商 buyerName={} buyerCode={} distributionLevels={} cooperationModes={} "
                        + "sellableCategories={} statuses={} updatedFrom={} updatedTo={} pageNo={} pageSize={}",
                query.buyerName(), query.buyerCode(), query.distributionLevels(), query.cooperationModes(),
                query.sellableCategories(), query.statuses(), query.updatedFrom(), query.updatedTo(),
                query.pageNo(), query.pageSize());
        List<Short> statusCodes = toStatusCodes(query.statuses());
        LambdaQueryWrapper<BuyerDO> wrapper = new LambdaQueryWrapper<BuyerDO>()
                .like(hasText(query.buyerName()), BuyerDO::getBuyerName, query.buyerName())
                .eq(hasText(query.buyerCode()), BuyerDO::getBuyerCode, query.buyerCode())
                .in(hasValues(query.distributionLevels()), BuyerDO::getDistributionLevel,
                        toShorts(query.distributionLevels()))
                .in(!statusCodes.isEmpty(), BuyerDO::getStatus, statusCodes)
                .ge(query.updatedFrom() != null, BuyerDO::getUpdatedAt, query.updatedFrom())
                .le(query.updatedTo() != null, BuyerDO::getUpdatedAt, query.updatedTo())
                .ne(BuyerDO::getStatus, BuyerStatus.ARCHIVED.getCode())
                .orderByDesc(BuyerDO::getUpdatedAt);
        Set<Long> scopedIds = matchingBuyerIds(query.cooperationModes(), query.sellableCategories());
        if (scopedIds != null) {
            if (scopedIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
            wrapper.in(BuyerDO::getId, scopedIds);
        }
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(query.pageNo() == null ? DEFAULT_PAGE_NO : query.pageNo());
        pageParam.setPageSize(query.pageSize() == null ? DEFAULT_PAGE_SIZE : query.pageSize());
        PageResult<BuyerDO> page = buyerMapper.selectPage(pageParam, wrapper);
        List<BuyerListItem> items = new ArrayList<>();
        for (BuyerDO buyer : page.getList()) {
            items.add(toListItem(buyer));
        }
        return new PageResult<>(items, page.getTotal());
    }

    public List<SimpleBuyerItem> listSimple(String buyerName, String buyerCode) {
        log.info("简易分销商列表 buyerName={} buyerCode={}", buyerName, buyerCode);
        List<BuyerDO> buyers = buyerMapper.selectList(new LambdaQueryWrapper<BuyerDO>()
                .like(hasText(buyerName), BuyerDO::getBuyerName, buyerName)
                .eq(hasText(buyerCode), BuyerDO::getBuyerCode, buyerCode)
                .ne(BuyerDO::getStatus, BuyerStatus.ARCHIVED.getCode())
                .orderByDesc(BuyerDO::getUpdatedAt));
        List<SimpleBuyerItem> items = new ArrayList<>();
        for (BuyerDO buyer : buyers) {
            items.add(new SimpleBuyerItem(buyer.getBuyerCode(), buyer.getBuyerName()));
        }
        return items;
    }

    /**
     * 上级下拉：forLevel=2 只回一级，forLevel=3 只回二级；排除 excludeBuyerCode 及其子孙。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#61-组织树}。
     */
    public List<SimpleBuyerItem> listParentOptions(String excludeBuyerCode, Integer forLevel) {
        log.info("上级分销商下拉 excludeBuyerCode={} forLevel={}", excludeBuyerCode, forLevel);
        short requiredLevel = requiredParentLevel(forLevel);
        Set<Long> excludedIds = excludeIds(excludeBuyerCode);
        List<BuyerDO> buyers = buyerMapper.selectList(new LambdaQueryWrapper<BuyerDO>()
                .eq(BuyerDO::getDistributionLevel, requiredLevel)
                .ne(BuyerDO::getStatus, BuyerStatus.ARCHIVED.getCode())
                .orderByDesc(BuyerDO::getUpdatedAt));
        List<SimpleBuyerItem> items = new ArrayList<>();
        for (BuyerDO buyer : buyers) {
            if (excludedIds.contains(buyer.getId())) {
                continue;
            }
            items.add(new SimpleBuyerItem(buyer.getBuyerCode(), buyer.getBuyerName()));
        }
        return items;
    }

    public BuyerDetail get(String buyerCode) {
        log.info("查询分销商详情 buyerCode={}", buyerCode);
        BuyerDO buyer = requireBuyer(buyerCode);
        List<BuyerContactDO> contacts = contactMapper.selectByBuyerId(buyer.getId());
        List<BuyerFileRefDO> fileRefs = fileRefMapper.selectByBuyerId(buyer.getId());
        List<String> licenseFileKeys = decryptFileKeys(buyer.getId(), fileRefs, BuyerFileRefDO.FILE_ROLE_LICENSE);
        List<String> qualificationFileKeys = decryptFileKeys(buyer.getId(), fileRefs,
                BuyerFileRefDO.FILE_ROLE_QUALIFICATION);
        List<String> integrationModes = integrationModeMapper.selectByBuyerId(buyer.getId()).stream()
                .map(BuyerIntegrationModeDO::getIntegrationMode)
                .toList();
        List<String> cooperationModes = cooperationModeMapper.selectByBuyerId(buyer.getId()).stream()
                .map(BuyerCooperationModeDO::getCooperationMode)
                .toList();
        List<String> sellableCategories = new ArrayList<>();
        List<String> productTypes = new ArrayList<>();
        for (BuyerCategoryScopeDO scope : categoryScopeMapper.selectByBuyerId(buyer.getId())) {
            if (BuyerScopeKind.SELLABLE.name().equals(scope.getScopeKind())) {
                sellableCategories.add(scope.getScopeCode());
            } else if (BuyerScopeKind.PRODUCT_TYPE.name().equals(scope.getScopeKind())) {
                productTypes.add(scope.getScopeCode());
            }
        }
        BuyerAccountDO account = accountMapper.selectByBuyerId(buyer.getId());
        BuyerContactDO primary = contacts.isEmpty() ? null : contacts.getFirst();
        String parentBuyerCode = null;
        if (buyer.getParentBuyerId() != null) {
            BuyerDO parent = buyerMapper.selectById(buyer.getParentBuyerId());
            parentBuyerCode = parent == null ? null : parent.getBuyerCode();
        }
        return new BuyerDetail(
                buyer.getId(),
                buyer.getBuyerCode(),
                buyer.getBuyerName(),
                BuyerStatus.fromCode(buyer.getStatus()).name(),
                parentBuyerCode,
                buyer.getDistributionLevel() == null ? null : buyer.getDistributionLevel().intValue(),
                buyer.getDistributorType(),
                buyer.getSupplyPreference(),
                buyer.getSettlementMethod(),
                buyer.getSettlementCycleDays(),
                buyer.getCooperationStartedAt(),
                buyer.getCooperationNote(),
                buyer.getLegalEntityName(),
                buyer.getCreditCode(),
                buyer.getLegalPersonName(),
                maskMobile(decrypt(buyer.getLegalPersonMobileCipher(), buyer.getId(), "legalPersonMobile",
                        firstVersion(buyer))),
                buyer.getContactAddress(),
                buyer.getBankName(),
                maskAccount(decrypt(buyer.getBankAccountCipher(), buyer.getId(), "bankAccount",
                        firstVersion(buyer))),
                licenseFileKeys,
                qualificationFileKeys,
                integrationModes,
                cooperationModes,
                sellableCategories,
                productTypes,
                contacts.stream().map(c -> new ContactLine(
                        c.getContactName(), maskMobile(c.getContactMobile()), c.getContactEmail())).toList(),
                primary == null ? null : primary.getContactName(),
                primary == null ? null : maskMobile(primary.getContactMobile()),
                account == null || account.getBalance() == null ? 0L : account.getBalance());
    }

    /**
     * 直接下级列表，不嵌套孙节点。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#61-组织树}。
     */
    public List<BuyerChildItem> children(String buyerCode) {
        log.info("查询分销商直接下级 buyerCode={}", buyerCode);
        BuyerDO buyer = requireBuyer(buyerCode);
        List<BuyerDO> children = buyerMapper.selectList(new LambdaQueryWrapper<BuyerDO>()
                .eq(BuyerDO::getParentBuyerId, buyer.getId())
                .orderByDesc(BuyerDO::getUpdatedAt));
        List<BuyerChildItem> items = new ArrayList<>();
        for (BuyerDO child : children) {
            items.add(new BuyerChildItem(
                    child.getBuyerCode(),
                    child.getBuyerName(),
                    child.getDistributionLevel() == null ? null : child.getDistributionLevel().intValue(),
                    BuyerStatus.fromCode(child.getStatus()).name()));
        }
        return items;
    }

    private BuyerDO requireBuyer(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode);
        if (buyer == null) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        return buyer;
    }

    private short requiredParentLevel(Integer forLevel) {
        if (forLevel != null && forLevel == PARENT_OPTION_LEVEL_TWO) {
            return 1;
        }
        if (forLevel != null && forLevel == PARENT_OPTION_LEVEL_THREE) {
            return 2;
        }
        throw exception(BUYER_PARENT_INVALID);
    }

    private Set<Long> excludeIds(String excludeBuyerCode) {
        if (!hasText(excludeBuyerCode)) {
            return Set.of();
        }
        BuyerDO excluded = buyerMapper.selectByBuyerCode(excludeBuyerCode.trim());
        if (excluded == null) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        ids.add(excluded.getId());
        List<BuyerDO> children = buyerMapper.selectList(new LambdaQueryWrapper<BuyerDO>()
                .eq(BuyerDO::getParentBuyerId, excluded.getId()));
        List<Long> childIds = new ArrayList<>();
        for (BuyerDO child : children) {
            ids.add(child.getId());
            childIds.add(child.getId());
        }
        if (!childIds.isEmpty()) {
            List<BuyerDO> grandchildren = buyerMapper.selectList(new LambdaQueryWrapper<BuyerDO>()
                    .in(BuyerDO::getParentBuyerId, childIds));
            for (BuyerDO row : grandchildren) {
                ids.add(row.getId());
            }
        }
        return ids;
    }

    /**
     * 合作模式与可售品类筛选取交集；两者都空时返回 null 表示不加 id 约束。
     */
    private Set<Long> matchingBuyerIds(List<String> cooperationModes, List<String> sellableCategories) {
        Set<Long> byMode = null;
        if (hasValues(cooperationModes)) {
            byMode = new HashSet<>();
            for (BuyerCooperationModeDO row : cooperationModeMapper.selectList(
                    new LambdaQueryWrapper<BuyerCooperationModeDO>()
                            .in(BuyerCooperationModeDO::getCooperationMode, cooperationModes))) {
                byMode.add(row.getBuyerId());
            }
        }
        Set<Long> byCategory = null;
        if (hasValues(sellableCategories)) {
            byCategory = new HashSet<>();
            for (BuyerCategoryScopeDO row : categoryScopeMapper.selectList(
                    new LambdaQueryWrapper<BuyerCategoryScopeDO>()
                            .eq(BuyerCategoryScopeDO::getScopeKind, BuyerScopeKind.SELLABLE.name())
                            .in(BuyerCategoryScopeDO::getScopeCode, sellableCategories))) {
                byCategory.add(row.getBuyerId());
            }
        }
        if (byMode == null && byCategory == null) {
            return null;
        }
        if (byMode == null) {
            return byCategory;
        }
        if (byCategory == null) {
            return byMode;
        }
        byMode.retainAll(byCategory);
        return byMode;
    }

    private BuyerListItem toListItem(BuyerDO buyer) {
        LinkedHashSet<String> sellableCategories = new LinkedHashSet<>();
        for (BuyerCategoryScopeDO scope : categoryScopeMapper.selectByBuyerId(buyer.getId())) {
            if (BuyerScopeKind.SELLABLE.name().equals(scope.getScopeKind()) && scope.getScopeCode() != null) {
                sellableCategories.add(scope.getScopeCode());
            }
        }
        List<String> cooperationModes = cooperationModeMapper.selectByBuyerId(buyer.getId()).stream()
                .map(BuyerCooperationModeDO::getCooperationMode)
                .toList();
        List<BuyerContactDO> contacts = contactMapper.selectByBuyerId(buyer.getId());
        BuyerContactDO primary = contacts.isEmpty() ? null : contacts.getFirst();
        return new BuyerListItem(
                buyer.getId(),
                buyer.getBuyerCode(),
                buyer.getBuyerName(),
                buyer.getDistributionLevel() == null ? null : buyer.getDistributionLevel().intValue(),
                BuyerStatus.fromCode(buyer.getStatus()).name(),
                buyer.getUpdatedAt(),
                new ArrayList<>(sellableCategories),
                cooperationModes,
                primary == null ? null : primary.getContactName(),
                primary == null ? null : maskMobile(primary.getContactMobile()));
    }

    private static String firstVersion(BuyerDO buyer) {
        if (buyer.getBankAccountKeyVersion() != null) {
            return buyer.getBankAccountKeyVersion();
        }
        return buyer.getDocumentKeyVersion();
    }

    private String decrypt(byte[] cipher, Long buyerId, String field, String keyVersion) {
        if (cipher == null || cipher.length == 0 || keyVersion == null) {
            return null;
        }
        byte[] aad = ("buyer:" + buyerId + ":" + field).getBytes(StandardCharsets.UTF_8);
        return new String(envelopeCrypto.decrypt(cipher, keyVersion, aad), StandardCharsets.UTF_8);
    }

    private List<String> decryptFileKeys(Long buyerId, List<BuyerFileRefDO> fileRefs, String fileRole) {
        List<String> keys = new ArrayList<>();
        for (BuyerFileRefDO ref : fileRefs) {
            if (!fileRole.equals(ref.getFileRole())) {
                continue;
            }
            byte[] aad = ("buyer:" + buyerId + ":" + fileRole + ":" + ref.getSortNo())
                    .getBytes(StandardCharsets.UTF_8);
            keys.add(new String(envelopeCrypto.decrypt(ref.getFileCipher(), ref.getKeyVersion(), aad),
                    StandardCharsets.UTF_8));
        }
        return keys;
    }

    static String maskMobile(String mobile) {
        if (mobile == null || mobile.length() < 7) {
            return mobile;
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

    static String maskAccount(String account) {
        if (account == null || account.length() < 4) {
            return account == null ? null : "****";
        }
        return "****" + account.substring(account.length() - 4);
    }

    private static List<Short> toStatusCodes(List<BuyerStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        List<Short> codes = new ArrayList<>();
        for (BuyerStatus status : statuses) {
            if (status != null) {
                codes.add(status.getCode());
            }
        }
        return codes;
    }

    private static List<Short> toShorts(List<Integer> values) {
        if (values == null) {
            return List.of();
        }
        List<Short> shorts = new ArrayList<>();
        for (Integer value : values) {
            if (value != null) {
                shorts.add(value.shortValue());
            }
        }
        return shorts;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasValues(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    public record BuyerPageQuery(
            String buyerName, String buyerCode,
            List<Integer> distributionLevels, List<String> cooperationModes, List<String> sellableCategories,
            List<BuyerStatus> statuses,
            LocalDateTime updatedFrom, LocalDateTime updatedTo,
            Integer pageNo, Integer pageSize) {
    }

    @Schema(description = "分销商简易下拉项")
    public record SimpleBuyerItem(
            @Schema(description = "采购商永久业务编码") String buyerCode,
            @Schema(description = "采购商标准名称") String buyerName) {
    }

    @Schema(description = "分销商直接下级")
    public record BuyerChildItem(
            @Schema(description = "采购商永久业务编码") String buyerCode,
            @Schema(description = "采购商标准名称") String buyerName,
            @Schema(description = "组织等级：1一级 2二级 3三级") Integer distributionLevel,
            @Schema(description = "管理状态") String status) {
    }

    @Schema(description = "分销商列表行")
    public record BuyerListItem(
            @Schema(description = "采购商内部主键") Long id,
            @Schema(description = "采购商永久业务编码") String buyerCode,
            @Schema(description = "采购商标准名称") String buyerName,
            @Schema(description = "组织等级：1一级 2二级 3三级") Integer distributionLevel,
            @Schema(description = "管理状态：ACTIVE / SUSPENDED / BLACKLIST") String status,
            @Schema(description = "最后修改时间，Asia/Shanghai") LocalDateTime updatedAt,
            @Schema(description = "可售品类") List<String> sellableCategories,
            @Schema(description = "合作模式") List<String> cooperationModes,
            String primaryContactName, String primaryContactMobile) {
    }

    /**
     * 分销商详情。下级不嵌套；{@code accountBalanceCents} 只读。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。
     */
    public record BuyerDetail(
            @Schema(description = "采购商内部主键") Long id,
            @Schema(description = "采购商永久业务编码") String buyerCode,
            @Schema(description = "采购商标准名称") String buyerName,
            @Schema(description = "管理状态：ACTIVE / SUSPENDED / BLACKLIST") String status,
            @Schema(description = "上级分销商编码，一级为空") String parentBuyerCode,
            @Schema(description = "组织等级：1一级 2二级 3三级") Integer distributionLevel,
            @Schema(description = "分销商类型：ENTERPRISE企业 PERSONAL个人 PLATFORM平台") String distributorType,
            @Schema(description = "供货择优倾向，不改变路由引擎") String supplyPreference,
            @Schema(description = "结算方式封闭枚举") String settlementMethod,
            @Schema(description = "结算周期天数") Integer settlementCycleDays,
            @Schema(description = "合作开始时间，Asia/Shanghai") LocalDateTime cooperationStartedAt,
            @Schema(description = "合作说明") String cooperationNote,
            @Schema(description = "公司名称") String legalEntityName,
            @Schema(description = "统一社会信用代码") String creditCode,
            @Schema(description = "法人姓名") String legalPersonName,
            String legalPersonMobileMasked,
            @Schema(description = "联系地址") String contactAddress,
            @Schema(description = "开户行") String bankName,
            String bankAccountMasked,
            @Schema(description = "执照文件fileKey") List<String> licenseFileKeys,
            @Schema(description = "资质文件fileKey") List<String> qualificationFileKeys,
            @Schema(description = "对接方式：API、H5、MANUAL") List<String> integrationModes,
            @Schema(description = "合作模式") List<String> cooperationModes,
            @Schema(description = "可售品类") List<String> sellableCategories,
            @Schema(description = "商品类型") List<String> productTypes,
            List<ContactLine> contacts,
            String primaryContactName, String primaryContactMobile,
            @Schema(description = "采购商账本余额，单位CNY分，只读") long accountBalanceCents) {
    }
}
