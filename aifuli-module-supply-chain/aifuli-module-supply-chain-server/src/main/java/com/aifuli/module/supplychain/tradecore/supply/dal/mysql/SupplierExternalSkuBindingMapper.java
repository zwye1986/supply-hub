package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierExternalSkuBindingDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierExternalSkuBindingMapper extends BaseMapperX<SupplierExternalSkuBindingDO> {

    default SupplierExternalSkuBindingDO selectByOfferIdForUpdate(Long offerId) {
        return selectOneForUpdate(SupplierExternalSkuBindingDO::getOfferId, offerId);
    }

    default SupplierExternalSkuBindingDO selectByConnectorAndExternalSku(Long connectorId, String externalSkuCode) {
        return selectOne(SupplierExternalSkuBindingDO::getConnectorId, connectorId,
                SupplierExternalSkuBindingDO::getExternalSkuCode, externalSkuCode);
    }
}
