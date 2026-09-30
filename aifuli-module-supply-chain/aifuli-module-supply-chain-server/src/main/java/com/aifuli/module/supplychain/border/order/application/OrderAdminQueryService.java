package com.aifuli.module.supplychain.border.order.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.order.dto.DetailAvailability;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainAftersaleStatus;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SupplierRoutingRecordDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SupplierRoutingRoundDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SupplierRoutingRecordMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SupplierRoutingRoundMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentRequestStatus;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOrderStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstorePaymentFlowStatus;
import com.aifuli.module.supplychain.border.o2o.dal.dataobject.O2OChannelDetailDO;
import com.aifuli.module.supplychain.border.o2o.dal.mysql.O2OChannelDetailMapper;
import com.aifuli.module.supplychain.border.o2o.domain.O2OChannelFulfillmentStatus;
import com.aifuli.module.supplychain.border.o2o.domain.O2ODetailStatus;
import com.aifuli.module.supplychain.border.o2o.domain.O2OFundStatus;
import com.aifuli.module.supplychain.border.o2o.domain.O2OPaymentStatus;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderType;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.routing.domain.RoutingDecisionStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.DETAIL_UNAVAILABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ORDER_QUERY_TIME_RANGE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_CHAIN_ORDER_NOT_FOUND;

/**
 * 平台订单管理端只读查询。列表严格只读总订单表；详情按 {@code detail_type} 只加载一个场景分支；
 * 路由记录只读已冻结轮次与候选。不读取履约资产、账户或账本，也不在查询线程调用渠道与供应商。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md#4-分层与数据访问}
 * 与 {@code docs/superpowers/specs/2026-09-21-order-routing-records-admin-design.md}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAdminQueryService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final ObjectMapper CHECK_RESULT_JSON = new ObjectMapper();

    private final SupplyChainOrderMapper orderMapper;
    private final FulfillmentRequestMapper fulfillmentRequestMapper;
    private final O2OChannelDetailMapper o2oDetailMapper;
    private final InstoreChannelOrderMapper instoreOrderMapper;
    private final InstorePaymentFlowMapper instorePaymentFlowMapper;
    private final SupplierRoutingRoundMapper routingRoundMapper;
    private final SupplierRoutingRecordMapper routingRecordMapper;
    private final BOrderMapper bOrderMapper;

    /**
     * 分页查询总订单摘要。场景详情 Mapper 不得在该方法中出现，避免列表 N+1 与跨场景耦合。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public PageResult<OrderSummary> page(OrderPageQuery query) {
        validateTimeRange(query.createdFrom(), query.createdTo());
        log.info("分页查询平台订单 orderNo={} buyerOrderNo={} buyerCode={} supplierCode={} skuCode={} "
                        + "orderType={} orderStatus={} aftersaleStatus={} createdFrom={} createdTo={} pageNo={} pageSize={}",
                query.orderNo(), query.buyerOrderNo(), query.buyerCode(), query.supplierCode(), query.skuCode(),
                query.orderType(), query.orderStatus(), query.aftersaleStatus(), query.createdFrom(), query.createdTo(),
                query.pageNo(), query.pageSize());

        PageParam pageParam = new PageParam();
        pageParam.setPageNo(query.pageNo() == null ? DEFAULT_PAGE_NO : query.pageNo());
        pageParam.setPageSize(query.pageSize() == null ? DEFAULT_PAGE_SIZE : query.pageSize());
        LambdaQueryWrapper<SupplyChainOrderDO> wrapper = new LambdaQueryWrapper<SupplyChainOrderDO>()
                .eq(StringUtils.hasText(query.orderNo()), SupplyChainOrderDO::getOrderNo, query.orderNo())
                .eq(StringUtils.hasText(query.buyerOrderNo()), SupplyChainOrderDO::getBuyerOrderNo,
                        query.buyerOrderNo())
                .eq(StringUtils.hasText(query.buyerCode()), SupplyChainOrderDO::getBuyerCodeSnapshot,
                        query.buyerCode())
                .eq(StringUtils.hasText(query.supplierCode()), SupplyChainOrderDO::getDisplaySupplierCode,
                        query.supplierCode())
                .eq(StringUtils.hasText(query.skuCode()), SupplyChainOrderDO::getSkuCodeSnapshot, query.skuCode())
                .eq(query.orderType() != null, SupplyChainOrderDO::getOrderType,
                        query.orderType() == null ? null : query.orderType().name())
                .eq(query.orderStatus() != null, SupplyChainOrderDO::getOrderStatus,
                        query.orderStatus() == null ? null : query.orderStatus().getCode())
                .eq(query.aftersaleStatus() != null, SupplyChainOrderDO::getAftersaleStatus,
                        query.aftersaleStatus() == null ? null : query.aftersaleStatus().getCode())
                .ge(query.createdFrom() != null, SupplyChainOrderDO::getCreatedAt, query.createdFrom())
                .le(query.createdTo() != null, SupplyChainOrderDO::getCreatedAt, query.createdTo())
                .orderByDesc(SupplyChainOrderDO::getCreatedAt)
                .orderByDesc(SupplyChainOrderDO::getId);
        PageResult<SupplyChainOrderDO> page = orderMapper.selectPage(pageParam, wrapper);
        return new PageResult<>(page.getList().stream().map(OrderAdminQueryService::toSummary).toList(),
                page.getTotal());
    }

    /**
     * 查询类型化详情。总表存在而详情缺失时按既有公共查询契约降级为成功的 UNAVAILABLE。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public OrderDetail get(String orderNo) {
        log.info("查询平台订单详情 orderNo={}", orderNo);
        SupplyChainOrderDO order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw exception(SUPPLY_CHAIN_ORDER_NOT_FOUND);
        }
        OrderSummary summary = toSummary(order);
        return switch (order.getDetailType()) {
            case SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT -> cardDetail(order, summary);
            case SupplyChainOrderTypes.DETAIL_TYPE_O2O_CHANNEL -> o2oDetail(order, summary);
            case SupplyChainOrderTypes.DETAIL_TYPE_INSTORE_CHANNEL -> instoreDetail(order, summary);
            default -> unavailable(summary);
        };
    }

    /**
     * 查询平台订单冻结的路由轮次与候选。O2O/INSTORE 不走标准路由，返回空 rounds。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public OrderRoutingRecordsView getRoutingRecords(String orderNo) {
        log.info("查询平台订单路由记录 orderNo={}", orderNo);
        SupplyChainOrderDO order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw exception(SUPPLY_CHAIN_ORDER_NOT_FOUND);
        }
        SupplyChainOrderType orderType = SupplyChainOrderType.valueOf(order.getOrderType());
        if (orderType != SupplyChainOrderType.CARD) {
            log.info("查询平台订单路由记录完成 orderNo={} orderType={} roundCount=0", orderNo, orderType);
            return emptyRoutingRecords(order, orderType);
        }
        List<SupplierRoutingRoundDO> rounds = routingRoundMapper.selectList(
                new LambdaQueryWrapper<SupplierRoutingRoundDO>()
                        .eq(SupplierRoutingRoundDO::getOrderId, order.getId())
                        .orderByAsc(SupplierRoutingRoundDO::getRoundSequence)
                        .orderByAsc(SupplierRoutingRoundDO::getId));
        List<RoutingRoundView> roundViews = new ArrayList<>();
        Set<Long> attemptIds = new HashSet<>();
        List<List<SupplierRoutingRecordDO>> recordsByRound = new ArrayList<>();
        for (SupplierRoutingRoundDO round : rounds) {
            List<SupplierRoutingRecordDO> records = routingRecordMapper.selectList(
                    new LambdaQueryWrapper<SupplierRoutingRecordDO>()
                            .eq(SupplierRoutingRecordDO::getRoutingRoundId, round.getId())
                            .orderByAsc(SupplierRoutingRecordDO::getCandidateSequence)
                            .orderByAsc(SupplierRoutingRecordDO::getId));
            recordsByRound.add(records);
            for (SupplierRoutingRecordDO record : records) {
                if (record.getAttemptBOrderId() != null) {
                    attemptIds.add(record.getAttemptBOrderId());
                }
            }
        }
        Map<Long, String> bOrderNos = loadBOrderNos(attemptIds);
        for (int i = 0; i < rounds.size(); i++) {
            roundViews.add(toRoundView(rounds.get(i), recordsByRound.get(i), bOrderNos));
        }
        log.info("查询平台订单路由记录完成 orderNo={} orderType={} roundCount={}",
                orderNo, orderType, roundViews.size());
        return new OrderRoutingRecordsView(order.getOrderNo(), orderType, order.getSkuCodeSnapshot(),
                order.getSkuNameSnapshot(), order.getBuyerCodeSnapshot(), List.copyOf(roundViews));
    }

    private Map<Long, String> loadBOrderNos(Set<Long> attemptIds) {
        Map<Long, String> bOrderNos = new HashMap<>();
        if (attemptIds.isEmpty()) {
            return bOrderNos;
        }
        for (BOrderDO attempt : bOrderMapper.selectBatchIds(attemptIds)) {
            if (attempt != null && attempt.getId() != null) {
                bOrderNos.put(attempt.getId(), attempt.getBOrderNo());
            }
        }
        return bOrderNos;
    }

    private static OrderRoutingRecordsView emptyRoutingRecords(SupplyChainOrderDO order,
                                                               SupplyChainOrderType orderType) {
        return new OrderRoutingRecordsView(order.getOrderNo(), orderType, order.getSkuCodeSnapshot(),
                order.getSkuNameSnapshot(), order.getBuyerCodeSnapshot(), List.of());
    }

    private static RoutingRoundView toRoundView(SupplierRoutingRoundDO round,
                                                List<SupplierRoutingRecordDO> records,
                                                Map<Long, String> bOrderNos) {
        List<RoutingCandidateView> candidates = records.stream()
                .map(record -> toCandidateView(record, bOrderNos))
                .toList();
        return new RoutingRoundView(round.getRoutingRoundNo(), round.getRoundSequence(), round.getRoutingType(),
                RoutingDecisionStatus.fromCode(round.getDecisionStatus()), round.getDecisionReasonCode(),
                round.getDecisionReasonMessage(), round.getCandidateCount(), round.getComputedAt(),
                List.copyOf(candidates));
    }

    private static RoutingCandidateView toCandidateView(SupplierRoutingRecordDO record,
                                                        Map<Long, String> bOrderNos) {
        return new RoutingCandidateView(
                record.getCandidateSequence(),
                record.getSupplierCodeSnapshot(),
                record.getSupplierNameSnapshot(),
                record.getConfiguredPriority(),
                record.getAvailabilityClass(),
                record.getSupplierSettlementUnitPrice(),
                Boolean.TRUE.equals(record.getEligibleFlag()),
                Boolean.TRUE.equals(record.getSelectedFlag()),
                record.getDecisionReasonCode(),
                record.getDecisionReasonMessage(),
                record.getAttemptBOrderId() == null ? null : bOrderNos.get(record.getAttemptBOrderId()),
                parseCheckResult(record.getCheckResultJson()));
    }

    private static RoutingCheckResult parseCheckResult(String json) {
        try {
            JsonNode node = CHECK_RESULT_JSON.readTree(json == null ? "{}" : json);
            List<String> reasons = new ArrayList<>();
            JsonNode reasonsNode = node.get("reasons");
            if (reasonsNode != null && reasonsNode.isArray()) {
                reasonsNode.forEach(item -> reasons.add(item.asText()));
            }
            return new RoutingCheckResult(
                    textOrNull(node, "inventory"),
                    textOrNull(node, "balance"),
                    textOrNull(node, "health"),
                    List.copyOf(reasons));
        } catch (Exception exception) {
            throw new IllegalStateException("Routing check snapshot JSON is corrupted", exception);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private OrderDetail cardDetail(SupplyChainOrderDO order, OrderSummary summary) {
        FulfillmentRequestDO detail = fulfillmentRequestMapper.selectByOrderId(order.getId());
        if (detail == null) {
            return unavailable(summary);
        }
        CardDetail card = new CardDetail(detail.getRequestNo(), detail.getTargetSchemaCode(),
                detail.getTargetSchemaVersion(), detail.getRequestedQuantity(), detail.getFulfilledQuantity(),
                FulfillmentRequestStatus.fromCode(detail.getStatus()), detail.getCreatedAt(), detail.getUpdatedAt());
        return available(summary, card, null, null);
    }

    private OrderDetail o2oDetail(SupplyChainOrderDO order, OrderSummary summary) {
        O2OChannelDetailDO detail = o2oDetailMapper.selectByOrderId(order.getId());
        if (detail == null) {
            return unavailable(summary);
        }
        O2ODetail o2o = new O2ODetail(detail.getChannelDetailNo(), detail.getChannelCode(),
                detail.getTransactionId(), detail.getSupplierCodeSnapshot(), detail.getSupplierNameSnapshot(),
                detail.getExternalStoreId(), detail.getExternalStoreName(), detail.getOutTradeNo(),
                detail.getChannelPayAmount(), detail.getBuyerPaidAmount(), detail.getBuyerSettlementAmount(),
                detail.getCurrency(), detail.getCashierReference(),
                O2OPaymentStatus.fromCode(detail.getPaymentStatus()),
                O2OFundStatus.fromCode(detail.getFundStatus()),
                O2OChannelFulfillmentStatus.fromCode(detail.getChannelFulfillmentStatus()),
                detail.getTotalChannelRefundAmount(), detail.getTotalBuyerRefundAmount(),
                detail.getTotalAccountReturnAmount(), O2ODetailStatus.fromCode(detail.getStatus()),
                detail.getCreatedAt(), detail.getUpdatedAt());
        return available(summary, null, o2o, null);
    }

    private OrderDetail instoreDetail(SupplyChainOrderDO order, OrderSummary summary) {
        InstoreChannelOrderDO detail = instoreOrderMapper.selectByOrderId(order.getId());
        if (detail == null) {
            return unavailable(summary);
        }
        List<InstorePaymentFlow> flows = instorePaymentFlowMapper.selectListByInstoreOrder(detail.getId()).stream()
                .map(OrderAdminQueryService::toInstorePaymentFlow)
                .toList();
        InstoreDetail instore = new InstoreDetail(detail.getInstoreOrderNo(), detail.getChannelCode(),
                detail.getChannelOrderId(), detail.getCompanyCode(), detail.getSupplierCodeSnapshot(),
                detail.getSupplierNameSnapshot(), detail.getExternalStoreId(), detail.getExternalStoreName(),
                detail.getTotalAmount(), detail.getTotalPaidAmount(), detail.getCurrency(),
                detail.getTotalChannelRefundAmount(), detail.getTotalBuyerRefundAmount(),
                detail.getTotalAccountReturnAmount(), InstoreOrderStatus.fromCode(detail.getOrderStatus()),
                flows, detail.getCreatedAt(), detail.getUpdatedAt(), detail.getCompletedAt());
        return available(summary, null, null, instore);
    }

    private static InstorePaymentFlow toInstorePaymentFlow(InstorePaymentFlowDO flow) {
        return new InstorePaymentFlow(flow.getPaymentFlowNo(), flow.getFlowSequence(), flow.getTradeId(),
                flow.getPayAmount(), flow.getOrderType(), InstorePaymentFlowStatus.fromCode(flow.getPayStatus()),
                flow.getOutTradeNo(), flow.getCashierReference(), flow.getBuyerPaidAmount(), flow.getPayTime(),
                flow.getExternalTransactionId(), flow.getCreatedAt(), flow.getUpdatedAt(), flow.getCompletedAt());
    }

    private static OrderDetail available(OrderSummary summary, CardDetail card, O2ODetail o2o,
                                         InstoreDetail instore) {
        return new OrderDetail(summary, DetailAvailability.AVAILABLE, null, card, o2o, instore);
    }

    private static OrderDetail unavailable(OrderSummary summary) {
        return new OrderDetail(summary, DetailAvailability.UNAVAILABLE, DETAIL_UNAVAILABLE, null, null, null);
    }

    private static OrderSummary toSummary(SupplyChainOrderDO order) {
        return new OrderSummary(order.getOrderNo(), SupplyChainOrderType.valueOf(order.getOrderType()),
                order.getBuyerCodeSnapshot(), order.getBuyerNameSnapshot(), order.getBuyerOrderNo(),
                order.getDisplaySupplierCode(), order.getDisplaySupplierName(), order.getBuyerSettlementAmount(),
                order.getSalesUnitPriceCents(), order.getProductSubtotalCents(), order.getServiceFeeCents(),
                order.getUserPayableAmountCents(),
                order.getCurrency(), order.getSkuCodeSnapshot(), order.getSkuNameSnapshot(), order.getQuantity(),
                SupplyChainOrderStatus.fromCode(order.getOrderStatus()),
                SupplyChainAftersaleStatus.fromCode(order.getAftersaleStatus()), order.getBusinessStage(),
                order.getDetailType(), order.getDetailVersion(), order.getCreatedAt(), order.getUpdatedAt(),
                order.getCompletedAt());
    }

    private static void validateTimeRange(LocalDateTime createdFrom, LocalDateTime createdTo) {
        if (createdFrom != null && createdTo != null && createdTo.isBefore(createdFrom)) {
            throw exception(ORDER_QUERY_TIME_RANGE_INVALID);
        }
    }

    /** 管理端订单分页条件；所有编码均精确匹配。 */
    public record OrderPageQuery(String orderNo, String buyerOrderNo, String buyerCode, String supplierCode,
                                 String skuCode, SupplyChainOrderType orderType,
                                 SupplyChainOrderStatus orderStatus, SupplyChainAftersaleStatus aftersaleStatus,
                                 LocalDateTime createdFrom, LocalDateTime createdTo,
                                 Integer pageNo, Integer pageSize) {
    }

    /** 总订单摘要；字段语义与 {@code sc_supply_chain_order} DDL COMMENT 一致。 */
    public record OrderSummary(
            @Schema(description = "供应链总订单稳定业务编号") String orderNo,
            @Schema(description = "订单类型：CARD/O2O/INSTORE") SupplyChainOrderType orderType,
            @Schema(description = "受理时采购商业务编码快照") String buyerCode,
            @Schema(description = "受理时采购商名称快照") String buyerName,
            @Schema(description = "采购商侧C端订单号；O2O场景为空") String buyerOrderNo,
            @Schema(description = "列表展示供应商编码快照") String supplierCode,
            @Schema(description = "列表展示供应商名称快照") String supplierName,
            @Schema(description = "采购商应向中台结算的订单金额，单位为分") Long buyerSettlementAmount,
            @Schema(description = "冻结的商品销售单价，单位CNY分") Long salesUnitPriceCents,
            @Schema(description = "冻结的商品小计，单位CNY分") Long productSubtotalCents,
            @Schema(description = "冻结的平台服务费，单位CNY分") Long serviceFeeCents,
            @Schema(description = "冻结的用户应付总额，单位CNY分") Long userPayableAmountCents,
            @Schema(description = "订单币种，一期固定CNY") String currency,
            @Schema(description = "CARD场景平台SKU编码快照") String skuCode,
            @Schema(description = "CARD场景平台SKU名称快照") String skuName,
            @Schema(description = "CARD场景请求数量") Long quantity,
            @Schema(description = "公共主状态") SupplyChainOrderStatus orderStatus,
            @Schema(description = "售后状态") SupplyChainAftersaleStatus aftersaleStatus,
            @Schema(description = "当前业务阶段稳定编码") String businessStage,
            @Schema(description = "场景详情类型") String detailType,
            @Schema(description = "已投影到总表的场景详情版本号") Long detailVersion,
            @Schema(description = "订单受理时间") LocalDateTime createdAt,
            @Schema(description = "订单最后更新时间") LocalDateTime updatedAt,
            @Schema(description = "订单进入业务终态时间") LocalDateTime completedAt) {
    }

    /** 类型化订单详情；card/o2o/instore 在可用时严格三选一。 */
    public record OrderDetail(
            @Schema(description = "供应链总订单摘要") OrderSummary summary,
            @Schema(description = "场景详情可用性") DetailAvailability detailAvailability,
            @Schema(description = "详情不可用时固定为DETAIL_UNAVAILABLE") String detailErrorCode,
            @Schema(description = "CARD标准商品履约详情；其它类型为空") CardDetail card,
            @Schema(description = "O2O渠道订单详情；其它类型为空") O2ODetail o2o,
            @Schema(description = "INSTORE到店购详情；其它类型为空") InstoreDetail instore) {
    }

    /** CARD 标准商品履约详情，不包含履约目标和交付资产。 */
    public record CardDetail(
            @Schema(description = "履约请求稳定业务编号") String requestNo,
            @Schema(description = "履约目标封闭Schema编码") String targetSchemaCode,
            @Schema(description = "履约目标Schema版本") Integer targetSchemaVersion,
            @Schema(description = "请求履约数量") Long requestedQuantity,
            @Schema(description = "已经成功交付的数量") Long fulfilledQuantity,
            @Schema(description = "履约请求状态") FulfillmentRequestStatus status,
            @Schema(description = "履约请求创建时间") LocalDateTime createdAt,
            @Schema(description = "履约请求最后更新时间") LocalDateTime updatedAt) {
    }

    /** O2O 渠道订单详情，不包含匿名用户与门店原始 JSON。 */
    public record O2ODetail(
            @Schema(description = "O2O渠道详情稳定业务编号") String channelDetailNo,
            @Schema(description = "O2O渠道编码") String channelCode,
            @Schema(description = "渠道交易唯一标识") String transactionId,
            @Schema(description = "渠道外部商户供应商编码快照") String supplierCode,
            @Schema(description = "渠道外部商户供应商名称快照") String supplierName,
            @Schema(description = "渠道外部门店标识") String externalStoreId,
            @Schema(description = "渠道外部门店名称快照") String externalStoreName,
            @Schema(description = "拉起采购商收银台的稳定外部交易号") String outTradeNo,
            @Schema(description = "渠道对账权威金额，单位为分") Long channelPayAmount,
            @Schema(description = "采购商收银台实际支付金额，单位为分") Long buyerPaidAmount,
            @Schema(description = "采购商账户冻结和结算金额，单位为分") Long buyerSettlementAmount,
            @Schema(description = "交易币种，一期固定CNY") String currency,
            @Schema(description = "采购商收银台支付单引用") String cashierReference,
            @Schema(description = "用户支付状态") O2OPaymentStatus paymentStatus,
            @Schema(description = "中台资金状态") O2OFundStatus fundStatus,
            @Schema(description = "渠道履约状态") O2OChannelFulfillmentStatus channelFulfillmentStatus,
            @Schema(description = "渠道累计发起退款金额，单位为分") Long totalChannelRefundAmount,
            @Schema(description = "采购商累计实际退款金额，单位为分") Long totalBuyerRefundAmount,
            @Schema(description = "中台累计返还采购商账户金额，单位为分") Long totalAccountReturnAmount,
            @Schema(description = "O2O详情聚合状态") O2ODetailStatus status,
            @Schema(description = "O2O详情创建时间") LocalDateTime createdAt,
            @Schema(description = "O2O详情最后更新时间") LocalDateTime updatedAt) {
    }

    /** INSTORE 渠道订单头及支付流水列表，不包含收银台 URL、会员编号与资金内部引用。 */
    public record InstoreDetail(
            @Schema(description = "到店购渠道订单稳定业务编号") String instoreOrderNo,
            @Schema(description = "到店购渠道编码") String channelCode,
            @Schema(description = "旅通订单号order_id") String channelOrderId,
            @Schema(description = "旅通企业组编号company_code，受理时固化") String companyCode,
            @Schema(description = "到店购外部商户供应商编码快照") String supplierCode,
            @Schema(description = "到店购外部商户供应商名称快照") String supplierName,
            @Schema(description = "渠道外部门店编号shop_code") String externalStoreId,
            @Schema(description = "渠道外部门店名称shop_name快照") String externalStoreName,
            @Schema(description = "订单应付总额，单位为分") Long totalAmount,
            @Schema(description = "已支付累计金额，单位为分") Long totalPaidAmount,
            @Schema(description = "交易币种，一期固定CNY") String currency,
            @Schema(description = "渠道累计发起退款金额，单位为分") Long totalChannelRefundAmount,
            @Schema(description = "采购商累计实际退款金额，单位为分") Long totalBuyerRefundAmount,
            @Schema(description = "中台累计返还采购商账户金额，单位为分") Long totalAccountReturnAmount,
            @Schema(description = "订单头聚合状态") InstoreOrderStatus status,
            @Schema(description = "按流水顺序号升序的支付流水") List<InstorePaymentFlow> paymentFlows,
            @Schema(description = "渠道订单创建时间") LocalDateTime createdAt,
            @Schema(description = "渠道订单最后更新时间") LocalDateTime updatedAt,
            @Schema(description = "渠道订单进入业务终态时间") LocalDateTime completedAt) {
    }

    /** INSTORE 单笔支付流水的管理端安全投影。 */
    public record InstorePaymentFlow(
            @Schema(description = "到店购支付流水稳定业务编号") String paymentFlowNo,
            @Schema(description = "同一渠道订单内支付流水顺序号，从1递增") Integer flowSequence,
            @Schema(description = "旅通支付流水号trade_id") String tradeId,
            @Schema(description = "本笔支付流水金额，单位为分") Long payAmount,
            @Schema(description = "旅通支付单类型：1线下扫码购，2动态码") Integer orderType,
            @Schema(description = "支付状态") InstorePaymentFlowStatus payStatus,
            @Schema(description = "拉起采购商收银台的稳定外部交易号") String outTradeNo,
            @Schema(description = "采购商收银台支付单引用") String cashierReference,
            @Schema(description = "采购商收银台实际支付金额，单位为分") Long buyerPaidAmount,
            @Schema(description = "支付成功时间") LocalDateTime payTime,
            @Schema(description = "旅通返回的支付流水号transaction_id") String externalTransactionId,
            @Schema(description = "支付流水创建时间") LocalDateTime createdAt,
            @Schema(description = "支付流水最后更新时间") LocalDateTime updatedAt,
            @Schema(description = "支付流水进入终态时间") LocalDateTime completedAt) {
    }

    /** 平台订单路由记录弹窗；rounds 在无冻结轮次或非 CARD 时为空数组。 */
    public record OrderRoutingRecordsView(
            @Schema(description = "供应链总订单稳定业务编号") String orderNo,
            @Schema(description = "订单类型：CARD/O2O/INSTORE") SupplyChainOrderType orderType,
            @Schema(description = "CARD场景平台SKU编码快照") String skuCode,
            @Schema(description = "CARD场景平台SKU名称快照") String skuName,
            @Schema(description = "受理时采购商业务编码快照") String buyerCode,
            @Schema(description = "冻结的供应商路由轮次") List<RoutingRoundView> rounds) {
    }

    /** 供应商路由轮次只读投影。 */
    public record RoutingRoundView(
            @Schema(description = "供应商路由轮次稳定业务编号") String routingRoundNo,
            @Schema(description = "同一路由主体内的轮次序号，从1递增；场景路由固定为1") Integer roundSequence,
            @Schema(description = "路由类型：STANDARD_PRODUCT标准商品供给路由，SCENARIO场景供应商路由") String routingType,
            @Schema(description = "对外返回枚举名：SELECTED / NO_CANDIDATE / CONFLICT（码 10/20/30）")
            RoutingDecisionStatus decisionStatus,
            @Schema(description = "本轮最终决策稳定原因码") String decisionReasonCode,
            @Schema(description = "本轮最终决策中文说明快照") String decisionReasonMessage,
            @Schema(description = "本轮候选供应商数量") Integer candidateCount,
            @Schema(description = "纯路由计算完成时间") LocalDateTime computedAt,
            @Schema(description = "本轮候选排序、检查和选择事实") List<RoutingCandidateView> candidates) {
    }

    /** 供应商路由候选只读投影。 */
    public record RoutingCandidateView(
            @Schema(description = "本轮候选排序序号，从1递增") Integer candidateSequence,
            @Schema(description = "候选供应商编码快照") String supplierCode,
            @Schema(description = "候选供应商名称快照") String supplierName,
            @Schema(description = "已发布规则中配置的候选优先级，数值越小越优先") Integer configuredPriority,
            @Schema(description = "动态可用性分类：CONFIRMED已确认，UNKNOWN未知，UNAVAILABLE不可用") String availabilityClass,
            @Schema(description = "标准商品候选供应商履约结算单价快照，单位为分") Long supplierSettlementUnitPrice,
            @Schema(description = "候选资格：true符合，false被淘汰") boolean eligible,
            @Schema(description = "本轮选中标记：true选中，false未选中") boolean selected,
            @Schema(description = "进入、淘汰或选择的稳定原因码") String decisionReasonCode,
            @Schema(description = "进入、淘汰或选择的中文说明快照") String decisionReasonMessage,
            @Schema(description = "选中后创建的B端履约尝试稳定业务编号；未关联时为空") String attemptBOrderNo,
            @Schema(description = "库存、余额、健康度等检查模式、结果、耗时和脱敏原因快照") RoutingCheckResult checkResult) {
    }

    /** 候选检查快照；来自 check_result_json，不含供应商原始报文。 */
    public record RoutingCheckResult(
            @Schema(description = "库存检查结果快照") String inventory,
            @Schema(description = "余额检查结果快照") String balance,
            @Schema(description = "健康度检查结果快照") String health,
            @Schema(description = "脱敏淘汰原因列表") List<String> reasons) {
    }
}
