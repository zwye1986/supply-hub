package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SupplierRoutingRecordDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierRoutingRecordMapper extends BaseMapperX<SupplierRoutingRecordDO> {

    default int attachBOrder(Long id, Long bOrderId) {
        return update(null, new LambdaUpdateWrapper<SupplierRoutingRecordDO>()
                .set(SupplierRoutingRecordDO::getAttemptBOrderId, bOrderId)
                .eq(SupplierRoutingRecordDO::getId, id)
                .eq(SupplierRoutingRecordDO::getSelectedFlag, true)
                .isNull(SupplierRoutingRecordDO::getAttemptBOrderId));
    }
}
