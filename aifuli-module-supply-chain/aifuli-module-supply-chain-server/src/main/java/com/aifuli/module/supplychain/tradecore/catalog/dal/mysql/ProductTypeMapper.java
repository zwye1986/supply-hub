package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import org.apache.ibatis.annotations.Mapper;

/** 商品类型配置数据访问。 */
@Mapper
public interface ProductTypeMapper extends BaseMapperX<ProductTypeDO> {

    default ProductTypeDO selectByCode(String typeCode) {
        return selectOne(ProductTypeDO::getTypeCode, typeCode);
    }

    default ProductTypeDO selectByCodeForUpdate(String typeCode) {
        return selectOneForUpdate(ProductTypeDO::getTypeCode, typeCode);
    }
}
