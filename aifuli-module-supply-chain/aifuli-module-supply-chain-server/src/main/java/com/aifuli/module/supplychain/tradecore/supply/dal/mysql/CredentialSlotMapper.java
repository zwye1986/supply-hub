package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CredentialSlotMapper extends BaseMapperX<CredentialSlotDO> {
    default CredentialSlotDO selectByNo(String slotNo) {
        return selectOne(CredentialSlotDO::getSlotNo, slotNo);
    }
}
