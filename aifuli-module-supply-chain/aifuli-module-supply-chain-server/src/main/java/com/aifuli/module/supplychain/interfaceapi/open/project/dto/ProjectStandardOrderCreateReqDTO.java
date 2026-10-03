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

    /** 采购商侧C端订单号，与buyer_id组成永久受理键 */
    private String buyerOrderNo;

    /** 首次请求固化的采购商商品编码 */
    private String buyerSkuCode;

    /** 请求数量，一期固定为1 */
    private Integer quantity;

    /** 履约目标参数，平铺 String 映射 */
    private Map<String, String> target;

    /** 回执状态及条件重试CAS版本号 */
    private Long previousReceiptVersion;
}
