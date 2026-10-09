package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService;
import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService.RoutingSnapshot;
import com.aifuli.module.supplychain.routing.application.SupplyQualificationUnavailableException;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 当前供给资格的唯一新视图读取边界：独立只读 RR、普通一致性读，不写目录／供给、不加行锁、不外呼。
 * 第一条业务实体查询建立新视图，再用一次库时钟评估真实路由；不得读取外层未提交写入作为授权。
 * 买家聚合只能形成目录投影，最终订单必须使用本项目结果，不能用其它项目的 CONFIRMED 授权。
 * 见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#3-应用边界与事务协议
 * 及 #71-资格语义不变。
 */
@Service
@RequiredArgsConstructor
public class CurrentSupplyEligibilityReader {
    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String BUYER_PRODUCT_REMOVED = "BUYER_PRODUCT_REMOVED";

    private final BuyerMapper buyerMapper;
    private final BuyerProductMapper productMapper;
    private final BuyerProjectMapper projectMapper;
    private final ProjectListingMapper listingMapper;
    private final ProjectListingVersionMapper listingVersionMapper;
    private final PlatformSkuMapper skuMapper;
    private final SupplyChainDatabaseClock databaseClock;
    private final SupplierRoutingApplicationService routing;

    /** 本项目授权读取入口；必须由其它 Bean 调用代理，业务时间永远取本次库时钟。见设计 §7.1。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public ProjectEligibility readProject(RoutingInput request) {
        Objects.requireNonNull(request, "request");
        buyerMapper.selectById(request.buyerId()); // 首个业务实体普通读，明确建立独立新 RR 视图。
        LocalDateTime evaluatedAt = databaseClock.now();
        return evaluate(new RoutingInput(request.buyerId(), request.projectId(), request.platformSkuId(),
                request.fulfillmentType(), request.quantity(), evaluatedAt,
                request.factsBySupplierCode(), request.deliveryAdcode()));
    }

    /**
     * 当前有效 BPD 的 ACTIVE 项目聚合；全项目在同一新视图内评估，不自调用另一个事务入口。
     * AVAILABLE PLT 的既存指针必须真实且归属一致；没有有效项目才使用 projectId=null 默认规则。
     * 见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#71-资格语义不变。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public BuyerEligibility readBuyer(Long buyerProductId) {
        BuyerProductDO product = productMapper.selectById(Objects.requireNonNull(buyerProductId));
        LocalDateTime evaluatedAt = databaseClock.now();
        if (product == null) {
            throw new IllegalStateException("Current eligibility references a missing buyer product");
        }
        if (buyerMapper.selectById(product.getBuyerId()) == null) {
            throw new IllegalStateException("Buyer product references a missing buyer");
        }
        var sku = skuMapper.selectById(product.getPlatformSkuId());
        if (sku == null) {
            throw new IllegalStateException("Buyer product references a missing platform SKU");
        }
        if (product.getStatus() != BuyerProductStatus.ACTIVE.getCode()) {
            return new BuyerEligibility(SupplyQualification.UNAVAILABLE, List.of(),
                    List.of(BUYER_PRODUCT_REMOVED), evaluatedAt, "BPD:" + product.getId(), null);
        }
        List<ProjectEligibility> projects = new ArrayList<>();
        for (ProjectListingDO listing : listingMapper.selectAvailableByBuyerProductId(product.getId())) {
            requireListingVersion(listing);
            var project = projectMapper.selectById(listing.getProjectId());
            if (project == null || !Objects.equals(project.getBuyerId(), product.getBuyerId())) {
                throw new IllegalStateException("Available project listing references a missing or foreign project");
            }
            if (project.getStatus() == BuyerProjectStatus.ACTIVE.getCode()) {
                projects.add(evaluate(new RoutingInput(product.getBuyerId(), project.getId(), sku.getId(),
                        sku.getFulfillmentType(), 1, evaluatedAt, Map.of())));
            }
        }
        if (projects.isEmpty()) {
            projects.add(evaluate(new RoutingInput(product.getBuyerId(), null, sku.getId(),
                    sku.getFulfillmentType(), 1, evaluatedAt, Map.of())));
        }
        SupplyQualification qualification = projects.stream().anyMatch(ProjectEligibility::confirmed)
                ? SupplyQualification.CONFIRMED
                : projects.stream().anyMatch(result -> result.qualification() == SupplyQualification.UNKNOWN)
                        ? SupplyQualification.UNKNOWN : SupplyQualification.UNAVAILABLE;
        return new BuyerEligibility(qualification, projects,
                projects.stream().flatMap(result -> result.reasonCodes().stream()).distinct().toList(),
                evaluatedAt, "BPD:" + product.getId() + ";" + String.join(";",
                        projects.stream().map(ProjectEligibility::sourceSummary).toList()),
                projects.stream().map(ProjectEligibility::nextFactBoundary).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null));
    }

    private void requireListingVersion(ProjectListingDO listing) {
        var version = listing.getCurrentVersionId() == null ? null
                : listingVersionMapper.selectById(listing.getCurrentVersionId());
        if (version == null || !Objects.equals(version.getListingId(), listing.getId())) {
            throw new IllegalStateException("Available project listing references a missing or foreign version");
        }
    }

    /** 只转换封闭缺条件，技术异常保留原因继续上抛，不在中间层打印堆栈。见设计 §7.1／§9。 */
    private ProjectEligibility evaluate(RoutingInput input) {
        try {
            RoutingSnapshot snapshot = routing.route(input);
            var mapped = CurrentSupplyQualificationPolicy.evaluate(snapshot.decision());
            String source = "project:" + input.projectId() + ";policy:" + snapshot.policyId()
                    + ";version:" + snapshot.policyVersionId() + ";digest:"
                    + HexFormat.of().formatHex(snapshot.configDigest()) + ";rule:" + snapshot.ruleCode()
                    + ";candidates:" + snapshot.decision().orderedCandidates();
            return new ProjectEligibility(input.projectId(), mapped.qualification(), snapshot,
                    mapped.reasonCodes(), input.businessTime(), source, snapshot.nextFactBoundary());
        } catch (SupplyQualificationUnavailableException missing) {
            return new ProjectEligibility(input.projectId(), SupplyQualification.UNAVAILABLE, null,
                    List.of(missing.reason().name()), input.businessTime(),
                    "project:" + input.projectId() + ";reason:" + missing.reason().name(), missing.nextFactBoundary());
        }
    }

    /** 不可变单项目结果；完整原路由快照供后续持久化使用，缺路由时不伪造快照。 */
    public record ProjectEligibility(Long projectId, SupplyQualification qualification,
                                     RoutingSnapshot routingSnapshot, List<String> reasonCodes,
                                     LocalDateTime evaluatedAt, String sourceSummary,
                                     LocalDateTime nextFactBoundary) {
        public ProjectEligibility { reasonCodes = List.copyOf(reasonCodes); }
        public boolean confirmed() { return qualification == SupplyQualification.CONFIRMED; }
    }

    /** 不可变买家目录聚合；projects 保留每个真实项目或唯一 null 默认结果。 */
    public record BuyerEligibility(SupplyQualification qualification, List<ProjectEligibility> projects,
                                   List<String> reasonCodes, LocalDateTime evaluatedAt,
                                   String sourceSummary, LocalDateTime nextFactBoundary) {
        public BuyerEligibility { projects = List.copyOf(projects); reasonCodes = List.copyOf(reasonCodes); }
    }
}
