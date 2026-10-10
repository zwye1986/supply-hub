package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogItemRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogPageReqDTO;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerCatalogQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 目录读RPC契约：实现只做纯委派，availableOnly与陈旧判定原样交给应用层，不缓存、不补默认状态、
 * 不吞技术失败伪装业务拒绝（过期UNKNOWN等真实结果由应用层给出）。设计 §6.4「目录 RPC读项及 availableOnly筛选遵守同一库时钟／AUTO过期规则」。
 */
class BuyerCatalogFreshnessContractTest {

    @Test
    void pageCatalogDelegatesVerbatimIncludingAvailableOnlyFlag() {
        BuyerCatalogQueryService service = mock(BuyerCatalogQueryService.class);
        PageResult<BuyerCatalogItemRespDTO> page = new PageResult<>(List.of(), 7L);
        BuyerCatalogPageReqDTO request = new BuyerCatalogPageReqDTO();
        request.setAvailableOnly(true);
        request.setPageNo(2);
        request.setPageSize(15);
        when(service.pageCatalog(request)).thenReturn(page);

        var impl = new BuyerCatalogQueryApiImpl(service);
        assertThat(impl.pageCatalog(request).getData()).isSameAs(page);
        verify(service).pageCatalog(request);
    }

    @Test
    void technicalFailureIsNotSwallowedOrMaskedAsBusinessRejection() {
        BuyerCatalogQueryService service = mock(BuyerCatalogQueryService.class);
        when(service.pageCatalog(any())).thenThrow(new IllegalStateException("freshness-read-failure"));
        var impl = new BuyerCatalogQueryApiImpl(service);

        // 契约：RPC实现不吞技术失败也不包装为业务码，由RPC框架终结边界统一处理。
        assertThatThrownBy(() -> impl.pageCatalog(new BuyerCatalogPageReqDTO()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("freshness-read-failure");
    }
}
