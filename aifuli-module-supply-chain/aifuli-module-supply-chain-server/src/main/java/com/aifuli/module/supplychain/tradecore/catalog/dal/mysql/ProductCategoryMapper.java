package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductCategoryMapper extends BaseMapperX<ProductCategoryDO> {
    default ProductCategoryDO selectByCode(String code) {
        return selectOne(ProductCategoryDO::getCategoryCode, code);
    }
}
