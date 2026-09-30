package com.aifuli.module.supplychain.api.order;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderDetailQueryReqDTO;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderDetailRespDTO;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderPageReqDTO;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderSummaryRespDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 供应链总订单查询")
public interface BuyerSupplyChainOrderQueryApi {

    String PREFIX = ApiConstants.PREFIX + "/buyer/orders";

    @GetMapping(PREFIX + "/page")
    @Operation(summary = "分页查询供应链总订单摘要",
            description = "只读取唯一订单聚合总表，不在列表查询中访问场景详情或供应商")
    CommonResult<PageResult<SupplyChainOrderSummaryRespDTO>> pageOrders(
            @Parameter(description = "供应链总订单分页条件", required = true)
            @Valid @SpringQueryMap SupplyChainOrderPageReqDTO request);

    @GetMapping(PREFIX + "/detail")
    @Operation(summary = "查询供应链订单类型化详情",
            description = "详情模块不可用时仍成功返回总表摘要和 DETAIL_UNAVAILABLE 标记")
    CommonResult<SupplyChainOrderDetailRespDTO> getOrderDetail(
            @Parameter(description = "供应链订单详情查询条件", required = true)
            @Valid @SpringQueryMap SupplyChainOrderDetailQueryReqDTO request);
}
