package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteReqDTO;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目订单首次报价永久冻结的核心语义：同键永远重放首次冻结结果；不绑定用户、支付时间或报价有效期；
 * 报价/消费按项目永久键定位，缺报价抛稳定业务错误供共享受理条件重试。
 * 设计见 {@code docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#2-首次签发与重放}、
 * {@code ...#3-受理内查取与唯一消费}。
 */
class StandardPricingQuoteServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 12, 0);

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
    void replaysQuoteWhoseLegacyExpiryHasPassedInsteadOfReissuing() {
        when(quoteMapper.selectByProjectOrder(2L, "ORD-1"))
                .thenReturn(issuedQuote(NOW.minusHours(1)));

        var response = service.quote(request());

        assertThat(response.getQuoteToken()).isEqualTo("token-1");
        assertThat(response.getUserPayableAmountCents()).isEqualTo(10_100L);
        verify(quoteMapper, never()).insert(any(StandardPricingQuoteDO.class));
        verify(pricingService, never()).ensureCurrentProjectVersion(anyString(), anyString());
    }

    @Test
    void replaysConsumedQuoteForSameBindingWithOriginalFrozenPrice() {
        StandardPricingQuoteDO consumed = issuedQuote(NOW.minusHours(1));
        consumed.setStatus(PricingQuoteStatus.CONSUMED.name());
        consumed.setConsumedOrderId(99L);
        when(quoteMapper.selectByProjectOrder(2L, "ORD-1")).thenReturn(consumed);

        var response = service.quote(request());

        assertThat(response.getQuoteToken()).isEqualTo("token-1");
        assertThat(response.getUserPayableAmountCents()).isEqualTo(10_100L);
        verify(quoteMapper, never()).insert(any(StandardPricingQuoteDO.class));
        verify(pricingService, never()).ensureCurrentProjectVersion(anyString(), anyString());
    }

    @Test
    void issuesQuoteWithoutUserBindingAndNullExpiry() {
        when(quoteMapper.selectByProjectOrder(2L, "ORD-1")).thenReturn(null);
        when(quoteMapper.insert(any(StandardPricingQuoteDO.class))).thenReturn(1);

        var response = service.quote(request());

        assertThat(response.getQuoteToken()).isNotBlank();
        assertThat(response.getExpiresAt()).isNull();
        ArgumentCaptor<StandardPricingQuoteDO> inserted = ArgumentCaptor.forClass(StandardPricingQuoteDO.class);
        verify(quoteMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getExpiresAt()).isNull();
        assertThat(inserted.getValue().getStatus()).isEqualTo(PricingQuoteStatus.ISSUED.name());
        assertThat(inserted.getValue().getBuyerOrderNo()).isEqualTo("ORD-1");
    }

    @Test
    void issuesSeparateQuoteForAnotherProjectWithSameOrderNumber() {
        when(projectMapper.selectByProjectCodeForUpdate("PRJ_B")).thenReturn(BuyerProjectDO.builder()
                .id(5L).projectCode("PRJ_B").buyerId(1L).status(BuyerProjectStatus.ACTIVE.getCode()).build());
        when(quoteMapper.selectByProjectOrder(2L, "ORD-1"))
                .thenReturn(issuedQuote(NOW.plusMinutes(10)));
        when(quoteMapper.selectByProjectOrder(5L, "ORD-1")).thenReturn(null);
        when(quoteMapper.insert(any(StandardPricingQuoteDO.class))).thenReturn(1);
        StandardPricingQuoteReqDTO projectB = request();
        projectB.setProjectCode("PRJ_B");

        var response = service.quote(projectB);

        assertThat(response.getQuoteToken()).isNotBlank();
        ArgumentCaptor<StandardPricingQuoteDO> inserted = ArgumentCaptor.forClass(StandardPricingQuoteDO.class);
        verify(quoteMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getProjectId()).isEqualTo(5L);
    }

    @Test
    void rejectsQuantityOtherThanOne() {
        StandardPricingQuoteReqDTO quantityTwo = request();
        quantityTwo.setQuantity(2L);
        when(quoteMapper.selectByProjectOrder(2L, "ORD-1")).thenReturn(null);
        when(quoteMapper.insert(any(StandardPricingQuoteDO.class))).thenReturn(1);

        assertThatThrownBy(() -> service.quote(quantityTwo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
        verify(quoteMapper, never()).insert(any(StandardPricingQuoteDO.class));
    }

    @Test
    void rejectsQuoteWhenBindingDiffers() {
        StandardPricingQuoteDO existing = issuedQuote(NOW.plusMinutes(10));
        existing.setPlatformSkuCode("OTHER_SKU");
        when(quoteMapper.selectByProjectOrder(2L, "ORD-1")).thenReturn(existing);

        assertThatThrownBy(() -> service.quote(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different pricing quote");
        verify(quoteMapper, never()).insert(any(StandardPricingQuoteDO.class));
    }

    @Test
    void requireForProjectAcceptsQuoteBeyondLegacyExpiryWithoutPaidAtWindow() {
        StandardPricingQuoteDO legacyExpired = issuedQuote(NOW.minusHours(1));
        when(quoteMapper.selectByProjectOrderForUpdate(2L, "ORD-1")).thenReturn(legacyExpired);

        FrozenQuote frozen = service.requireForProject(new ProjectQuoteKeyContext(1L, 2L, 4L, "ORD-1", 1L));

        assertThat(frozen.quoteId()).isEqualTo(7L);
        assertThat(frozen.listingId()).isEqualTo(3L);
        assertThat(frozen.consumed()).isFalse();
    }

    /**
     * 过渡 token 校验随请求 token 字段删除而退役：项目键消费不再携带或校验 quoteToken，
     * 不能让 HTTP 请求自选报价；同永久项目键只使用首次冻结报价。
     * 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#2-首次身份与重放优先。
     */
    @Test
    void requireForProjectDoesNotRequireTransitionalTokenMatch() {
        StandardPricingQuoteDO quote = issuedQuote(NOW.minusHours(1));
        quote.setQuoteToken("token-other");
        when(quoteMapper.selectByProjectOrderForUpdate(2L, "ORD-1")).thenReturn(quote);

        FrozenQuote frozen = service.requireForProject(new ProjectQuoteKeyContext(1L, 2L, 4L, "ORD-1", 1L));

        assertThat(frozen.quoteId()).isEqualTo(7L);
    }

    @Test
    void requireForProjectReplaysConsumedQuoteIdempotently() {
        StandardPricingQuoteDO consumed = issuedQuote(NOW.minusHours(1));
        consumed.setStatus(PricingQuoteStatus.CONSUMED.name());
        consumed.setConsumedOrderId(99L);
        when(quoteMapper.selectByProjectOrderForUpdate(2L, "ORD-1")).thenReturn(consumed);

        FrozenQuote frozen = service.requireForProject(new ProjectQuoteKeyContext(1L, 2L, 4L, "ORD-1", 1L));

        assertThat(frozen.quoteId()).isEqualTo(7L);
        assertThat(frozen.consumed()).isTrue();
        assertThat(frozen.consumedOrderId()).isEqualTo(99L);
    }

    @Test
    void requireForProjectRejectsMissingQuoteWithStableBusinessError() {
        when(quoteMapper.selectByProjectOrderForUpdate(2L, "ORD-1")).thenReturn(null);

        assertThatThrownBy(() -> service.requireForProject(new ProjectQuoteKeyContext(1L, 2L, 4L, "ORD-1", 1L)))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode())
                        .isEqualTo(SupplyChainErrorCodeConstants.STANDARD_PRICING_QUOTE_MISSING.getCode()))
                .hasMessageContaining("首次冻结报价");
    }

    @Test
    void requireForProjectRejectsBindingMismatch() {
        when(quoteMapper.selectByProjectOrderForUpdate(2L, "ORD-1"))
                .thenReturn(issuedQuote(NOW.minusHours(1)));

        assertThatThrownBy(() -> service.requireForProject(new ProjectQuoteKeyContext(1L, 2L, 4L, "ORD-1", 2L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another project, sku, or order");
    }

    @Test
    void markConsumedIsIdempotentForSameOrderAndRejectsAnotherOrder() {
        StandardPricingQuoteDO consumed = issuedQuote(NOW.minusHours(1));
        consumed.setId(7L);
        consumed.setStatus(PricingQuoteStatus.CONSUMED.name());
        consumed.setConsumedOrderId(50L);
        when(quoteMapper.selectByIdForUpdate(7L)).thenReturn(consumed);

        service.markConsumed(7L, 50L, NOW);
        assertThatThrownBy(() -> service.markConsumed(7L, 51L, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another order");
    }

    @Test
    void markConsumedConsumesIssuedQuoteAndRejectsInconsistentState() {
        StandardPricingQuoteDO issued = issuedQuote(NOW.minusHours(1));
        issued.setId(7L);
        when(quoteMapper.selectByIdForUpdate(7L)).thenReturn(issued);
        when(quoteMapper.updateById(any(StandardPricingQuoteDO.class))).thenReturn(1);

        service.markConsumed(7L, 50L, NOW);

        ArgumentCaptor<StandardPricingQuoteDO> updated = ArgumentCaptor.forClass(StandardPricingQuoteDO.class);
        verify(quoteMapper).updateById(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo(PricingQuoteStatus.CONSUMED.name());
        assertThat(updated.getValue().getConsumedOrderId()).isEqualTo(50L);

        StandardPricingQuoteDO inconsistent = issuedQuote(NOW.minusHours(1));
        inconsistent.setId(8L);
        inconsistent.setConsumedOrderId(88L);
        when(quoteMapper.selectByIdForUpdate(8L)).thenReturn(inconsistent);
        assertThatThrownBy(() -> service.markConsumed(8L, 50L, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inconsistent");
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
        when(pricingService.ensureCurrentProjectVersion(anyString(), anyString()))
                .thenReturn(new CurrentProjectPricing(version, 3L, 4L, "MOVIE_100"));
    }

    private static StandardPricingQuoteReqDTO request() {
        StandardPricingQuoteReqDTO request = new StandardPricingQuoteReqDTO();
        request.setSourceSystem("AIFULI_MALL");
        request.setExternalTenantId("tenant-a");
        request.setProjectCode("PRJ_A");
        request.setPlatformSkuCode("MOVIE_100");
        request.setBuyerOrderNo("ORD-1");
        request.setQuantity(1L);
        return request;
    }

    private static StandardPricingQuoteDO issuedQuote(LocalDateTime expiresAt) {
        return StandardPricingQuoteDO.builder()
                .id(7L).quoteToken("token-1").sourceSystem("AIFULI_MALL").externalTenantId("tenant-a")
                .buyerId(1L).projectId(2L).listingId(3L).platformSkuId(4L).platformSkuCode("MOVIE_100")
                .projectPricingVersionId(9L)
                .buyerOrderNo("ORD-1").quantity(1L).distributionUnitPriceCents(9_800L)
                .salesUnitPriceCents(10_000L).productSubtotalCents(10_000L).serviceFeeCents(100L)
                .userPayableAmountCents(10_100L)
                .taxMetadataJson("{\"taxIncluded\":false,\"taxRateCode\":\"NON_TAXABLE\",\"taxRateLabel\":\"不征税\"}")
                .status(PricingQuoteStatus.ISSUED.name()).expiresAt(expiresAt).createdAt(NOW.minusMinutes(1))
                .build();
    }
}
