package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购订单详情入口请求，订单归属和项目范围由可信上下文及订单查询确定。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}。</p>
 */
@Schema(description = "到店购订单详情入口请求")
@Data
public class OrderDetailEntryReqDTO implements Serializable {

    /** 供应链总订单稳定业务编号 */
    @Schema(description = "供应链总订单稳定业务编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "ORDER-001")
    @NotBlank(message = "订单号不能为空")
    @Size(max = 32, message = "订单号不能超过 32 个字符")
    private String orderNo;
}
