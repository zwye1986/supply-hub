package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.framework.excel.core.util.ExcelUtils;
import com.aifuli.module.supplychain.border.order.application.OrderViewQueryService;
import com.aifuli.module.supplychain.border.order.application.OrderViewQueryService.OrderRouteRecordView;
import com.aifuli.module.supplychain.border.order.application.OrderViewQueryService.OrderView;
import com.aifuli.module.supplychain.border.order.application.OrderViewQueryService.OrderViewPageQuery;
import com.aifuli.module.supplychain.interfaceapi.admin.vo.OrderViewExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ORDER_EXPORT_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ORDER_QUERY_EXPRESSION;

/**
 * 订单管理前端契约 REST（order-views）：对齐前端 OrderVO / OrderRouteRecordVO。
 * 旧 /supply-chain/orders、/supply-chain/supplier-orders 端点完全不动（设计 §3.1）。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#3-订单管理端对齐设计}。</p>
 */
@Slf4j
@Tag(name = "管理后台 - 供应链订单(前端契约)")
@RestController
@RequestMapping("/supply-chain/order-views")
@Validated
@RequiredArgsConstructor
public class OrderViewController {

    private final OrderViewQueryService queryService;

    @GetMapping
    @PreAuthorize(ORDER_QUERY_EXPRESSION)
    @Operation(summary = "分页查询订单(前端契约)",
            description = "对齐前端 OrderVO 结构；金额单位为分，前端展示层换算；"
                    + "不支持解密资产，资产仅详情接口返回。")
    public CommonResult<PageResult<OrderView>> page(
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
            @Parameter(description = "业务类型多选：VIRTUAL_CARD/VIRTUAL_RECHARGE/DINING/MOVIE/HEMA/INSTORE")
            @Size(max = 6) @RequestParam(required = false) List<String> bizTypes,
            @Parameter(description = "平台订单状态多选：WAIT_PAY/PROCESSING/SUCCESS/FAILED/CLOSED")
            @Size(max = 5) @RequestParam(required = false) List<String> statuses,
            @Parameter(description = "供应商名称快照，多选 IN")
            @Size(max = 20) @RequestParam(required = false) List<String> supplierNames,
            @Parameter(description = "分销商名称快照，多选 IN")
            @Size(max = 20) @RequestParam(required = false) List<String> distributorNames,
            @Parameter(description = "商品名称，模糊匹配")
            @Size(max = 128) @RequestParam(required = false) String productName,
            @Parameter(description = "下单人手机号，模糊匹配")
            @Size(max = 32) @RequestParam(required = false) String buyerKeyword,
            @Parameter(description = "B端供应商订单号，精确匹配（按子单反查总单）")
            @Size(max = 32) @RequestParam(required = false) String supplierOrderNo,
            @Parameter(description = "分销商订单号，精确匹配")
            @Size(max = 128) @RequestParam(required = false) String distributorOrderNo,
            @Parameter(description = "订单受理时间下界，包含（前端参数名）")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime createFrom,
            @Parameter(description = "订单受理时间上界，包含（前端参数名）")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime createTo,
            @Parameter(description = "页码，从1开始")
            @Min(1) @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数，最大100")
            @Min(1) @Max(100) @RequestParam(required = false) Integer pageSize) {
        log.info("订单前端契约分页入口 userId={} orderNo={} bizTypes={} statuses={} createFrom={} createTo={}",
                getLoginUserId(), orderNo, bizTypes, statuses, createFrom, createTo);
        OrderViewPageQuery query = new OrderViewPageQuery(orderNo, buyerOrderNo, buyerCode, supplierCode,
                skuCode, bizTypes, statuses, supplierNames, distributorNames, productName, buyerKeyword,
                supplierOrderNo, distributorOrderNo, createFrom, createTo, pageNo, pageSize);
        return success(queryService.page(query));
    }

    @GetMapping("/{orderNo}")
    @PreAuthorize(ORDER_QUERY_EXPRESSION)
    @Operation(summary = "查询订单详情(前端契约)",
            description = "source=PLATFORM(默认)按平台总单号；source=SUPPLIER按B端供应商订单号。"
                    + "详情会解密交付资产并返回明文（卡号卡密/券码/直充信息），每次明文返回均记录敏感访问审计；"
                    + "TODO(安全)：明文返回为过渡方案，后续版本将改为授权查询 + 默认脱敏。金额单位为分。")
    public CommonResult<OrderView> get(
            @Parameter(description = "source=PLATFORM时为平台总单号；source=SUPPLIER时为B端供应商订单号")
            @Size(max = 32) @PathVariable String orderNo,
            @Parameter(description = "视角：PLATFORM平台总单（默认）/ SUPPLIER供应商子单")
            @RequestParam(required = false, defaultValue = "PLATFORM") String source) {
        log.info("订单前端契约详情入口 userId={} orderNo={} source={}", getLoginUserId(), orderNo, source);
        return success(queryService.get(orderNo, source, getLoginUserId()));
    }

    @GetMapping("/{orderNo}/route-record")
    @PreAuthorize(ORDER_QUERY_EXPRESSION)
    @Operation(summary = "查询订单路由记录(前端契约)",
            description = "对齐前端 OrderRouteRecordVO：概要 + 按尝试序号升序的步骤；"
                    + "O2O/INSTORE 未进入标准路由，返回概要与空 steps；金额单位为分。")
    public CommonResult<OrderRouteRecordView> getRouteRecord(
            @Parameter(description = "供应链中台订单号")
            @Size(max = 32) @PathVariable String orderNo) {
        log.info("订单前端契约路由记录入口 userId={} orderNo={}", getLoginUserId(), orderNo);
        return success(queryService.routeRecord(orderNo));
    }

    @GetMapping("/export")
    @PreAuthorize(ORDER_EXPORT_EXPRESSION)
    @Operation(summary = "导出订单Excel(前端契约)",
            description = "复用分页全部筛选条件；导出上限 10000 行，超限报错提示缩小范围；"
                    + "金额单位为分；导出行为会记录入口日志（筛选参数脱敏 + 导出行数）。")
    public void export(
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
            @Parameter(description = "业务类型多选：VIRTUAL_CARD/VIRTUAL_RECHARGE/DINING/MOVIE/HEMA/INSTORE")
            @Size(max = 6) @RequestParam(required = false) List<String> bizTypes,
            @Parameter(description = "平台订单状态多选：WAIT_PAY/PROCESSING/SUCCESS/FAILED/CLOSED")
            @Size(max = 5) @RequestParam(required = false) List<String> statuses,
            @Parameter(description = "供应商名称快照，多选 IN")
            @Size(max = 20) @RequestParam(required = false) List<String> supplierNames,
            @Parameter(description = "分销商名称快照，多选 IN")
            @Size(max = 20) @RequestParam(required = false) List<String> distributorNames,
            @Parameter(description = "商品名称，模糊匹配")
            @Size(max = 128) @RequestParam(required = false) String productName,
            @Parameter(description = "下单人手机号，模糊匹配")
            @Size(max = 32) @RequestParam(required = false) String buyerKeyword,
            @Parameter(description = "B端供应商订单号，精确匹配（按子单反查总单）")
            @Size(max = 32) @RequestParam(required = false) String supplierOrderNo,
            @Parameter(description = "分销商订单号，精确匹配")
            @Size(max = 128) @RequestParam(required = false) String distributorOrderNo,
            @Parameter(description = "订单受理时间下界，包含（前端参数名）")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime createFrom,
            @Parameter(description = "订单受理时间上界，包含（前端参数名）")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime createTo,
            HttpServletResponse response) throws IOException {
        log.info("订单前端契约导出入口 userId={} orderNo={} bizTypes={} statuses={} createFrom={} createTo={}",
                getLoginUserId(), orderNo, bizTypes, statuses, createFrom, createTo);
        OrderViewPageQuery query = new OrderViewPageQuery(orderNo, buyerOrderNo, buyerCode, supplierCode,
                skuCode, bizTypes, statuses, supplierNames, distributorNames, productName, buyerKeyword,
                supplierOrderNo, distributorOrderNo, createFrom, createTo, null, null);
        List<OrderViewExcelVO> rows = queryService.exportRows(query).stream()
                .map(view -> new OrderViewExcelVO(view.orderNo(), view.bizType(), view.status(),
                        view.supplierName(), view.distributorName(),
                        view.items().isEmpty() ? null : view.items().get(0).skuName(),
                        view.amount(), view.qty(), view.createdAt()))
                .toList();
        ExcelUtils.write(response, "供应链订单导出.xlsx", "订单", OrderViewExcelVO.class, rows);
    }
}
