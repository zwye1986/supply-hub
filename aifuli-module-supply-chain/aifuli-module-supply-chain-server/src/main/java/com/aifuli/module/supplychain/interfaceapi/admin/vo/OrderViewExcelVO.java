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

    /**
     * 平台订单号或采购商订单号
     */
    @ExcelProperty("订单号")
    private String orderNo;

    /**
     * 业务类型
     */
    @ExcelProperty("业务类型")
    private String bizType;

    /**
     * 订单履约状态
     */
    @ExcelProperty("订单状态")
    private String status;

    /**
     * 供应商标准名称
     */
    @ExcelProperty("供应商")
    private String supplierName;

    /**
     * 采购商/分销商名称
     */
    @ExcelProperty("分销商")
    private String distributorName;

    /**
     * 商品名称
     */
    @ExcelProperty("商品名称")
    private String productName;

    /**
     * 交易金额，单位分
     */
    @ExcelProperty("金额(分)")
    private Long amount;

    /**
     * 商品数量
     */
    @ExcelProperty("数量")
    private Long qty;

    /**
     * 订单创建时间，Asia/Shanghai
     */
    @ExcelProperty("下单时间")
    private LocalDateTime createdAt;
}
