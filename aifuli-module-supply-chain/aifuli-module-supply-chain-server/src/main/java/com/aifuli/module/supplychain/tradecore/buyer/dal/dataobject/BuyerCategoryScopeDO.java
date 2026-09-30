package com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 分销商可售品类与商品类型。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#13-模块边界}。
 * 三码语义见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#43-分销商范围表}。
 */
@TableName("sc_buyer_category_scope")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerCategoryScopeDO {

    /** 分销商可售类目行主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购商内部主键，逻辑引用sc_buyer.id */
    private Long buyerId;
    /** 范围维度：SELLABLE可售品类 PRODUCT_TYPE商品类型，见BuyerScopeKind */
    private String scopeKind;
    /** 范围编码：可售品类仍使用 SellableCategory；商品类型从 sc_product_type 动态取值。 */
    private String scopeCode;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
