package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.border.order.application.SupplierOrderAdminQueryService;
import com.aifuli.module.supplychain.border.order.application.SupplierOrderAdminQueryService.SupplierOrderPageQuery;
import com.aifuli.module.supplychain.border.order.application.SupplierOrderAdminQueryService.SupplierOrderView;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
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
 * 供应商订单管理端只读 REST。一行对应一次 B 端履约尝试，不是平台总单。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-supplier-order-admin-readonly-design.md#3-rest}。
 */
@Slf4j
@Tag(name = "管理后台 - 供应链供应商订单")
@RestController
@RequestMapping("/supply-chain/supplier-orders")
@Validated
@RequiredArgsConstructor
public class SupplierOrderAdminController {

    private final SupplierOrderAdminQueryService queryService;

    @GetMapping
    @PreAuthorize(ORDER_QUERY_EXPRESSION)
    @Operation(summary = "分页查询供应商履约尝试",
            description = "只读 sc_b_order；O2O/INSTORE 不进入本列表。金额单位为分。"
                    + "前端关 mock 后应调用本资源，不得用 /orders?source=SUPPLIER 混聚合。")
    public CommonResult<PageResult<SupplierOrderView>> page(
            @Parameter(description = "B端履约尝试稳定业务编号，精确匹配")
            @Size(max = 32) @RequestParam(required = false) String bOrderNo,
            @Parameter(description = "供应链中台订单号，精确匹配")
            @Size(max = 32) @RequestParam(required = false) String orderNo,
            @Parameter(description = "受理时采购商编码快照，精确匹配")
            @Size(max = 32) @RequestParam(required = false) String buyerCode,
            @Parameter(description = "尝试供应商业务编码，精确匹配")
            @Size(max = 32) @RequestParam(required = false) String supplierCode,
            @Parameter(description = "履约状态枚举名")
            @RequestParam(required = false) String fulfillmentStatus,
            @Parameter(description = "尝试创建时间下界，包含")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime createdFrom,
            @Parameter(description = "尝试创建时间上界，包含")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime createdTo,
            @Parameter(description = "页码，从1开始")
            @Min(1) @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数，最大100")
            @Min(1) @Max(100) @RequestParam(required = false) Integer pageSize) {
        log.info("供应商订单分页入口 userId={} bOrderNo={} orderNo={} buyerCode={} supplierCode={} "
                        + "fulfillmentStatus={} createdFrom={} createdTo={} pageNo={} pageSize={}",
                getLoginUserId(), bOrderNo, orderNo, buyerCode, supplierCode, fulfillmentStatus,
                createdFrom, createdTo, pageNo, pageSize);
        SupplierOrderPageQuery query = new SupplierOrderPageQuery(bOrderNo, orderNo, buyerCode, supplierCode,
                ClosedEnums.parse(BOrderFulfillmentStatus.class, fulfillmentStatus),
                createdFrom, createdTo, pageNo, pageSize);
        return success(queryService.page(query));
    }

    @GetMapping("/{bOrderNo}")
    @PreAuthorize(ORDER_QUERY_EXPRESSION)
    @Operation(summary = "查询供应商履约尝试详情",
            description = "与列表同一映射；不返回卡密、幂等键或供应商原始报文。")
    public CommonResult<SupplierOrderView> get(
            @Parameter(description = "B端履约尝试稳定业务编号")
            @Size(max = 32) @PathVariable String bOrderNo) {
        log.info("供应商订单详情入口 userId={} bOrderNo={}", getLoginUserId(), bOrderNo);
        return success(queryService.get(bOrderNo));
    }
}
