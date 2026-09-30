package com.aifuli.module.supplychain.tradecore.account.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.FundReservationDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FundReservationMapper extends BaseMapperX<FundReservationDO> {

    default FundReservationDO selectByReference(Long buyerId, String referenceType, String referenceNo) {
        return selectOne(FundReservationDO::getBuyerId, buyerId,
                FundReservationDO::getReferenceType, referenceType,
                FundReservationDO::getReferenceNo, referenceNo);
    }

    default FundReservationDO selectByReferenceForUpdate(Long buyerId, String referenceType, String referenceNo) {
        return selectOneForUpdate(FundReservationDO::getBuyerId, buyerId,
                FundReservationDO::getReferenceType, referenceType,
                FundReservationDO::getReferenceNo, referenceNo);
    }

    default FundReservationDO selectByNoForUpdate(String reservationNo) {
        return selectOneForUpdate(FundReservationDO::getReservationNo, reservationNo);
    }
}
