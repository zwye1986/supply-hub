package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingPriceSource.PriceBasis;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FormulaRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.HideRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.ServiceFeeRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND;

/**
 * 项目商品列表：显式项目分销价覆盖优先显示当前通用版本金额；无覆盖时保留旧冻结价，
 * 项目综合定价覆盖配置按当前版本指针回显。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectListingQueryService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final BuyerProjectMapper projectMapper;
    private final ProjectListingMapper listingMapper;
    private final ProjectListingVersionMapper listingVersionMapper;
    private final ProjectSkuPricingMapper projectPricingMapper;
    private final ProjectSkuPricingVersionMapper projectPricingVersionMapper;
    private final BuyerProductMapper productMapper;
    private final PlatformSkuMapper skuMapper;
    private final ProjectListingPriceSource priceSource;
    private final ObjectMapper objectMapper;

    /**
     * 分页未移除项目商品，按更新时间倒序。配置只取当前综合定价版本；本页身份和版本分别批量读取，
     * 以同一只读事务视图避免保存时指针切换导致配置错配，不触发价格重算或版本写入。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", readOnly = true)
    public PageResult<ProjectListingListItem> page(String projectCode, Integer pageNo, Integer pageSize) {
        log.info("分页查询项目商品 projectCode={} pageNo={} pageSize={}", projectCode, pageNo, pageSize);
        BuyerProjectDO project = projectMapper.selectByProjectCode(projectCode);
        if (project == null) {
            throw exception(PROJECT_NOT_FOUND);
        }
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(pageNo == null ? DEFAULT_PAGE_NO : pageNo);
        pageParam.setPageSize(pageSize == null ? DEFAULT_PAGE_SIZE : pageSize);
        PageResult<ProjectListingDO> page = listingMapper.selectPage(pageParam, new LambdaQueryWrapper<ProjectListingDO>()
                .eq(ProjectListingDO::getProjectId, project.getId())
                .eq(ProjectListingDO::getStatus, ProjectListingStatus.AVAILABLE.getCode())
                .orderByDesc(ProjectListingDO::getUpdatedAt));
        Map<Long, ProjectSkuPricingVersionDO> pricingVersions = currentPricingVersions(page.getList());
        List<ProjectListingListItem> items = new ArrayList<>();
        for (ProjectListingDO listing : page.getList()) {
            items.add(toListItem(listing, pricingVersions.get(listing.getId())));
        }
        return new PageResult<>(items, page.getTotal());
    }

    /**
     * 只从已分页且已限定项目的 listing 加载定价身份，再按 current_version_id 批量读取版本。
     * 指针异常必须显式失败，避免将历史旧版或继承后的有效配置误当作用户保存的项目规则。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
     */
    private Map<Long, ProjectSkuPricingVersionDO> currentPricingVersions(List<ProjectListingDO> listings) {
        if (listings.isEmpty()) {
            return Map.of();
        }
        List<Long> listingIds = listings.stream().map(ProjectListingDO::getId).toList();
        List<ProjectSkuPricingDO> identities = projectPricingMapper.selectList(
                new LambdaQueryWrapper<ProjectSkuPricingDO>()
                        .in(ProjectSkuPricingDO::getListingId, listingIds));
        Set<Long> versionIds = identities.stream().map(ProjectSkuPricingDO::getCurrentVersionId)
                .filter(id -> id != null).collect(Collectors.toSet());
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProjectSkuPricingVersionDO> versionsById = projectPricingVersionMapper.selectList(
                new LambdaQueryWrapper<ProjectSkuPricingVersionDO>()
                        .in(ProjectSkuPricingVersionDO::getId, versionIds)).stream()
                .collect(Collectors.toMap(ProjectSkuPricingVersionDO::getId, Function.identity()));
        Map<Long, ProjectSkuPricingVersionDO> byListing = new HashMap<>();
        for (ProjectSkuPricingDO identity : identities) {
            if (identity.getCurrentVersionId() == null) {
                continue;
            }
            ProjectSkuPricingVersionDO version = versionsById.get(identity.getCurrentVersionId());
            if (version == null || !identity.getId().equals(version.getPricingId())) {
                throw new IllegalStateException("project pricing current version is missing or mismatched for listingId="
                        + identity.getListingId());
            }
            byListing.put(identity.getListingId(), version);
        }
        return byListing;
    }

    private ProjectListingListItem toListItem(ProjectListingDO listing, ProjectSkuPricingVersionDO pricingVersion) {
        BuyerProductDO product = listing.getBuyerProductId() == null
                ? null : productMapper.selectById(listing.getBuyerProductId());
        PlatformSkuDO sku = product == null || product.getPlatformSkuId() == null
                ? null : skuMapper.selectById(product.getPlatformSkuId());
        PriceBasis basis = product == null ? null : priceSource.resolve(product);
        Long suggestPriceCents = sku == null ? null : priceSource.legacyPlatformPrice(sku.getId());
        Long platformBasePriceCents = basis == null ? null : basis.platformBaseCents();
        Long distPriceCents = basis == null ? null : basis.distributionCents();
        ProjectListingVersionDO frozen = listing.getCurrentVersionId() == null
                ? null : listingVersionMapper.selectById(listing.getCurrentVersionId());
        ProjectOverrides savedOverrides = savedPricingOverrides(listing.getId(), pricingVersion);
        boolean genericCovered = savedOverrides != null && !savedOverrides.distributionInherited();
        Long finalPriceCents = genericCovered ? pricingVersion.getDistributionPriceCents()
                : frozen == null ? null : frozen.getBuyerSettlementUnitPrice();
        boolean covered = genericCovered || frozen != null && PriceSource.L3.name().equals(frozen.getSettlementPriceLevel());
        return new ProjectListingListItem(
                listing.getListingCode(),
                sku == null ? null : sku.getSkuCode(),
                sku == null ? null : sku.getSkuName(),
                suggestPriceCents,
                sku == null ? null : sku.getSuggestedSalePrice(),
                platformBasePriceCents,
                distPriceCents,
                covered ? finalPriceCents : null,
                finalPriceCents,
                genericCovered ? PriceSource.L3.name() : frozen == null ? null : frozen.getSettlementPriceLevel(),
                covered,
                savedOverrides == null ? null : ProjectPricingConfigView.from(savedOverrides));
    }

    /** 当前版本未发布时返回 null；已发布却无法解析时保留异常因果，禁止伪造全继承配置。 */
    private ProjectOverrides savedPricingOverrides(Long listingId, ProjectSkuPricingVersionDO pricingVersion) {
        if (pricingVersion == null) {
            return null;
        }
        try {
            ProjectOverrides config = objectMapper.readValue(pricingVersion.getOverrideJson(), ProjectOverrides.class);
            if (config == null) {
                throw new IllegalStateException("project pricing config is null for listingId=" + listingId);
            }
            return config;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new IllegalStateException("project pricing config is invalid for listingId=" + listingId, ex);
        }
    }

    /**
     * 项目商品列表行。金额单位 CNY 分。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
     */
    @Schema(name = "ProjectListingListItem", description = "项目商品列表行；金额均为 CNY 分")
    public record ProjectListingListItem(
            @Schema(description = "项目商品稳定业务编码") String listingCode,
            @Schema(description = "平台 SKU 永久业务编码；平台 SKU 缺失时为空") String platformSkuCode,
            @Schema(description = "平台 SKU 名称；平台 SKU 缺失时为空") String skuName,
            @Schema(description = "历史 L1 建议结算价兼容字段，单位 CNY 分；无旧 L1 时为空") Long suggestPriceCents,
            @Schema(description = "平台 SKU 销售建议价，单位 CNY 分；平台 SKU 缺失时为空") Long suggestedSalePriceCents,
            @Schema(description = "旧 L3 调价模式使用的 B 端平台基价，单位 CNY 分；无有效基价时为空") Long platformBasePriceCents,
            @Schema(description = "当前分销商有效分销价，优先通用定价版本，单位 CNY 分；未定价时为空") Long distPriceCents,
            @Schema(description = "项目通用定价显式覆盖分销价时取当前项目版本分销价；否则旧冻结版本为 L3 时取冻结价，单位 CNY 分") Long projectPriceCents,
            @Schema(description = "有项目通用分销价覆盖时取当前项目版本分销价，否则取旧冻结单价，单位 CNY 分") Long finalPriceCents,
            @Schema(description = "项目分销价覆盖或旧冻结版本的结算层级：L1、L2、L3") String priceSource,
            @Schema(description = "当前通用项目分销价显式覆盖，或旧冻结版本结算层级为 L3") boolean projectPriceCovered,
            @Schema(description = "当前项目综合定价版本保存的五组继承开关及覆盖规则；无当前版本时为空")
            ProjectPricingConfigView projectPricingConfig) {
    }

    /**
     * 项目已保存覆盖配置的真实响应类型。逐字段复制持久化的 {@link ProjectOverrides}，不合并上游有效值，
     * 因此既保持原有 JSON 键和值，也让 Swagger 直接解析实际类型及其子对象。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
     */
    @Schema(name = "ProjectPricingConfigView", description = "项目当前版本保存的五组配置。每组 Inherited=true 表示继承分销商配置、计算时忽略本组规则；false 表示使用本项目覆盖规则。继承时规则对象通常为 null，但已保存的非空规则仍原样回显。此对象不是合并后的有效配置或旧 L3 冻结价。")
    public record ProjectPricingConfigView(
            @Schema(description = "分销价是否继承分销商配置；true 继承，false 使用 distributionRule") boolean distributionInherited,
            @Schema(description = "项目分销价覆盖公式；distributionInherited=true 时通常为 null，若保存了规则仍原样回显但不参与计算")
            ProjectFormulaRuleView distributionRule,
            @Schema(description = "销售价是否继承分销商配置；true 继承，false 使用 salesRule") boolean salesInherited,
            @Schema(description = "项目销售价覆盖公式；salesInherited=true 时通常为 null，若保存了规则仍原样回显但不参与计算")
            ProjectFormulaRuleView salesRule,
            @Schema(description = "服务费是否继承分销商配置；true 继承，false 使用 serviceFeeRule") boolean serviceFeeInherited,
            @Schema(description = "项目服务费覆盖公式；serviceFeeInherited=true 时通常为 null，若保存了规则仍原样回显但不参与计算")
            ProjectServiceFeeRuleView serviceFeeRule,
            @Schema(description = "利润隐藏阈值是否继承分销商配置；true 继承，false 使用 hideRule") boolean hideInherited,
            @Schema(description = "项目利润隐藏阈值；hideInherited=true 时通常为 null，若保存了规则仍原样回显但不参与计算")
            ProjectHideRuleView hideRule,
            @Schema(description = "税务展示属性是否继承分销商配置；true 继承，false 使用 taxMetadata") boolean taxInherited,
            @Schema(description = "项目税务展示属性；taxInherited=true 时为 null；税务不参与价格计算")
            ProjectTaxMetadataView taxMetadata) {

        public static ProjectPricingConfigView from(ProjectOverrides source) {
            return new ProjectPricingConfigView(
                    source.distributionInherited(), ProjectFormulaRuleView.from(source.distributionRule()),
                    source.salesInherited(), ProjectFormulaRuleView.from(source.salesRule()),
                    source.serviceFeeInherited(), ProjectServiceFeeRuleView.from(source.serviceFeeRule()),
                    source.hideInherited(), ProjectHideRuleView.from(source.hideRule()),
                    source.taxInherited(), ProjectTaxMetadataView.from(source.taxMetadata()));
        }
    }

    /** 价格公式回显；设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#2-公式与精度}。 */
    @Schema(name = "ProjectFormulaRuleView", description = "项目分销价或销售价覆盖公式；对应的 distributionInherited/salesInherited=true 时通常为 null，已保存的非空规则仍回显但不参与计算。CUSTOM 时按 (base × percentage / 100) operator operand 计算，最终金额四舍五入到分；DIRECT_MARKET_PRICE 时直接等于市场价，其余字段为空。")
    public record ProjectFormulaRuleView(
            @Schema(description = "调价方式：DIRECT_MARKET_PRICE 直接等于市场价；CUSTOM 自定义公式", example = "CUSTOM") String mode,
            @Schema(description = "CUSTOM 的价格基数：MARKET_PRICE 市场价、SUGGESTED_SALE_PRICE 销售建议价、SUPPLY_PRICE 最低有效供货价；直接等于市场价时为 null", example = "MARKET_PRICE") String base,
            @Schema(description = "CUSTOM 的基数百分比，大于 0、最多两位小数；100 表示基数原值，85 表示基数的 85%；直接等于市场价时为 null", example = "85") BigDecimal percentage,
            @Schema(description = "CUSTOM 的运算符：ADD 加、SUBTRACT 减、MULTIPLY 乘、DIVIDE 除；直接等于市场价时为 null", example = "SUBTRACT") String operator,
            @Schema(description = "CUSTOM 的运算值，非负；ADD/SUBTRACT 的单位为元、最多两位小数，MULTIPLY/DIVIDE 为无单位系数、最多四位小数，DIVIDE 不得为 0；直接等于市场价时为 null", example = "1.50") BigDecimal operand) {

        static ProjectFormulaRuleView from(FormulaRule source) {
            return source == null ? null : new ProjectFormulaRuleView(
                    source.mode() == null ? null : source.mode().name(),
                    source.base() == null ? null : source.base().name(), source.percentage(),
                    source.operator() == null ? null : source.operator().name(), source.operand());
        }
    }

    /** 服务费规则回显；设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#2-公式与精度}。 */
    @Schema(name = "ProjectServiceFeeRuleView", description = "项目服务费覆盖规则；serviceFeeInherited=true 时通常为 null，已保存的非空规则仍回显但不参与计算。按 (商品小计 × percentage / 100) operator operand 计算，最终金额四舍五入到分。")
    public record ProjectServiceFeeRuleView(
            @Schema(description = "商品小计的百分比，大于 0、最多两位小数；2 表示商品小计的 2%", example = "2") BigDecimal percentage,
            @Schema(description = "服务费运算符：ADD 加、SUBTRACT 减、MULTIPLY 乘、DIVIDE 除", example = "ADD") String operator,
            @Schema(description = "服务费运算值，非负；加减单位为元、最多两位小数，乘除为无单位系数、最多四位小数，除数不得为 0", example = "1.00") BigDecimal operand,
            @Schema(description = "是否隐藏自定义服务费文案；不影响费用金额或收取", example = "false") boolean hideCopy,
            @Schema(description = "服务费自定义展示文案；hideCopy=false 时应提供，未配置时为空", example = "服务费") String copy) {

        static ProjectServiceFeeRuleView from(ServiceFeeRule source) {
            return source == null ? null : new ProjectServiceFeeRuleView(source.percentage(),
                    source.operator() == null ? null : source.operator().name(), source.operand(),
                    source.hideCopy(), source.copy());
        }
    }

    /** 利润隐藏规则回显；设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#2-公式与精度}。 */
    @Schema(name = "ProjectHideRuleView", description = "项目利润隐藏阈值；hideInherited=true 时通常为 null，已保存的非空规则仍回显但不参与计算。分销区与销售区同时配置时取较高值；利润率严格低于该值时用户端隐藏商品。")
    public record ProjectHideRuleView(
            @Schema(description = "分销区利润隐藏阈值，百分数 0 到 100、最多两位小数；未配置时为 null", example = "12.50")
            BigDecimal distributionThresholdPercent,
            @Schema(description = "销售区利润隐藏阈值，百分数 0 到 100、最多两位小数；未配置时为 null", example = "10")
            BigDecimal salesThresholdPercent) {

        static ProjectHideRuleView from(HideRule source) {
            return source == null ? null : new ProjectHideRuleView(
                    source.distributionThresholdPercent(), source.salesThresholdPercent());
        }
    }

    /** 税务展示属性回显；设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#4-税务展示属性}。 */
    @Schema(name = "ProjectTaxMetadataView", description = "项目税务展示属性；taxInherited=true 时此对象为 null。使用保存后归一化的值，不参与价格计算。")
    public record ProjectTaxMetadataView(
            @Schema(description = "true 含税，false 不含税；不含税时 taxRateCode 必须为 NON_TAXABLE", example = "true") Boolean taxIncluded,
            @Schema(description = "system 动态字典 supply_chain_tax_rate 的启用编码；不含税时为 NON_TAXABLE", example = "SIX_PERCENT") String taxRateCode,
            @Schema(description = "保存时解析并冻结的税率名称；字典未提供名称时为 null", example = "6%") String taxRateLabel) {

        static ProjectTaxMetadataView from(TaxMetadata source) {
            return source == null ? null : new ProjectTaxMetadataView(
                    source.taxIncluded(), source.taxRateCode(), source.taxRateLabel());
        }
    }
}
