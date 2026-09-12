package com.aifuli.module.supplychain.border.order.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.order.dto.DetailAvailability;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainAftersaleStatus;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.DETAIL_UNAVAILABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ORDER_QUERY_TIME_RANGE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_CHAIN_ORDER_NOT_FOUND;

/**
 * 平台订单管理端只读查询。列表严格只读总订单表；详情按 {@code detail_type} 只加载一个场景分支，
 * 不读取履约资产、账户或账本，也不在查询线程调用渠道与供应商。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md#4-分层与数据访问}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAdminQueryService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final SupplyChainOrderMapper orderMapper;
    private final FulfillmentRequestMapper fulfillmentRequestMapper;
    private final O2OChannelDetailMapper o2oDetailMapper;
    private final InstoreChannelOrderMapper instoreOrderMapper;
    private final InstorePaymentFlowMapper instorePaymentFlowMapper;

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
}
