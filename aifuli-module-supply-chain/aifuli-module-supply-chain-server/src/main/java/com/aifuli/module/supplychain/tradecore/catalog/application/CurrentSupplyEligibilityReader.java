package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.application.RoutingConfigurationMissingException;
import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.routing.domain.RoutingType;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationExecutorPort;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CurrentSupplyEligibility;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/**
 * 从独立新 RR 视图读取当前项目供给，不继承 accept 的旧快照、不行锁、不外呼、不依赖目录自身发布状态。
 * 库时钟后首次主体一致性读建立视图，随后 route 的 REQUIRED 事务加入该视图；调用者必须经 Spring 代理。
 * 设计见 {@code docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#5-当前供给reader与错误分类}。
 */
@Service
@RequiredArgsConstructor
public class CurrentSupplyEligibilityReader {
    private final SupplyChainDatabaseClock clock;
    private final BuyerMapper buyers;
    private final BuyerProjectMapper projects;
    private final PlatformSkuMapper skus;
    private final PlatformSpuMapper spus;
    private final SupplierRoutingApplicationService routing;
    private final ProjectListingMapper projectListings;
    private final SupplierRoutingPolicyMapper policies;
    private final SupplierRoutingPolicyVersionMapper policyVersions;
    private final SupplierOfferMapper offers;
    private final SupplierOfferVersionMapper offerVersions;
    private final PublishedConnectorConfigurationResolver connectorConfig;
    private final AdapterMetadataRegistry metadataRegistry;
    private final ClosedJsonSchemaValidator schemas;
    private final OperationExecutorPort executor;
    private final ObjectMapper json;

    /** projectId=null 仅表示默认路由评估，不允许调用方借此替代真实项目最终准入。 */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.REQUIRES_NEW,
            readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CurrentSupplyEligibility evaluate(Long buyerId, Long projectId, Long platformSkuId) {
        LocalDateTime at = clock.now();
        return evaluateAt(buyerId, projectId, platformSkuId, at);
    }

    /** 同一新视图汇总真实项目首选；没有 ACTIVE 项目可用选品时才走默认路由，不跨项目改选候选。 */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.REQUIRES_NEW,
            readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CurrentSupplyEligibility evaluateBuyerSelection(Long buyerId, Long buyerProductId, Long platformSkuId) {
        LocalDateTime at = clock.now();
        var selected = projectListings.selectAvailableByBuyerProductId(buyerProductId);
        var activeProjects = new ArrayList<BuyerProjectDO>();
        // 先核全部引用，再计算资格；首个 CONFIRMED 不能掩盖后续坏归属。
        for (var item : selected) {
            BuyerProjectDO project = projects.selectById(item.getProjectId());
            if (!Objects.equals(item.getBuyerProductId(), buyerProductId)
                    || project == null || !Objects.equals(project.getBuyerId(), buyerId)) {
                throw new IllegalStateException("Supply eligibility project listing ownership is invalid");
            }
            if (Objects.equals(project.getStatus(), BuyerProjectStatus.ACTIVE.getCode())) {
                activeProjects.add(project);
            }
        }
        CurrentSupplyEligibility aggregate = null;
        LocalDateTime boundary = null;
        for (var project : activeProjects) {
            CurrentSupplyEligibility result = evaluateAt(buyerId, project.getId(), platformSkuId, at);
            boundary = earliest(boundary, result.nextBoundaryAt());
            if (aggregate == null || !aggregate.confirmed() && (result.confirmed()
                    || result.availability() == ListingAvailability.UNKNOWN)) {
                aggregate = result;
            }
        }
        return aggregate == null ? evaluateAt(buyerId, null, platformSkuId, at)
                : withBoundary(aggregate, boundary);
    }

    private CurrentSupplyEligibility evaluateAt(Long buyerId, Long projectId, Long platformSkuId, LocalDateTime at) {
        BuyerDO buyer = buyers.selectById(buyerId);
        if (buyer == null) {
            throw new IllegalStateException("Supply eligibility buyer reference is missing");
        }
        if (!Objects.equals(buyer.getStatus(), BuyerStatus.ACTIVE.getCode())) {
            return unavailable("BUYER_NOT_ACTIVE", at);
        }
        if (projectId != null) {
            BuyerProjectDO project = projects.selectById(projectId);
            if (project == null || !Objects.equals(project.getBuyerId(), buyerId)) {
                throw new IllegalStateException("Supply eligibility project reference is missing or mismatched");
            }
            if (!Objects.equals(project.getStatus(), BuyerProjectStatus.ACTIVE.getCode())) {
                return unavailable("PROJECT_NOT_ACTIVE", at);
            }
        }
        PlatformSkuDO sku = skus.selectById(platformSkuId);
        PlatformSpuDO spu = sku == null ? null : spus.selectById(sku.getSpuId());
        if (sku == null || spu == null) {
            throw new IllegalStateException("Supply eligibility SKU or SPU reference is missing");
        }
        if (!Objects.equals(sku.getStatus(), CatalogEntityStatus.ACTIVE.getCode())
                || !Objects.equals(spu.getStatus(), CatalogEntityStatus.ACTIVE.getCode())) {
            return unavailable("SKU_NOT_SALEABLE", at);
        }
        try {
            var snapshot = routing.route(new RoutingInput(
                    buyerId, projectId, platformSkuId, sku.getFulfillmentType(), 1L, at, Map.of()));
            var result = CurrentSupplyEligibility.fromRouting(snapshot, at);
            var boundary = routeBoundary(snapshot, platformSkuId, at);
            if (result.confirmed() && !executionSupported(snapshot, sku)) {
                return new CurrentSupplyEligibility(ListingAvailability.UNKNOWN,
                        "EXECUTOR_NOT_SUPPORTED", at, snapshot, boundary);
            }
            return withBoundary(result, boundary);
        } catch (RoutingConfigurationMissingException missing) {
            return new CurrentSupplyEligibility(ListingAvailability.UNKNOWN, missing.reason(), at, null,
                    policyBoundary(at));
        }
    }

    /** 只验证本地已发布执行配置及实现注册，不读秘密、不调用 execute、不改选路由候选。 */
    private boolean executionSupported(SupplierRoutingApplicationService.RoutingSnapshot snapshot, PlatformSkuDO sku) {
        var selected = snapshot.decision().selectedCandidate();
        SupplierOfferVersionDO offer = offerVersions.selectById(selected.offerVersionId());
        if (offer == null || !Objects.equals(offer.getConnectorVersionId(), selected.connectorVersionId())) {
            throw new IllegalStateException("Selected offer execution version is missing or mismatched");
        }
        var config = connectorConfig.resolve(selected.connectorVersionId());
        var metadata = metadataRegistry.require(config.adapterType());
        var required = new HashSet<Capability>();
        Capability primary = Capability.valueOf(offer.getPrimaryCapabilityCode());
        if (primary != SupplierOfferPublicationService.derivePrimaryCapability(sku.getFulfillmentType())) {
            throw new IllegalStateException("Published offer primary capability contradicts SKU");
        }
        required.add(primary);
        CompletionMode completion = CompletionMode.fromCode(offer.getCompletionMode());
        UnknownResultPolicy unknown = UnknownResultPolicy.fromCode(offer.getUnknownResultPolicy());
        CreateIdempotencyMode idempotency = CreateIdempotencyMode.fromCode(offer.getCreateIdempotencyMode());
        PartialDeliveryMode.fromCode(offer.getPartialDeliveryMode());
        ResultMultiplicity.fromCode(offer.getResultMultiplicity());
        if (unknown == UnknownResultPolicy.REPLAY_SAME_KEY && idempotency != CreateIdempotencyMode.REPLAYABLE_RESULT) {
            throw new IllegalStateException("Published offer replay profile is contradictory");
        }
        if (completion == CompletionMode.QUERY || completion == CompletionMode.QUERY_OR_CALLBACK
                || unknown == UnknownResultPolicy.QUERY_ORDER) required.add(Capability.QUERY_ORDER);
        if (completion == CompletionMode.CALLBACK || completion == CompletionMode.QUERY_OR_CALLBACK
                || unknown == UnknownResultPolicy.WAIT_CALLBACK) required.add(Capability.RECEIVE_CALLBACK);
        if (!config.capabilities().containsAll(required) || !metadata.capabilities().containsAll(required)) {
            throw new IllegalStateException("Published offer execution capabilities are incomplete");
        }
        try {
            var configJson = json.readTree(offer.getFulfillmentConfigJson());
            schemas.validate(metadata.offerSchemaResource(), configJson);
            metadata.validateOfferConfig(offer.getFulfillmentConfigSchemaVersion(), configJson);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Published offer fulfillment config is invalid JSON", invalid);
        }
        return executor.supports(config.adapterType());
    }

    /** 所有实际候选的未来 Offer 起止都能改变排序，不能只取首选失效时间。 */
    private LocalDateTime routeBoundary(SupplierRoutingApplicationService.RoutingSnapshot snapshot,
                                       Long skuId, LocalDateTime at) {
        LocalDateTime boundary = policyBoundary(at);
        for (Long supplierId : snapshot.decision().orderedCandidates().stream()
                .map(candidate -> candidate.supplierId()).distinct().toList()) {
            for (var identity : offers.selectPublishedBySupplierAndSku(supplierId, skuId)) {
                var version = offerVersions.selectById(identity.getCurrentVersionId());
                if (version == null || !Objects.equals(version.getOfferId(), identity.getId())) {
                    throw new IllegalStateException("Published offer boundary reference is missing or mismatched");
                }
                boundary = futureBoundary(boundary, version.getValidFrom(), at);
                boundary = futureBoundary(boundary, version.getValidTo(), at);
            }
        }
        return boundary;
    }

    private LocalDateTime policyBoundary(LocalDateTime at) {
        LocalDateTime boundary = null;
        for (var policy : policies.selectActiveByType(RoutingType.STANDARD_PRODUCT.code())) {
            for (var version : policyVersions.selectPublished(policy.getId())) {
                boundary = futureBoundary(boundary, version.getValidFrom(), at);
                boundary = futureBoundary(boundary, version.getValidTo(), at);
            }
        }
        return boundary;
    }

    private static LocalDateTime futureBoundary(LocalDateTime current, LocalDateTime value, LocalDateTime at) {
        return value != null && value.isAfter(at) ? earliest(current, value) : current;
    }

    private static LocalDateTime earliest(LocalDateTime first, LocalDateTime second) {
        return first == null ? second : second == null || first.isBefore(second) ? first : second;
    }

    private static CurrentSupplyEligibility withBoundary(CurrentSupplyEligibility result, LocalDateTime boundary) {
        return new CurrentSupplyEligibility(result.availability(), result.reason(), result.evaluatedAt(),
                result.routingSnapshot(), boundary);
    }

    private static CurrentSupplyEligibility unavailable(String reason, LocalDateTime at) {
        return new CurrentSupplyEligibility(ListingAvailability.UNAVAILABLE, reason, at, null);
    }
}
