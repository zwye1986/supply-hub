package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.security.AdapterTypeAllowList;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductSellabilityService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierOfferEligibilityServiceTest {
    @Mock SupplierMapper supplierMapper;
    @Mock SupplierConnectorMapper connectorMapper;
    @Mock SupplierConnectorVersionMapper connectorVersionMapper;
    @Mock SupplierConnectorCapabilityMapper capabilityMapper;
    @Mock InventorySourceMapper inventorySourceMapper;
    @Mock SupplierOfferMapper offerMapper;
    @Mock SupplierOfferVersionMapper offerVersionMapper;
    @Mock PlatformSkuMapper skuMapper;
    @Mock PlatformSpuMapper spuMapper;
    @Mock ProductSellabilityService productSellabilityService;
    @Mock AdapterTypeAllowList adapterTypeAllowList;
    @Mock AdapterMetadataRegistry metadataRegistry;
    @InjectMocks SupplierOfferEligibilityService service;
    final LocalDateTime now = LocalDateTime.of(2026, 10, 11, 12, 0);
    SupplierOfferDO offer;
    SupplierDO supplier;
    SupplierOfferVersionDO version;
    SupplierConnectorDO connector;
    SupplierConnectorVersionDO connectorVersion;
    InventorySourceDO inventory;
    PlatformSkuDO sku;
    PlatformSpuDO spu;
    List<SupplierConnectorCapabilityDO> capabilities;

    @BeforeEach void seed() {
        offer = SupplierOfferDO.builder().id(1L).supplierId(2L).platformSkuId(3L)
                .status((short)10).currentVersionId(4L).build();
        supplier = SupplierDO.builder().id(2L).status((short)10).build();
        version = SupplierOfferVersionDO.builder().id(4L).offerId(1L).connectorVersionId(6L)
                .inventorySourceId(7L).currency("CNY").supplierSettlementUnitPrice(100L)
                .minQuantity(1L).maxQuantity(10L).quantityStep(1L).primaryCapabilityCode("ISSUE_ASSET")
                .completionMode("SYNCHRONOUS").unknownResultPolicy("MANUAL")
                .validFrom(now.minusDays(1)).build();
        connector = SupplierConnectorDO.builder().id(5L).supplierId(2L).status((short)10)
                .currentVersionId(6L).adapterType("TEST").build();
        connectorVersion = SupplierConnectorVersionDO.builder().id(6L).connectorId(5L).status((short)10).build();
        inventory = InventorySourceDO.builder().id(7L).supplierId(2L).platformSkuId(3L)
                .status((short)10).sourceType("REMOTE_ON_DEMAND").build();
        sku = PlatformSkuDO.builder().id(3L).spuId(8L).status((short)10).fulfillmentType("VOUCHER_CODE").build();
        spu = new PlatformSpuDO(); spu.setId(8L); spu.setStatus((short)10);
        capabilities = List.of(capability("ISSUE_ASSET"));
        when(adapterTypeAllowList.isAllowed("TEST")).thenReturn(true);
        when(productSellabilityService.isSellable(any(), any())).thenReturn(true);
        when(capabilityMapper.selectByConnectorVersionId(6L)).thenAnswer(i -> capabilities);
        when(capabilityMapper.selectList(any())).thenAnswer(i -> capabilities);
        when(skuMapper.selectBatchIds(any())).thenReturn(List.of(sku));
        when(spuMapper.selectBatchIds(any())).thenReturn(List.of(spu));
        when(offerMapper.selectList(any())).thenReturn(List.of(offer));
        when(supplierMapper.selectBatchIds(any())).thenReturn(List.of(supplier));
        when(offerVersionMapper.selectBatchIds(any())).thenReturn(List.of(version));
        when(connectorMapper.selectBatchIds(any())).thenReturn(List.of(connector));
        when(connectorVersionMapper.selectBatchIds(any())).thenReturn(List.of(connectorVersion));
        when(inventorySourceMapper.selectBatchIds(any())).thenReturn(List.of(inventory));
    }
    private SupplierConnectorCapabilityDO capability(String code) {
        return SupplierConnectorCapabilityDO.builder().connectorVersionId(6L).capabilityCode(code).enabled((short)1).build();
    }
    @Test void frozenQueryProfileRequiresQueryCapability() {
        version.setCompletionMode("QUERY");
        assertThat(service.resolveMinimumSupplyPrices(List.of(3L), now)).isEmpty();
    }
    @Test void currentSkuTypeMustMatchFrozenPrimaryCapability() {
        sku.setFulfillmentType("DIRECT_RECHARGE");
        assertThat(service.resolveMinimumSupplyPrices(List.of(3L), now)).isEmpty();
    }
    @Test void minimumCostUsesSupportedQuantityOne() {
        version.setMinQuantity(2L);
        assertThat(service.resolveMinimumSupplyPrices(List.of(3L), now)).isEmpty();
    }
    @Test void cheaperOfferWithMinimumTwoCannotReplaceQuantityOneCost() {
        SupplierOfferDO cheaper = SupplierOfferDO.builder().id(9L).supplierId(2L).platformSkuId(3L)
                .status((short)10).currentVersionId(10L).build();
        SupplierOfferVersionDO cheaperVersion = SupplierOfferVersionDO.builder().id(10L).offerId(9L)
                .connectorVersionId(6L).inventorySourceId(7L).currency("CNY").supplierSettlementUnitPrice(95L)
                .minQuantity(2L).maxQuantity(10L).quantityStep(1L).primaryCapabilityCode("ISSUE_ASSET")
                .completionMode("SYNCHRONOUS").unknownResultPolicy("MANUAL").validFrom(now.minusDays(1)).build();
        when(offerMapper.selectList(any())).thenReturn(List.of(offer, cheaper));
        when(offerVersionMapper.selectBatchIds(any())).thenReturn(List.of(version, cheaperVersion));
        assertThat(service.resolveMinimumSupplyPrices(List.of(3L), now)).containsEntry(3L,100L);
    }
    @Test void validityIsInclusiveAtStartAndExclusiveAtEnd() {
        version.setValidFrom(now);
        version.setValidTo(now.plusSeconds(1));
        assertThat(service.resolveMinimumSupplyPrices(List.of(3L), now)).containsEntry(3L,100L);
        assertThat(service.resolveMinimumSupplyPrices(List.of(3L), now.plusSeconds(1))).isEmpty();
    }
    @Test void foreignFrozenVersionIsExcludedEvenWithSamePrice() {
        version.setOfferId(999L);
        assertThat(service.resolveMinimumSupplyPrices(List.of(3L), now)).isEmpty();
    }
    @Test void abilitiesAreLoadedOnceForCandidateSet() {
        assertThat(service.resolveMinimumSupplyPrices(List.of(3L), now)).containsEntry(3L,100L);
        verify(capabilityMapper).selectList(any());
        verify(capabilityMapper, never()).selectByConnectorVersionId(any());
    }
    @Test void identityDraftDriftDoesNotInvalidateFrozenPrice() {
        offer.setDraftUnitPrice(999L);
        assertThat(service.resolveMinimumSupplyPrices(List.of(3L), now)).containsEntry(3L,100L);
    }
    @Test void zeroCostIsKeptForDisplay() {
        version.setSupplierSettlementUnitPrice(0L);
        assertThat(service.resolveMinimumSupplyPrices(List.of(3L), now)).containsEntry(3L,0L);
    }
    @Test void unavailableInventoryRemovesCost() {
        inventory.setStatus((short)20);
        assertThat(service.resolveMinimumSupplyPrices(List.of(3L), now)).isEmpty();
    }
}
