package com.aifuli.module.supplychain.border.order.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
import com.aifuli.module.supplychain.border.order.domain.BOrderSettlementStatus;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderType;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
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
import java.util.Set;

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
        return new PageResult<>(page.getList().stream()
                .map(attempt -> toView(attempt, orders, suppliers))
                .toList(), page.getTotal());
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
        return toView(attempt, orders, suppliers);
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
                                            Map<Long, SupplierDO> suppliers) {
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
                attempt.getCompletedAt());
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
            @Schema(description = "对外枚举名：WAITING=0待执行、PENDING=10执行中、PROCESSING=20查询中、SUCCEEDED=40成功、FAILED=50确定失败、MANUAL=90人工处理")
            BOrderFulfillmentStatus fulfillmentStatus,
            @Schema(description = "对外枚举名：PENDING=0待报告、MATCHED=10已匹配、MISMATCH=20有差异、MANUAL=90人工处理")
            BOrderSettlementStatus settlementStatus,
            @Schema(description = "B端履约尝试创建时间") LocalDateTime createdAt,
            @Schema(description = "B端履约尝试最后更新时间") LocalDateTime updatedAt,
            @Schema(description = "首次开始外部履约时间") LocalDateTime startedAt,
            @Schema(description = "履约尝试进入终态时间") LocalDateTime completedAt) {
    }
}
