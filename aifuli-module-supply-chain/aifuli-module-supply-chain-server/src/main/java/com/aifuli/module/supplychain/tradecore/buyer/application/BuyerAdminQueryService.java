package com.aifuli.module.supplychain.tradecore.buyer.application;

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
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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
    private static final int DISTRIBUTION_LEVEL_ONE = 1;
    private static final int DISTRIBUTION_LEVEL_TWO = 2;
    private static final int DISTRIBUTION_LEVEL_THREE = 3;
    private static final int PARENT_OPTION_LEVEL_TWO = DISTRIBUTION_LEVEL_TWO;
    private static final int PARENT_OPTION_LEVEL_THREE = DISTRIBUTION_LEVEL_THREE;
    private static final int DEFAULT_DISTRIBUTION_LEVEL = DISTRIBUTION_LEVEL_ONE;
    private static final int MAX_DISTRIBUTION_LEVEL = DISTRIBUTION_LEVEL_THREE;

    private final BuyerMapper buyerMapper;
    private final BuyerContactMapper contactMapper;
    private final BuyerIntegrationModeMapper integrationModeMapper;
    private final BuyerCooperationModeMapper cooperationModeMapper;
    private final BuyerCategoryScopeMapper categoryScopeMapper;
    private final BuyerFileRefMapper fileRefMapper;
    private final BuyerAccountMapper accountMapper;
    private final BuyerProjectMapper projectMapper;
    private final BuyerProductMapper productMapper;
    private final ProductTypeMapper productTypeMapper;
    private final EnvelopeCryptoPort envelopeCrypto;

    /**
     * 分页查询分销商组织树。根列表只含一级，二级和三级放进 {@code children}；归档行永不出现。
     *
     * <p>先一次读出未归档分销商，再在内存中筛选、挂接和按一级分页。命中二级或三级时带回一级祖先，
     * 并且只保留命中分支；挂不回未归档一级的节点丢弃。项目数、SKU 数按可见子树累加，合作模式、
     * 可售品类、商品类型取可见子树并集。设计见
     * {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。</p>
     */
    public PageResult<BuyerListItem> page(BuyerPageQuery query) {
        log.info("分页查询分销商 buyerName={} buyerCode={} distributionLevels={} cooperationModes={} "
                        + "sellableCategories={} statuses={} updatedFrom={} updatedTo={} pageNo={} pageSize={}",
                query.buyerName(), query.buyerCode(), query.distributionLevels(), query.cooperationModes(),
                query.sellableCategories(), query.statuses(), query.updatedFrom(), query.updatedTo(),
                query.pageNo(), query.pageSize());
        List<BuyerDO> buyers = buyerMapper.selectList(new LambdaQueryWrapper<BuyerDO>()
                .ne(BuyerDO::getStatus, BuyerStatus.ARCHIVED.getCode()));
        if (buyers == null || buyers.isEmpty()) {
            return new PageResult<>(List.of(), 0L);
        }
        List<Long> buyerIds = buyers.stream().map(BuyerDO::getId).toList();
        Map<Long, List<String>> sellableCategories = new HashMap<>();
        Map<Long, List<String>> productTypes = new HashMap<>();
        loadScopeCodes(buyerIds, sellableCategories, productTypes);
        Map<Long, List<String>> cooperationModes = loadCooperationModes(buyerIds);

        Map<Long, BuyerDO> buyersById = new HashMap<>();
        Map<Long, List<BuyerDO>> childrenByParent = new HashMap<>();
        for (BuyerDO buyer : buyers) {
            buyersById.put(buyer.getId(), buyer);
            if (buyer.getParentBuyerId() != null) {
                childrenByParent.computeIfAbsent(buyer.getParentBuyerId(), ignored -> new ArrayList<>())
                        .add(buyer);
            }
        }
        Comparator<BuyerDO> order = buyerTreeOrder();
        for (List<BuyerDO> children : childrenByParent.values()) {
            children.sort(order);
        }

        Set<Long> included = new HashSet<>();
        for (BuyerDO buyer : buyers) {
            if (!matchesListFilter(buyer, query, sellableCategories, cooperationModes)) {
                continue;
            }
            includeHitBranch(buyer, buyersById, childrenByParent, included);
        }
        List<BuyerDO> roots = new ArrayList<>();
        for (BuyerDO buyer : buyers) {
            if (included.contains(buyer.getId()) && isLevelOneRoot(buyer)) {
                roots.add(buyer);
            }
        }
        roots.sort(order);

        long total = roots.size();
        List<BuyerDO> pageRoots = sliceRoots(roots, normalizePageNo(query.pageNo()), normalizePageSize(query.pageSize()));
        if (pageRoots.isEmpty()) {
            return new PageResult<>(List.of(), total);
        }
        List<Long> visibleIds = new ArrayList<>();
        for (BuyerDO root : pageRoots) {
            collectVisibleIds(root, childrenByParent, included, visibleIds);
        }
        Map<Long, BuyerContactDO> primaryContacts = loadPrimaryContacts(visibleIds);
        Map<Long, Long> projectCounts = loadProjectCounts(visibleIds);
        Map<Long, Long> skuCounts = loadSkuCounts(visibleIds);
        List<BuyerListItem> items = new ArrayList<>(pageRoots.size());
        for (BuyerDO root : pageRoots) {
            items.add(toTreeNode(root, childrenByParent, included, sellableCategories, productTypes,
                    cooperationModes, primaryContacts, projectCounts, skuCounts));
        }
        return new PageResult<>(items, total);
    }

    public List<SimpleBuyerItem> listSimple(String buyerName, String buyerCode) {
        return listSimple(buyerName, buyerCode, null);
    }

    /**
     * 名称下拉继续排除归档，并支持显式状态筛选；结算方候选调用只选 ACTIVE。
     * 不做组织范围筛选，不改变普通关联分销商下拉的兼容投影。
     * 设计：docs/superpowers/specs/2026-10-14-project-settlement-buyer-design.md#4-前端。
     */
    public List<SimpleBuyerItem> listSimple(String buyerName, String buyerCode, List<BuyerStatus> statuses) {
        log.info("简易分销商列表 buyerName={} buyerCode={}", buyerName, buyerCode);
        List<BuyerDO> buyers = buyerMapper.selectList(new LambdaQueryWrapper<BuyerDO>()
                .like(hasText(buyerName), BuyerDO::getBuyerName, buyerName)
                .eq(hasText(buyerCode), BuyerDO::getBuyerCode, buyerCode)
                .ne(BuyerDO::getStatus, BuyerStatus.ARCHIVED.getCode())
                .in(statuses != null && !statuses.isEmpty(), BuyerDO::getStatus,
                        statuses == null ? List.of() : statuses.stream().map(BuyerStatus::getCode).toList())
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
        List<String> productTypeCodes = new ArrayList<>();
        for (BuyerCategoryScopeDO scope : categoryScopeMapper.selectByBuyerId(buyer.getId())) {
            if (BuyerScopeKind.SELLABLE.name().equals(scope.getScopeKind())) {
                sellableCategories.add(scope.getScopeCode());
            } else if (BuyerScopeKind.PRODUCT_TYPE.name().equals(scope.getScopeKind())) {
                productTypeCodes.add(scope.getScopeCode());
            }
        }
        List<ProductTypeItem> productTypes = loadProductTypes(productTypeCodes);
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
                echoCooperationStartedAt(buyer),
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
     * 将分销商已保存的商品类型编码批量解析为详情回显项。
     *
     * <p>详情的 {@code id} 使用商品类型业务编码，而不是数据库内部主键，保证前端回填后仍可原样提交
     * {@code productTypes[]} 保存请求；查询按范围行顺序返回且一次批量读取，避免详情扩展出 N+1 查询。
     * 商品类型主数据由 {@code sc_product_type} 维护。设计见
     * {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。</p>
     */
    private List<ProductTypeItem> loadProductTypes(List<String> productTypeCodes) {
        if (productTypeCodes.isEmpty()) {
            return List.of();
        }
        List<String> distinctCodes = productTypeCodes.stream().distinct().toList();
        Map<String, ProductTypeDO> typesByCode = new LinkedHashMap<>();
        productTypeMapper.selectList(new LambdaQueryWrapper<ProductTypeDO>()
                        .in(ProductTypeDO::getTypeCode, distinctCodes))
                .forEach(type -> typesByCode.put(type.getTypeCode(), type));
        return distinctCodes.stream()
                .map(typesByCode::get)
                .filter(type -> type != null)
                .map(type -> new ProductTypeItem(type.getTypeCode(), type.getTypeName()))
                .toList();
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
     * 命中节点满足全部筛选条件。合作模式、可售品类各自命中任一；两者同时出现时必须同时满足。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。
     */
    private static boolean matchesListFilter(BuyerDO buyer, BuyerPageQuery query,
                                             Map<Long, List<String>> sellableCategories,
                                             Map<Long, List<String>> cooperationModes) {
        if (hasText(query.buyerName())
                && (buyer.getBuyerName() == null || !buyer.getBuyerName().contains(query.buyerName()))) {
            return false;
        }
        if (hasText(query.buyerCode()) && !query.buyerCode().equals(buyer.getBuyerCode())) {
            return false;
        }
        if (hasValues(query.distributionLevels())
                && !query.distributionLevels().contains(effectiveLevel(buyer))) {
            return false;
        }
        if (!matchesStatus(buyer, query.statuses())) {
            return false;
        }
        if (query.updatedFrom() != null
                && (buyer.getUpdatedAt() == null || buyer.getUpdatedAt().isBefore(query.updatedFrom()))) {
            return false;
        }
        if (query.updatedTo() != null
                && (buyer.getUpdatedAt() == null || buyer.getUpdatedAt().isAfter(query.updatedTo()))) {
            return false;
        }
        if (hasValues(query.cooperationModes())
                && !containsAny(cooperationModes.get(buyer.getId()), query.cooperationModes())) {
            return false;
        }
        return !hasValues(query.sellableCategories())
                || containsAny(sellableCategories.get(buyer.getId()), query.sellableCategories());
    }

    private static boolean matchesStatus(BuyerDO buyer, List<BuyerStatus> statuses) {
        if (!hasValues(statuses)) {
            return true;
        }
        if (buyer.getStatus() == null) {
            return false;
        }
        for (BuyerStatus status : statuses) {
            if (status != null && buyer.getStatus() == status.getCode()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(List<String> owned, List<String> requested) {
        if (owned == null || owned.isEmpty()) {
            return false;
        }
        for (String value : requested) {
            if (value != null && owned.contains(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把命中节点、它的一级祖先，以及命中节点下面仍能挂接的下级放进可见集合。
     * 等级链中断或上级不在未归档集合中时，整条分支丢弃。
     */
    private void includeHitBranch(BuyerDO hit, Map<Long, BuyerDO> buyersById,
                                  Map<Long, List<BuyerDO>> childrenByParent, Set<Long> included) {
        if (!attachable(hit, buyersById)) {
            return;
        }
        BuyerDO cursor = hit;
        for (int depth = 0; depth < MAX_DISTRIBUTION_LEVEL && cursor != null; depth++) {
            included.add(cursor.getId());
            if (effectiveLevel(cursor) == DISTRIBUTION_LEVEL_ONE) {
                break;
            }
            cursor = buyersById.get(cursor.getParentBuyerId());
        }
        includeDescendants(hit, buyersById, childrenByParent, included);
    }

    private void includeDescendants(BuyerDO node, Map<Long, BuyerDO> buyersById,
                                    Map<Long, List<BuyerDO>> childrenByParent, Set<Long> included) {
        int level = effectiveLevel(node);
        if (level >= DISTRIBUTION_LEVEL_THREE) {
            return;
        }
        int childLevel = level + 1;
        for (BuyerDO child : childrenByParent.getOrDefault(node.getId(), List.of())) {
            if (effectiveLevel(child) != childLevel || !attachable(child, buyersById)) {
                continue;
            }
            included.add(child.getId());
            includeDescendants(child, buyersById, childrenByParent, included);
        }
    }

    /**
     * 一级必须没有上级；二级的上级必须是没有上级的一级；三级的上级必须是这样的二级。
     * 历史空等级按一级处理。上级已归档时不在 {@code buyersById} 中，因此挂不回去。
     */
    private static boolean attachable(BuyerDO node, Map<Long, BuyerDO> buyersById) {
        int level = effectiveLevel(node);
        if (level == DISTRIBUTION_LEVEL_ONE) {
            return node.getParentBuyerId() == null;
        }
        BuyerDO parent = node.getParentBuyerId() == null ? null : buyersById.get(node.getParentBuyerId());
        if (parent == null) {
            return false;
        }
        if (level == DISTRIBUTION_LEVEL_TWO) {
            return effectiveLevel(parent) == DISTRIBUTION_LEVEL_ONE && parent.getParentBuyerId() == null;
        }
        if (level == DISTRIBUTION_LEVEL_THREE) {
            return effectiveLevel(parent) == DISTRIBUTION_LEVEL_TWO && attachable(parent, buyersById);
        }
        return false;
    }

    private static boolean isLevelOneRoot(BuyerDO buyer) {
        return effectiveLevel(buyer) == DISTRIBUTION_LEVEL_ONE && buyer.getParentBuyerId() == null;
    }

    private static int effectiveLevel(BuyerDO buyer) {
        return buyer.getDistributionLevel() == null
                ? DEFAULT_DISTRIBUTION_LEVEL : buyer.getDistributionLevel().intValue();
    }

    private static Comparator<BuyerDO> buyerTreeOrder() {
        return Comparator
                .comparing(BuyerDO::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(BuyerDO::getId, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private static int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo < 1 ? DEFAULT_PAGE_NO : pageNo;
    }

    private static int normalizePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : pageSize;
    }

    private static List<BuyerDO> sliceRoots(List<BuyerDO> roots, int pageNo, int pageSize) {
        long from = (long) (pageNo - 1) * pageSize;
        if (from >= roots.size()) {
            return List.of();
        }
        int start = (int) from;
        return roots.subList(start, Math.min(start + pageSize, roots.size()));
    }

    private void collectVisibleIds(BuyerDO node, Map<Long, List<BuyerDO>> childrenByParent,
                                   Set<Long> included, List<Long> visibleIds) {
        visibleIds.add(node.getId());
        for (BuyerDO child : childrenByParent.getOrDefault(node.getId(), List.of())) {
            if (included.contains(child.getId())) {
                collectVisibleIds(child, childrenByParent, included, visibleIds);
            }
        }
    }

    /**
     * 按可见子树生成列表节点。数量先加子节点已经汇总的值，集合先保留本节点顺序再追加下级新值。
     */
    private BuyerListItem toTreeNode(BuyerDO buyer, Map<Long, List<BuyerDO>> childrenByParent, Set<Long> included,
                                     Map<Long, List<String>> sellableCategories, Map<Long, List<String>> productTypes,
                                     Map<Long, List<String>> cooperationModes, Map<Long, BuyerContactDO> primaryContacts,
                                     Map<Long, Long> projectCounts, Map<Long, Long> skuCounts) {
        List<BuyerListItem> children = new ArrayList<>();
        for (BuyerDO child : childrenByParent.getOrDefault(buyer.getId(), List.of())) {
            if (!included.contains(child.getId())) {
                continue;
            }
            children.add(toTreeNode(child, childrenByParent, included, sellableCategories, productTypes,
                    cooperationModes, primaryContacts, projectCounts, skuCounts));
        }
        BuyerContactDO primary = primaryContacts.get(buyer.getId());
        List<String> categories = unionValues(
                sellableCategories.getOrDefault(buyer.getId(), List.of()), children, BuyerListItem::sellableCategories);
        List<String> types = unionValues(
                productTypes.getOrDefault(buyer.getId(), List.of()), children, BuyerListItem::productTypes);
        List<String> modes = unionValues(
                cooperationModes.getOrDefault(buyer.getId(), List.of()), children, BuyerListItem::cooperationModes);
        long projectCount = projectCounts.getOrDefault(buyer.getId(), 0L);
        long skuCount = skuCounts.getOrDefault(buyer.getId(), 0L);
        for (BuyerListItem child : children) {
            projectCount += child.projectCount();
            skuCount += child.skuCount();
        }
        return new BuyerListItem(
                buyer.getId(),
                buyer.getBuyerCode(),
                buyer.getBuyerName(),
                buyer.getDistributionLevel() == null ? null : buyer.getDistributionLevel().intValue(),
                BuyerStatus.fromCode(buyer.getStatus()).name(),
                buyer.getUpdatedAt(),
                categories,
                types,
                modes,
                projectCount,
                skuCount,
                echoCooperationStartedAt(buyer),
                primary == null ? null : primary.getContactName(),
                primary == null ? null : primary.getContactMobile(),
                List.copyOf(children));
    }

    private static List<String> unionValues(List<String> own, List<BuyerListItem> children,
                                            Function<BuyerListItem, List<String>> extractor) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (own != null) {
            values.addAll(own);
        }
        for (BuyerListItem child : children) {
            List<String> childValues = extractor.apply(child);
            if (childValues != null) {
                values.addAll(childValues);
            }
        }
        return new ArrayList<>(values);
    }

    /**
     * 一次读出范围内分销商的可售品类和商品类型。两类范围分开收集，不互相推导。
     */
    private void loadScopeCodes(List<Long> buyerIds, Map<Long, List<String>> sellableCategories,
                                Map<Long, List<String>> productTypes) {
        Map<Long, LinkedHashSet<String>> categories = new HashMap<>();
        Map<Long, LinkedHashSet<String>> types = new HashMap<>();
        for (BuyerCategoryScopeDO scope : categoryScopeMapper.selectList(
                new LambdaQueryWrapper<BuyerCategoryScopeDO>()
                        .in(BuyerCategoryScopeDO::getBuyerId, buyerIds)
                        .orderByAsc(BuyerCategoryScopeDO::getId))) {
            if (scope.getScopeCode() == null || scope.getBuyerId() == null) {
                continue;
            }
            if (BuyerScopeKind.SELLABLE.name().equals(scope.getScopeKind())) {
                categories.computeIfAbsent(scope.getBuyerId(), ignored -> new LinkedHashSet<>())
                        .add(scope.getScopeCode());
            } else if (BuyerScopeKind.PRODUCT_TYPE.name().equals(scope.getScopeKind())) {
                types.computeIfAbsent(scope.getBuyerId(), ignored -> new LinkedHashSet<>())
                        .add(scope.getScopeCode());
            }
        }
        categories.forEach((buyerId, codes) -> sellableCategories.put(buyerId, new ArrayList<>(codes)));
        types.forEach((buyerId, codes) -> productTypes.put(buyerId, new ArrayList<>(codes)));
    }

    private Map<Long, BuyerContactDO> loadPrimaryContacts(List<Long> buyerIds) {
        Map<Long, BuyerContactDO> primaryContacts = new HashMap<>();
        if (buyerIds.isEmpty()) {
            return primaryContacts;
        }
        for (BuyerContactDO contact : contactMapper.selectList(new LambdaQueryWrapper<BuyerContactDO>()
                .in(BuyerContactDO::getBuyerId, buyerIds)
                .orderByAsc(BuyerContactDO::getBuyerId, BuyerContactDO::getSortNo))) {
            primaryContacts.putIfAbsent(contact.getBuyerId(), contact);
        }
        return primaryContacts;
    }

    private Map<Long, Long> loadSkuCounts(List<Long> buyerIds) {
        Map<Long, Long> skuCounts = new HashMap<>();
        if (buyerIds.isEmpty()) {
            return skuCounts;
        }
        productMapper.selectList(new LambdaQueryWrapper<BuyerProductDO>()
                        .select(BuyerProductDO::getBuyerId)
                        .in(BuyerProductDO::getBuyerId, buyerIds)
                        .eq(BuyerProductDO::getStatus, CatalogEntityStatus.ACTIVE.getCode()))
                .forEach(product -> skuCounts.merge(product.getBuyerId(), 1L, Long::sum));
        return skuCounts;
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

    /**
     * 列表合作时间与详情合作开始时间共用回显口径：未填或 Unix 纪元脏值回落到创建时间。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。
     */
    private static LocalDateTime echoCooperationStartedAt(BuyerDO buyer) {
        LocalDateTime startedAt = BuyerAdminCommandService.normalizeCooperationStartedAt(
                buyer.getCooperationStartedAt());
        return startedAt != null ? startedAt : buyer.getCreatedAt();
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

    @Schema(description = "分销商列表行。一级的 children 为二级，二级的 children 为三级，三级为空列表")
    public record BuyerListItem(
            @Schema(description = "采购商内部主键") Long id,
            @Schema(description = "采购商永久业务编码") String buyerCode,
            @Schema(description = "采购商标准名称") String buyerName,
            @Schema(description = "组织等级：1一级 2二级 3三级") Integer distributionLevel,
            @Schema(description = "管理状态：ACTIVE / SUSPENDED / BLACKLIST") String status,
            @Schema(description = "最后修改时间，Asia/Shanghai") LocalDateTime updatedAt,
            @Schema(description = "本级及可见下级可售品类并集") List<String> sellableCategories,
            @Schema(description = "本级及可见下级商品类型并集") List<String> productTypes,
            @Schema(description = "本级及可见下级合作模式并集") List<String> cooperationModes,
            @Schema(description = "本级及可见下级未归档项目数合计") long projectCount,
            @Schema(description = "本级及可见下级有效分销商品数合计") long skuCount,
            @Schema(description = "合作时间，取档案合作开始时间；未填或年份不晚于1970时回落创建时间，Asia/Shanghai") LocalDateTime cooperationTime,
            @Schema(description = "主联系人姓名") String primaryContactName,
            @Schema(description = "主联系人手机号") String primaryContactMobile,
            @Schema(description = "直属下级。一级包含二级，二级包含三级，三级为空列表") List<BuyerListItem> children) {
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
            @Schema(description = "合作开始时间；未填或年份不晚于1970时回落创建时间，Asia/Shanghai") LocalDateTime cooperationStartedAt,
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
            @Schema(description = "商品类型，id为业务编码，name为类型名称") List<ProductTypeItem> productTypes,
            @Schema(description = "联系人列表") List<ContactLine> contacts,
            @Schema(description = "主联系人姓名") String primaryContactName,
            @Schema(description = "主联系人手机号") String primaryContactMobile,
            @Schema(description = "采购商账本余额，单位CNY分，只读") long accountBalanceCents) {
    }

    /** 分销商详情商品类型回显项；id 为可提交的商品类型业务编码。 */
    @Schema(description = "分销商详情商品类型")
    public record ProductTypeItem(
            @Schema(description = "商品类型业务编码") String id,
            @Schema(description = "商品类型名称") String name) {
    }
}
