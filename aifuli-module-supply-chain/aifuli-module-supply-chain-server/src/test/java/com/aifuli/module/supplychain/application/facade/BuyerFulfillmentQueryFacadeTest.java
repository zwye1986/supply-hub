package com.aifuli.module.supplychain.application.facade;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAssetQueryReqDTO;
import com.aifuli.module.supplychain.border.fulfillment.application.BuyerFulfillmentQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购商履约查询门面：必填字段校验后才委派领域服务。
 */
@ExtendWith(MockitoExtension.class)
class BuyerFulfillmentQueryFacadeTest {

    @Mock
    private BuyerFulfillmentQueryService queryService;

    private BuyerFulfillmentQueryFacade facade;

    @BeforeEach
    void setUp() {
        facade = new BuyerFulfillmentQueryFacade(queryService);
    }

    @Test
    void getFulfillmentAssetsRejectsBlankRequiredFields() {
        assertThatThrownBy(() -> facade.getFulfillmentAssets(new FulfillmentAssetQueryReqDTO()))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
        verify(queryService, never()).getFulfillmentAssets(any());
    }

    @Test
    void getFulfillmentAssetsDelegatesWhenRequiredFieldsPresent() {
        FulfillmentAssetQueryReqDTO request = new FulfillmentAssetQueryReqDTO();
        request.setSourceSystem("AIFULI_MALL");
        request.setExternalTenantId("tenant-wusheng");
        request.setFulfillmentRequestNo("FR_001");
        request.setBuyerUserId("member-1024");
        request.setScopeCode("FULFILLMENT_ASSET_READ");
        when(queryService.getFulfillmentAssets(request)).thenReturn(List.of());

        assertThat(facade.getFulfillmentAssets(request)).isEmpty();
        verify(queryService).getFulfillmentAssets(request);
    }
}
