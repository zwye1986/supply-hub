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

    /**
     * 按项目永久键 (projectId, buyerOrderNo) 查询受理回执（普通一致性读）。
     * 新受理幂等键为项目维度；历史 NULL 项目回执不经本入口访问。
     * 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#3-回执竞争、重试和真实返回。
     */
    default FulfillmentAcceptanceReceiptDO selectByProjectKey(Long projectId, String buyerOrderNo) {
        return selectOne(FulfillmentAcceptanceReceiptDO::getProjectId, projectId,
                FulfillmentAcceptanceReceiptDO::getBuyerOrderNo, buyerOrderNo);
    }

    /** 按项目永久键的当前读锁定（FOR UPDATE）：claim 竞争裁决与重探测均使用最新提交版本。 */
    default FulfillmentAcceptanceReceiptDO selectByProjectKeyForUpdate(Long projectId, String buyerOrderNo) {
        return selectOneForUpdate(new LambdaQueryWrapper<FulfillmentAcceptanceReceiptDO>()
                .eq(FulfillmentAcceptanceReceiptDO::getProjectId, projectId)
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

    /** 受理成功收敛：固化报价绑定与订单/请求引用，供查单与重放校验完整引用链。 */
    default int markAccepted(Long id, Long listingVersionId, Long pricingQuoteId, Long orderId, String orderNo,
                             String requestNo, LocalDateTime acceptedAt) {
        return update(null, new LambdaUpdateWrapper<FulfillmentAcceptanceReceiptDO>()
                .set(FulfillmentAcceptanceReceiptDO::getListingVersionId, listingVersionId)
                .set(FulfillmentAcceptanceReceiptDO::getPricingQuoteId, pricingQuoteId)
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
