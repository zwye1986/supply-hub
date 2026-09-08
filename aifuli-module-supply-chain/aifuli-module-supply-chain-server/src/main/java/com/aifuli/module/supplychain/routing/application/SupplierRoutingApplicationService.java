package com.aifuli.module.supplychain.routing.application;

import com.aifuli.module.supplychain.routing.dal.dataobject.StandardProductRoutingCandidateDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.StandardProductRoutingRuleDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.aifuli.module.supplychain.routing.dal.mysql.StandardProductRoutingCandidateMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.StandardProductRoutingRuleMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper;
import com.aifuli.module.supplychain.routing.domain.RoutingCandidate;
import com.aifuli.module.supplychain.routing.domain.RoutingDecision;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.routing.domain.RoutingType;
import com.aifuli.module.supplychain.routing.domain.SupplierRoutingEngine;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.InventorySourceDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.InventorySourceMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.InventorySourceStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** 读取已发布配置并构造不可变候选快照，最终选择只委托纯路由内核。 */
@Service
@RequiredArgsConstructor
public class SupplierRoutingApplicationService {

    private static final String ROUTING_TYPE = RoutingType.STANDARD_PRODUCT.code();
    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final SupplierRoutingPolicyMapper policyMapper;
    private final SupplierRoutingPolicyVersionMapper versionMapper;
    private final StandardProductRoutingRuleMapper ruleMapper;
    private final StandardProductRoutingCandidateMapper candidateMapper;
    private final BuyerMapper buyerMapper;
    private final PlatformSkuMapper skuMapper;
    private final SupplierMapper supplierMapper;
    private final SupplierOfferMapper offerMapper;
    private final SupplierOfferVersionMapper offerVersionMapper;
    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorVersionMapper connectorVersionMapper;
    private final InventorySourceMapper inventorySourceMapper;

    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public RoutingSnapshot route(RoutingInput input) {
        if (input.quantity() != 1) {
            throw new IllegalArgumentException("M1 standard-product routing only accepts quantity one");
        }
        requireActiveSubject(input);
        SupplierRoutingPolicyDO policy = requireOnly(
                policyMapper.selectActiveByType(ROUTING_TYPE), "active standard-product routing policy");
        SupplierRoutingPolicyVersionDO version = requireOnly(
                versionMapper.selectEffective(policy.getId(), input.businessTime()),
                "effective published routing policy version");
        StandardProductRoutingRuleDO rule = requireOnly(ruleMapper.selectExactMatches(
                version.getId(), input.buyerId(), input.platformSkuId(), input.fulfillmentType()),
                "exact Buyer + PlatformSKU + FulfillmentType routing rule");
        List<StandardProductRoutingCandidateDO> configured = candidateMapper.selectByRuleId(rule.getId());
        if (configured.isEmpty()) {
            throw new IllegalStateException("Published routing rule has no candidates");
        }
        List<RoutingCandidate> candidates = configured.stream()
                .map(item -> resolveCandidate(item, input))
                .toList();
        RoutingDecision decision = new SupplierRoutingEngine().decide(input, candidates);
        return new RoutingSnapshot(policy.getId(), version.getId(), version.getVersionNo(),
                version.getConfigDigest(), rule.getId(), rule.getRuleCode(), decision);
    }

    private RoutingCandidate resolveCandidate(StandardProductRoutingCandidateDO configured,
                                              RoutingInput input) {
        SupplierDO supplier = supplierMapper.selectById(configured.getSupplierId());
        if (supplier == null) {
            throw new IllegalStateException("Published routing candidate references a missing supplier");
        }
        List<String> structuralReasons = new ArrayList<>();
        if (supplier.getStatus() != SupplierStatus.ACTIVE.getCode()) {
            structuralReasons.add("SUPPLIER_NOT_ACTIVE");
        }
        List<ResolvedOffer> effectiveOffers = offerMapper
                .selectPublishedBySupplierAndSku(supplier.getId(), input.platformSkuId()).stream()
                .map(offer -> resolveOffer(offer, supplier, input))
                .filter(item -> item != null)
                .toList();
        if (effectiveOffers.isEmpty()) {
            structuralReasons.add("NO_EFFECTIVE_OFFER");
        } else if (effectiveOffers.size() > 1) {
            structuralReasons.add("AMBIGUOUS_EFFECTIVE_OFFER");
        }
        ResolvedOffer resolved = effectiveOffers.size() == 1 ? effectiveOffers.getFirst() : null;
        Eligibility eligibility = evaluateEligibility(configured, input, supplier.getSupplierCode(),
                resolved == null ? null : resolved.version().getSupplierSettlementUnitPrice(),
                structuralReasons);
        return new RoutingCandidate(configured.getCandidatePriority(), supplier.getId(),
                supplier.getSupplierCode(), supplier.getSupplierName(),
                resolved == null ? null : resolved.offer().getId(),
                resolved == null ? null : resolved.version().getId(),
                resolved == null ? null : resolved.version().getConnectorVersionId(),
                resolved == null ? null : resolved.version().getInventorySourceId(),
                resolved == null ? null : resolved.version().getSupplierSettlementUnitPrice(),
                resolved == null ? null : resolved.version().getCurrency(),
                eligibility.reasons().isEmpty(), eligibility.availabilityClass(), eligibility.reasons(),
                eligibility.inventoryStatus(), eligibility.balanceStatus(), eligibility.healthStatus(),
                eligibility.latestFactAt());
    }

    private ResolvedOffer resolveOffer(SupplierOfferDO offer, SupplierDO supplier, RoutingInput input) {
        if (offer.getStatus() != OfferStatus.PUBLISHED.getCode() || offer.getCurrentVersionId() == null) {
            return null;
        }
        SupplierOfferVersionDO version = offerVersionMapper.selectById(offer.getCurrentVersionId());
        if (version == null || !offer.getId().equals(version.getOfferId())
                || !effectiveAt(version.getValidFrom(), version.getValidTo(), input.businessTime())
                || input.quantity() < version.getMinQuantity()
                || input.quantity() > version.getMaxQuantity()
                || (input.quantity() - version.getMinQuantity()) % version.getQuantityStep() != 0
                || !"CNY".equals(version.getCurrency())) {
            return null;
        }
        SupplierConnectorVersionDO connectorVersion =
                connectorVersionMapper.selectById(version.getConnectorVersionId());
        SupplierConnectorDO connector = connectorVersion == null
                ? null : connectorMapper.selectById(connectorVersion.getConnectorId());
        InventorySourceDO inventory = inventorySourceMapper.selectById(version.getInventorySourceId());
        if (connectorVersion == null || connector == null || inventory == null
                || connectorVersion.getStatus() != ConnectorVersionStatus.PUBLISHED.getCode()
                || connector.getStatus() != 10
                || !connectorVersion.getId().equals(connector.getCurrentVersionId())
                || !connector.getSupplierId().equals(supplier.getId())
                || inventory.getStatus() != InventorySourceStatus.ACTIVE.getCode()
                || !inventory.getSupplierId().equals(supplier.getId())
                || !inventory.getPlatformSkuId().equals(input.platformSkuId())
                || !"REMOTE_ON_DEMAND".equals(inventory.getSourceType())) {
            return null;
        }
        return new ResolvedOffer(offer, version);
    }

    private static Eligibility evaluateEligibility(StandardProductRoutingCandidateDO configured,
                                                   RoutingInput input, String supplierCode,
                                                   Long unitPrice, List<String> structuralReasons) {
        List<String> reasons = new ArrayList<>(structuralReasons);
        RoutingInput.CandidateFacts facts = input.factsFor(supplierCode);
        CheckResult inventory = evaluateFact("INVENTORY", configured.getInventoryCheckMode(),
                configured.getInventoryMaxAgeSeconds(), facts.inventory(), input.businessTime(), null);
        Long requiredBalance = unitPrice == null ? null : Math.multiplyExact(unitPrice, input.quantity());
        CheckResult balance = evaluateFact("BALANCE", configured.getBalanceCheckMode(),
                configured.getBalanceMaxAgeSeconds(), facts.balance(), input.businessTime(), requiredBalance);
        CheckResult health = evaluateFact("HEALTH", configured.getHealthCheckMode(),
                configured.getHealthMaxAgeSeconds(), facts.health(), input.businessTime(), null);
        Stream.of(inventory, balance, health)
                .map(CheckResult::rejectionReason).filter(Objects::nonNull)
                .forEach(reasons::add);
        boolean unknown = Stream.of(inventory, balance, health)
                .anyMatch(CheckResult::unknown);
        String availability = reasons.isEmpty() ? (unknown ? "UNKNOWN" : "CONFIRMED") : "UNAVAILABLE";
        return new Eligibility(List.copyOf(reasons), availability,
                inventory.snapshotStatus(), balance.snapshotStatus(), health.snapshotStatus(),
                latestFactAt(facts));
    }

    private static CheckResult evaluateFact(String factName, String mode, Integer maxAgeSeconds,
                                            RoutingInput.Fact fact, LocalDateTime businessTime,
                                            Long requiredAmount) {
        if ("UNSUPPORTED".equals(mode)) {
            return new CheckResult("NOT_CHECKED", null, false);
        }
        boolean required = "REQUIRED".equals(mode);
        if (fact == null) {
            return new CheckResult("NOT_CAPTURED",
                    required ? factName + "_FACT_REQUIRED" : null, !required);
        }
        boolean fresh = !fact.observedAt().isAfter(businessTime)
                && !fact.observedAt().isBefore(businessTime.minusSeconds(maxAgeSeconds))
                && fact.validUntil().isAfter(businessTime);
        if (!fresh) {
            return new CheckResult("STALE", required ? factName + "_FACT_STALE" : null, !required);
        }
        if (fact.status() == RoutingInput.FactStatus.AVAILABLE) {
            if (requiredAmount != null && (fact.amount() == null || fact.amount() < requiredAmount)) {
                return new CheckResult("INSUFFICIENT", factName + "_INSUFFICIENT", false);
            }
            return new CheckResult("AVAILABLE", null, false);
        }
        if (fact.status() == RoutingInput.FactStatus.UNKNOWN) {
            return new CheckResult("UNKNOWN", required ? factName + "_FACT_UNKNOWN" : null, !required);
        }
        return new CheckResult(fact.status().name(), factName + "_" + fact.status().name(), false);
    }

    private void requireActiveSubject(RoutingInput input) {
        BuyerDO buyer = buyerMapper.selectById(input.buyerId());
        PlatformSkuDO sku = skuMapper.selectById(input.platformSkuId());
        if (buyer == null || buyer.getStatus() != BuyerStatus.ACTIVE.getCode()
                || sku == null || sku.getStatus() != CatalogEntityStatus.ACTIVE.getCode()
                || !input.fulfillmentType().equals(sku.getFulfillmentType())) {
            throw new IllegalArgumentException("Routing buyer or platform SKU is not active and exact");
        }
    }

    private static boolean effectiveAt(LocalDateTime from, LocalDateTime to, LocalDateTime at) {
        return !from.isAfter(at) && (to == null || to.isAfter(at));
    }

    private static <T> T requireOnly(List<T> values, String description) {
        if (values.size() != 1) {
            throw new IllegalStateException("Routing requires exactly one " + description
                    + ", but found " + values.size());
        }
        return values.getFirst();
    }

    private static LocalDateTime latestFactAt(RoutingInput.CandidateFacts facts) {
        return Stream.of(facts.inventory(), facts.balance(), facts.health())
                .filter(Objects::nonNull)
                .map(RoutingInput.Fact::observedAt)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    public record RoutingSnapshot(Long policyId, Long policyVersionId, int policyVersionNo,
                                  byte[] configDigest, Long ruleId, String ruleCode,
                                  RoutingDecision decision) {
        public RoutingSnapshot {
            configDigest = Arrays.copyOf(configDigest, configDigest.length);
        }

        @Override
        public byte[] configDigest() {
            return Arrays.copyOf(configDigest, configDigest.length);
        }
    }

    private record ResolvedOffer(SupplierOfferDO offer, SupplierOfferVersionDO version) {
    }

    private record Eligibility(List<String> reasons, String availabilityClass,
                               String inventoryStatus, String balanceStatus, String healthStatus,
                               LocalDateTime latestFactAt) {
    }

    private record CheckResult(String snapshotStatus, String rejectionReason, boolean unknown) {
    }
}
