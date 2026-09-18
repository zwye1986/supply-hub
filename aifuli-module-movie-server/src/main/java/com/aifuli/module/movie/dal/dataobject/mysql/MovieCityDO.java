package com.aifuli.module.movie.dal.dataobject.mysql;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 电影平台表 movie_city，不继承租户或逻辑删除基类。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#3-主数据映射
 */
@Data
@TableName("movie_city")
public class MovieCityDO {
    /** 城市主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 行政区划统一代码 (如 110100) */
    private String cityCode;

    /** 城市标准名称 (如 北京市) */
    private String cityName;

    /** 拼音全拼 (beijing) */
    private String pinYin;

    /** 首字母索引 (B) */
    private String firstLetter;

    /** 是否热门城市 0-否 1-是 */
    private Integer isHot;

    /** 状态 1-启用 0-禁用 */
    private Integer status;

    /** 创建时间 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;

}
