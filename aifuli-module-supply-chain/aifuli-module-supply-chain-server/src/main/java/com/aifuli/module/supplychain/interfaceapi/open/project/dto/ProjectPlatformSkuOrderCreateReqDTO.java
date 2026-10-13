package com.aifuli.module.supplychain.interfaceapi.open.project.dto;

import lombok.Data;
import java.util.Map;

/** v2封闭下单输入；见 docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#32-创建请求闭包。 */
@Data
public class ProjectPlatformSkuOrderCreateReqDTO {
    /** 项目侧订单号，与可信projectId组成永久受理键 */
    private String buyerOrderNo;
    /** 格式3首次请求的平台SKU编码；不接受BPD/PLT/LST别名 */
    private String platformSkuCode;
    /** 请求数量，固定为1 */
    private Integer quantity;
    /** 履约目标参数，封闭Schema的平铺字符串映射 */
    private Map<String, String> target;
    /** 条件重试CAS版本号，必须为正整数 */
    private Long previousReceiptVersion;
}
