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
 * 供货配置一行上的多种供货模式。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#41-供货模式-supplymode}。
 */
@TableName("sc_supplier_supply_scope_mode")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierSupplyScopeModeDO {
    /** 供货配置模式行主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 供货配置主键，逻辑引用sc_supplier_supply_scope.id */
    private Long supplyScopeId;
    /** 供货模式 */
    private String supplyMode;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
