package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainAftersaleStatus;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.order.application.OrderAdminQueryService;
import com.aifuli.module.supplychain.border.order.application.OrderAdminQueryService.OrderDetail;
import com.aifuli.module.supplychain.border.order.application.OrderAdminQueryService.OrderPageQuery;
import com.aifuli.module.supplychain.border.order.application.OrderAdminQueryService.OrderSummary;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderType;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ORDER_QUERY_EXPRESSION;

/**
 * 平台订单管理端只读 REST。列表只返回总表摘要，详情以安全 tagged union 暴露现有三类场景字段。
 * 设计见 {@code docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md#3-rest-契约}。
 */
@Slf4j
@Tag(name = "管理后台 - 供应链订单")
@RestController
@RequestMapping("/supply-chain/orders")
@Validated
@RequiredArgsConstructor
public class OrderAdminController {

    private final OrderAdminQueryService queryService;

    @GetMapping
    @PreAuthorize(ORDER_QUERY_EXPRESSION)
    @Operation(summary = "分页查询供应链订单",
            description = "只查询供应链总订单表，不加载场景详情或调用外部服务；默认按受理时间倒序。")
    public CommonResult<PageResult<OrderSummary>> page(
            @Parameter(description = "供应链中台订单号，精确匹配")
            @Size(max = 32) @RequestParam(required = false) String orderNo,
            @Parameter(description = "采购商订单号，精确匹配")
            @Size(max = 128) @RequestParam(required = false) String buyerOrderNo,
            @Parameter(description = "受理时采购商编码快照，精确匹配")
            @Size(max = 32) @RequestParam(required = false) String buyerCode,
            @Parameter(description = "列表展示供应商编码快照，精确匹配")
            @Size(max = 32) @RequestParam(required = false) String supplierCode,
            @Parameter(description = "标准SKU编码快照，精确匹配")
            @Size(max = 32) @RequestParam(required = false) String skuCode,
            @Parameter(description = "订单类型：CARD/O2O/INSTORE")
            @RequestParam(required = false) String orderType,
            @Parameter(description = "公共主状态枚举名")
            @RequestParam(required = false) String orderStatus,
            @Parameter(description = "售后状态枚举名")
            @RequestParam(required = false) String aftersaleStatus,
            @Parameter(description = "订单受理时间下界，包含")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime createdFrom,
            @Parameter(description = "订单受理时间上界，包含")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime createdTo,
            @Parameter(description = "页码，从1开始")
            @Min(1) @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数，最大100")
            @Min(1) @Max(100) @RequestParam(required = false) Integer pageSize) {
        log.info("平台订单分页入口 userId={} orderNo={} buyerOrderNo={} buyerCode={} supplierCode={} skuCode={} "
                        + "orderType={} orderStatus={} aftersaleStatus={} createdFrom={} createdTo={} pageNo={} pageSize={}",
                getLoginUserId(), orderNo, buyerOrderNo, buyerCode, supplierCode, skuCode, orderType,
                orderStatus, aftersaleStatus, createdFrom, createdTo, pageNo, pageSize);
        OrderPageQuery query = new OrderPageQuery(orderNo, buyerOrderNo, buyerCode, supplierCode, skuCode,
                ClosedEnums.parse(SupplyChainOrderType.class, orderType),
                ClosedEnums.parse(SupplyChainOrderStatus.class, orderStatus),
                ClosedEnums.parse(SupplyChainAftersaleStatus.class, aftersaleStatus),
                createdFrom, createdTo, pageNo, pageSize);
        return success(queryService.page(query));
    }

    @GetMapping("/{orderNo}")
    @PreAuthorize(ORDER_QUERY_EXPRESSION)
    @Operation(summary = "查询供应链订单类型化详情",
            description = "详情缺失或类型未注册时仍成功返回总表摘要，并标记DETAIL_UNAVAILABLE；不返回敏感履约资产。")
    public CommonResult<OrderDetail> get(@Size(max = 32) @PathVariable String orderNo) {
        log.info("平台订单详情入口 userId={} orderNo={}", getLoginUserId(), orderNo);
        return success(queryService.get(orderNo));
    }
}
