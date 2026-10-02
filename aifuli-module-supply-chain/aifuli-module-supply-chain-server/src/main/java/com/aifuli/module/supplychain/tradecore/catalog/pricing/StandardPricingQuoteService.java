package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteReqDTO;
import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteRespDTO;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.STANDARD_PRICING_QUOTE_MISSING;

/**
 * 标准商品项目订单首次报价永久冻结与履约消费。
 *
 * <p>永久键为「项目 + 采购商订单号」：首次成功报价冻结价格、商品身份与文案，此后无论价格或
 * Listing 版本如何变化，同键重复询价一律重放首次冻结结果；不校验报价有效期，也不绑定用户、
 * 支付时间或调用方商品版本。HTTP 下单不提交报价 token，受理在供应链事务内按项目永久键查取
 * 唯一报价并一次性消费；缺报价抛稳定业务错误 {@code STANDARD_PRICING_QUOTE_MISSING}，
 * 供共享受理保存为可条件重试的拒绝回执。</p>
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md}；
 * 该实现取代 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md} §7 的
 * 15 分钟重签语义，不改变其通用定价计算、费用/税务快照与资金职责。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandardPricingQuoteService {
    private static final String TX = "supplyChainTransactionManager";
    /** 报价商品数量当前仅支持一件，与受理层"一期履约数量必须为一"的约束一致。 */
    private static final long SUPPORTED_QUANTITY = 1L;
    private final BuyerResolver buyerResolver;
    private final BuyerProjectMapper projectMapper;
    private final GenericBatchPricingService pricingService;
    private final StandardPricingQuoteMapper quoteMapper;
    private final SupplyChainDatabaseClock clock;
    private final ObjectMapper objectMapper;
    private final GenericPricingCalculator calculator = new GenericPricingCalculator();

    /**
     * 签发或重放项目订单首次永久冻结报价。
     *
     * <p>先经真实 {@link BuyerResolver} 解析来源分销商，再按 projectCode 行锁项目并校验归属与
     * ACTIVE 状态，保证同项目首次签发串行；SOURCE/TENANT 只记录调用来源，永久键不含它们。
     * 已有同键报价（无论 ISSUED 或 CONSUMED、无论历史 expiresAt 是否过期）在不变量一致时
     * 直接重放首次冻结结果，不重新定价、不更换 token。</p>
     */
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
        if (request.getQuantity() == null || request.getQuantity() != SUPPORTED_QUANTITY) {
            throw new IllegalArgumentException("quote quantity must be 1 in the current phase");
        }
        StandardPricingQuoteDO existing = quoteMapper.selectByProjectOrder(project.getId(), request.getBuyerOrderNo());
        if (existing != null) {
            requireSameBinding(existing, request, project.getId(), buyer.id());
            log.info("重放项目订单首次永久冻结报价 projectCode={} buyerOrderNo={} skuCode={} quantity={} quoteId={} consumed={}",
                    request.getProjectCode(), request.getBuyerOrderNo(), existing.getPlatformSkuCode(),
                    existing.getQuantity(), existing.getId(),
                    PricingQuoteStatus.CONSUMED.name().equals(existing.getStatus()));
            return response(existing);
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
                .projectPricingVersionId(version.getId())
                .buyerOrderNo(request.getBuyerOrderNo()).quantity(request.getQuantity())
                .distributionUnitPriceCents(version.getDistributionPriceCents())
                .salesUnitPriceCents(version.getSalesPriceCents()).productSubtotalCents(subtotal)
                .serviceFeeCents(fee).userPayableAmountCents(payable)
                .taxMetadataJson(json(config.taxMetadata()))
                .serviceFeeCopy(config.serviceFeeRule().hideCopy() ? null : config.serviceFeeRule().copy())
                .status(PricingQuoteStatus.ISSUED.name()).expiresAt(null).createdAt(now).build();
        quoteMapper.insert(quote);
        log.info("签发项目订单首次永久冻结报价 sourceSystem={} externalTenantId={} projectCode={} buyerOrderNo={} skuCode={} quantity={} pricingVersionId={} quoteId={}",
                request.getSourceSystem(), request.getExternalTenantId(), request.getProjectCode(),
                request.getBuyerOrderNo(), request.getPlatformSkuCode(), request.getQuantity(),
                version.getId(), quote.getId());
        return response(quote);
    }

    /**
     * 旧内部受理的过渡消费入口：在供应链业务事务内按项目永久键行锁唯一报价，
     * 并额外核对过渡 token 指向该报价；不再校验用户、支付时间或历史失效时间。
     * 调用方落订单后必须调用 {@link #markConsumed}，两者必须处于同一事务。
     */
    @Transactional(transactionManager = TX, rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public FrozenQuote requireForFulfillment(QuoteConsumptionContext context) {
        Objects.requireNonNull(context, "quote consumption context is required");
        StandardPricingQuoteDO quote = requireLockedQuote(context.buyerId(), context.projectId(),
                context.platformSkuId(), context.buyerOrderNo(), context.quantity());
        if (!Objects.equals(context.token(), quote.getQuoteToken())) {
            throw new IllegalStateException("pricing quote token does not match the project permanently keyed quote");
        }
        return frozen(quote);
    }

    /**
     * 共享受理的最终消费入口：仅凭可信项目键（采购商+项目+平台SKU+项目订单号+数量）行锁首次冻结报价。
     * 缺报价抛稳定业务错误 {@code STANDARD_PRICING_QUOTE_MISSING}（可条件重试），
     * 不重新定价、不任取最新历史报价；返回不可变 {@link FrozenQuote} 及商品身份快照供受理校验。
     */
    @Transactional(transactionManager = TX, rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public FrozenQuote requireForProject(ProjectQuoteKeyContext context) {
        Objects.requireNonNull(context, "project quote key context is required");
        StandardPricingQuoteDO quote = requireLockedQuote(context.buyerId(), context.projectId(),
                context.platformSkuId(), context.buyerOrderNo(), context.quantity());
        return frozen(quote);
    }

    /**
     * 在供应链业务事务内把冻结报价一次性消费给真实中台订单；消费必须与订单写入同一事务提交。
     * 行锁重新校验状态与消费引用：同订单幂等返回且不覆盖首次消费时间，异订单或矛盾引用拒绝，
     * 不允许把同一报价转移消费给另一订单。
     */
    @Transactional(transactionManager = TX, rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public void markConsumed(Long quoteId, Long orderId, LocalDateTime now) {
        StandardPricingQuoteDO quote = quoteMapper.selectByIdForUpdate(quoteId);
        if (quote == null) {
            throw new IllegalStateException("pricing quote disappeared during consumption");
        }
        if (PricingQuoteStatus.CONSUMED.name().equals(quote.getStatus())) {
            if (!Objects.equals(orderId, quote.getConsumedOrderId())) {
                throw new IllegalStateException("pricing quote was consumed by another order");
            }
            return;
        }
        if (quote.getConsumedOrderId() != null) {
            throw new IllegalStateException("pricing quote consumption state is inconsistent");
        }
        quote.setStatus(PricingQuoteStatus.CONSUMED.name());
        quote.setConsumedOrderId(orderId);
        quote.setConsumedAt(now);
        if (quoteMapper.updateById(quote) != 1) {
            throw new IllegalStateException("pricing quote consumption lost update");
        }
    }

    /** 行锁读取并校验项目永久键唯一报价的绑定一致性与状态；缺报价按稳定业务错误拒绝。 */
    private StandardPricingQuoteDO requireLockedQuote(Long buyerId, Long projectId, Long platformSkuId,
                                                      String buyerOrderNo, long quantity) {
        StandardPricingQuoteDO quote = quoteMapper.selectByProjectOrderForUpdate(projectId, buyerOrderNo);
        if (quote == null) {
            throw exception(STANDARD_PRICING_QUOTE_MISSING);
        }
        if (!buyerId.equals(quote.getBuyerId()) || !platformSkuId.equals(quote.getPlatformSkuId())
                || !buyerOrderNo.equals(quote.getBuyerOrderNo()) || quantity != quote.getQuantity()) {
            throw new IllegalStateException("pricing quote is bound to another project, sku, or order");
        }
        if (!PricingQuoteStatus.ISSUED.name().equals(quote.getStatus())
                && !PricingQuoteStatus.CONSUMED.name().equals(quote.getStatus())) {
            throw new IllegalStateException("pricing quote status is invalid");
        }
        return quote;
    }

    /** 重放幂等要求同键请求与首次冻结报价的商品与数量不变量完全一致，不能覆盖旧快照。 */
    private static void requireSameBinding(StandardPricingQuoteDO quote, StandardPricingQuoteReqDTO request,
                                           Long projectId, Long buyerId) {
        if (!projectId.equals(quote.getProjectId()) || !buyerId.equals(quote.getBuyerId())
                || !request.getQuantity().equals(quote.getQuantity())
                || !request.getPlatformSkuCode().equals(quote.getPlatformSkuCode())) {
            throw new IllegalStateException("buyer order number already has a different pricing quote");
        }
    }

    private FrozenQuote frozen(StandardPricingQuoteDO quote) {
        return new FrozenQuote(quote.getId(), quote.getProjectPricingVersionId(), quote.getListingId(),
                quote.getDistributionUnitPriceCents(), quote.getSalesUnitPriceCents(),
                quote.getProductSubtotalCents(), quote.getServiceFeeCents(),
                quote.getUserPayableAmountCents(), quote.getTaxMetadataJson(),
                PricingQuoteStatus.CONSUMED.name().equals(quote.getStatus()), quote.getConsumedOrderId());
    }

    private StandardPricingQuoteRespDTO response(StandardPricingQuoteDO quote) {
        TaxMetadata tax = fromJson(quote.getTaxMetadataJson(), TaxMetadata.class);
        StandardPricingQuoteRespDTO response = new StandardPricingQuoteRespDTO();
        response.setQuoteToken(quote.getQuoteToken());
        response.setPricingVersionId(quote.getProjectPricingVersionId());
        response.setPlatformSkuCode(quote.getPlatformSkuCode());
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
        // 历史报价的失效时间仅原样返回；永久冻结报价为 null，且不参与下单或重签判断。
        response.setExpiresAt(quote.getExpiresAt() == null ? null
                : clock.toInstant(quote.getExpiresAt()).atOffset(ZoneOffset.UTC));
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

    /**
     * 不可变冻结报价快照，含 listingId 等商品身份，供共享受理核对商品归属与首次冻结绑定。
     * consumed/consumedOrderId 表达既有同订单幂等重放语义，不代表可再次消费给另一订单。
     */
    public record FrozenQuote(Long quoteId, Long pricingVersionId, Long listingId,
                              long distributionUnitPriceCents, long salesUnitPriceCents,
                              long productSubtotalCents, long serviceFeeCents,
                              long userPayableAmountCents, String taxMetadataJson,
                              boolean consumed, Long consumedOrderId) { }
}
