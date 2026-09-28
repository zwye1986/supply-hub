package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND;

/**
 * 项目管理端查询：多值筛选、simple 下拉、详情只读 SKU 统计，不返回 app 密钥。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#71-档案}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAdminQueryService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final BuyerProjectMapper projectMapper;
    private final BuyerMapper buyerMapper;
    private final ProjectListingMapper listingMapper;
    private final ProjectListingVersionMapper listingVersionMapper;
    private final ProjectSkuPricingMapper projectPricingMapper;
    private final ProjectSkuPricingVersionMapper projectPricingVersionMapper;
    private final ObjectMapper objectMapper;

    /**
     * 分页查询项目，按更新时间倒序。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#71-档案}。
     */
    public PageResult<ProjectListItem> page(ProjectPageQuery query) {
        log.info("分页查询项目 projectName={} projectCode={} statuses={} buyerCode={} projectTypes={} "
                        + "periodFrom={} periodTo={} pageNo={} pageSize={}",
                query.projectName(), query.projectCode(), query.statuses(), query.buyerCode(),
                query.projectTypes(), query.periodFrom(), query.periodTo(),
                query.pageNo(), query.pageSize());
        LambdaQueryWrapper<BuyerProjectDO> wrapper = new LambdaQueryWrapper<BuyerProjectDO>()
                .like(hasText(query.projectName()), BuyerProjectDO::getProjectName, query.projectName())
                .eq(hasText(query.projectCode()), BuyerProjectDO::getProjectCode, query.projectCode())
                .in(hasValues(query.statuses()), BuyerProjectDO::getStatus, toStatusCodes(query.statuses()))
                .in(hasValues(query.projectTypes()), BuyerProjectDO::getProjectType, query.projectTypes())
                .ge(query.periodFrom() != null, BuyerProjectDO::getStartDate, query.periodFrom())
                .le(query.periodTo() != null, BuyerProjectDO::getEndDate, query.periodTo())
                .orderByDesc(BuyerProjectDO::getUpdatedAt);
        if (hasText(query.buyerCode())) {
            BuyerDO buyer = buyerMapper.selectByBuyerCode(query.buyerCode().trim());
            if (buyer == null) {
                throw exception(BUYER_PROFILE_NOT_FOUND);
            }
            wrapper.eq(BuyerProjectDO::getBuyerId, buyer.getId());
        }
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(query.pageNo() == null ? DEFAULT_PAGE_NO : query.pageNo());
        pageParam.setPageSize(query.pageSize() == null ? DEFAULT_PAGE_SIZE : query.pageSize());
        PageResult<BuyerProjectDO> page = projectMapper.selectPage(pageParam, wrapper);
        Map<Long, ListingStats> statsByProject = listingStats(page.getList().stream()
                .map(BuyerProjectDO::getId).toList());
        List<ProjectListItem> items = new ArrayList<>();
        for (BuyerProjectDO project : page.getList()) {
            items.add(toListItem(project, statsByProject.getOrDefault(project.getId(), new ListingStats(0, 0))));
        }
        return new PageResult<>(items, page.getTotal());
    }

    public List<SimpleProjectItem> listSimple(String projectName, String projectCode) {
        log.info("简易项目列表 projectName={} projectCode={}", projectName, projectCode);
        List<BuyerProjectDO> projects = projectMapper.selectList(new LambdaQueryWrapper<BuyerProjectDO>()
                .like(hasText(projectName), BuyerProjectDO::getProjectName, projectName)
                .eq(hasText(projectCode), BuyerProjectDO::getProjectCode, projectCode)
                .orderByDesc(BuyerProjectDO::getUpdatedAt));
        List<SimpleProjectItem> items = new ArrayList<>();
        for (BuyerProjectDO project : projects) {
            items.add(new SimpleProjectItem(project.getProjectCode(), project.getProjectName()));
        }
        return items;
    }

    /**
     * 项目详情。含内部主键与只读 SKU 统计；不返回 appId/appKey。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#71-档案}。
     */
    public ProjectDetail get(String projectCode) {
        log.info("查询项目详情 projectCode={}", projectCode);
        BuyerProjectDO project = projectMapper.selectByProjectCode(projectCode);
        if (project == null) {
            throw exception(PROJECT_NOT_FOUND);
        }
        BuyerDO buyer = project.getBuyerId() == null ? null : buyerMapper.selectById(project.getBuyerId());
        ListingStats stats = listingStats(List.of(project.getId()))
                .getOrDefault(project.getId(), new ListingStats(0, 0));
        return new ProjectDetail(
                project.getId(),
                project.getProjectCode(),
                project.getProjectName(),
                BuyerProjectStatus.fromCode(project.getStatus()).name(),
                buyer == null ? null : buyer.getBuyerCode(),
                buyer == null ? null : buyer.getBuyerName(),
                project.getProjectType(),
                project.getStartDate(),
                project.getEndDate(),
                project.getSettlementPartyType(),
                project.getDescription(),
                project.getCatalogMode(),
                stats.skuCount(),
                stats.projectPriceSkuCount());
    }

    private ProjectListItem toListItem(BuyerProjectDO project, ListingStats stats) {
        BuyerDO buyer = project.getBuyerId() == null ? null : buyerMapper.selectById(project.getBuyerId());
        return new ProjectListItem(
                project.getId(),
                project.getProjectCode(),
                project.getProjectName(),
                BuyerProjectStatus.fromCode(project.getStatus()).name(),
                buyer == null ? null : buyer.getBuyerCode(),
                buyer == null ? null : buyer.getBuyerName(),
                project.getProjectType(),
                project.getStartDate(),
                project.getEndDate(),
                project.getSettlementPartyType(),
                project.getUpdatedAt(),
                stats.skuCount(),
                stats.projectPriceSkuCount());
    }

    /**
     * 项目专属价覆盖数与商品列表一致：通用项目版本显式覆盖分销价，或旧冻结层级为 L3，取并集。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
     */
    private Map<Long, ListingStats> listingStats(List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        List<ProjectListingDO> listings = listingMapper.selectList(new LambdaQueryWrapper<ProjectListingDO>()
                .in(ProjectListingDO::getProjectId, projectIds)
                .eq(ProjectListingDO::getStatus, ProjectListingStatus.AVAILABLE.getCode()));
        if (listings.isEmpty()) {
            return Map.of();
        }
        List<Long> listingIds = listings.stream().map(ProjectListingDO::getId).toList();
        Map<Long, ProjectSkuPricingDO> pricingByListing = projectPricingMapper
                .selectList(new LambdaQueryWrapper<ProjectSkuPricingDO>()
                        .in(ProjectSkuPricingDO::getListingId, listingIds))
                .stream().collect(Collectors.toMap(ProjectSkuPricingDO::getListingId, pricing -> pricing));
        Set<Long> pricingVersionIds = pricingByListing.values().stream()
                .map(ProjectSkuPricingDO::getCurrentVersionId)
                .filter(id -> id != null).collect(Collectors.toSet());
        Map<Long, ProjectSkuPricingVersionDO> pricingVersions = pricingVersionIds.isEmpty() ? Map.of()
                : projectPricingVersionMapper.selectList(new LambdaQueryWrapper<ProjectSkuPricingVersionDO>()
                        .in(ProjectSkuPricingVersionDO::getId, pricingVersionIds))
                .stream().collect(Collectors.toMap(ProjectSkuPricingVersionDO::getId, version -> version));
        Set<Long> listingVersionIds = listings.stream().map(ProjectListingDO::getCurrentVersionId)
                .filter(id -> id != null).collect(Collectors.toSet());
        Map<Long, ProjectListingVersionDO> listingVersions = listingVersionIds.isEmpty() ? Map.of()
                : listingVersionMapper.selectList(new LambdaQueryWrapper<ProjectListingVersionDO>()
                        .in(ProjectListingVersionDO::getId, listingVersionIds))
                .stream().collect(Collectors.toMap(ProjectListingVersionDO::getId, version -> version));
        Map<Long, int[]> counts = new HashMap<>();
        for (ProjectListingDO listing : listings) {
            int[] count = counts.computeIfAbsent(listing.getProjectId(), ignored -> new int[2]);
            count[0]++;
            if (hasGenericProjectPrice(listing.getId(), pricingByListing, pricingVersions)
                    || hasLegacyProjectPrice(listing, listingVersions)) {
                count[1]++;
            }
        }
        Map<Long, ListingStats> result = new HashMap<>();
        counts.forEach((projectId, count) -> result.put(projectId, new ListingStats(count[0], count[1])));
        return result;
    }

    private static boolean hasLegacyProjectPrice(ProjectListingDO listing,
                                                 Map<Long, ProjectListingVersionDO> listingVersions) {
        if (listing.getCurrentVersionId() == null) {
            return false;
        }
        ProjectListingVersionDO version = listingVersions.get(listing.getCurrentVersionId());
        return version != null && PriceSource.L3.name().equals(version.getSettlementPriceLevel());
    }

    private boolean hasGenericProjectPrice(Long listingId, Map<Long, ProjectSkuPricingDO> pricingByListing,
                                           Map<Long, ProjectSkuPricingVersionDO> pricingVersions) {
        ProjectSkuPricingDO identity = pricingByListing.get(listingId);
        if (identity == null || identity.getCurrentVersionId() == null) {
            return false;
        }
        ProjectSkuPricingVersionDO version = pricingVersions.get(identity.getCurrentVersionId());
        if (version == null || !identity.getId().equals(version.getPricingId())) {
            throw new IllegalStateException("project pricing current version is missing or mismatched for listingId=" + listingId);
        }
        try {
            ProjectOverrides overrides = objectMapper.readValue(version.getOverrideJson(), ProjectOverrides.class);
            if (overrides == null) {
                throw new IllegalStateException("project pricing config is null for listingId=" + listingId);
            }
            return !overrides.distributionInherited();
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new IllegalStateException("project pricing config is invalid for listingId=" + listingId, ex);
        }
    }

    private static List<Short> toStatusCodes(List<BuyerProjectStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        List<Short> codes = new ArrayList<>();
        for (BuyerProjectStatus status : statuses) {
            if (status != null) {
                codes.add(status.getCode());
            }
        }
        return codes;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasValues(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    private record ListingStats(int skuCount, int projectPriceSkuCount) {
    }

    public record ProjectPageQuery(
            String projectName, String projectCode,
            List<BuyerProjectStatus> statuses, String buyerCode, List<String> projectTypes,
            LocalDate periodFrom, LocalDate periodTo,
            Integer pageNo, Integer pageSize) {
    }

    @Schema(description = "项目简易下拉项")
    public record SimpleProjectItem(
            @Schema(description = "采购项目全局永久编码") String projectCode,
            @Schema(description = "采购项目名称") String projectName) {
    }

    @Schema(description = "项目列表行")
    public record ProjectListItem(
            @Schema(description = "采购项目内部主键") Long id,
            @Schema(description = "采购项目全局永久编码") String projectCode,
            @Schema(description = "采购项目名称") String projectName,
            @Schema(description = "项目状态") String status,
            @Schema(description = "采购商永久业务编码") String buyerCode,
            @Schema(description = "采购商标准名称") String buyerName,
            @Schema(description = "项目类型封闭枚举") String projectType,
            @Schema(description = "项目开始日") LocalDate startDate,
            @Schema(description = "项目结束日") LocalDate endDate,
            @Schema(description = "结算方，本期仅DISTRIBUTOR") String settlementPartyType,
            @Schema(description = "最后修改时间，Asia/Shanghai") LocalDateTime updatedAt,
            @Schema(description = "未移除项目商品数量") int skuCount,
            @Schema(description = "当前项目商品中通用分销价覆盖或旧 L3 冻结覆盖的数量，去重") int projectPriceSkuCount) {
    }

    /**
     * 项目详情。不含 appId/appKey。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#71-档案}。
     */
    public record ProjectDetail(
            @Schema(description = "采购项目内部主键") Long id,
            @Schema(description = "采购项目全局永久编码") String projectCode,
            @Schema(description = "采购项目名称") String projectName,
            @Schema(description = "项目状态") String status,
            @Schema(description = "采购商永久业务编码") String buyerCode,
            @Schema(description = "采购商标准名称") String buyerName,
            @Schema(description = "项目类型封闭枚举") String projectType,
            @Schema(description = "项目开始日") LocalDate startDate,
            @Schema(description = "项目结束日") LocalDate endDate,
            @Schema(description = "结算方，本期仅DISTRIBUTOR") String settlementPartyType,
            @Schema(description = "项目说明") String description,
            @Schema(description = "目录模式：MANAGED中台管理，INDEPENDENT项目独立") String catalogMode,
            @Schema(description = "未移除项目商品数量") int skuCount,
            @Schema(description = "当前 listing 中项目通用分销价覆盖或旧 L3 冻结覆盖的数量") int projectPriceSkuCount) {
    }
}
