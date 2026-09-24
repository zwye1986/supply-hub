package com.aifuli.module.supplychain.interfaceapi.admin.vo;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 订单前端契约 Excel 导出行。列 = 平台订单列表列，金额单位为分（设计 §3.5）。
 * 设计见
 * {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#35-导出}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderViewExcelVO {

    @ExcelProperty("订单号")
    private String orderNo;

    @ExcelProperty("业务类型")
    private String bizType;

    @ExcelProperty("订单状态")
    private String status;

    @ExcelProperty("供应商")
    private String supplierName;

    @ExcelProperty("分销商")
    private String distributorName;

    @ExcelProperty("商品名称")
    private String productName;

    @ExcelProperty("金额(分)")
    private Long amount;

    @ExcelProperty("数量")
    private Long qty;

    @ExcelProperty("下单时间")
    private LocalDateTime createdAt;
}
