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

/**
 * 平台商品类型配置。类型编码属于可运营扩展数据，不再由 Java 枚举封闭。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#25-新建平台商品类型表-sc_product_type}。
 */
@TableName("sc_product_type")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductTypeDO {
    /** 商品类型内部主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 商品类型业务编码 */
    private String typeCode;
    /** 商品类型名称 */
    private String typeName;
    /** 展示排序值，越小越靠前 */
    private Integer sort;
    /** 管理状态：10启用，20停用，90已归档 */
    private Short status;
    /** 类型备注与说明 */
    private String remark;
    /** 乐观锁版本号 */
    @Version
    private Long version;
    /** 创建人用户ID */
    private Long createdBy;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
    /** 最后修改人用户ID */
    private Long updatedBy;
    /** 最后修改时间，Asia/Shanghai */
    private LocalDateTime updatedAt;
}
