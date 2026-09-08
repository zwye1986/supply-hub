package com.aifuli.module.supplychain.api.catalog;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogChangeBatchRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogChangesReqDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogItemRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogPageReqDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 采购商标准商品池")
public interface BuyerCatalogQueryApi {

    String PREFIX = ApiConstants.PREFIX + "/buyer/catalog";

    @GetMapping(PREFIX + "/page")
    @Operation(summary = "分页查询采购商标准商品池",
            description = "只返回品牌、标准商品、履约类型、采购商价和可用性")
    CommonResult<PageResult<BuyerCatalogItemRespDTO>> pageCatalog(
            @Parameter(description = "采购商商品池分页条件", required = true)
            @Valid @SpringQueryMap BuyerCatalogPageReqDTO request);

    @GetMapping(PREFIX + "/changes")
    @Operation(summary = "按持久化游标拉取商品增量",
            description = "事件只负责唤醒；该接口返回的快照和 nextCursor 才是同步真相")
    CommonResult<BuyerCatalogChangeBatchRespDTO> getCatalogChanges(
            @Parameter(description = "采购商商品增量拉取条件", required = true)
            @Valid @SpringQueryMap BuyerCatalogChangesReqDTO request);
}
