package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAcceptanceReceiptMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAcceptanceReceiptDO;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogResolver;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** NULL尝试版本及双编码Schema桥接；见桥接设计§3.1/§10。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BuyerCatalogAcceptanceAttemptTest {
    @Mock SupplyChainDatabaseClock databaseClock;
    @Mock BusinessNoGenerator businessNoGenerator;
    @Mock FulfillmentAcceptanceReceiptMapper receiptMapper;
    @Mock BuyerListingMapper listingMapper;
    @Mock BuyerListingVersionMapper listingVersionMapper;
    @Mock PlatformSkuMapper skuMapper;
    @Mock RechargeFrequencyLifecycle rechargeFrequencyLifecycle;
    @Mock BuyerAcceptanceCatalogResolver catalogResolver;
    @InjectMocks FulfillmentAcceptanceLifecycle lifecycle;
    private BuyerListingDO draft;
    private final byte[] fingerprint = new byte[32];
    @BeforeEach
    void fixture() {
        draft = BuyerListingDO.builder().id(5L).buyerId(1L).platformSkuId(3L).buyerSkuCode("old")
                .listingNo("LST_5").status((short) 0).version(0L).build();
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 10, 4, 12, 0));
        when(businessNoGenerator.next(anyString())).thenReturn("RCP_TEST");
        when(catalogResolver.resolve(1L, "BPD_7")).thenReturn(new BuyerAcceptanceCatalogResolver.CatalogIdentity(
                1L, "BPD_7", 7L, "BPD_7", 5L, "LST_5", 3L, "MANUAL"));
        when(listingMapper.selectById(5L)).thenReturn(draft);
        when(receiptMapper.insert(any(FulfillmentAcceptanceReceiptDO.class))).thenAnswer(i -> {
            i.<FulfillmentAcceptanceReceiptDO>getArgument(0).setId(20L); return 1;
        });
    }
    @Test
    void bpdSchemaUsesApplicationResolverRatherThanOldCodeOnlyLookup() {
        when(skuMapper.selectById(3L)).thenReturn(PlatformSkuDO.builder().id(3L).targetSchemaCode("ACCOUNT").targetSchemaVersion(1).build());
        var snapshot = lifecycle.resolveTargetSchema(1L, "BPD_7");
        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.platformSkuId()).isEqualTo(3L);
        verify(catalogResolver).resolve(1L, "BPD_7");
    }
    @Test
    void draftFirstClaimStoresRealNullVersionInsteadOfInventingOneOrThrowing() {
        when(listingMapper.selectByBuyerAndSkuCode(1L, "BPD_7")).thenReturn(draft);
        assertThatCode(() -> lifecycle.claim(command(null))).doesNotThrowAnyException();
        var row = org.mockito.ArgumentCaptor.forClass(FulfillmentAcceptanceReceiptDO.class);
        verify(receiptMapper).insert(row.capture());
        assertThat(row.getValue().getListingVersionNo()).isNull();
    }
    @Test
    void retryableNullVersionCorrectCasDoesNotUnboxNull() {
        var receipt = FulfillmentAcceptanceReceiptDO.builder().id(20L).projectId(2L).buyerId(1L)
                .status(AcceptanceReceiptStatus.REJECTED_RETRYABLE.getCode()).version(1L)
                .requestFingerprint(fingerprint).listingVersionNo(null).build();
        when(receiptMapper.selectByProjectKey(2L, "order")).thenReturn(receipt);
        when(receiptMapper.selectByProjectKeyForUpdate(2L, "order")).thenReturn(receipt);
        // 无真实Mapper写入，本用例验证Lifecycle不得在进入Mapper之前拆箱；真实CAS另由MySQL门禁验证。
        assertThatThrownBy(() -> lifecycle.claim(command(1L)))
                .isInstanceOf(FulfillmentAcceptanceLifecycle.IdempotencyConflictException.class);
    }
    @Test
    void mapperNullableCasParameterIsInteger() {
        var method = java.util.Arrays.stream(FulfillmentAcceptanceReceiptMapper.class.getMethods())
                .filter(m -> m.getName().equals("reopenRetryable")).findFirst().orElseThrow();
        assertThat(method.getParameterTypes()[2]).isEqualTo(Integer.class);
    }
    @Test
    void finalAcceptanceLocksReceiptThenResolvedIdThenSkuBeforeOrdinaryAssociationRead() {
        var identity = new BuyerAcceptanceCatalogResolver.CatalogIdentity(1L, "BPD_7", 7L, "BPD_7", 5L, "LST_5", 3L, "MANUAL");
        when(receiptMapper.selectByIdForUpdate(20L)).thenReturn(FulfillmentAcceptanceReceiptDO.builder()
                .id(20L).status(AcceptanceReceiptStatus.PROCESSING.getCode()).requestFingerprint(fingerprint).build());
        when(catalogResolver.lockResolvedIdentity(identity)).thenReturn(draft);
        when(skuMapper.selectByIdForUpdate(3L)).thenReturn(PlatformSkuDO.builder().id(3L).build());
        assertThatThrownBy(() -> lifecycle.accept(20L, command(null, identity)))
                .isInstanceOf(FulfillmentAcceptanceLifecycle.BusinessRejection.class);
        var order = inOrder(receiptMapper, catalogResolver, skuMapper);
        order.verify(receiptMapper).selectByIdForUpdate(20L);
        order.verify(catalogResolver).lockResolvedIdentity(identity);
        order.verify(skuMapper).selectByIdForUpdate(3L);
        order.verify(catalogResolver).verifyAssociationAfterSkuLock(identity, draft);
        verify(catalogResolver, never()).resolve(any(), any());
        verify(listingMapper, never()).selectByBuyerAndSkuCodeForUpdate(any(), any());
    }
    @Test
    void nonNullCurrentVersionPointingAtAnotherListingRemainsSystemFailure() {
        draft.setCurrentVersionId(90L);
        when(listingVersionMapper.selectById(90L)).thenReturn(BuyerListingVersionDO.builder()
                .id(90L).listingId(999L).versionNo(1).build());
        assertThatThrownBy(() -> lifecycle.claim(command(null))).isInstanceOf(IllegalStateException.class);
        verify(receiptMapper, never()).insert(any(FulfillmentAcceptanceReceiptDO.class));
    }
    @Test
    void finalAcceptanceDoesNotDowngradeBrokenNonNullCurrentToRetryableDraft() {
        var identity = new BuyerAcceptanceCatalogResolver.CatalogIdentity(1L, "BPD_7", 7L, "BPD_7", 5L, "LST_5", 3L, "MANUAL");
        draft.setCurrentVersionId(90L);
        when(receiptMapper.selectByIdForUpdate(20L)).thenReturn(FulfillmentAcceptanceReceiptDO.builder()
                .id(20L).status(AcceptanceReceiptStatus.PROCESSING.getCode()).requestFingerprint(fingerprint).build());
        when(catalogResolver.lockResolvedIdentity(identity)).thenReturn(draft);
        when(skuMapper.selectByIdForUpdate(3L)).thenReturn(PlatformSkuDO.builder().id(3L).build());
        assertThatThrownBy(() -> lifecycle.accept(20L, command(null, identity))).isExactlyInstanceOf(IllegalStateException.class);
        when(listingVersionMapper.selectById(90L)).thenReturn(BuyerListingVersionDO.builder()
                .id(90L).listingId(999L).versionNo(1).build());
        assertThatThrownBy(() -> lifecycle.accept(20L, command(null, identity))).isExactlyInstanceOf(IllegalStateException.class);
    }
    private FulfillmentAcceptanceLifecycle.PreparedAcceptance command(Long previous) {
        return command(previous, null);
    }
    private FulfillmentAcceptanceLifecycle.PreparedAcceptance command(Long previous, BuyerAcceptanceCatalogResolver.CatalogIdentity identity) {
        return new FulfillmentAcceptanceLifecycle.PreparedAcceptance(2L, "PRJ", "项目", 1L, "BYR", "买家",
                "order", null, "CARD", "BPD_7", 1L, "ACCOUNT", 1, null, fingerprint, null,
                true, fingerprint, fingerprint, fingerprint, previous, "trace", identity);
    }
}
