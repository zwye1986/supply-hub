package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 供货配置主表。供货模式已迁至 {@code sc_supplier_supply_scope_mode}。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#41-供货模式-supplymode}。
 * 三码语义见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#41-rest}。
 */
@TableName("sc_supplier_supply_scope")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierSupplyScopeDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long supplierId;
    /** 可售品类封闭枚举，见SellableCategory */
    private String sellableCategory;
    /** 商品类型封闭枚举，见ProductTypeTag */
    private String productType;
    /** 商品分类封闭枚举，见ProductClassification */
    private String productClassification;
    private Long maxQuantity;
    /** 允许最低起订量，预留字段；当前仅存储和回显。 */
    private Long minQuantity;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
