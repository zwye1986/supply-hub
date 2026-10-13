package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingPriceSource.PriceBasis;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.ProjectListingPricingInitializationEvent;
import com.aifuli.module.supplychain.tradecore.profile.domain.L3AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.ListingPriceSource;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SETTLEMENT_PRICE_MISSING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_LISTING_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_SKU_NOT_IN_BUYER_CATALOG;

/**
 * 项目商品命令：SKU 必须已是所属分销商的有效分销商品；发新版本冻结结算价，不 UPDATE 已有 version 行。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectListingCommandService {

    private final SupplyChainDatabaseClock databaseClock;
    private final ApplicationEventPublisher eventPublisher;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final int MAX_BATCH_LISTING_COUNT = 200;

    private final BuyerProjectMapper projectMapper;
    private final BuyerMapper buyerMapper;
    private final BuyerProductMapper productMapper;
    private final PlatformSkuMapper skuMapper;
    private final ProjectListingMapper listingMapper;
    private final ProjectListingVersionMapper listingVersionMapper;
    private final ProjectSkuPricingMapper projectPricingMapper;
    private final ProjectListingPriceSource priceSource;
    private final BusinessNoGenerator businessNoGenerator;
    private final SettlementPriceResolver settlementPriceResolver;
    private final GenericBatchPricingService genericPricingService;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public List<String> addProducts(String projectCode, List<ListingLine> lines, Long operatorId) {
        // 行内定价加入同一个RC批次；设计见docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#34-闭环四同步重算的锁顺序与并发事务规范。
        com.aifuli.module.supplychain.tradecore.supply.application.SupplierLifecycleTransactions.requireReadCommitted();
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("At least one SKU is required");
        }
        BuyerProjectDO project = requireProjectForUpdate(projectCode);
        String buyerCode = null;
        if (project.getBuyerId() != null) {
            BuyerDO buyer = buyerMapper.selectById(project.getBuyerId());
            buyerCode = buyer == null ? null : buyer.getBuyerCode();
        }
        log.info("发布项目商品 projectCode={} buyerCode={} skuCount={} operatorId={}",
                projectCode, buyerCode, lines.size(), operatorId);
        // 在任何单行初始化或定价前预锁整批SKU根，保持请求处理顺序与既有准入规则。
        genericPricingService.lockPlatformPricingByCodes(lines.stream().map(line -> line.platformSkuCode()).toList());
        LocalDateTime now = databaseClock.now();
        List<String> codes = new ArrayList<>();
        for (ListingLine line : lines) {
            if (line.pricingOverrides() != null && line.l3AdjustMode() != null
                    && line.l3AdjustMode() != L3AdjustMode.FOLLOW_L2) {
                throw new IllegalArgumentException("行内通用定价不能与旧 L3 调价同时提交");
            }
            String listingCode = addOrRevive(project, line, operatorId, now);
            codes.add(listingCode);
            if (line.pricingOverrides() != null) {
                genericPricingService.saveProject(projectCode, List.of(listingCode), line.pricingOverrides(), operatorId);
            }
        }
        return codes;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void patchL3(String projectCode, String listingCode, L3AdjustMode mode,
                        Integer percent, Long amountCents, Long operatorId) {
        log.info("改项目商品L3 projectCode={} listingCode={} l3AdjustMode={} operatorId={}",
                projectCode, listingCode, mode, operatorId);
        BuyerProjectDO project = requireProjectForUpdate(projectCode);
        ProjectListingDO listing = requireLiveListing(project.getId(), listingCode);
        BuyerProductDO product = requireBuyerProduct(project, listing);
        LocalDateTime now = databaseClock.now();
        publishVersion(listing, product, mode == null ? L3AdjustMode.FOLLOW_L2 : mode,
                percent, amountCents, operatorId, now);
    }

    /**
     * 只读试算单一项目内的多条 L3 价格。同一分销商的其它项目不会被读取或改写。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4112-分销商与项目分层批量定价}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public List<L3PricingPreview> previewL3Batch(String projectCode, List<String> listingCodes,
                                                  L3AdjustMode mode, Integer percent, Long amountCents) {
        BuyerProjectDO project = requireProject(projectCode);
        L3AdjustMode resolvedMode = mode == null ? L3AdjustMode.FOLLOW_L2 : mode;
        List<L3PricingPreview> previews = new ArrayList<>();
        for (String listingCode : normalizeCodes(listingCodes)) {
            ProjectListingDO listing = requireLiveListing(project.getId(), listingCode, false);
            BuyerProductDO product = requireBuyerProduct(project, listing);
            PlatformSkuDO sku = skuMapper.selectById(product.getPlatformSkuId());
            if (sku == null) {
                throw exception(PROJECT_SKU_NOT_IN_BUYER_CATALOG);
            }
            PriceBasis basis = priceSource.resolve(product);
            if (basis.distributionCents() == null || basis.distributionSource() == null
                    || basis.generic() && basis.platformBaseCents() == null) {
                throw exception(PLATFORM_SETTLEMENT_PRICE_MISSING);
            }
            ProjectListingVersionDO currentVersion = listing.getCurrentVersionId() == null ? null
                    : listingVersionMapper.selectById(listing.getCurrentVersionId());
            if (currentVersion == null) {
                throw exception(PROJECT_LISTING_NOT_FOUND);
            }
            FrozenPrice preview = freeze(basis, resolvedMode, percent, amountCents);
            previews.add(new L3PricingPreview(listing.getListingCode(), sku.getSkuCode(), basis.platformBaseCents(),
                    basis.distributionCents(), basis.distributionSource(), currentVersion.getBuyerSettlementUnitPrice(),
                    PriceSource.valueOf(currentVersion.getSettlementPriceLevel()),
                    preview.unitPriceCents(), preview.level()));
        }
        return previews;
    }

    /**
     * 在一个本地事务内批量发布项目 L3 版本，任一 listing 不属于当前项目时全量回滚。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4112-分销商与项目分层批量定价}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void patchL3Batch(String projectCode, List<String> listingCodes, L3AdjustMode mode,
                             Integer percent, Long amountCents, Long operatorId) {
        List<String> codes = normalizeCodes(listingCodes);
        log.info("批量修改项目商品L3 projectCode={} listingCount={} l3AdjustMode={} operatorId={}",
                projectCode, codes.size(), mode, operatorId);
        BuyerProjectDO project = requireProjectForUpdate(projectCode);
        LocalDateTime now = databaseClock.now();
        L3AdjustMode resolvedMode = mode == null ? L3AdjustMode.FOLLOW_L2 : mode;
        for (String listingCode : codes) {
            ProjectListingDO listing = requireLiveListing(project.getId(), listingCode, true);
            publishVersion(listing, requireBuyerProduct(project, listing), resolvedMode,
                    percent, amountCents, operatorId, now);
        }
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void retire(String projectCode, String listingCode, Long operatorId) {
        log.info("移除项目商品 projectCode={} listingCode={} operatorId={}",
                projectCode, listingCode, operatorId);
        BuyerProjectDO project = requireProjectForUpdate(projectCode);
        ProjectListingDO listing = requireLiveListing(project.getId(), listingCode);
        listing.setStatus(ProjectListingStatus.REMOVED.getCode());
        listing.setUpdatedBy(operatorId);
        listing.setUpdatedAt(databaseClock.now());
        if (listingMapper.updateById(listing) != 1) {
            throw exception(PROJECT_LISTING_NOT_FOUND);
        }
    }

    private String addOrRevive(BuyerProjectDO project, ListingLine line, Long operatorId, LocalDateTime now) {
        BuyerProductDO product = requireLiveBuyerProduct(project.getBuyerId(), line.platformSkuCode());
        L3AdjustMode mode = line.l3AdjustMode() == null ? L3AdjustMode.FOLLOW_L2 : line.l3AdjustMode();
        ProjectListingDO existing = listingMapper.selectByProjectAndBuyerProduct(project.getId(), product.getId());
        if (existing != null) {
            existing.setStatus(ProjectListingStatus.AVAILABLE.getCode());
            existing.setUpdatedBy(operatorId);
            existing.setUpdatedAt(now);
            publishVersion(existing, product, mode, line.percent(), line.amountCents(), operatorId, now);
            ensureUnpricedIdentity(existing.getId(), now);
            eventPublisher.publishEvent(new ProjectListingPricingInitializationEvent(existing.getId(), operatorId));
            return existing.getListingCode();
        }
        String code = businessNoGenerator.next(BusinessNoPrefixes.PLT);
        ProjectListingDO listing = ProjectListingDO.builder()
                .listingCode(code)
                .projectId(project.getId())
                .buyerProductId(product.getId())
                .status(ProjectListingStatus.AVAILABLE.getCode())
                .version(0L)
                .createdBy(operatorId)
                .createdAt(now)
                .updatedBy(operatorId)
                .updatedAt(now)
                .build();
        try {
            listingMapper.insert(listing);
        } catch (DuplicateKeyException duplicate) {
            ProjectListingDO raced = listingMapper.selectByProjectAndBuyerProduct(project.getId(), product.getId());
            if (raced == null) {
                throw duplicate;
            }
            raced.setStatus(ProjectListingStatus.AVAILABLE.getCode());
            raced.setUpdatedBy(operatorId);
            raced.setUpdatedAt(now);
            publishVersion(raced, product, mode, line.percent(), line.amountCents(), operatorId, now);
            ensureUnpricedIdentity(raced.getId(), now);
            eventPublisher.publishEvent(new ProjectListingPricingInitializationEvent(raced.getId(), operatorId));
            return raced.getListingCode();
        }
        publishVersion(listing, product, mode, line.percent(), line.amountCents(), operatorId, now);
        ensureUnpricedIdentity(listing.getId(), now);
        eventPublisher.publishEvent(new ProjectListingPricingInitializationEvent(listing.getId(), operatorId));
        return code;
    }

    /**
     * 新建或复活项目商品时写入通用定价身份，未配置规则前保持 UNPRICED。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#1-范围与不变量}。
     */
    private void ensureUnpricedIdentity(Long listingId, LocalDateTime now) {
        if (projectPricingMapper.selectByListingId(listingId) != null) {
            return;
        }
        projectPricingMapper.insert(ProjectSkuPricingDO.builder()
                .listingId(listingId)
                .pricingStatus(PricingStatus.UNPRICED.name())
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /**
     * 同一本地事务插入不可变 version 并切换 current_version_id；FOLLOW_L2 不写 L3，EQUAL_L1/CUSTOM 为项目覆盖。
     * 价格输入以当前分销商通用版本为准，仅历史行回退旧链。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
     */
    private void publishVersion(ProjectListingDO listing, BuyerProductDO product, L3AdjustMode mode,
                                Integer percent, Long amountCents, Long operatorId, LocalDateTime now) {
        FrozenPrice frozen = freeze(product, mode, percent, amountCents);
        List<ProjectListingVersionDO> versions = listingVersionMapper.selectByListingId(listing.getId());
        int versionNo = versions.isEmpty() ? 1 : versions.getLast().getVersionNo() + 1;
        ProjectListingVersionDO version = ProjectListingVersionDO.builder()
                .listingId(listing.getId())
                .versionNo(versionNo)
                .buyerSettlementUnitPrice(frozen.unitPriceCents())
                .settlementPriceLevel(frozen.level().name())
                .priceSource(frozen.listingSource().name())
                .l3AdjustMode(mode.name())
                .l3Percent(percent)
                .l3AmountCents(amountCents)
                .currency(Money.CURRENCY_CNY)
                .publishedBy(operatorId)
                .publishedAt(now)
                .createdAt(now)
                .build();
        listingVersionMapper.insert(version);
        listing.setCurrentVersionId(version.getId());
        listing.setUpdatedBy(operatorId);
        listing.setUpdatedAt(now);
        if (listingMapper.updateById(listing) != 1) {
            throw exception(PROJECT_LISTING_NOT_FOUND);
        }
    }

    private FrozenPrice freeze(BuyerProductDO product, L3AdjustMode mode, Integer percent, Long amountCents) {
        return freeze(priceSource.resolve(product), mode, percent, amountCents);
    }

    /**
     * 试算复用已读取的同一组版本输入，避免在一次请求里混合两次读取结果。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
     */
    private FrozenPrice freeze(PriceBasis basis, L3AdjustMode mode, Integer percent, Long amountCents) {
        if (basis.generic() && (basis.platformBaseCents() == null || basis.distributionCents() == null)) {
            throw exception(PLATFORM_SETTLEMENT_PRICE_MISSING);
        }
        if (mode == L3AdjustMode.FOLLOW_L2) {
            settlementPriceResolver.applyL3(mode, basis.platformBaseCents() == null ? 0L : basis.platformBaseCents(),
                    percent, amountCents);
            if (basis.distributionCents() == null || basis.distributionSource() == null) {
                throw exception(PLATFORM_SETTLEMENT_PRICE_MISSING);
            }
            return new FrozenPrice(basis.distributionCents(), basis.distributionSource(), ListingPriceSource.INHERITED);
        }
        if (basis.platformBaseCents() == null) {
            throw exception(PLATFORM_SETTLEMENT_PRICE_MISSING);
        }
        Long l3 = settlementPriceResolver.applyL3(mode, basis.platformBaseCents(), percent, amountCents);
        return new FrozenPrice(l3, PriceSource.L3, ListingPriceSource.PROJECT_OVERRIDE);
    }

    private BuyerProductDO requireLiveBuyerProduct(Long buyerId, String platformSkuCode) {
        if (platformSkuCode == null || platformSkuCode.isBlank()) {
            throw exception(PROJECT_SKU_NOT_IN_BUYER_CATALOG);
        }
        PlatformSkuDO sku = skuMapper.selectByCode(platformSkuCode.trim());
        if (sku == null) {
            throw exception(PROJECT_SKU_NOT_IN_BUYER_CATALOG);
        }
        BuyerProductDO product = productMapper.selectByBuyerAndSku(buyerId, sku.getId());
        if (product == null
                || product.getStatus() == null
                || product.getStatus() != BuyerProductStatus.ACTIVE.getCode()) {
            throw exception(PROJECT_SKU_NOT_IN_BUYER_CATALOG);
        }
        return product;
    }

    private BuyerProjectDO requireProjectForUpdate(String projectCode) {
        BuyerProjectDO project = projectMapper.selectByProjectCodeForUpdate(projectCode);
        if (project == null) {
            throw exception(PROJECT_NOT_FOUND);
        }
        return project;
    }

    private BuyerProjectDO requireProject(String projectCode) {
        BuyerProjectDO project = projectMapper.selectByProjectCode(projectCode);
        if (project == null) {
            throw exception(PROJECT_NOT_FOUND);
        }
        return project;
    }

    private ProjectListingDO requireLiveListing(Long projectId, String listingCode) {
        return requireLiveListing(projectId, listingCode, true);
    }

    private ProjectListingDO requireLiveListing(Long projectId, String listingCode, boolean forUpdate) {
        ProjectListingDO listing = forUpdate ? listingMapper.selectByCodeForUpdate(listingCode)
                : listingMapper.selectByCode(listingCode);
        if (listing == null
                || !projectId.equals(listing.getProjectId())
                || listing.getStatus() == null
                || listing.getStatus() != ProjectListingStatus.AVAILABLE.getCode()) {
            throw exception(PROJECT_LISTING_NOT_FOUND);
        }
        return listing;
    }

    private BuyerProductDO requireBuyerProduct(BuyerProjectDO project, ProjectListingDO listing) {
        BuyerProductDO product = productMapper.selectById(listing.getBuyerProductId());
        if (product == null || product.getStatus() == null
                || !java.util.Objects.equals(project.getBuyerId(), product.getBuyerId())
                || product.getStatus() != BuyerProductStatus.ACTIVE.getCode()) {
            throw exception(PROJECT_SKU_NOT_IN_BUYER_CATALOG);
        }
        return product;
    }

    private static List<String> normalizeCodes(List<String> codes) {
        if (codes == null || codes.isEmpty() || codes.size() > MAX_BATCH_LISTING_COUNT) {
            throw new IllegalArgumentException("listingCodes must contain 1 to 200 items");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                throw exception(PROJECT_LISTING_NOT_FOUND);
            }
            normalized.add(code.trim());
        }
        return List.copyOf(normalized);
    }

    private record FrozenPrice(long unitPriceCents, PriceSource level, ListingPriceSource listingSource) {
    }

    /** 行内定价覆盖与目录发布同事务保存；设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。 */
    public record ListingLine(String platformSkuCode, L3AdjustMode l3AdjustMode,
                              Integer percent, Long amountCents, ProjectOverrides pricingOverrides) {
        public ListingLine(String platformSkuCode, L3AdjustMode l3AdjustMode, Integer percent, Long amountCents) {
            this(platformSkuCode, l3AdjustMode, percent, amountCents, null);
        }
    }

    public record L3PricingPreview(
            @Schema(description = "项目商品永久业务编码") String listingCode,
            @Schema(description = "平台SKU永久业务编码") String platformSkuCode,
            @Schema(description = "L1平台结算单价，单位CNY分") Long l1PriceCents,
            @Schema(description = "当前L2有效单价，单位CNY分") long l2PriceCents,
            @Schema(description = "当前L2有效价来源：L1或L2") PriceSource l2PriceSource,
            @Schema(description = "当前项目冻结最终价，单位CNY分") Long currentFinalPriceCents,
            @Schema(description = "当前项目冻结最终价来源：L1、L2或L3") PriceSource currentPriceSource,
            @Schema(description = "试算后项目最终价，单位CNY分") long previewFinalPriceCents,
            @Schema(description = "试算后项目最终价来源：L1、L2或L3") PriceSource previewPriceSource) {
    }
}
