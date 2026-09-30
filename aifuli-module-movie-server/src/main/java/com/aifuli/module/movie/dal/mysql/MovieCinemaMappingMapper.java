package com.aifuli.module.movie.dal.mysql;

import com.aifuli.module.movie.dal.dataobject.mysql.MovieCinemaMappingDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** movie_cinema_mapping 持久化接口，条件查询使用 LambdaWrapper 绑定列。 */
@Mapper
public interface MovieCinemaMappingMapper extends BaseMapper<MovieCinemaMappingDO> {
}
