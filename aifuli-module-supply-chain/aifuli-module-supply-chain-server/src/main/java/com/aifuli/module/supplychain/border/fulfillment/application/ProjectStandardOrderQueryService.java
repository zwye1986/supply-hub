package com.aifuli.module.supplychain.border.fulfillment.application;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.border.fulfillment.domain.ProjectStandardAssetAuditReason.REFERENCE_INCONSISTENT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_CHAIN_ORDER_NOT_FOUND;

import com.aifuli.module.supplychain.api.fulfillment.dto.*;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.StandardProductContext;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 项目标准查询的独立只读快照；所有归属与资产元数据来自同一RR视图，不调用供应商或推进履约。 设计见
 * docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#2-一致快照与归属校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectStandardOrderQueryService {
    private final FulfillmentAcceptanceReceiptMapper receipts;
    private final SupplyChainOrderMapper orders;
    private final FulfillmentRequestMapper requests;
    private final FulfillmentAssetMapper assets;
    private final BOrderMapper bOrders;
    private final FulfillmentOperationMapper operations;
    private final SupplyChainDatabaseClock clock;
    private final com.aifuli.module.supplychain.border.order.dal.mysql.OrderStatusLogMapper statusLogs;

    /** 独立事务避免外层较低隔离级别改变该读视图；不加行锁。 */
    @Transactional(
            transactionManager = "supplyChainTransactionManager",
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ,
            readOnly = true)
    public ProjectStandardOrderQueryRespDTO query(
            StandardProductContext context, String buyerOrderNo) {
        return read(context, buyerOrderNo).summary();
    }

    /** 资产编排通过Spring代理取得完整授权快照，解密和审计随后进行。 */
    @Transactional(
            transactionManager = "supplyChainTransactionManager",
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ,
            readOnly = true)
    public Snapshot snapshot(StandardProductContext context, String buyerOrderNo) {
        return read(context, buyerOrderNo);
    }

    private Snapshot read(StandardProductContext context, String buyerOrderNo) {
        log.debug("[项目标准查单] projectId={}, buyerOrderNo={}", context.projectId(), buyerOrderNo);
        var receiptRows =
                receipts.selectList(
                        new LambdaQueryWrapper<FulfillmentAcceptanceReceiptDO>()
                                .eq(
                                        FulfillmentAcceptanceReceiptDO::getProjectId,
                                        context.projectId())
                                .eq(FulfillmentAcceptanceReceiptDO::getBuyerOrderNo, buyerOrderNo));
        var orderRows =
                orders.selectList(
                        new LambdaQueryWrapper<SupplyChainOrderDO>()
                                .eq(SupplyChainOrderDO::getProjectId, context.projectId())
                                .eq(SupplyChainOrderDO::getBuyerOrderNo, buyerOrderNo)
                                .eq(
                                        SupplyChainOrderDO::getOrderType,
                                        SupplyChainOrderTypes.ORDER_TYPE_CARD));
        if (receiptRows.isEmpty() && orderRows.isEmpty())
            throw exception(SUPPLY_CHAIN_ORDER_NOT_FOUND);
        require(receiptRows.size() == 1 && orderRows.size() <= 1);
        var receipt = receiptRows.getFirst();
        require(
                Objects.equals(receipt.getProjectId(), context.projectId())
                        && Objects.equals(receipt.getBuyerId(), context.buyerId()));
        require(
                receipt.getStatus() != null
                        && receipt.getQuantity() != null
                        && receipt.getQuantity() > 0
                        && receipt.getVersion() != null);
        AcceptanceReceiptStatus receiptStatus;
        try {
            receiptStatus = AcceptanceReceiptStatus.fromCode(receipt.getStatus());
        } catch (IllegalArgumentException failure) {
            throw inconsistent(failure);
        }
        var dto = new ProjectStandardOrderQueryRespDTO();
        dto.setBuyerOrderNo(receipt.getBuyerOrderNo());
        dto.setRequestId(receipt.getReceiptNo());
        dto.setReceiptStatus(FulfillmentReceiptStatus.valueOf(receiptStatus.name()));
        dto.setReceiptVersion(receipt.getVersion());
        dto.setRejectionCode(receipt.getRejectionCode());
        dto.setAcceptedAt(offset(receipt.getAcceptedAt()));
        var now = clock.now();
        if (orderRows.isEmpty()) {
            require(
                    receiptStatus != AcceptanceReceiptStatus.ACCEPTED
                            && receipt.getResultOrderId() == null
                            && receipt.getResultOrderNo() == null
                            && receipt.getResultFulfillmentRequestNo() == null);
            dto.setRequestedQuantity(receipt.getQuantity());
            dto.setDeliveredQuantity(0L);
            dto.setAssetAvailable(false);
            dto.setUpdatedAt(offset(receipt.getUpdatedAt()));
            return new Snapshot(receipt, null, null, List.of(), dto);
        }
        var order = orderRows.getFirst();
        require(
                receiptStatus == AcceptanceReceiptStatus.ACCEPTED
                        && Objects.equals(order.getProjectId(), context.projectId())
                        && Objects.equals(order.getBuyerId(), context.buyerId())
                        && Objects.equals(receipt.getResultOrderId(), order.getId())
                        && order.getOrderNo() != null
                        && Objects.equals(receipt.getResultOrderNo(), order.getOrderNo())
                        && Objects.equals(order.getAcceptanceReceiptId(), receipt.getId())
                        && SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT.equals(
                                order.getDetailType())
                        && order.getDetailId() != null);
        var request = requests.selectById(order.getDetailId());
        require(
                request != null
                        && Objects.equals(request.getId(), order.getDetailId())
                        && Objects.equals(request.getOrderId(), order.getId())
                        && Objects.equals(request.getBuyerId(), context.buyerId())
                        && request.getRequestNo() != null
                        && Objects.equals(
                                receipt.getResultFulfillmentRequestNo(), request.getRequestNo()));
        // 双向引用还必须绑定同一商品和冻结目标，禁止拼接另一商品快照。
        // 设计见 docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#2-一致快照与归属校验。
        require(
                order.getPlatformSkuId() != null
                        && Objects.equals(request.getPlatformSkuId(), order.getPlatformSkuId())
                        && Objects.equals(request.getRequestedQuantity(), order.getQuantity())
                        && Objects.equals(request.getRequestedQuantity(), receipt.getQuantity())
                        && Objects.equals(
                                request.getTargetSchemaCode(), receipt.getTargetSchemaCode())
                        && request.getTargetDigest() != null
                        && receipt.getTargetDigest() != null
                        && Arrays.equals(request.getTargetDigest(), receipt.getTargetDigest()));
        require(
                order.getOrderStatus() != null
                        && request.getStatus() != null
                        && request.getRequestedQuantity() != null
                        && request.getRequestedQuantity() > 0
                        && request.getFulfilledQuantity() != null
                        && request.getFulfilledQuantity() >= 0
                        && request.getFulfilledQuantity() <= request.getRequestedQuantity());
        SupplyChainOrderStatus orderStatus;
        FulfillmentRequestStatus requestStatus;
        try {
            orderStatus = SupplyChainOrderStatus.fromCode(order.getOrderStatus());
            requestStatus = FulfillmentRequestStatus.fromCode(request.getStatus());
        } catch (IllegalArgumentException failure) {
            throw inconsistent(failure);
        }
        var frozenSchema = schema(request.getTargetSchemaCode());
        List<FulfillmentAssetDO> deliverable = new ArrayList<>();
        if (frozenSchema == FulfillmentTargetSchemaCode.VOUCHER_DELIVERY_TARGET) {
            for (var asset : assets.selectListByFulfillmentRequestId(request.getId())) {
                require(
                        Objects.equals(asset.getFulfillmentRequestId(), request.getId())
                                && Objects.equals(asset.getBuyerId(), context.buyerId())
                                && asset.getBOrderId() != null
                                && asset.getOperationId() != null);
                var bOrder = bOrders.selectById(asset.getBOrderId());
                var operation = operations.selectById(asset.getOperationId());
                require(
                        bOrder != null
                                && Objects.equals(bOrder.getId(), asset.getBOrderId())
                                && Objects.equals(bOrder.getFulfillmentRequestId(), request.getId())
                                && Objects.equals(bOrder.getOrderId(), order.getId())
                                && asset.getSupplierId() != null
                                && Objects.equals(bOrder.getSupplierId(), asset.getSupplierId())
                                && operation != null
                                && Objects.equals(operation.getId(), asset.getOperationId())
                                && Objects.equals(operation.getBOrderId(), bOrder.getId()));
                require(
                        asset.getLifecycleStatus() != null
                                && asset.getId() != null
                                && asset.getSequenceNo() != null
                                && asset.getSequenceNo() > 0);
                try {
                    var assetType = FulfillmentType.valueOf(asset.getAssetType());
                    require(
                            assetType == FulfillmentType.VOUCHER_CODE
                                    || assetType == FulfillmentType.CARD_CODE_SECRET
                                    || assetType == FulfillmentType.VOUCHER_URL);
                } catch (RuntimeException failure) {
                    throw inconsistent(failure);
                }
                FulfillmentAssetStatus lifecycle;
                try {
                    lifecycle = FulfillmentAssetStatus.fromCode(asset.getLifecycleStatus());
                } catch (IllegalArgumentException failure) {
                    throw inconsistent(failure);
                }
                if ((lifecycle == FulfillmentAssetStatus.AVAILABLE
                                || lifecycle == FulfillmentAssetStatus.DELIVERED)
                        && (asset.getValidFrom() == null || !asset.getValidFrom().isAfter(now))
                        && (asset.getValidUntil() == null || asset.getValidUntil().isAfter(now)))
                    deliverable.add(asset);
            }
        }
        deliverable.sort(
                Comparator.comparing(FulfillmentAssetDO::getBOrderId)
                        .thenComparing(FulfillmentAssetDO::getSequenceNo));
        dto.setOrderNo(order.getOrderNo());
        dto.setOrderStatus(orderStatus);
        // 公共失败原因只读平台终态日志；不返回供应商金额、账号、Case 或原始报文。
        // 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#7-持久化与协议兼容。
        if (orderStatus == SupplyChainOrderStatus.FAILED) {
            var reasons = statusLogs.selectList(new LambdaQueryWrapper<com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO>()
                    .eq(com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO::getOrderId, order.getId())
                    .eq(com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO::getToOrderStatus, orderStatus.getCode())
                    .orderByDesc(com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO::getId));
            if (!reasons.isEmpty()) {
                dto.setFailureReasonCode(reasons.getFirst().getReasonCode());
                try {
                    dto.setFailureReasonNote(com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengStableFailureReason
                            .valueOf(dto.getFailureReasonCode()).chineseDescription());
                } catch (IllegalArgumentException | NullPointerException ignored) {
                    // 历史未知原因不拼接自由文本；说明缺失即未知。
                }
            }
        }
        dto.setFulfillmentStatus(map(requestStatus));
        dto.setRequestedQuantity(request.getRequestedQuantity());
        dto.setDeliveredQuantity(request.getFulfilledQuantity());
        dto.setAssetAvailable(
                !deliverable.isEmpty()
                        && orderStatus != SupplyChainOrderStatus.FAILED
                        && orderStatus != SupplyChainOrderStatus.CANCELLED);
        dto.setCompletedAt(offset(order.getCompletedAt()));
        dto.setUpdatedAt(
                offset(
                        latest(
                                receipt.getUpdatedAt(),
                                order.getUpdatedAt(),
                                request.getUpdatedAt())));
        return new Snapshot(receipt, order, request, List.copyOf(deliverable), dto);
    }

    private FulfillmentStatus map(FulfillmentRequestStatus value) {
        return switch (value) {
            case PENDING_ROUTING, ROUTING -> FulfillmentStatus.ACCEPTED;
            case FULFILLING -> FulfillmentStatus.PROCESSING;
            case SUCCEEDED -> FulfillmentStatus.SUCCEEDED;
            case FAILED -> FulfillmentStatus.FAILED;
            case MANUAL -> FulfillmentStatus.MANUAL;
        };
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null
                ? null
                : clock.toInstant(value)
                        .atZone(SupplyChainDatabaseClock.DATABASE_ZONE)
                        .toOffsetDateTime();
    }

    private static LocalDateTime latest(LocalDateTime... values) {
        return Arrays.stream(values)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    static FulfillmentTargetSchemaCode schema(String code) {
        try {
            return FulfillmentTargetSchemaCode.valueOf(code);
        } catch (RuntimeException failure) {
            throw inconsistent(failure);
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw inconsistent(null);
    }

    private static ProjectStandardAssetQueryFailure inconsistent(Throwable cause) {
        return new ProjectStandardAssetQueryFailure(REFERENCE_INCONSISTENT, cause);
    }

    public record Snapshot(
            FulfillmentAcceptanceReceiptDO receipt,
            SupplyChainOrderDO order,
            FulfillmentRequestDO request,
            List<FulfillmentAssetDO> deliverableAssets,
            ProjectStandardOrderQueryRespDTO summary) {}
}
