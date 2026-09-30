package com.aifuli.module.movie.dal.dataobject.mysql;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 电影平台表 movie_film_mapping，不继承租户或逻辑删除基类。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#3-主数据映射
 */
@Data
@TableName("movie_film_mapping")
public class MovieFilmMappingDO {
    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 供应商渠道 (jy, jc) */
    private String channel;

    /** 供应商原始影片ID */
    private String supplierFilmId;

    /** 关联的标准影片ID */
    private Long standardFilmId;

    /** 专资编码快照 */
    private String filmCode;

    /** 创建时间 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

}
