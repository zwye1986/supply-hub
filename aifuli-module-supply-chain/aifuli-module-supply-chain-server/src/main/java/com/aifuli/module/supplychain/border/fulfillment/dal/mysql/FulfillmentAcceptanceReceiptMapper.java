package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAcceptanceReceiptDO;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface FulfillmentAcceptanceReceiptMapper extends BaseMapperX<FulfillmentAcceptanceReceiptDO> {

    default FulfillmentAcceptanceReceiptDO selectByBusinessKey(Long buyerId, String buyerOrderNo) {
        return selectOne(FulfillmentAcceptanceReceiptDO::getBuyerId, buyerId,
                FulfillmentAcceptanceReceiptDO::getBuyerOrderNo, buyerOrderNo);
    }

    default FulfillmentAcceptanceReceiptDO selectByBusinessKeyForUpdate(Long buyerId, String buyerOrderNo) {
        return selectOneForUpdate(new LambdaQueryWrapper<FulfillmentAcceptanceReceiptDO>()
                .eq(FulfillmentAcceptanceReceiptDO::getBuyerId, buyerId)
                .eq(FulfillmentAcceptanceReceiptDO::getBuyerOrderNo, buyerOrderNo));
    }

    default FulfillmentAcceptanceReceiptDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(FulfillmentAcceptanceReceiptDO::getId, id);
    }

    default int reopenRetryable(Long id, long expectedVersion, int listingVersionNo,
                                byte[] requestFingerprint, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<FulfillmentAcceptanceReceiptDO>()
                .set(FulfillmentAcceptanceReceiptDO::getListingVersionNo, listingVersionNo)
                .set(FulfillmentAcceptanceReceiptDO::getListingVersionId, null)
                .set(FulfillmentAcceptanceReceiptDO::getRequestFingerprint, requestFingerprint)
                .setSql("attempt_no = attempt_no + 1")
                .set(FulfillmentAcceptanceReceiptDO::getStatus, AcceptanceReceiptStatus.PROCESSING.getCode())
                .set(FulfillmentAcceptanceReceiptDO::getRejectionCode, null)
                .set(FulfillmentAcceptanceReceiptDO::getRejectedAt, null)
                .set(FulfillmentAcceptanceReceiptDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(FulfillmentAcceptanceReceiptDO::getId, id)
                .eq(FulfillmentAcceptanceReceiptDO::getVersion, expectedVersion)
                .eq(FulfillmentAcceptanceReceiptDO::getStatus, AcceptanceReceiptStatus.REJECTED_RETRYABLE.getCode()));
    }

    default int markAccepted(Long id, Long listingVersionId, Long orderId, String orderNo,
                             String requestNo, LocalDateTime acceptedAt) {
        return update(null, new LambdaUpdateWrapper<FulfillmentAcceptanceReceiptDO>()
                .set(FulfillmentAcceptanceReceiptDO::getListingVersionId, listingVersionId)
                .set(FulfillmentAcceptanceReceiptDO::getStatus, AcceptanceReceiptStatus.ACCEPTED.getCode())
                .set(FulfillmentAcceptanceReceiptDO::getResultOrderId, orderId)
                .set(FulfillmentAcceptanceReceiptDO::getResultOrderNo, orderNo)
                .set(FulfillmentAcceptanceReceiptDO::getResultFulfillmentRequestNo, requestNo)
                .set(FulfillmentAcceptanceReceiptDO::getAcceptedAt, acceptedAt)
                .set(FulfillmentAcceptanceReceiptDO::getUpdatedAt, acceptedAt)
                .setSql("version = version + 1")
                .eq(FulfillmentAcceptanceReceiptDO::getId, id)
                .eq(FulfillmentAcceptanceReceiptDO::getStatus, AcceptanceReceiptStatus.PROCESSING.getCode()));
    }

    default int markRejected(Long id, Long listingVersionId, short status, String rejectionCode,
                             LocalDateTime rejectedAt) {
        return update(null, new LambdaUpdateWrapper<FulfillmentAcceptanceReceiptDO>()
                .set(FulfillmentAcceptanceReceiptDO::getListingVersionId, listingVersionId)
                .set(FulfillmentAcceptanceReceiptDO::getStatus, status)
                .set(FulfillmentAcceptanceReceiptDO::getRejectionCode, rejectionCode)
                .set(FulfillmentAcceptanceReceiptDO::getRejectedAt, rejectedAt)
                .set(FulfillmentAcceptanceReceiptDO::getUpdatedAt, rejectedAt)
                .setSql("version = version + 1")
                .eq(FulfillmentAcceptanceReceiptDO::getId, id)
                .eq(FulfillmentAcceptanceReceiptDO::getStatus, AcceptanceReceiptStatus.PROCESSING.getCode()));
    }
}
