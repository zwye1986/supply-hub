package com.aifuli.module.movie.dal.dataobject.mysql;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 电影平台表 movie_city_mapping，不继承租户或逻辑删除基类。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#3-主数据映射
 */
@Data
@TableName("movie_city_mapping")
public class MovieCityMappingDO {
    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 供应商渠道编码 (jy, jc) */
    private String channel;

    /** 供应商原始城市ID */
    private String supplierCityId;

    /** 供应商原始城市名 */
    private String supplierCityName;

    /** 绑定的标准城市ID (关联 movie_city.id) */
    private Long standardCityId;

    /** 创建时间 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

}
