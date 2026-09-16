package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteReqDTO;
import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteRespDTO;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.StandardPricingQuoteDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.StandardPricingQuoteMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingConfig;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.ServiceFeeRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.CurrentProjectPricing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * 标准商品项目报价签发与履约消费。凭证绑定来源租户、项目、SKU、数量、用户和采购商订单号，
 * 有效期 15 分钟；已消费订单号无论是否过期都禁止重新签发。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#7-项目报价凭证与标准履约}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandardPricingQuoteService {
    private static final String TX = "supplyChainTransactionManager";
    private static final int VALID_MINUTES = 15;
    private final BuyerResolver buyerResolver;
    private final BuyerProjectMapper projectMapper;
    private final GenericBatchPricingService pricingService;
    private final StandardPricingQuoteMapper quoteMapper;
    private final SupplyChainDatabaseClock clock;
    private final ObjectMapper objectMapper;
    private final GenericPricingCalculator calculator = new GenericPricingCalculator();

    @Transactional(transactionManager = TX, rollbackFor = Exception.class)
    public StandardPricingQuoteRespDTO quote(StandardPricingQuoteReqDTO request) {
        LocalDateTime now = clock.now();
        BuyerResolver.BuyerSnapshot buyer = buyerResolver.resolve(request.getSourceSystem(),
                request.getExternalTenantId(), now);
        BuyerProjectDO project = projectMapper.selectByProjectCodeForUpdate(request.getProjectCode());
        if (project == null || !buyer.id().equals(project.getBuyerId())
                || project.getStatus() != BuyerProjectStatus.ACTIVE.getCode()) {
            throw new IllegalArgumentException("active project does not belong to resolved buyer");
        }
        String userId = FulfillmentTargetFields.normalizeMobile(request.getBuyerUserId());
        if (userId == null) {
            throw new IllegalArgumentException("buyer user id must be a valid mobile number");
        }
        StandardPricingQuoteDO existing = quoteMapper.selectByOrder(request.getSourceSystem(),
                request.getExternalTenantId(), request.getBuyerOrderNo());
        if (existing != null && (PricingQuoteStatus.CONSUMED.name().equals(existing.getStatus())
                || existing.getConsumedOrderId() != null)) {
            throw new IllegalStateException("buyer order number already consumed a pricing quote");
        }
        if (existing != null && !existing.getExpiresAt().isBefore(now)) {
            requireSameRequest(existing, request, project.getId(), userId);
            return response(existing, request.getPlatformSkuCode());
        }
        CurrentProjectPricing current = pricingService.ensureCurrentProjectVersion(
                request.getProjectCode(), request.getPlatformSkuCode());
        ProjectSkuPricingVersionDO version = current.version();
        if (version == null || !PricingStatus.READY.name().equals(version.getPricingStatus())) {
            throw new IllegalStateException("project SKU is not currently saleable");
        }
        PricingConfig config = fromJson(version.getEffectiveConfigJson(), PricingConfig.class);
        long subtotal = Math.multiplyExact(version.getSalesPriceCents(), request.getQuantity());
        long fee = calculator.calculateServiceFee(config.serviceFeeRule(), subtotal);
        long payable = Math.addExact(subtotal, fee);
        StandardPricingQuoteDO quote = StandardPricingQuoteDO.builder()
                .quoteToken(UUID.randomUUID().toString().replace("-", ""))
                .sourceSystem(request.getSourceSystem()).externalTenantId(request.getExternalTenantId())
                .buyerId(buyer.id()).projectId(project.getId()).listingId(current.listingId())
                .platformSkuId(current.platformSkuId()).platformSkuCode(current.platformSkuCode())
                .projectPricingVersionId(version.getId()).buyerUserId(userId)
                .buyerOrderNo(request.getBuyerOrderNo()).quantity(request.getQuantity())
                .distributionUnitPriceCents(version.getDistributionPriceCents())
                .salesUnitPriceCents(version.getSalesPriceCents()).productSubtotalCents(subtotal)
                .serviceFeeCents(fee).userPayableAmountCents(payable)
                .taxMetadataJson(json(config.taxMetadata()))
                .serviceFeeCopy(config.serviceFeeRule().hideCopy() ? null : config.serviceFeeRule().copy())
                .status(PricingQuoteStatus.ISSUED.name()).expiresAt(now.plusMinutes(VALID_MINUTES)).createdAt(now).build();
        quoteMapper.insert(quote);
        log.info("签发标准商品报价 sourceSystem={} externalTenantId={} projectCode={} buyerOrderNo={} skuCode={} quantity={} pricingVersionId={} expiresAt={}",
                request.getSourceSystem(), request.getExternalTenantId(), request.getProjectCode(),
                request.getBuyerOrderNo(), request.getPlatformSkuCode(), request.getQuantity(), version.getId(), quote.getExpiresAt());
        return response(quote, request.getPlatformSkuCode());
    }

    /**
     * 在标准履约受理事务中锁定并校验报价；调用方落订单后必须调用 {@link #markConsumed}。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#7-项目报价凭证与标准履约}。
     */
    public FrozenQuote requireForFulfillment(QuoteConsumptionContext context) {
        Objects.requireNonNull(context, "quote consumption context is required");
        StandardPricingQuoteDO quote = quoteMapper.selectByTokenForUpdate(context.token());
        if (quote == null || !context.buyerId().equals(quote.getBuyerId()) || !context.projectId().equals(quote.getProjectId())
                || !context.platformSkuId().equals(quote.getPlatformSkuId()) || !context.buyerOrderNo().equals(quote.getBuyerOrderNo())
                || !context.buyerUserId().equals(quote.getBuyerUserId()) || context.quantity() != quote.getQuantity()
                || context.paidAt().isBefore(quote.getCreatedAt()) || context.paidAt().isAfter(quote.getExpiresAt())) {
            throw new IllegalStateException("pricing quote is invalid, expired, or bound to another order");
        }
        if (!PricingQuoteStatus.ISSUED.name().equals(quote.getStatus())
                && !PricingQuoteStatus.CONSUMED.name().equals(quote.getStatus())) {
            throw new IllegalStateException("pricing quote status is invalid");
        }
        return new FrozenQuote(quote.getId(), quote.getProjectPricingVersionId(),
                quote.getDistributionUnitPriceCents(), quote.getSalesUnitPriceCents(),
                quote.getProductSubtotalCents(), quote.getServiceFeeCents(),
                quote.getUserPayableAmountCents(), quote.getTaxMetadataJson(),
                PricingQuoteStatus.CONSUMED.name().equals(quote.getStatus()), quote.getConsumedOrderId());
    }

    public void markConsumed(Long quoteId, Long orderId, LocalDateTime now) {
        StandardPricingQuoteDO quote = quoteMapper.selectById(quoteId);
        if (quote == null) {
            throw new IllegalStateException("pricing quote disappeared during consumption");
        }
        if (PricingQuoteStatus.CONSUMED.name().equals(quote.getStatus())) {
            if (!Objects.equals(orderId, quote.getConsumedOrderId())) {
                throw new IllegalStateException("pricing quote was consumed by another order");
            }
            return;
        }
        quote.setStatus(PricingQuoteStatus.CONSUMED.name());
        quote.setConsumedOrderId(orderId);
        quote.setConsumedAt(now);
        if (quoteMapper.updateById(quote) != 1) {
            throw new IllegalStateException("pricing quote consumption lost update");
        }
    }

    private static void requireSameRequest(StandardPricingQuoteDO quote, StandardPricingQuoteReqDTO request,
                                           Long projectId, String userId) {
        if (!projectId.equals(quote.getProjectId()) || !userId.equals(quote.getBuyerUserId())
                || !request.getQuantity().equals(quote.getQuantity())
                || !request.getPlatformSkuCode().equals(quote.getPlatformSkuCode())) {
            throw new IllegalStateException("buyer order number already has a different pricing quote");
        }
    }

    private StandardPricingQuoteRespDTO response(StandardPricingQuoteDO quote, String skuCode) {
        TaxMetadata tax = fromJson(quote.getTaxMetadataJson(), TaxMetadata.class);
        StandardPricingQuoteRespDTO response = new StandardPricingQuoteRespDTO();
        response.setQuoteToken(quote.getQuoteToken());
        response.setPricingVersionId(quote.getProjectPricingVersionId());
        response.setPlatformSkuCode(skuCode);
        response.setQuantity(quote.getQuantity());
        response.setDistributionUnitPriceCents(quote.getDistributionUnitPriceCents());
        response.setSalesUnitPriceCents(quote.getSalesUnitPriceCents());
        response.setProductSubtotalCents(quote.getProductSubtotalCents());
        response.setServiceFeeCents(quote.getServiceFeeCents());
        response.setUserPayableAmountCents(quote.getUserPayableAmountCents());
        response.setTaxIncluded(tax.taxIncluded());
        response.setTaxRateCode(tax.taxRateCode());
        response.setTaxRateLabel(tax.taxRateLabel());
        response.setServiceFeeCopy(quote.getServiceFeeCopy());
        response.setCurrency(Money.CURRENCY_CNY);
        response.setExpiresAt(clock.toInstant(quote.getExpiresAt()).atOffset(ZoneOffset.UTC));
        return response;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("quote JSON serialization failed", ex); }
    }
    private <T> T fromJson(String value, Class<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("quote JSON deserialization failed", ex); }
    }

    public record FrozenQuote(Long quoteId, Long pricingVersionId,
                              long distributionUnitPriceCents, long salesUnitPriceCents,
                              long productSubtotalCents, long serviceFeeCents,
                              long userPayableAmountCents, String taxMetadataJson,
                              boolean consumed, Long consumedOrderId) { }
}
