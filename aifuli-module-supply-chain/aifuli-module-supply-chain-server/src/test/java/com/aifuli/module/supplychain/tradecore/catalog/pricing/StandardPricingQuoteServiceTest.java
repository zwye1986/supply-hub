package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteReqDTO;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.StandardPricingQuoteDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.StandardPricingQuoteMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.CurrentProjectPricing;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService.FrozenQuote;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FixedOperator;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FormulaRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.HideRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PriceMode;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingConfig;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.ServiceFeeRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 15 分钟边界、上下文绑定、已消费订单号禁止重签。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#7-项目报价凭证与标准履约}。
 */
class StandardPricingQuoteServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 12, 0);
    private static final String MOBILE = "13800138000";

    private final BuyerResolver buyerResolver = mock(BuyerResolver.class);
    private final BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
    private final GenericBatchPricingService pricingService = mock(GenericBatchPricingService.class);
    private final StandardPricingQuoteMapper quoteMapper = mock(StandardPricingQuoteMapper.class);
    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private StandardPricingQuoteService service;

    @BeforeEach
    void setUp() throws Exception {
        when(clock.now()).thenReturn(NOW);
        when(clock.toInstant(any())).thenAnswer(invocation ->
                ((LocalDateTime) invocation.getArgument(0)).atZone(SupplyChainDatabaseClock.DATABASE_ZONE).toInstant());
        when(buyerResolver.resolve("AIFULI_MALL", "tenant-a", NOW))
                .thenReturn(new BuyerResolver.BuyerSnapshot(1L, "BYR_A", "分销商A", "A", 1));
        when(projectMapper.selectByProjectCodeForUpdate("PRJ_A")).thenReturn(BuyerProjectDO.builder()
                .id(2L).projectCode("PRJ_A").buyerId(1L).status(BuyerProjectStatus.ACTIVE.getCode()).build());
        service = new StandardPricingQuoteService(buyerResolver, projectMapper, pricingService, quoteMapper,
                clock, objectMapper);
        stubReadyVersion();
    }

    @Test
    void reusesUnexpiredIssuedQuoteForSameBinding() {
        StandardPricingQuoteDO existing = issuedQuote(NOW.plusMinutes(10));
        when(quoteMapper.selectByOrder("AIFULI_MALL", "tenant-a", "ORD-1")).thenReturn(existing);

        var response = service.quote(request());

        assertThat(response.getQuoteToken()).isEqualTo("token-1");
        verify(quoteMapper, never()).insert(any(StandardPricingQuoteDO.class));
    }

    @Test
    void reissuesWhenUnconsumedQuoteExpired() {
        when(quoteMapper.selectByOrder("AIFULI_MALL", "tenant-a", "ORD-1"))
                .thenReturn(issuedQuote(NOW.minusSeconds(1)));
        when(quoteMapper.insert(any(StandardPricingQuoteDO.class))).thenReturn(1);

        service.quote(request());

        ArgumentCaptor<StandardPricingQuoteDO> inserted = ArgumentCaptor.forClass(StandardPricingQuoteDO.class);
        verify(quoteMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo(PricingQuoteStatus.ISSUED.name());
        assertThat(inserted.getValue().getExpiresAt()).isEqualTo(NOW.plusMinutes(15));
    }

    @Test
    void rejectsExpiredConsumedOrderNumber() {
        StandardPricingQuoteDO consumed = issuedQuote(NOW.minusMinutes(1));
        consumed.setStatus(PricingQuoteStatus.CONSUMED.name());
        consumed.setConsumedOrderId(99L);
        when(quoteMapper.selectByOrder("AIFULI_MALL", "tenant-a", "ORD-1")).thenReturn(consumed);

        assertThatThrownBy(() -> service.quote(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already consumed");
        verify(quoteMapper, never()).insert(any(StandardPricingQuoteDO.class));
    }

    @Test
    void rejectsUnexpiredQuoteWhenBindingDiffers() {
        StandardPricingQuoteDO existing = issuedQuote(NOW.plusMinutes(10));
        existing.setPlatformSkuCode("OTHER_SKU");
        when(quoteMapper.selectByOrder("AIFULI_MALL", "tenant-a", "ORD-1")).thenReturn(existing);

        assertThatThrownBy(() -> service.quote(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different pricing quote");
    }

    @Test
    void reusesIssuedQuoteWhenExpiresAtEqualsNow() {
        when(quoteMapper.selectByOrder("AIFULI_MALL", "tenant-a", "ORD-1")).thenReturn(issuedQuote(NOW));

        var response = service.quote(request());

        assertThat(response.getQuoteToken()).isEqualTo("token-1");
        verify(quoteMapper, never()).insert(any(StandardPricingQuoteDO.class));
    }

    @Test
    void requireForFulfillmentAcceptsPaidAtOnCreatedAndExpiryInclusiveBounds() {
        StandardPricingQuoteDO quote = issuedQuote(NOW.plusMinutes(15));
        quote.setCreatedAt(NOW);
        when(quoteMapper.selectByTokenForUpdate("token-1")).thenReturn(quote);

        FrozenQuote atCreated = service.requireForFulfillment(new QuoteConsumptionContext(
                "token-1", 1L, 2L, 4L, "ORD-1", MOBILE, 1L, NOW));
        FrozenQuote atExpiry = service.requireForFulfillment(new QuoteConsumptionContext(
                "token-1", 1L, 2L, 4L, "ORD-1", MOBILE, 1L, NOW.plusMinutes(15)));

        assertThat(atCreated.quoteId()).isEqualTo(7L);
        assertThat(atExpiry.quoteId()).isEqualTo(7L);
    }

    @Test
    void requireForFulfillmentRejectsPaidAtAfterExpiry() {
        StandardPricingQuoteDO quote = issuedQuote(NOW.plusMinutes(15));
        quote.setCreatedAt(NOW);
        when(quoteMapper.selectByTokenForUpdate("token-1")).thenReturn(quote);

        assertThatThrownBy(() -> service.requireForFulfillment(new QuoteConsumptionContext(
                "token-1", 1L, 2L, 4L, "ORD-1", MOBILE, 1L, NOW.plusMinutes(15).plusNanos(1_000_000))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void markConsumedIsIdempotentForSameOrderAndRejectsAnotherOrder() {
        StandardPricingQuoteDO consumed = issuedQuote(NOW.plusMinutes(10));
        consumed.setId(7L);
        consumed.setStatus(PricingQuoteStatus.CONSUMED.name());
        consumed.setConsumedOrderId(50L);
        when(quoteMapper.selectById(7L)).thenReturn(consumed);

        service.markConsumed(7L, 50L, NOW);
        assertThatThrownBy(() -> service.markConsumed(7L, 51L, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another order");
    }

    private void stubReadyVersion() throws Exception {
        FormulaRule direct = new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null);
        PricingConfig config = new PricingConfig(direct, direct,
                new ServiceFeeRule(new BigDecimal("1"), FixedOperator.MULTIPLY, BigDecimal.ZERO, true, null),
                new HideRule(null, null),
                new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, "不征税"));
        ProjectSkuPricingVersionDO version = ProjectSkuPricingVersionDO.builder()
                .id(9L).pricingStatus(PricingStatus.READY.name())
                .distributionPriceCents(9_800L).salesPriceCents(10_000L)
                .effectiveConfigJson(objectMapper.writeValueAsString(config)).build();
        when(pricingService.ensureCurrentProjectVersion("PRJ_A", "MOVIE_100"))
                .thenReturn(new CurrentProjectPricing(version, 3L, 4L, "MOVIE_100"));
    }

    private static StandardPricingQuoteReqDTO request() {
        StandardPricingQuoteReqDTO request = new StandardPricingQuoteReqDTO();
        request.setSourceSystem("AIFULI_MALL");
        request.setExternalTenantId("tenant-a");
        request.setProjectCode("PRJ_A");
        request.setPlatformSkuCode("MOVIE_100");
        request.setBuyerUserId(MOBILE);
        request.setBuyerOrderNo("ORD-1");
        request.setQuantity(1L);
        return request;
    }

    private static StandardPricingQuoteDO issuedQuote(LocalDateTime expiresAt) {
        return StandardPricingQuoteDO.builder()
                .id(7L).quoteToken("token-1").sourceSystem("AIFULI_MALL").externalTenantId("tenant-a")
                .buyerId(1L).projectId(2L).listingId(3L).platformSkuId(4L).platformSkuCode("MOVIE_100")
                .projectPricingVersionId(9L).buyerUserId(FulfillmentTargetFields.normalizeMobile(MOBILE))
                .buyerOrderNo("ORD-1").quantity(1L).distributionUnitPriceCents(9_800L)
                .salesUnitPriceCents(10_000L).productSubtotalCents(10_000L).serviceFeeCents(100L)
                .userPayableAmountCents(10_100L)
                .taxMetadataJson("{\"taxIncluded\":false,\"taxRateCode\":\"NON_TAXABLE\",\"taxRateLabel\":\"不征税\"}")
                .status(PricingQuoteStatus.ISSUED.name()).expiresAt(expiresAt).createdAt(NOW.minusMinutes(1))
                .build();
    }
}
