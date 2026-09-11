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
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerCategoryScopeMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerContactMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerCooperationModeMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerFileRefMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerIntegrationModeMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerScopeKind;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PARENT_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;

/**
 * 分销商管理端查询：列表多值筛选、simple / parent-options 投影、档案原值回显、全部下级。
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
    private static final int DEFAULT_DISTRIBUTION_LEVEL = 1;
    private static final int MAX_DISTRIBUTION_LEVEL = 3;

    private final BuyerMapper buyerMapper;
    private final BuyerContactMapper contactMapper;
    private final BuyerIntegrationModeMapper integrationModeMapper;
    private final BuyerCooperationModeMapper cooperationModeMapper;
    private final BuyerCategoryScopeMapper categoryScopeMapper;
    private final BuyerFileRefMapper fileRefMapper;
    private final BuyerAccountMapper accountMapper;
    private final BuyerProjectMapper projectMapper;
    private final BuyerProductMapper productMapper;
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
        return new PageResult<>(toListItems(page.getList()), page.getTotal());
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
                decrypt(buyer.getLegalPersonMobileCipher(), buyer.getId(), "legalPersonMobile",
                        firstVersion(buyer)),
                buyer.getContactAddress(),
                buyer.getBankName(),
                decrypt(buyer.getBankAccountCipher(), buyer.getId(), "bankAccount",
                        firstVersion(buyer)),
                licenseFileKeys,
                qualificationFileKeys,
                integrationModes,
                cooperationModes,
                sellableCategories,
                productTypes,
                contacts.stream().map(c -> new ContactLine(
                        c.getContactName(), c.getContactMobile(), c.getContactEmail())).toList(),
                primary == null ? null : primary.getContactName(),
                primary == null ? null : primary.getContactMobile(),
                account == null || account.getBalance() == null ? 0L : account.getBalance());
    }

    /**
     * 查询当前分销商的全部下级，包含直属下级和孙级，返回扁平列表。
     *
     * <p>组织树最多三级，因此从当前节点开始逐层查询，最多访问两层；历史空等级按一级处理。
     * 遍历结构时保留归档中间节点，
     * 避免其未归档后代丢失；最终响应统一排除归档节点，并按创建时间、主键倒序稳定排列。
     * 合作模式和项目数针对完整结果集批量读取，禁止逐行查询。</p>
     *
     * <p>设计见
     * {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#61-组织树}
     * 与
     * {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。</p>
     */
    public List<BuyerChildItem> children(String buyerCode) {
        log.info("查询分销商全部下级 buyerCode={}", buyerCode);
        BuyerDO buyer = requireBuyer(buyerCode);
        List<BuyerDO> descendants = new ArrayList<>();
        List<Long> parentIds = List.of(buyer.getId());
        int distributionLevel = buyer.getDistributionLevel() == null
                ? DEFAULT_DISTRIBUTION_LEVEL : buyer.getDistributionLevel().intValue();
        int remainingLevels = Math.max(0, MAX_DISTRIBUTION_LEVEL - distributionLevel);
        for (int depth = 0; depth < remainingLevels && !parentIds.isEmpty(); depth++) {
            List<BuyerDO> level = buyerMapper.selectList(new LambdaQueryWrapper<BuyerDO>()
                    .in(BuyerDO::getParentBuyerId, parentIds));
            descendants.addAll(level);
            parentIds = level.stream().map(BuyerDO::getId).toList();
        }
        List<BuyerDO> visibleDescendants = descendants.stream()
                .filter(descendant -> descendant.getStatus() != BuyerStatus.ARCHIVED.getCode())
                .sorted(Comparator
                        .comparing(BuyerDO::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(BuyerDO::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        if (visibleDescendants.isEmpty()) {
            return List.of();
        }
        List<Long> descendantIds = visibleDescendants.stream().map(BuyerDO::getId).toList();
        Map<Long, List<String>> cooperationModes = loadCooperationModes(descendantIds);
        Map<Long, Long> projectCounts = loadProjectCounts(descendantIds);
        List<BuyerChildItem> items = new ArrayList<>(visibleDescendants.size());
        for (BuyerDO child : visibleDescendants) {
            items.add(new BuyerChildItem(
                    child.getBuyerCode(),
                    child.getBuyerName(),
                    child.getDistributionLevel() == null ? null : child.getDistributionLevel().intValue(),
                    cooperationModes.getOrDefault(child.getId(), List.of()),
                    projectCounts.getOrDefault(child.getId(), 0L),
                    BuyerStatus.fromCode(child.getStatus()).name(),
                    child.getCreatedAt()));
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

    /**
     * 一次批量装配当页分销商的范围、项目、分销商品和联系人，避免列表字段扩展形成 N+1 查询。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。
     */
    private List<BuyerListItem> toListItems(List<BuyerDO> buyers) {
        if (buyers.isEmpty()) {
            return List.of();
        }
        List<Long> buyerIds = buyers.stream().map(BuyerDO::getId).toList();
        Map<Long, LinkedHashSet<String>> sellableCategories = new HashMap<>();
        Map<Long, LinkedHashSet<String>> productTypes = new HashMap<>();
        for (BuyerCategoryScopeDO scope : categoryScopeMapper.selectList(
                new LambdaQueryWrapper<BuyerCategoryScopeDO>()
                        .in(BuyerCategoryScopeDO::getBuyerId, buyerIds)
                        .orderByAsc(BuyerCategoryScopeDO::getId))) {
            if (scope.getScopeCode() == null) {
                continue;
            }
            if (BuyerScopeKind.SELLABLE.name().equals(scope.getScopeKind())) {
                sellableCategories.computeIfAbsent(scope.getBuyerId(), ignored -> new LinkedHashSet<>())
                        .add(scope.getScopeCode());
            } else if (BuyerScopeKind.PRODUCT_TYPE.name().equals(scope.getScopeKind())) {
                productTypes.computeIfAbsent(scope.getBuyerId(), ignored -> new LinkedHashSet<>())
                        .add(scope.getScopeCode());
            }
        }

        Map<Long, List<String>> cooperationModes = loadCooperationModes(buyerIds);

        Map<Long, BuyerContactDO> primaryContacts = new HashMap<>();
        for (BuyerContactDO contact : contactMapper.selectList(new LambdaQueryWrapper<BuyerContactDO>()
                .in(BuyerContactDO::getBuyerId, buyerIds)
                .orderByAsc(BuyerContactDO::getBuyerId, BuyerContactDO::getSortNo))) {
            primaryContacts.putIfAbsent(contact.getBuyerId(), contact);
        }

        Map<Long, Long> projectCounts = loadProjectCounts(buyerIds);

        Map<Long, Long> skuCounts = new HashMap<>();
        productMapper.selectList(new LambdaQueryWrapper<BuyerProductDO>()
                        .select(BuyerProductDO::getBuyerId)
                        .in(BuyerProductDO::getBuyerId, buyerIds)
                        .eq(BuyerProductDO::getStatus, CatalogEntityStatus.ACTIVE.getCode()))
                .forEach(product -> skuCounts.merge(product.getBuyerId(), 1L, Long::sum));

        List<BuyerListItem> items = new ArrayList<>(buyers.size());
        for (BuyerDO buyer : buyers) {
            BuyerContactDO primary = primaryContacts.get(buyer.getId());
            items.add(new BuyerListItem(
                    buyer.getId(),
                    buyer.getBuyerCode(),
                    buyer.getBuyerName(),
                    buyer.getDistributionLevel() == null ? null : buyer.getDistributionLevel().intValue(),
                    BuyerStatus.fromCode(buyer.getStatus()).name(),
                    buyer.getUpdatedAt(),
                    new ArrayList<>(sellableCategories.getOrDefault(buyer.getId(), new LinkedHashSet<>())),
                    new ArrayList<>(productTypes.getOrDefault(buyer.getId(), new LinkedHashSet<>())),
                    cooperationModes.getOrDefault(buyer.getId(), List.of()),
                    projectCounts.getOrDefault(buyer.getId(), 0L),
                    skuCounts.getOrDefault(buyer.getId(), 0L),
                    buyer.getCooperationStartedAt(),
                    primary == null ? null : primary.getContactName(),
                    primary == null ? null : primary.getContactMobile()));
        }
        return items;
    }

    private Map<Long, List<String>> loadCooperationModes(List<Long> buyerIds) {
        Map<Long, List<String>> cooperationModes = new HashMap<>();
        cooperationModeMapper.selectList(new LambdaQueryWrapper<BuyerCooperationModeDO>()
                        .in(BuyerCooperationModeDO::getBuyerId, buyerIds)
                        .orderByAsc(BuyerCooperationModeDO::getId))
                .forEach(mode -> cooperationModes
                        .computeIfAbsent(mode.getBuyerId(), ignored -> new ArrayList<>())
                        .add(mode.getCooperationMode()));
        return cooperationModes;
    }

    private Map<Long, Long> loadProjectCounts(List<Long> buyerIds) {
        Map<Long, Long> projectCounts = new HashMap<>();
        projectMapper.selectList(new LambdaQueryWrapper<BuyerProjectDO>()
                        .select(BuyerProjectDO::getBuyerId)
                        .in(BuyerProjectDO::getBuyerId, buyerIds)
                        .ne(BuyerProjectDO::getStatus, BuyerProjectStatus.ARCHIVED.getCode()))
                .forEach(project -> projectCounts.merge(project.getBuyerId(), 1L, Long::sum));
        return projectCounts;
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

    @Schema(description = "分销商全部下级，包含直属下级和孙级")
    public record BuyerChildItem(
            @Schema(description = "采购商永久业务编码") String buyerCode,
            @Schema(description = "采购商标准名称") String buyerName,
            @Schema(description = "组织等级：1一级 2二级 3三级") Integer distributionLevel,
            @Schema(description = "合作模式") List<String> cooperationModes,
            @Schema(description = "未归档关联项目数") long projectCount,
            @Schema(description = "管理状态：ACTIVE / SUSPENDED / BLACKLIST") String status,
            @Schema(description = "创建时间，Asia/Shanghai") LocalDateTime createdAt) {
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
            @Schema(description = "商品类型") List<String> productTypes,
            @Schema(description = "合作模式") List<String> cooperationModes,
            @Schema(description = "未归档关联项目数") long projectCount,
            @Schema(description = "有效分销SKU商品数") long skuCount,
            @Schema(description = "合作时间，取档案合作开始时间，Asia/Shanghai") LocalDateTime cooperationTime,
            @Schema(description = "主联系人姓名") String primaryContactName,
            @Schema(description = "主联系人手机号") String primaryContactMobile) {
    }

    /**
     * 分销商详情。档案敏感字段在管理端查询权限内按原值回显，下级不嵌套；
     * {@code accountBalanceCents} 只读。库内信封加密与日志脱敏规则不变。
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
            @Schema(description = "法人手机号") String legalPersonMobile,
            @Schema(description = "联系地址") String contactAddress,
            @Schema(description = "开户行") String bankName,
            @Schema(description = "开户账号") String bankAccount,
            @Schema(description = "执照文件fileKey") List<String> licenseFileKeys,
            @Schema(description = "资质文件fileKey") List<String> qualificationFileKeys,
            @Schema(description = "对接方式：API、H5、MANUAL") List<String> integrationModes,
            @Schema(description = "合作模式") List<String> cooperationModes,
            @Schema(description = "可售品类") List<String> sellableCategories,
            @Schema(description = "商品类型") List<String> productTypes,
            @Schema(description = "联系人列表") List<ContactLine> contacts,
            @Schema(description = "主联系人姓名") String primaryContactName,
            @Schema(description = "主联系人手机号") String primaryContactMobile,
            @Schema(description = "采购商账本余额，单位CNY分，只读") long accountBalanceCents) {
    }
}
