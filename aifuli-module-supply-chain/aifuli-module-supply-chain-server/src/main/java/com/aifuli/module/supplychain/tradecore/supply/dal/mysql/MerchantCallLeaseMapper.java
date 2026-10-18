package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.MerchantCallLeaseDO;
import org.apache.ibatis.annotations.Mapper;

/** 额度服务持有商户根锁后访问租约，Lambda绑定列避免SQL漂移。 */
@Mapper
public interface MerchantCallLeaseMapper extends BaseMapperX<MerchantCallLeaseDO> { }
