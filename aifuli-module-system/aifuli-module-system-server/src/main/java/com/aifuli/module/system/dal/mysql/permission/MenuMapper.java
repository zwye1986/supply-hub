package com.aifuli.module.system.dal.mysql.permission;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.aifuli.module.system.controller.admin.permission.vo.menu.MenuListReqVO;
import com.aifuli.module.system.dal.dataobject.permission.MenuDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MenuMapper extends BaseMapperX<MenuDO> {

    default MenuDO selectByParentIdAndName(Long parentId, String name) {
        return selectOne(MenuDO::getParentId, parentId, MenuDO::getName, name);
    }

    default Long selectCountByParentId(Long parentId) {
        return selectCount(MenuDO::getParentId, parentId);
    }

    default List<MenuDO> selectListByParentId(Long parentId) {
        return selectList(MenuDO::getParentId, parentId);
    }

    default List<MenuDO> selectList(MenuListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<MenuDO>()
                .eqIfPresent(MenuDO::getApplicationCode, reqVO.getApplicationCode())
                .likeIfPresent(MenuDO::getName, reqVO.getName())
                .eqIfPresent(MenuDO::getStatus, reqVO.getStatus()));
    }

    default List<MenuDO> selectListByPermission(String permission) {
        return selectList(MenuDO::getPermission, permission);
    }

    default MenuDO selectByComponentName(String componentName) {
        return selectOne(MenuDO::getComponentName, componentName);
    }

}
