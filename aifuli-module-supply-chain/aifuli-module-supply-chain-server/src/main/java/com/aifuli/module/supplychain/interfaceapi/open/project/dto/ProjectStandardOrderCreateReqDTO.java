package com.aifuli.module.supplychain.interfaceapi.open.project.dto;

import lombok.Data;

import java.util.Map;

/**
 * 项目标准下单经严格解析后的内部输入 DTO。
 *
 * <p>字段由专用局部解析器从已验签正文严格生成，避免全局宽松反序列化行为。
 * 详见 {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#1-controller与严格请求解析}。</p>
 */
@Data
public class ProjectStandardOrderCreateReqDTO {

    /** 采购商订单号，最大长度 128 */
    private String buyerOrderNo;

    /** 采购商侧 SKU 编码，最大长度 128 */
    private String buyerSkuCode;

    /** 履约数量，必须为严格整数 1 */
    private Integer quantity;

    /** 履约目标参数，平铺 String 映射 */
    private Map<String, String> target;

    /** 可选前次回执 CAS 版本号，正整数 */
    private Long previousReceiptVersion;
}
