package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.catalog.BuyerCatalogQueryApi;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogChangeBatchRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogChangesReqDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogItemRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogPageReqDTO;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerCatalogQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.pojo.CommonResult.success;

@RestController
@Primary
@Validated
@RequiredArgsConstructor
public class BuyerCatalogQueryApiImpl implements BuyerCatalogQueryApi {

    private final BuyerCatalogQueryService queryService;

    @Override
    public CommonResult<PageResult<BuyerCatalogItemRespDTO>> pageCatalog(BuyerCatalogPageReqDTO request) {
        return success(queryService.pageCatalog(request));
    }

    @Override
    public CommonResult<BuyerCatalogChangeBatchRespDTO> getCatalogChanges(BuyerCatalogChangesReqDTO request) {
        return success(queryService.getCatalogChanges(request));
    }
}
