package com.aifuli.module.mp.dal.mysql.tag;

import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.aifuli.module.mp.controller.admin.tag.vo.MpTagPageReqVO;
import com.aifuli.module.mp.dal.dataobject.tag.MpTagDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MpTagMapper extends BaseMapperX<MpTagDO> {

    default PageResult<MpTagDO> selectPage(MpTagPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MpTagDO>()
                .eqIfPresent(MpTagDO::getAccountId, reqVO.getAccountId())
                .likeIfPresent(MpTagDO::getName, reqVO.getName())
                .orderByDesc(MpTagDO::getId));
    }

    default List<MpTagDO> selectListByAccountId(Long accountId) {
        return selectList(MpTagDO::getAccountId, accountId);
    }

}
