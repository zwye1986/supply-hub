package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSettlementAccountDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierSettlementAccountMapper extends BaseMapperX<SupplierSettlementAccountDO> {
    default SupplierSettlementAccountDO selectBySupplierId(Long supplierId) {
        return selectOne(SupplierSettlementAccountDO::getSupplierId, supplierId);
    }
}
