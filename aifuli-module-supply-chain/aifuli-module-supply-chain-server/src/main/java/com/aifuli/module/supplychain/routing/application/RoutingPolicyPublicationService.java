package com.aifuli.module.supplychain.routing.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.routing.dal.dataobject.StandardProductRoutingCandidateDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.StandardProductRoutingRuleDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.aifuli.module.supplychain.routing.dal.mysql.StandardProductRoutingCandidateMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.StandardProductRoutingRuleMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper;
import com.aifuli.module.supplychain.routing.domain.RoutingType;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** 标准商品路由策略的草稿创建和不可变版本发布入口。 */
@Service
@RequiredArgsConstructor
public class RoutingPolicyPublicationService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String ROUTING_TYPE = RoutingType.STANDARD_PRODUCT.code();
    private static final short POLICY_ACTIVE = 10;
    private static final short POLICY_INACTIVE = 20;
    private static final short POLICY_ARCHIVED = 90;
    private static final short VERSION_DRAFT = 0;
    private static final short VERSION_PUBLISHED = 10;
    private static final Set<String> CHECK_MODES = Set.of("REQUIRED", "OPTIONAL", "UNSUPPORTED");

    private final SupplierRoutingPolicyMapper policyMapper;
    private final SupplierRoutingPolicyVersionMapper versionMapper;
    private final StandardProductRoutingRuleMapper ruleMapper;
    private final StandardProductRoutingCandidateMapper candidateMapper;
    private final BuyerMapper buyerMapper;
    private final PlatformSkuMapper skuMapper;
    private final SupplierMapper supplierMapper;
    private final OutboxEventRepository outboxRepository;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public DraftResult createDraft(CreateDraftCommand command) {
        validateDraftCommand(command);
        LocalDateTime now = now();
        SupplierRoutingPolicyDO policy = policyMapper.selectByCodeForUpdate(command.policyCode());
        if (policy == null) {
            policy = SupplierRoutingPolicyDO.builder()
                    .policyCode(command.policyCode()).policyName(command.policyName())
                    .routingType(ROUTING_TYPE).status(POLICY_INACTIVE).activeFlag(null).version(0L)
                    .createdBy(command.operatorId()).createdAt(now)
                    .updatedBy(command.operatorId()).updatedAt(now).build();
            policyMapper.insert(policy);
        } else {
            if (!ROUTING_TYPE.equals(policy.getRoutingType()) || policy.getStatus() == POLICY_ARCHIVED) {
                throw new IllegalStateException("Routing policy cannot accept a standard-product draft");
            }
            if (!policy.getPolicyName().equals(command.policyName())) {
                throw new IllegalArgumentException("Routing policy name cannot be changed through a version draft");
            }
        }

        SupplierRoutingPolicyVersionDO latest = versionMapper.selectLatest(policy.getId());
        int versionNo = latest == null ? 1 : latest.getVersionNo() + 1;
        List<ResolvedRule> resolvedRules = resolveAndValidateRules(command.rules());
        byte[] digest = digest(command, resolvedRules);
        SupplierRoutingPolicyVersionDO version = SupplierRoutingPolicyVersionDO.builder()
                .policyId(policy.getId()).versionNo(versionNo).versionName(command.versionName())
                .validFrom(command.validFrom()).validTo(command.validTo()).configDigest(digest)
                .status(VERSION_DRAFT).version(0L).createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        versionMapper.insert(version);

        for (ResolvedRule resolved : resolvedRules) {
            StandardProductRoutingRuleDO rule = StandardProductRoutingRuleDO.builder()
                    .policyVersionId(version.getId()).ruleCode(resolved.draft().ruleCode())
                    .ruleName(resolved.draft().ruleName()).buyerId(resolved.buyer().getId())
                    .platformSkuId(resolved.sku().getId())
                    .fulfillmentType(resolved.draft().fulfillmentType())
                    .rulePriority(resolved.draft().rulePriority()).version(0L)
                    .createdBy(command.operatorId()).createdAt(now)
                    .updatedBy(command.operatorId()).updatedAt(now).build();
            ruleMapper.insert(rule);
            for (ResolvedCandidate candidate : resolved.candidates()) {
                CandidateDraft draft = candidate.draft();
                candidateMapper.insert(StandardProductRoutingCandidateDO.builder()
                        .ruleId(rule.getId()).supplierId(candidate.supplier().getId())
                        .candidatePriority(draft.candidatePriority())
                        .inventoryCheckMode(draft.inventoryCheckMode())
                        .inventoryMaxAgeSeconds(draft.inventoryMaxAgeSeconds())
                        .balanceCheckMode(draft.balanceCheckMode())
                        .balanceMaxAgeSeconds(draft.balanceMaxAgeSeconds())
                        .healthCheckMode(draft.healthCheckMode())
                        .healthMaxAgeSeconds(draft.healthMaxAgeSeconds())
                        .version(0L).createdBy(command.operatorId()).createdAt(now)
                        .updatedBy(command.operatorId()).updatedAt(now).build());
            }
        }
        return new DraftResult(policy.getId(), version.getId(), versionNo,
                HexFormat.of().formatHex(digest));
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public PublishResult publish(PublishCommand command) {
        if (command.policyVersionId() == null || command.expectedVersion() < 0
                || command.operatorId() == null || isBlank(command.publishReason())
                || isBlank(command.traceId())) {
            throw new IllegalArgumentException("Routing policy publication command is incomplete");
        }
        SupplierRoutingPolicyVersionDO version = versionMapper.selectByIdForUpdate(command.policyVersionId());
        if (version == null || version.getStatus() != VERSION_DRAFT
                || version.getVersion() != command.expectedVersion()) {
            throw new IllegalStateException("Routing policy version is not an expected draft");
        }
        SupplierRoutingPolicyDO policy = policyMapper.selectByIdForUpdate(version.getPolicyId());
        if (policy == null || policy.getStatus() == POLICY_ARCHIVED
                || !ROUTING_TYPE.equals(policy.getRoutingType())) {
            throw new IllegalStateException("Routing policy is not publishable");
        }
        requireNoPublishedOverlap(version, versionMapper.selectPublished(policy.getId()));
        List<SupplierRoutingPolicyDO> active = policyMapper.selectActiveByType(ROUTING_TYPE);
        if (active.stream().anyMatch(item -> !item.getId().equals(policy.getId()))) {
            throw new IllegalStateException("Another standard-product routing policy is active");
        }
        requirePublishableReferences(version.getId());

        LocalDateTime now = now();
        version.setStatus(VERSION_PUBLISHED);
        version.setPublishReason(command.publishReason());
        version.setPublishedBy(command.operatorId());
        version.setPublishedAt(now);
        version.setUpdatedBy(command.operatorId());
        version.setUpdatedAt(now);
        if (versionMapper.updateById(version) != 1) {
            throw new IllegalStateException("Routing policy version publication lost optimistic lock");
        }

        policy.setStatus(POLICY_ACTIVE);
        policy.setActiveFlag((short) 1);
        policy.setUpdatedBy(command.operatorId());
        policy.setUpdatedAt(now);
        if (policyMapper.updateById(policy) != 1) {
            throw new IllegalStateException("Routing policy activation lost optimistic lock");
        }
        outboxRepository.appendLocal("ROUTING_POLICY", policy.getPolicyCode(), policy.getVersion(),
                "ROUTING_POLICY_PUBLISHED", "supply-availability-recalculation",
                new PolicyPublishedPayload(policy.getPolicyCode(), version.getId(), version.getVersionNo(),
                        HexFormat.of().formatHex(version.getConfigDigest())),
                command.traceId(), command.correlationId(), command.causationId(), now);
        return new PublishResult(policy.getPolicyCode(), version.getId(), version.getVersionNo(),
                policy.getVersion(), HexFormat.of().formatHex(version.getConfigDigest()));
    }

    private List<ResolvedRule> resolveAndValidateRules(List<RuleDraft> rules) {
        Set<String> matchKeys = new HashSet<>();
        Set<String> ruleCodes = new HashSet<>();
        List<ResolvedRule> result = new ArrayList<>();
        for (RuleDraft rule : rules) {
            if (isBlank(rule.ruleCode()) || isBlank(rule.ruleName()) || isBlank(rule.buyerCode())
                    || isBlank(rule.platformSkuCode()) || isBlank(rule.fulfillmentType())
                    || rule.rulePriority() < 0 || rule.candidates() == null || rule.candidates().isEmpty()
                    || !ruleCodes.add(rule.ruleCode())) {
                throw new IllegalArgumentException("Routing rule is incomplete, duplicated, or has no candidates");
            }
            BuyerDO buyer = buyerMapper.selectByBuyerCode(rule.buyerCode());
            PlatformSkuDO sku = skuMapper.selectByCode(rule.platformSkuCode());
            if (buyer == null || buyer.getStatus() != BuyerStatus.ACTIVE.getCode()
                    || sku == null || sku.getStatus() != CatalogEntityStatus.ACTIVE.getCode()
                    || !rule.fulfillmentType().equals(sku.getFulfillmentType())) {
                throw new IllegalArgumentException("Routing rule buyer or platform SKU is not active and exact");
            }
            String matchKey = buyer.getId() + ":" + sku.getId() + ":" + rule.fulfillmentType();
            if (!matchKeys.add(matchKey)) {
                throw new IllegalArgumentException("Overlapping exact routing rule: " + matchKey);
            }
            Set<String> supplierCodes = new HashSet<>();
            List<ResolvedCandidate> candidates = new ArrayList<>();
            for (CandidateDraft candidate : rule.candidates()) {
                validateCandidate(candidate);
                if (!supplierCodes.add(candidate.supplierCode())) {
                    throw new IllegalArgumentException("Duplicate supplier in routing rule: "
                            + candidate.supplierCode());
                }
                SupplierDO supplier = supplierMapper.selectByCode(candidate.supplierCode());
                if (supplier == null || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()
                        || !"STANDARD_SUPPLIER".equals(supplier.getSupplierType())) {
                    throw new IllegalArgumentException("Active standard supplier does not exist: "
                            + candidate.supplierCode());
                }
                candidates.add(new ResolvedCandidate(candidate, supplier));
            }
            result.add(new ResolvedRule(rule, buyer, sku, List.copyOf(candidates)));
        }
        return List.copyOf(result);
    }

    private void requirePublishableReferences(Long versionId) {
        List<StandardProductRoutingRuleDO> rules = ruleMapper.selectByPolicyVersionId(versionId);
        if (rules.isEmpty()) {
            throw new IllegalStateException("Published routing version must contain a rule");
        }
        Set<String> matchKeys = new HashSet<>();
        for (StandardProductRoutingRuleDO rule : rules) {
            BuyerDO buyer = buyerMapper.selectById(rule.getBuyerId());
            PlatformSkuDO sku = skuMapper.selectById(rule.getPlatformSkuId());
            if (buyer == null || buyer.getStatus() != BuyerStatus.ACTIVE.getCode()
                    || sku == null || sku.getStatus() != CatalogEntityStatus.ACTIVE.getCode()
                    || !rule.getFulfillmentType().equals(sku.getFulfillmentType())
                    || !matchKeys.add(rule.getBuyerId() + ":" + rule.getPlatformSkuId()
                    + ":" + rule.getFulfillmentType())) {
                throw new IllegalStateException("Routing draft contains inactive or overlapping exact rules");
            }
            List<StandardProductRoutingCandidateDO> candidates = candidateMapper.selectByRuleId(rule.getId());
            if (candidates.isEmpty()) {
                throw new IllegalStateException("Published routing rule must contain candidates");
            }
            for (StandardProductRoutingCandidateDO candidate : candidates) {
                SupplierDO supplier = supplierMapper.selectById(candidate.getSupplierId());
                if (supplier == null || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()
                        || !"STANDARD_SUPPLIER".equals(supplier.getSupplierType())) {
                    throw new IllegalStateException("Routing candidate supplier is not active");
                }
            }
        }
    }

    private static void requireNoPublishedOverlap(SupplierRoutingPolicyVersionDO draft,
                                                  List<SupplierRoutingPolicyVersionDO> published) {
        boolean overlap = published.stream().anyMatch(existing ->
                startsBeforeEnd(draft.getValidFrom(), existing.getValidTo())
                        && startsBeforeEnd(existing.getValidFrom(), draft.getValidTo()));
        if (overlap) {
            throw new IllegalStateException("Routing policy version effective interval overlaps a published version");
        }
    }

    private static boolean startsBeforeEnd(LocalDateTime start, LocalDateTime end) {
        return end == null || start.isBefore(end);
    }

    private static void validateDraftCommand(CreateDraftCommand command) {
        if (isBlank(command.policyCode()) || isBlank(command.policyName())
                || isBlank(command.versionName()) || command.validFrom() == null
                || (command.validTo() != null && !command.validTo().isAfter(command.validFrom()))
                || command.operatorId() == null || command.rules() == null || command.rules().isEmpty()) {
            throw new IllegalArgumentException("Routing policy draft command is incomplete");
        }
    }

    private static void validateCandidate(CandidateDraft candidate) {
        if (candidate == null || isBlank(candidate.supplierCode()) || candidate.candidatePriority() < 0) {
            throw new IllegalArgumentException("Routing candidate is incomplete");
        }
        requireCheckMode(candidate.inventoryCheckMode(), candidate.inventoryMaxAgeSeconds(), "inventory");
        requireCheckMode(candidate.balanceCheckMode(), candidate.balanceMaxAgeSeconds(), "balance");
        requireCheckMode(candidate.healthCheckMode(), candidate.healthMaxAgeSeconds(), "health");
    }

    private static void requireCheckMode(String mode, Integer maxAge, String factName) {
        if (!CHECK_MODES.contains(mode)
                || ("UNSUPPORTED".equals(mode) && maxAge != null)
                || (!"UNSUPPORTED".equals(mode) && (maxAge == null || maxAge <= 0))) {
            throw new IllegalArgumentException("Invalid " + factName + " check mode");
        }
    }

    private static byte[] digest(CreateDraftCommand command, List<ResolvedRule> resolvedRules) {
        StringBuilder canonical = new StringBuilder()
                .append(command.policyCode()).append('\u001f').append(command.versionName()).append('\u001f')
                .append(command.validFrom()).append('\u001f').append(command.validTo()).append('\u001e');
        resolvedRules.stream().sorted(Comparator.comparing(item -> item.draft().ruleCode()))
                .forEach(rule -> {
                    RuleDraft draft = rule.draft();
                    canonical.append(draft.ruleCode()).append('\u001f').append(draft.ruleName()).append('\u001f')
                            .append(draft.buyerCode()).append('\u001f').append(draft.platformSkuCode()).append('\u001f')
                            .append(draft.fulfillmentType()).append('\u001f').append(draft.rulePriority())
                            .append('\u001e');
                    rule.candidates().stream()
                            .sorted(Comparator.comparing(item -> item.supplier().getSupplierCode()))
                            .forEach(candidate -> appendCandidate(canonical, candidate));
                });
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void appendCandidate(StringBuilder canonical, ResolvedCandidate resolved) {
        CandidateDraft item = resolved.draft();
        canonical.append(item.supplierCode()).append('\u001f').append(item.candidatePriority())
                .append('\u001f').append(item.inventoryCheckMode()).append('\u001f')
                .append(item.inventoryMaxAgeSeconds()).append('\u001f').append(item.balanceCheckMode())
                .append('\u001f').append(item.balanceMaxAgeSeconds()).append('\u001f')
                .append(item.healthCheckMode()).append('\u001f').append(item.healthMaxAgeSeconds())
                .append('\u001d');
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    public record CreateDraftCommand(String policyCode, String policyName, String versionName,
                                     LocalDateTime validFrom, LocalDateTime validTo,
                                     List<RuleDraft> rules, Long operatorId) {
        public CreateDraftCommand {
            rules = rules == null ? null : List.copyOf(rules);
        }
    }

    public record RuleDraft(String ruleCode, String ruleName, String buyerCode,
                            String platformSkuCode, String fulfillmentType, int rulePriority,
                            List<CandidateDraft> candidates) {
        public RuleDraft {
            candidates = candidates == null ? null : List.copyOf(candidates);
        }
    }

    public record CandidateDraft(String supplierCode, int candidatePriority,
                                 String inventoryCheckMode, Integer inventoryMaxAgeSeconds,
                                 String balanceCheckMode, Integer balanceMaxAgeSeconds,
                                 String healthCheckMode, Integer healthMaxAgeSeconds) {
    }

    public record PublishCommand(Long policyVersionId, long expectedVersion, String publishReason,
                                 Long operatorId, String traceId,
                                 String correlationId, String causationId) {
    }

    public record DraftResult(Long policyId, Long policyVersionId,
                              int versionNo, String configDigestHex) {
    }

    public record PublishResult(String policyCode, Long policyVersionId, int versionNo,
                                long policyAggregateVersion, String configDigestHex) {
    }

    public record PolicyPublishedPayload(String policyCode, Long policyVersionId,
                                         int versionNo, String configDigestHex) {
    }

    private record ResolvedRule(RuleDraft draft, BuyerDO buyer, PlatformSkuDO sku,
                                List<ResolvedCandidate> candidates) {
    }

    private record ResolvedCandidate(CandidateDraft draft, SupplierDO supplier) {
    }
}
