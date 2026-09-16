package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sc_product_category")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCategoryDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String categoryCode;
    private String categoryName;
    /** 商品类型编码，由sc_product_type动态维护 */
    private String productType;
    /** 分类描述 */
    private String description;
    private Long parentId;
    private Short categoryLevel;
    private Integer sort;
    /** 前端展示可见性: 1-显示, 0-隐藏 */
    private Short visible;
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
