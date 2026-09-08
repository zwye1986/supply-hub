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
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
        List<ProjectListItem> items = new ArrayList<>();
        for (BuyerProjectDO project : page.getList()) {
            items.add(toListItem(project));
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
        ListingStats stats = listingStats(project.getId());
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

    private ProjectListItem toListItem(BuyerProjectDO project) {
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
                project.getUpdatedAt());
    }

    /**
     * skuCount 为未移除 listing 数；projectPriceSkuCount 为当前版本 settlement_price_level=L3 的数量。
     */
    private ListingStats listingStats(Long projectId) {
        List<ProjectListingDO> listings = listingMapper.selectList(new LambdaQueryWrapper<ProjectListingDO>()
                .eq(ProjectListingDO::getProjectId, projectId)
                .eq(ProjectListingDO::getStatus, ProjectListingStatus.AVAILABLE.getCode()));
        int skuCount = listings.size();
        int projectPriceSkuCount = 0;
        for (ProjectListingDO listing : listings) {
            if (listing.getCurrentVersionId() == null) {
                continue;
            }
            ProjectListingVersionDO version = listingVersionMapper.selectById(listing.getCurrentVersionId());
            if (version != null && PriceSource.L3.name().equals(version.getSettlementPriceLevel())) {
                projectPriceSkuCount++;
            }
        }
        return new ListingStats(skuCount, projectPriceSkuCount);
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
            @Schema(description = "最后修改时间，Asia/Shanghai") LocalDateTime updatedAt) {
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
            @Schema(description = "当前 listing 中 settlement_price_level=L3 的数量") int projectPriceSkuCount) {
    }
}
