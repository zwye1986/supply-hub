package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.SkuSuggestedPriceStateDO;
import org.apache.ibatis.annotations.Mapper;

/** 只保存动态建议价结果；写入方必须已持有对应平台定价根锁。 */
@Mapper
public interface SkuSuggestedPriceStateMapper extends BaseMapperX<SkuSuggestedPriceStateDO> { }
