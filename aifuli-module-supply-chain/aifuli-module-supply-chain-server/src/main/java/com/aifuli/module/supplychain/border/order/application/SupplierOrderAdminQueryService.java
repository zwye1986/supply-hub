package com.aifuli.module.supplychain.border.order.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
import com.aifuli.module.supplychain.border.order.domain.BOrderSettlementStatus;
import com.aifuli.module.supplychain.border.order.domain.OrderViewMappings;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderType;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ORDER_QUERY_TIME_RANGE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_CHAIN_B_ORDER_NOT_FOUND;

/**
 * 供应商订单管理端只读查询。一行对应一次 B 端履约尝试，不加载 Operation、CallLog 或履约资产。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-09-21-supplier-order-admin-readonly-design.md#4-分层}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierOrderAdminQueryService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final BOrderMapper bOrderMapper;
    private final SupplyChainOrderMapper orderMapper;
    private final SupplierMapper supplierMapper;
    private final OrderFailureViewQuery failureViews;
    private final PlatformSkuMapper platformSkuMapper;
    private final PlatformSpuMapper platformSpuMapper;
    private final SupplierOfferMapper offerMapper;

    /**
     * 分页查询供应商履约尝试。总单与供应商档案批量补齐，禁止列表 N+1。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public PageResult<SupplierOrderView> page(SupplierOrderPageQuery query) {
        validateTimeRange(query.createdFrom(), query.createdTo());
        log.info("分页查询供应商订单 bOrderNo={} orderNo={} buyerCode={} supplierCode={} "
                        + "fulfillmentStatus={} createdFrom={} createdTo={} pageNo={} pageSize={}",
                query.bOrderNo(), query.orderNo(), query.buyerCode(), query.supplierCode(),
                query.fulfillmentStatus(), query.createdFrom(), query.createdTo(),
                query.pageNo(), query.pageSize());
        LambdaQueryWrapper<BOrderDO> wrapper = new LambdaQueryWrapper<BOrderDO>()
                .eq(StringUtils.hasText(query.bOrderNo()), BOrderDO::getBOrderNo, query.bOrderNo())
                .eq(query.fulfillmentStatus() != null, BOrderDO::getFulfillmentStatus,
                        query.fulfillmentStatus() == null ? null : query.fulfillmentStatus().getCode())
                .ge(query.createdFrom() != null, BOrderDO::getCreatedAt, query.createdFrom())
                .le(query.createdTo() != null, BOrderDO::getCreatedAt, query.createdTo())
                .orderByDesc(BOrderDO::getCreatedAt)
                .orderByDesc(BOrderDO::getId);
        Long orderId = resolveSingleOrderId(query.orderNo(), query.buyerCode());
        if (orderId == null && StringUtils.hasText(query.orderNo())) {
            return emptyPage();
        }
        if (orderId != null) {
            wrapper.eq(BOrderDO::getOrderId, orderId);
        } else if (StringUtils.hasText(query.buyerCode())) {
            List<Long> orderIds = orderMapper.selectList(new LambdaQueryWrapper<SupplyChainOrderDO>()
                            .eq(SupplyChainOrderDO::getBuyerCodeSnapshot, query.buyerCode()))
                    .stream().map(SupplyChainOrderDO::getId).toList();
            if (orderIds.isEmpty()) {
                return emptyPage();
            }
            wrapper.in(BOrderDO::getOrderId, orderIds);
        }
        if (StringUtils.hasText(query.supplierCode())) {
            SupplierDO supplier = supplierMapper.selectByCode(query.supplierCode());
            if (supplier == null || supplier.getId() == null) {
                return emptyPage();
            }
            wrapper.eq(BOrderDO::getSupplierId, supplier.getId());
        }
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(query.pageNo() == null ? DEFAULT_PAGE_NO : query.pageNo());
        pageParam.setPageSize(query.pageSize() == null ? DEFAULT_PAGE_SIZE : query.pageSize());
        PageResult<BOrderDO> page = bOrderMapper.selectPage(pageParam, wrapper);
        Map<Long, SupplyChainOrderDO> orders = loadOrders(page.getList());
        Map<Long, SupplierDO> suppliers = loadSuppliers(page.getList());
        return new PageResult<>(assembleRows(page.getList(), orders, suppliers, false), page.getTotal());
    }

    /**
     * 按业务编号查询一次履约尝试。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public SupplierOrderView get(String bOrderNo) {
        log.info("查询供应商订单详情 bOrderNo={}", bOrderNo);
        BOrderDO attempt = bOrderMapper.selectOne(new LambdaQueryWrapper<BOrderDO>()
                .eq(BOrderDO::getBOrderNo, bOrderNo));
        if (attempt == null) {
            throw exception(SUPPLY_CHAIN_B_ORDER_NOT_FOUND);
        }
        SupplyChainOrderDO order = orderMapper.selectById(attempt.getOrderId());
        SupplierDO supplier = attempt.getSupplierId() == null ? null : supplierMapper.selectById(attempt.getSupplierId());
        Map<Long, SupplyChainOrderDO> orders = new HashMap<>();
        if (order != null) {
            orders.put(order.getId(), order);
        }
        Map<Long, SupplierDO> suppliers = new HashMap<>();
        if (supplier != null && supplier.getId() != null) {
            suppliers.put(supplier.getId(), supplier);
        }
        return assembleRows(List.of(attempt), orders, suppliers, true).getFirst();
    }

    /**
     * 当页批量补齐商品业务类型与所选 Offer 的当前供货模式，不使用供应商范围的首项模式。
     * 该模式不是订单冻结事实；缺少关联时保持未知，不影响订单及资金状态。
     * 设计：docs/superpowers/specs/2026-09-21-supplier-order-admin-readonly-design.md#81-当前合作商品供货模式与业务类型。
     */
    private List<SupplierOrderView> assembleRows(List<BOrderDO> attempts, Map<Long, SupplyChainOrderDO> orders,
                                                Map<Long, SupplierDO> suppliers, boolean detail) {
        Set<Long> skuIds = orders.values().stream().map(SupplyChainOrderDO::getPlatformSkuId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PlatformSkuDO> skus = skuIds.isEmpty() ? Map.of()
                : platformSkuMapper.selectBatchIds(skuIds).stream()
                        .collect(Collectors.toMap(PlatformSkuDO::getId, row -> row));
        Set<Long> spuIds = skus.values().stream().map(PlatformSkuDO::getSpuId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PlatformSpuDO> spus = spuIds.isEmpty() ? Map.of()
                : platformSpuMapper.selectBatchIds(spuIds).stream()
                        .collect(Collectors.toMap(PlatformSpuDO::getId, row -> row));
        Set<Long> offerIds = attempts.stream().map(BOrderDO::getOfferId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SupplierOfferDO> offers = offerIds.isEmpty() ? Map.of()
                : offerMapper.selectBatchIds(offerIds).stream()
                        .collect(Collectors.toMap(SupplierOfferDO::getId, row -> row));
        return attempts.stream().map(attempt -> {
            SupplyChainOrderDO order = orders.get(attempt.getOrderId());
            PlatformSkuDO sku = order == null || order.getPlatformSkuId() == null ? null
                    : skus.get(order.getPlatformSkuId());
            PlatformSpuDO spu = sku == null || sku.getSpuId() == null ? null : spus.get(sku.getSpuId());
            SupplierOfferDO offer = attempt.getOfferId() == null ? null : offers.get(attempt.getOfferId());
            String bizType = order == null || (SupplyChainOrderType.CARD.name().equals(order.getOrderType()) && sku == null)
                    ? null : OrderViewMappings.bizType(order.getOrderType(),
                            sku == null ? null : sku.getFulfillmentType(),
                            spu == null ? null : spu.getProductType());
            return toView(attempt, orders, suppliers, detail ? failureViews.read(attempt) : null,
                    bizType, offer == null ? null : offer.getOfferSupplyMode());
        }).toList();
    }

    private Long resolveSingleOrderId(String orderNo, String buyerCode) {
        if (!StringUtils.hasText(orderNo)) {
            return null;
        }
        SupplyChainOrderDO order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return null;
        }
        if (StringUtils.hasText(buyerCode) && !buyerCode.equals(order.getBuyerCodeSnapshot())) {
            return null;
        }
        return order.getId();
    }

    private Map<Long, SupplyChainOrderDO> loadOrders(List<BOrderDO> attempts) {
        Set<Long> ids = new HashSet<>();
        for (BOrderDO attempt : attempts) {
            if (attempt.getOrderId() != null) {
                ids.add(attempt.getOrderId());
            }
        }
        Map<Long, SupplyChainOrderDO> orders = new HashMap<>();
        if (ids.isEmpty()) {
            return orders;
        }
        for (SupplyChainOrderDO order : orderMapper.selectBatchIds(ids)) {
            if (order != null && order.getId() != null) {
                orders.put(order.getId(), order);
            }
        }
        return orders;
    }

    private Map<Long, SupplierDO> loadSuppliers(List<BOrderDO> attempts) {
        Set<Long> ids = new HashSet<>();
        for (BOrderDO attempt : attempts) {
            if (attempt.getSupplierId() != null) {
                ids.add(attempt.getSupplierId());
            }
        }
        Map<Long, SupplierDO> suppliers = new HashMap<>();
        if (ids.isEmpty()) {
            return suppliers;
        }
        for (SupplierDO supplier : supplierMapper.selectBatchIds(ids)) {
            if (supplier != null && supplier.getId() != null) {
                suppliers.put(supplier.getId(), supplier);
            }
        }
        return suppliers;
    }

    private static SupplierOrderView toView(BOrderDO attempt, Map<Long, SupplyChainOrderDO> orders,
                                            Map<Long, SupplierDO> suppliers, OrderFailureViewQuery.View failureClosure,
                                            String bizType, String supplyMode) {
        SupplyChainOrderDO order = attempt.getOrderId() == null ? null : orders.get(attempt.getOrderId());
        if (order == null) {
            throw new IllegalStateException("Supplier attempt is missing its platform order: " + attempt.getBOrderNo());
        }
        SupplierDO supplier = attempt.getSupplierId() == null ? null : suppliers.get(attempt.getSupplierId());
        return new SupplierOrderView(
                attempt.getBOrderNo(),
                attempt.getAttemptNo(),
                Boolean.TRUE.equals(attempt.getActiveFlag()),
                order.getOrderNo(),
                SupplyChainOrderType.valueOf(order.getOrderType()),
                order.getBuyerCodeSnapshot(),
                order.getBuyerNameSnapshot(),
                order.getBuyerOrderNo(),
                supplier == null ? null : supplier.getSupplierCode(),
                supplier == null ? null : supplier.getSupplierName(),
                order.getSkuCodeSnapshot(),
                order.getSkuNameSnapshot(),
                attempt.getBillno(),
                attempt.getExternalOrderNo(),
                attempt.getRequestedQuantity(),
                attempt.getFulfilledQuantity(),
                attempt.getSupplierSettlementUnitPrice(),
                attempt.getExpectedSupplierSettlementAmount(),
                attempt.getCurrency(),
                BOrderFulfillmentStatus.fromCode(attempt.getFulfillmentStatus()),
                BOrderSettlementStatus.fromCode(attempt.getSettlementStatus()),
                attempt.getCreatedAt(),
                attempt.getUpdatedAt(),
                attempt.getStartedAt(),
                attempt.getCompletedAt(), failureClosure, bizType, supplyMode);
    }

    private static PageResult<SupplierOrderView> emptyPage() {
        return new PageResult<>(List.of(), 0L);
    }

    private static void validateTimeRange(LocalDateTime createdFrom, LocalDateTime createdTo) {
        if (createdFrom != null && createdTo != null && createdTo.isBefore(createdFrom)) {
            throw exception(ORDER_QUERY_TIME_RANGE_INVALID);
        }
    }

    /** 管理端供应商订单分页条件；编码均精确匹配。 */
    public record SupplierOrderPageQuery(String bOrderNo, String orderNo, String buyerCode, String supplierCode,
                                         BOrderFulfillmentStatus fulfillmentStatus,
                                         LocalDateTime createdFrom, LocalDateTime createdTo,
                                         Integer pageNo, Integer pageSize) {
    }

    /** 供应商履约尝试只读投影；字段语义与 {@code sc_b_order} DDL COMMENT 一致。 */
    public record SupplierOrderView(
            @Schema(description = "B端履约尝试稳定业务编号") String bOrderNo,
            @Schema(description = "履约请求内供应商尝试序号，从1递增") Integer attemptNo,
            @Schema(description = "活动尝试标记：true 非终态，false 终态") boolean active,
            @Schema(description = "供应链总订单稳定业务编号") String orderNo,
            @Schema(description = "订单类型：CARD/O2O/INSTORE；本工作台现网应为 CARD") SupplyChainOrderType orderType,
            @Schema(description = "受理时采购商业务编码快照") String buyerCode,
            @Schema(description = "受理时采购商名称快照") String buyerName,
            @Schema(description = "采购商侧C端订单号") String buyerOrderNo,
            @Schema(description = "实际尝试供应商业务编码") String supplierCode,
            @Schema(description = "实际尝试供应商名称") String supplierName,
            @Schema(description = "CARD场景平台SKU编码快照") String skuCode,
            @Schema(description = "CARD场景平台SKU名称快照") String skuName,
            @Schema(description = "发给供应商的稳定业务单号") String billno,
            @Schema(description = "供应商返回的外部订单号") String externalOrderNo,
            @Schema(description = "本次整单请求履约数量") Long requestedQuantity,
            @Schema(description = "本次已经成功交付数量") Long fulfilledQuantity,
            @Schema(description = "冻结的供应商履约结算单价，单位为分") Long supplierSettlementUnitPrice,
            @Schema(description = "预计供应商结算金额，单位为分") Long expectedSupplierSettlementAmount,
            @Schema(description = "供应商结算币种，一期固定CNY") String currency,
            @Schema(description = "对外枚举名：WAITING=0待执行、PENDING=10执行中、PROCESSING=20查询中、SUCCEEDED=40成功、FAILED=50确定失败、CANCELLED=60已取消、MANUAL=90人工处理")
            BOrderFulfillmentStatus fulfillmentStatus,
            @Schema(description = "对外枚举名：PENDING=0待报告、MATCHED=10已匹配、MISMATCH=20有差异、MANUAL=90人工处理")
            BOrderSettlementStatus settlementStatus,
            @Schema(description = "B端履约尝试创建时间") LocalDateTime createdAt,
            @Schema(description = "B端履约尝试最后更新时间") LocalDateTime updatedAt,
            @Schema(description = "首次开始外部履约时间") LocalDateTime startedAt,
            @Schema(description = "履约尝试进入终态时间") LocalDateTime completedAt,
            @Schema(description = "失败处置详情；列表及成功尝试为空") OrderFailureViewQuery.View failureClosure,
            @Schema(description = "页面业务类型，与平台订单一致；缺少CARD商品事实时为空") String bizType,
            @Schema(description = "该Offer供货模式（当前合作商品配置），不是订单历史快照") String supplyMode) {
    }
}
