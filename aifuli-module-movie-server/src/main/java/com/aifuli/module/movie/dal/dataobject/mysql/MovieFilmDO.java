package com.aifuli.module.movie.dal.dataobject.mysql;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 电影平台表 movie_film，不继承租户或逻辑删除基类。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#3-主数据映射
 */
@Data
@TableName("movie_film")
public class MovieFilmDO {
    /** 影片主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 国家专资影片编码 (12位唯一业务键) */
    private String filmCode;

    /** 影片标准中文名 */
    private String filmName;

    /** 海报封面图URL */
    private String posterUrl;

    /** 影片时长 (分钟) */
    private Integer duration;

    /** 公映日期 */
    private LocalDate publishDate;

    /** 导演姓名 */
    private String director;

    /** 主要演职员表 */
    private String castActors;

    /** 影片剧情简介 */
    private String intro;

    /** 类型标签 (科幻/动作/剧情) */
    private String filmType;

    /** 大众评分 (如 9.3) */
    private BigDecimal score;

    /** 是否预售 0-热映中 1-预售待映 */
    private Integer isPresell;

    /** 状态 1-上架 0-下架 */
    private Integer status;

    /** 创建时间 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;

}
