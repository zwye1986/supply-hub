package com.aifuli.module.supplychain.interfaceapi.open.project;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.api.fulfillment.dto.ProjectStandardOrderCreateRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.ProjectStandardOrderQueryRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.ProjectStandardOrderAssetsRespDTO;
import com.aifuli.module.supplychain.application.facade.ProjectStandardOrderCommandFacade;
import com.aifuli.module.supplychain.application.facade.ProjectStandardOrderQueryFacade;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * 平台SKU v2三个精确POST入口；读取已验签原字节，闭包与操作上下文不能借用v1。
 * 见 docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#3-http-与签名合同。
 */
@RestController
@RequestMapping(ProjectAuthOperations.STANDARD_ORDERS_V2_PATH)
@RequiredArgsConstructor
public class ProjectPlatformSkuOrderController {
    private final ProjectCallContextAccessor contextAccessor;
    private final ProjectStandardOrderCommandFacade commands;
    private final ProjectStandardOrderQueryFacade queries;
    private final ProjectStandardHttpRequestParser parser;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public CommonResult<ProjectStandardOrderCreateRespDTO> create(HttpServletRequest request, @RequestBody byte[] body) {
        ProjectStandardOrderController.requireNoQueryString(request);
        return CommonResult.success(commands.createV2(contextAccessor.requireStandardProductContext(
                ProjectAuthOperations.STANDARD_ORDER_CREATE_V2), parser.parseCreateV2(body)));
    }

    @PostMapping(value = "/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CommonResult<ProjectStandardOrderQueryRespDTO> query(HttpServletRequest request, @RequestBody byte[] body) {
        ProjectStandardOrderController.requireNoQueryString(request);
        return CommonResult.success(queries.queryV2(contextAccessor.requireStandardProductContext(
                ProjectAuthOperations.STANDARD_ORDER_QUERY_V2), parser.parseBuyerOrderNo(body)));
    }

    @PostMapping(value = "/assets/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CommonResult<ProjectStandardOrderAssetsRespDTO> assets(HttpServletRequest request, @RequestBody byte[] body) {
        ProjectStandardOrderController.requireNoQueryString(request);
        return CommonResult.success(queries.assetsV2(contextAccessor.requireStandardProductContext(
                ProjectAuthOperations.STANDARD_ORDER_ASSET_QUERY_V2), parser.parseBuyerOrderNo(body)));
    }
}
