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
    private String brandCode;
    private String brandName;
    private String englishName;
    private String logoUrl;
    private Short status;
    private Integer sort;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
