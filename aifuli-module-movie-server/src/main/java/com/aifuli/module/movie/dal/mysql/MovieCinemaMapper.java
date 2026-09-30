package com.aifuli.module.movie.dal.mysql;

import com.aifuli.module.movie.dal.dataobject.mysql.MovieCinemaDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** movie_cinema 持久化接口，条件查询使用 LambdaWrapper 绑定列。 */
@Mapper
public interface MovieCinemaMapper extends BaseMapper<MovieCinemaDO> {
}
