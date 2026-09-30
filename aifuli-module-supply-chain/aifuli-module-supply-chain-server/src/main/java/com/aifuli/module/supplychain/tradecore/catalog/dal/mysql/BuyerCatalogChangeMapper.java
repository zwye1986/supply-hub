package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerCatalogChangeDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BuyerCatalogChangeMapper extends BaseMapperX<BuyerCatalogChangeDO> {

    default List<BuyerCatalogChangeDO> selectAfterCursor(Long buyerId, long cursor, int limit) {
        return selectList(new LambdaQueryWrapper<BuyerCatalogChangeDO>()
                .eq(BuyerCatalogChangeDO::getBuyerId, buyerId)
                .gt(BuyerCatalogChangeDO::getId, cursor)
                .orderByAsc(BuyerCatalogChangeDO::getId)
                .last("LIMIT " + limit));
    }
}
