package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.MerchantQuotaAuditDO;
import org.apache.ibatis.annotations.Mapper;

/** 配置事务追加与管理端只读分页；不访问其它进程的数据。 */
@Mapper
public interface MerchantQuotaAuditMapper extends BaseMapperX<MerchantQuotaAuditDO> { }
