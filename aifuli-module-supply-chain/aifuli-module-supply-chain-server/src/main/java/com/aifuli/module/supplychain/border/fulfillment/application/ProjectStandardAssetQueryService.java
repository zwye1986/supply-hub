package com.aifuli.module.supplychain.border.fulfillment.application;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.border.fulfillment.domain.ProjectStandardAssetAuditReason.*;
import static com.aifuli.module.supplychain.border.fulfillment.domain.SensitiveAccessAuditResultStatus.*;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.*;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.fulfillment.dto.*;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.StandardProductContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目资产查询编排：先完整授权，再解密，独立审计提交后才交付明文；任何错误均不返回部分列表。 设计见
 * docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#4-资产结果与封闭载荷。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectStandardAssetQueryService {
    private final ProjectStandardOrderQueryService queries;
    private final ProjectStandardAssetPayloadDecoder decoder;
    private final ProjectStandardAssetAuditService audits;
    private final BusinessNoGenerator numbers;

    /** 查询和解密不产生状态变更；日志只输出安全的业务标识与数量。 */
    public ProjectStandardOrderAssetsRespDTO query(
            StandardProductContext context, String buyerOrderNo) {
        log.debug("[项目资产查询] projectId={}, buyerOrderNo={}", context.projectId(), buyerOrderNo);
        String trace = numbers.next(BusinessNoPrefixes.AUD);
        String orderNo = null;
        Long requestId = 0L;
        var events = new ArrayList<ProjectStandardAssetAuditEvent>();
        ProjectStandardOrderAssetsRespDTO result = null;
        RuntimeException error = null;
        ProjectStandardAssetAuditReason reason = AUTHORIZED_QUERY;
        SensitiveAccessAuditResultStatus status = SUCCESS;
        try {
            var snapshot = queries.snapshot(context, buyerOrderNo);
            orderNo = snapshot.summary().getOrderNo();
            requestId = snapshot.request() == null ? 0L : snapshot.request().getId();
            var schema =
                    ProjectStandardOrderQueryService.schema(
                            snapshot.request() == null
                                    ? snapshot.receipt().getTargetSchemaCode()
                                    : snapshot.request().getTargetSchemaCode());
            if (schema != FulfillmentTargetSchemaCode.VOUCHER_DELIVERY_TARGET)
                throw exception(ASSET_QUERY_UNSUPPORTED_PRODUCT_TYPE);
            if (snapshot.order() == null
                    && snapshot.receipt().getStatus()
                            != AcceptanceReceiptStatus.PROCESSING.getCode())
                throw exception(ASSET_QUERY_ORDER_NOT_ACCEPTED);
            if (snapshot.summary().getOrderStatus() == SupplyChainOrderStatus.FAILED
                    || snapshot.summary().getOrderStatus() == SupplyChainOrderStatus.CANCELLED)
                throw exception(ASSET_QUERY_ORDER_TERMINATED);
            result = new ProjectStandardOrderAssetsRespDTO();
            result.setBuyerOrderNo(buyerOrderNo);
            result.setOrderNo(orderNo);
            var delivered = new ArrayList<ProjectStandardOrderAssetRespDTO>();
            for (var asset : snapshot.deliverableAssets()) {
                try {
                    delivered.add(decoder.decode(asset));
                    events.add(
                            new ProjectStandardAssetAuditEvent(
                                    SensitiveAccessAuditAccessType.ASSET_DECRYPT,
                                    asset.getId(),
                                    SUCCESS,
                                    AUTHORIZED_QUERY));
                } catch (RuntimeException failure) {
                    events.add(
                            new ProjectStandardAssetAuditEvent(
                                    SensitiveAccessAuditAccessType.ASSET_DECRYPT,
                                    asset.getId(),
                                    FAILED,
                                    failure instanceof ProjectStandardAssetQueryFailure safe
                                            ? safe.reason()
                                            : DECRYPT_FAILED));
                    throw failure;
                }
            }
            result.setAssets(List.copyOf(delivered));
            if (delivered.isEmpty()) {
                if (snapshot.summary().getOrderStatus() == SupplyChainOrderStatus.SUCCEEDED
                        || snapshot.summary().getFulfillmentStatus() == FulfillmentStatus.SUCCEEDED)
                    throw new ProjectStandardAssetQueryFailure(REFERENCE_INCONSISTENT);
                if (snapshot.summary().getOrderStatus() == SupplyChainOrderStatus.MANUAL
                        || snapshot.summary().getFulfillmentStatus() == FulfillmentStatus.MANUAL) {
                    result.setHint("订单正在人工处理中，请通过查单接口查看进度");
                    reason = MANUAL_WAITING;
                } else {
                    result.setHint("请等待，稍后重新获卡");
                    reason = WAITING;
                }
            }
        } catch (RuntimeException failure) {
            error = failure;
            reason = reason(failure);
            status = failure instanceof ServiceException ? REJECTED : FAILED;
        }
        events.add(
                new ProjectStandardAssetAuditEvent(
                        SensitiveAccessAuditAccessType.ASSET_QUERY, requestId, status, reason));
        try {
            audits.append(context, orderNo, trace, List.copyOf(events));
        } catch (RuntimeException auditFailure) {
            var safe = new IllegalStateException("敏感访问审计写入失败，安全拒绝返回", auditFailure);
            if (error != null) safe.addSuppressed(error);
            throw safe;
        }
        log.debug(
                "[项目资产查询结果] projectId={}, buyerOrderNo={}, result={}, reason={}",
                context.projectId(),
                buyerOrderNo,
                status,
                reason);
        if (error != null) throw error;
        log.debug(
                "[项目资产查询] projectId={}, buyerOrderNo={}, assetCount={}",
                context.projectId(),
                buyerOrderNo,
                result.getAssets().size());
        return result;
    }

    private static ProjectStandardAssetAuditReason reason(RuntimeException failure) {
        if (failure instanceof ProjectStandardAssetQueryFailure safe) return safe.reason();
        if (failure instanceof ServiceException service) {
            if (service.getCode().equals(SUPPLY_CHAIN_ORDER_NOT_FOUND.getCode()))
                return ORDER_NOT_FOUND;
            if (service.getCode().equals(ASSET_QUERY_UNSUPPORTED_PRODUCT_TYPE.getCode()))
                return UNSUPPORTED_PRODUCT_TYPE;
            if (service.getCode().equals(ASSET_QUERY_ORDER_NOT_ACCEPTED.getCode()))
                return ORDER_NOT_ACCEPTED;
            if (service.getCode().equals(ASSET_QUERY_ORDER_TERMINATED.getCode()))
                return ORDER_TERMINATED;
        }
        return REFERENCE_INCONSISTENT;
    }
}
