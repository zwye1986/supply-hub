package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PRODUCT_NOT_FOUND;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 保存提交与目录刷新分离；显式重算不吞故障。见最小业务设计 §3、§7。 */
class BuyerCatalogManagementServiceTest {
    private final BuyerProductCommandService selection = mock(BuyerProductCommandService.class);
    private final GenericBatchPricingService pricing = mock(GenericBatchPricingService.class);
    private final BuyerSelectionCatalogLifecycle lifecycle = mock(BuyerSelectionCatalogLifecycle.class);
    private final BuyerMapper buyers = mock(BuyerMapper.class);
    private final BuyerProductMapper products = mock(BuyerProductMapper.class);
    private final BuyerListingMapper listings = mock(BuyerListingMapper.class);

    @Test
    void failedDomainSaveNeverStartsIdentityOrRefresh() throws Exception {
        var failure = new IllegalStateException("domain rolled back");
        when(selection.addProducts("BYR_A", List.of(), 9L)).thenThrow(failure);
        Object service = service();
        assertThatThrownBy(() -> call(service, "addProducts", new Class<?>[]{String.class, List.class, Long.class},
                "BYR_A", List.of(), 9L)).isSameAs(failure);
        verifyNoInteractions(lifecycle, buyers, products);
        verify(selection, never()).ensureIdentity(any(), any());
    }

    @Test
    void committedSelectionReturnsWorkerCodesAndContinuesAfterOneRefreshFailure() throws Exception {
        List<String> committed = List.of("BPD_A", "BPD_B");
        when(selection.addProducts("BYR_A", List.of(), 9L)).thenReturn(committed);
        when(selection.ensureIdentity("BPD_A", 9L)).thenReturn("LST_A");
        when(selection.ensureIdentity("BPD_B", 9L)).thenReturn("LST_B");
        when(lifecycle.refreshListing("LST_A", 9L)).thenThrow(new IllegalStateException("refresh rollback"));
        Object result = call(service(), "addProducts", new Class<?>[]{String.class, List.class, Long.class},
                "BYR_A", List.of(), 9L);
        assertThat(result).isSameAs(committed);
        var order = inOrder(selection, lifecycle);
        order.verify(selection).addProducts("BYR_A", List.of(), 9L);
        order.verify(selection).ensureIdentity("BPD_A", 9L);
        order.verify(lifecycle).refreshListing("LST_A", 9L);
        order.verify(selection).ensureIdentity("BPD_B", 9L);
        order.verify(lifecycle).refreshListing("LST_B", 9L);
    }

    @Test
    void pricingPreservesWholeBatchAndBooleanAfterIdentityFailure() throws Exception {
        var codes = List.of("BPD_A", "BPD_B", "BPD_A");
        when(selection.ensureIdentity("BPD_A", 9L)).thenThrow(new IllegalStateException("identity failure"));
        when(selection.ensureIdentity("BPD_B", 9L)).thenReturn("LST_B");
        Object result = call(service(), "saveBuyerPricing", new Class<?>[]{String.class, List.class, ProjectOverrides.class, Long.class},
                "BYR_A", codes, null, 9L);
        assertThat(result).isEqualTo(true);
        var order = inOrder(pricing, selection, lifecycle);
        order.verify(pricing).saveBuyer("BYR_A", codes, null, 9L);
        order.verify(selection).ensureIdentity("BPD_A", 9L);
        order.verify(selection).ensureIdentity("BPD_B", 9L);
        order.verify(lifecycle).refreshListing("LST_B", 9L);
        verify(selection, times(1)).ensureIdentity("BPD_A", 9L);
    }

    @Test
    void failedPricingBatchDoesNotRefresh() throws Exception {
        var failure = new IllegalStateException("batch rollback");
        doThrow(failure).when(pricing).saveBuyer("BYR_A", List.of("BPD_A"), null, 9L);
        Object service = service();
        assertThatThrownBy(() -> call(service, "saveBuyerPricing", new Class<?>[]{String.class, List.class, ProjectOverrides.class, Long.class},
                "BYR_A", List.of("BPD_A"), null, 9L)).isSameAs(failure);
        verifyNoInteractions(selection, lifecycle);
    }

    @Test
    void crossBuyerRecalculationFailsBeforeIdentityWrite() throws Exception {
        when(buyers.selectByBuyerCode("BYR_A")).thenReturn(BuyerDO.builder().id(1L).build());
        when(products.selectByCode("BPD_OTHER")).thenReturn(BuyerProductDO.builder().id(2L).buyerId(99L).build());
        Object service = service();
        assertThatThrownBy(() -> call(service, "recalculate", new Class<?>[]{String.class, String.class, Long.class},
                "BYR_A", "BPD_OTHER", 9L)).isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode()).isEqualTo(BUYER_PRODUCT_NOT_FOUND.getCode());
        verifyNoInteractions(selection, lifecycle);
    }

    @Test
    void explicitRecalculationReturnsRealResultAndPropagatesTechnicalFailure() throws Exception {
        when(buyers.selectByBuyerCode("BYR_A")).thenReturn(BuyerDO.builder().id(1L).build());
        when(products.selectByCode("BPD_A")).thenReturn(BuyerProductDO.builder().id(2L).buyerId(1L).build());
        when(selection.ensureIdentity("BPD_A", 9L)).thenReturn("LST_A");
        var result = new BuyerSelectionCatalogLifecycle.RefreshResult("LST_A", "DRAFT", "UNKNOWN", "DEFERRED_PRICE", null, null, null);
        when(lifecycle.refreshListing("LST_A", 9L)).thenReturn(result);
        Object service = service();
        assertThat(call(service, "recalculate", new Class<?>[]{String.class, String.class, Long.class},
                "BYR_A", "BPD_A", 9L)).isSameAs(result);
        var failure = new IllegalStateException("mysql failure");
        when(lifecycle.refreshListing("LST_A", 9L)).thenThrow(failure);
        assertThatThrownBy(() -> call(service, "recalculate", new Class<?>[]{String.class, String.class, Long.class},
                "BYR_A", "BPD_A", 9L)).isSameAs(failure);
    }

    @Test
    void retiredExistingIdentityIsNotRecreated() throws Exception {
        when(buyers.selectByBuyerCode("BYR_A")).thenReturn(BuyerDO.builder().id(1L).build());
        when(products.selectByCode("BPD_A")).thenReturn(BuyerProductDO.builder().id(2L).buyerId(1L)
                .platformSkuId(3L).buyerProductCode("BPD_A").build());
        when(listings.selectByBuyerAndSku(1L, 3L)).thenReturn(BuyerListingDO.builder().listingNo("LST_A")
                .buyerId(1L).platformSkuId(3L).buyerProductId(2L).buyerSkuCode("BPD_A")
                .managementMode("AUTO_SELECTION").status((short) 90).build());
        var result = new BuyerSelectionCatalogLifecycle.RefreshResult("LST_A", "RETIRED", "UNKNOWN", "RETIRED", null, null, null);
        when(lifecycle.refreshListing("LST_A", 9L)).thenReturn(result);
        assertThat(call(service(), "recalculate", new Class<?>[]{String.class, String.class, Long.class},
                "BYR_A", "BPD_A", 9L)).isSameAs(result);
        verifyNoInteractions(selection);
    }

    @Test
    void everyEntryForbidsAnOuterTransaction() throws Exception {
        for (var method : service().getClass().getDeclaredMethods()) {
            if (!List.of("addProducts", "saveBuyerPricing", "recalculate").contains(method.getName())) continue;
            var tx = method.getAnnotation(Transactional.class);
            assertThat(tx).isNotNull();
            assertThat(tx.propagation()).isEqualTo(Propagation.NEVER);
            assertThat(tx.transactionManager()).isEqualTo("supplyChainTransactionManager");
        }
    }

    // 首次红灯用反射让“应用服务尚不存在”表现为明确断言失败，而非整个测试源无法编译。
    private Object service() throws Exception {
        String name = getClass().getPackageName() + ".BuyerCatalogManagementService";
        Class<?> type = null;
        try { type = Class.forName(name); } catch (ClassNotFoundException missing) { /* 下方明确断言 */ }
        assertThat(type).as("保存后目录编排服务必须存在").isNotNull();
        return type.getConstructor(BuyerProductCommandService.class, GenericBatchPricingService.class,
                BuyerSelectionCatalogLifecycle.class, BuyerMapper.class, BuyerProductMapper.class, BuyerListingMapper.class)
                .newInstance(selection, pricing, lifecycle, buyers, products, listings);
    }

    private static Object call(Object service, String name, Class<?>[] types, Object... args) throws Exception {
        try { return service.getClass().getMethod(name, types).invoke(service, args); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException runtime) throw runtime;
            throw wrapped;
        }
    }
}
