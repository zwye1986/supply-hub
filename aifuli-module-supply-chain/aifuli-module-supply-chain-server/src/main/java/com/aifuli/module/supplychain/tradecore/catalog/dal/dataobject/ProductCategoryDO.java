package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@TableName(value = "sc_product_category", autoResultMap = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCategoryDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String categoryCode;
    /** 分类节点内部全局短序号，供直接子分类编码父段引用 */
    private Short categoryNodeNo;
    private String categoryName;
    /** 商品类型编码，由sc_product_type动态维护 */
    private String productType;
    /** 商品类型编码列表(JSON数组)，由sc_product_type动态维护 */
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private List<String> typeCodes;
    /** 分类描述 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    /** 父类目内部主键，逻辑引用本表id；挂到根时置为 null，必须允许更新为 null */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
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
