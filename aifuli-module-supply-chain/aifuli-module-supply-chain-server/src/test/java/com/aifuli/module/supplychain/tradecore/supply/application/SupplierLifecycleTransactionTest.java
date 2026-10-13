package com.aifuli.module.supplychain.tradecore.supply.application;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.lang.reflect.Method;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SupplierLifecycleTransactionTest {
    @Test void lifecycleWriteEntrypointsDeclareReadCommitted() {
        Map<Class<?>, String[]> entries = Map.of(
                SupplierCooperativeProductService.class, new String[]{"createPublished","createDrafts","changeOfferStatus","updateCommercial","retire"},
                SupplierOfferPublicationService.class, new String[]{"createOffer","createRemoteOnDemandInventory","publish","listOnShelf","activateInventorySource"},
                ConnectorPublicationService.class, new String[]{"publish","retire"},
                SupplierProfileCommandService.class, new String[]{"changeStatus"},
                com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.class, new String[]{"savePlatform","saveBuyer","saveProject","initializePlatformDefaults","initializeBuyerProduct","initializeProjectListing","lockPlatformPricingByCodes"},
                com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.class, new String[]{"createProduct","updateProduct"},
                com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductSelectionBatchTransactionalWorker.class, new String[]{"executeBatch"},
                com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingCommandService.class, new String[]{"addProducts"});
        entries.forEach((type, names) -> {
            for (String name : names) {
                Method method = java.util.Arrays.stream(type.getMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
                assertThat(method.getAnnotation(Transactional.class).isolation()).as(type.getSimpleName()+"."+name)
                        .isEqualTo(Isolation.READ_COMMITTED);
            }
        });
    }
    @Test void oldStatusPatchRejectsOuterDefaultIsolationBeforeBusinessRead() {
        SupplierCooperativeProductService service = new SupplierCooperativeProductService(
                null,null,null,null,null,null,null,null,null,null,null,null,null,null);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.changeOfferStatus("SUP", "OFR", com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus.PUBLISHED,1L))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ_COMMITTED");
        } finally { TransactionSynchronizationManager.clear(); }
    }
}
