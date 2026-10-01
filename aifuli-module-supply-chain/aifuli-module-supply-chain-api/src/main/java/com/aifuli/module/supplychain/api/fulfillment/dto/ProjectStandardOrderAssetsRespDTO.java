package com.aifuli.module.supplychain.api.fulfillment.dto;

import lombok.Data;

import java.util.List;

/**
 * 项目标准订单封闭响应。设计见
 * docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#1-分层与可信输入。
 */
@Data
public class ProjectStandardOrderAssetsRespDTO {
    /** 采购商订单号 */
    private String buyerOrderNo;

    /** 供应链总订单稳定业务编号 */
    private String orderNo;

    /** 已授权可交付资产 */
    private List<ProjectStandardOrderAssetRespDTO> assets;

    /** 等待或人工处理提示；有资产时为空 */
    private String hint;

    /** 安全诊断摘要；任何交付值均不得进入日志。 */
    @Override
    public String toString() {
        return "ProjectStandardOrderAssetsRespDTO[sensitive values omitted]";
    }
}
