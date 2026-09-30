package com.aifuli.module.movie.dal.dataobject.mysql;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 电影平台表 movie_cinema，不继承租户或逻辑删除基类。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#3-主数据映射
 */
@Data
@TableName("movie_cinema")
public class MovieCinemaDO {
    /** 影院主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 国家专资办影院编码 (8位唯一业务键) */
    private String cinemaCode;

    /** 影院标准名称 */
    private String cinemaName;

    /** 所属标准城市ID */
    private Long standardCityId;

    /** 城市名称冗余 */
    private String cityName;

    /** 行政区名称 (如 朝阳区) */
    private String regionName;

    /** 影院详细物理地址 */
    private String address;

    /** 百度/高德经度 */
    private BigDecimal longitude;

    /** 百度/高德纬度 */
    private BigDecimal latitude;

    /** 影院联系电话 */
    private String phone;

    /** 状态 1-正常营业 0-停业下架 */
    private Integer status;

    /** 创建时间 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;

}
