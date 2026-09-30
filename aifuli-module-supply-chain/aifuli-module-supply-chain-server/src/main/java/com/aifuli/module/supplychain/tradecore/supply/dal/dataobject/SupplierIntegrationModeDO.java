package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 供应商对接方式多选。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#13-模块边界}。
 */
@TableName("sc_supplier_integration_mode")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierIntegrationModeDO {

    /** 供应商对接方式行主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 供应商内部主键，逻辑引用sc_supplier.id */
    private Long supplierId;
    /** 对接方式：API、H5、MANUAL */
    private String integrationMode;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
