package com.aifuli.module.movie.dal.dataobject.mysql;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 电影平台表 movie_cinema_mapping，不继承租户或逻辑删除基类。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#3-主数据映射
 */
@Data
@TableName("movie_cinema_mapping")
public class MovieCinemaMappingDO {
    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 供应商渠道编码 (jy, jc) */
    private String channel;

    /** 供应商原始影院ID */
    private String supplierCinemaId;

    /** 供应商原始影院名 */
    private String supplierCinemaName;

    /** 标准影院ID (关联 movie_cinema.id) */
    private Long standardCinemaId;

    /** 专资编码快照 */
    private String cinemaCode;

    /** 该渠道该影院状态 1-可售 0-不可售 */
    private Integer status;

    /** 创建时间 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

}
