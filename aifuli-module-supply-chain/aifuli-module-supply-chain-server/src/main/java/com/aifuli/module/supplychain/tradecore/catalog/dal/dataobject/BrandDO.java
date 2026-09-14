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

@TableName("sc_brand")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 平台品牌永久业务编码 */
    private String brandCode;
    /** 品牌中文或标准名称 */
    private String brandName;
    /** 品牌简称 */
    private String shortName;
    /** 品牌英文名称 */
    private String englishName;
    /** 品牌Logo地址 */
    private String logoUrl;
    /** 品牌介绍与详细说明 */
    private String description;
    private Short status;
    private Integer sort;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
