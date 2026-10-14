package com.aifuli.module.supplychain.border.order.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAssetDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SensitiveAccessAuditDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SupplierRoutingRoundDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAssetMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SensitiveAccessAuditMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SupplierRoutingRoundMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentAssetStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.SensitiveAccessAuditAccessType;
import com.aifuli.module.supplychain.border.fulfillment.domain.SensitiveAccessAuditResultStatus;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
import com.aifuli.module.supplychain.border.order.domain.OrderViewMappings;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderType;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.StandardPricingQuoteDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.StandardPricingQuoteMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeModeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeModeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aifuli.module.supplychain.api.fulfillment.dto.SupplierValidTimeState;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengType201AssetCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ORDER_EXPORT_EXCEED_LIMIT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ORDER_QUERY_TIME_RANGE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_CHAIN_B_ORDER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_CHAIN_ORDER_NOT_FOUND;

/**
 * 订单管理前端契约查询服务（order-views）。对齐前端 OrderVO / OrderRouteRecordVO 结构：
 * bizType 与状态按前端枚举映射（{@link OrderViewMappings}），金额单位为分；
 * 旧 /supply-chain/orders、/supply-chain/supplier-orders 端点完全不动。
 *
 * <p>敏感资产明文：详情接口解密交付资产并逐条写敏感访问审计（principal=ADMIN_USER）。
 * TODO(安全)：明文返回为过渡方案，后续版本将改为授权查询 + 默认脱敏。
 * 设计见
 * {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#3-订单管理端对齐设计}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderViewQueryService {
    private final OrderFailureViewQuery failureViews;
    private final com.aifuli.module.supplychain.border.order.dal.mysql.OrderStatusLogMapper statusLogs;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 导出上限：设计 §3.5，超限提示缩小筛选范围。 */
    public static final int EXPORT_MAX_ROWS = 10000;
    /** 管理端明文查看资产写入审计使用的 Scope 编码（非采购商 RPC Scope 体系，仅审计留痕）。 */
    private static final String AUDIT_SCOPE_ADMIN_ORDER_VIEW = "ADMIN_ORDER_VIEW";
    /** 管理端明文查看资产的审计主体类型，与 sc_sensitive_access_audit.principal_type 语义对齐。 */
    private static final String PRINCIPAL_TYPE_ADMIN_USER = "ADMIN_USER";
    /** 采购商用户标识 HMAC 摘要域分隔符，与 BuyerFulfillmentQueryService 保持一致。 */
    private static final String SENSITIVE_AUDIT_USER_DOMAIN = "SENSITIVE_AUDIT_USER";

    /**
     * bizType 筛选的 CARD 子查询（跨表 IN 子查询，Lambda 无法表达，AGENTS §8 允许的例外）。
     * 表列名与 002-catalog / 003-supply DDL 对齐。
     */
    private static final String SUBSQL_RECHARGE_SKU =
            "SELECT id FROM sc_platform_sku WHERE fulfillment_type IN ('DIRECT_RECHARGE', 'ACCOUNT_BOUND_BENEFIT')";
    private static final String SUBSQL_VOUCHER_SKU_PREFIX =
            "SELECT sku.id FROM sc_platform_sku sku JOIN sc_platform_spu spu ON sku.spu_id = spu.id"
                    + " WHERE sku.fulfillment_type IN ('CARD_CODE_SECRET', 'VOUCHER_CODE', 'VOUCHER_URL')";
    private static final String SUBSQL_VOUCHER_SKU_ALL = SUBSQL_VOUCHER_SKU_PREFIX;
    private static final String SUBSQL_VOUCHER_SKU_MOVIE =
            SUBSQL_VOUCHER_SKU_PREFIX + " AND spu.product_type = 'MOVIE'";
    private static final String SUBSQL_VOUCHER_SKU_DINING =
            SUBSQL_VOUCHER_SKU_PREFIX + " AND spu.product_type = 'DINING'";
    private static final String SUBSQL_VOUCHER_SKU_DEFAULT =
            SUBSQL_VOUCHER_SKU_PREFIX
                    + " AND (spu.product_type IS NULL OR spu.product_type NOT IN ('MOVIE', 'DINING'))";

    private final SupplyChainOrderMapper orderMapper;
    private final BOrderMapper bOrderMapper;
    private final SupplierMapper supplierMapper;
    private final FulfillmentRequestMapper fulfillmentRequestMapper;
    private final FulfillmentAssetMapper assetMapper;
    private final FulfillmentOperationMapper operationMapper;
    private final SupplierRoutingRoundMapper routingRoundMapper;
    private final SensitiveAccessAuditMapper auditMapper;
    private final StandardPricingQuoteMapper quoteMapper;
    private final PlatformSkuMapper platformSkuMapper;
    private final PlatformSpuMapper platformSpuMapper;
    private final SupplierSupplyScopeMapper supplyScopeMapper;
    private final SupplierSupplyScopeModeMapper supplyScopeModeMapper;
    private final EnvelopeCryptoPort envelopeCrypto;
    private final HmacFingerprintPort hmacFingerprint;
    private final BusinessNoGenerator businessNoGenerator;
    private final SupplyChainDatabaseClock databaseClock;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ 分页

    /**
     * 前端契约分页：严格只读总订单表 + 批量补齐（报价凭证/B端尝试/SKU/SPU），禁止列表 N+1 与资产解密。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public PageResult<OrderView> page(OrderViewPageQuery query) {
        validateTimeRange(query);
        log.info("订单前端契约分页入口 orderNo={} bizTypes={} statuses={} supplierNames={} distributorNames={} "
                        + "productName={} buyerKeyword={} supplierOrderNo={} distributorOrderNo={} "
                        + "createFrom={} createTo={} pageNo={} pageSize={}",
                query.orderNo(), query.bizTypes(), query.statuses(), query.supplierNames(),
                query.distributorNames(), query.productName(), query.buyerKeyword(), query.supplierOrderNo(),
                query.distributorOrderNo(), query.createFrom(), query.createTo(), query.pageNo(), query.pageSize());

        LambdaQueryWrapper<SupplyChainOrderDO> wrapper = baseWrapper(query);
        applyBizTypeFilter(wrapper, query.bizTypes());
        applyStatusFilter(wrapper, query.statuses());

        PageParam pageParam = new PageParam();
        pageParam.setPageNo(query.pageNo() == null ? DEFAULT_PAGE_NO : query.pageNo());
        pageParam.setPageSize(query.pageSize() == null ? DEFAULT_PAGE_SIZE : query.pageSize());
        PageResult<SupplyChainOrderDO> page = orderMapper.selectPage(pageParam, wrapper);
        List<OrderView> rows = assembleRows(page.getList(), null);
        return new PageResult<>(rows, page.getTotal());
    }

    // ------------------------------------------------------------------ 详情

    /**
     * 前端契约详情。source=PLATFORM 按 total orderNo，source=SUPPLIER 按 B 端单号。
     * 本方法含资产解密与审计写入（principal=登录用户），禁止标记 readOnly 事务。
     */
    public OrderView get(String orderNo, String source, Long userId) {
        boolean supplierView = "SUPPLIER".equalsIgnoreCase(source);
        log.info("订单前端契约详情入口 orderNo={} source={} userId={}", orderNo, source, userId);
        if (supplierView) {
            return supplierView(orderNo, userId);
        }
        SupplyChainOrderDO order = requireOrderByNo(orderNo);
        List<OrderView> rows = assembleRows(List.of(order), userId);
        OrderView view = rows.get(0);
        log.info("订单前端契约详情完成 orderNo={} bizType={} assetLoaded={}",
                orderNo, view.bizType(), view.items() != null && !view.items().isEmpty());
        return view;
    }

    /** source=SUPPLIER：按 B 端单号组装前端视图，回填总单号与供货模式。 */
    private OrderView supplierView(String bOrderNo, Long userId) {
        BOrderDO attempt = bOrderMapper.selectOne(new LambdaQueryWrapper<BOrderDO>()
                .eq(BOrderDO::getBOrderNo, bOrderNo));
        if (attempt == null) {
            throw exception(SUPPLY_CHAIN_B_ORDER_NOT_FOUND);
        }
        SupplyChainOrderDO order = orderMapper.selectById(attempt.getOrderId());
        if (order == null) {
            throw exception(SUPPLY_CHAIN_ORDER_NOT_FOUND);
        }
        SupplierDO supplier = attempt.getSupplierId() == null ? null
                : supplierMapper.selectById(attempt.getSupplierId());
        List<OrderView> rows = assembleRows(List.of(order), null);
        OrderView view = rows.get(0);
        String supplyMode = resolveSupplyMode(attempt.getSupplierId(), order);
        List<SupplierOrderView> supplierOrders = List.of(toSupplierOrderView(attempt, supplier));
        // 供货模式与发货时间本期按设计 §3.3：供货模式取供应商供货范围配置，取不到为空；shipTime/deliveryMode 本期空
        OrderView detail = withSupplierView(view, order, attempt, supplyMode, supplierOrders);
        log.info("B端订单前端契约详情完成 bOrderNo={} orderNo={} userId={}", bOrderNo, order.getOrderNo(), userId);
        return detail;
    }

    // ------------------------------------------------------------------ 路由记录

    /**
     * 前端契约路由记录（route-record 单数）。O2O/INSTORE 未进入标准路由，返回概要 + 空 steps。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public OrderRouteRecordView routeRecord(String orderNo) {
        log.info("订单前端契约路由记录入口 orderNo={}", orderNo);
        SupplyChainOrderDO order = requireOrderByNo(orderNo);
        if (!SupplyChainOrderType.CARD.name().equals(order.getOrderType())) {
            return new OrderRouteRecordView(orderNo, order.getSkuNameSnapshot(), null,
                    0, 0, null, null, null, null, List.of());
        }
        List<BOrderDO> attempts = bOrderMapper.selectList(new LambdaQueryWrapper<BOrderDO>()
                .eq(BOrderDO::getOrderId, order.getId())
                .orderByAsc(BOrderDO::getAttemptNo)
                .orderByAsc(BOrderDO::getId));
        Map<Long, SupplierDO> suppliers = loadSuppliers(attempts);
        Map<Long, FulfillmentOperationDO> lastOperations = loadLastOperations(attempts);
        List<RouteStep> steps = new ArrayList<>();
        int failCount = 0;
        BOrderDO active = null;
        BOrderDO lastSucceeded = null;
        for (BOrderDO attempt : attempts) {
            boolean succeeded = attempt.getFulfillmentStatus() != null
                    && attempt.getFulfillmentStatus() == BOrderFulfillmentStatus.SUCCEEDED.getCode();
            if (!succeeded) {
                failCount++;
            } else {
                lastSucceeded = attempt;
            }
            if (Boolean.TRUE.equals(attempt.getActiveFlag())) {
                active = attempt;
            }
            steps.add(toStep(attempt, suppliers, lastOperations, succeeded));
        }
        BOrderDO finalAttempt = active != null ? active : lastSucceeded;
        SupplierDO finalSupplier = finalAttempt == null || finalAttempt.getSupplierId() == null
                ? null : suppliers.get(finalAttempt.getSupplierId());
        Long durationSec = resolveDurationSec(order);
        String status = steps.isEmpty() ? null
                : (failCount == steps.size() ? "FAILED" : "SUCCESS");
        log.info("订单前端契约路由记录完成 orderNo={} tryCount={} failCount={} status={}",
                orderNo, steps.size(), failCount, status);
        return new OrderRouteRecordView(orderNo, order.getSkuNameSnapshot(),
                resolveBizType(order), attempts.size(), failCount, status,
                finalSupplier == null ? null : finalSupplier.getSupplierName(),
                finalAttempt == null ? null : finalAttempt.getBOrderNo(),
                durationSec, List.copyOf(steps));
    }

    // ------------------------------------------------------------------ 导出

    /**
     * 导出数据行：复用分页全部筛选条件，上限 {@link #EXPORT_MAX_ROWS} 行，超限报错。
     * 列表只读、不解密资产；行结构与分页一致。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public List<OrderView> exportRows(OrderViewPageQuery query) {
        validateTimeRange(query);
        LambdaQueryWrapper<SupplyChainOrderDO> wrapper = baseWrapper(query);
        applyBizTypeFilter(wrapper, query.bizTypes());
        applyStatusFilter(wrapper, query.statuses());
        // 取上限+1行用于判断超限，避免额外 count 查询
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(DEFAULT_PAGE_NO);
        pageParam.setPageSize(EXPORT_MAX_ROWS + 1);
        List<SupplyChainOrderDO> orders = orderMapper.selectPage(pageParam, wrapper).getList();
        if (orders.size() > EXPORT_MAX_ROWS) {
            throw exception(ORDER_EXPORT_EXCEED_LIMIT);
        }
        log.info("订单前端契约导出 rows={} bizTypes={} statuses={}",
                orders.size(), query.bizTypes(), query.statuses());
        return assembleRows(orders, null);
    }

    // ------------------------------------------------------------------ 组装

    /**
     * 批量把总单 DO 组装为前端 OrderView。userId 非空时解密交付资产并写审计（仅详情调用）；
     * 列表与导出路径传 null，不触碰资产。
     */
    private List<OrderView> assembleRows(List<SupplyChainOrderDO> orders, Long userId) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, StandardPricingQuoteDO> quotes = loadQuotes(orders);
        Map<Long, List<BOrderDO>> attemptsByOrder = loadAttempts(orders);
        Set<Long> supplierIds = new HashSet<>();
        for (List<BOrderDO> attempts : attemptsByOrder.values()) {
            for (BOrderDO attempt : attempts) {
                if (attempt.getSupplierId() != null) {
                    supplierIds.add(attempt.getSupplierId());
                }
            }
        }
        Map<Long, SupplierDO> suppliers = loadSuppliers(supplierIds);
        Map<Long, PlatformSkuDO> skus = loadSkus(orders);
        Map<Long, PlatformSpuDO> spus = loadSpus(skus.values());

        List<OrderView> rows = new ArrayList<>(orders.size());
        for (SupplyChainOrderDO order : orders) {
            PlatformSkuDO sku = order.getPlatformSkuId() == null ? null : skus.get(order.getPlatformSkuId());
            PlatformSpuDO spu = sku == null || sku.getSpuId() == null ? null : spus.get(sku.getSpuId());
            StandardPricingQuoteDO quote = order.getPricingQuoteId() == null ? null
                    : quotes.get(order.getPricingQuoteId());
            List<BOrderDO> attempts = attemptsByOrder.getOrDefault(order.getId(), List.of());
            OrderItemView item = toItemView(order, quote,
                    userId == null ? OrderAssets.EMPTY : loadAssets(order, userId));
            List<SupplierOrderView> supplierOrders = attempts.stream()
                    .map(attempt -> toSupplierOrderView(attempt,
                            attempt.getSupplierId() == null ? null : suppliers.get(attempt.getSupplierId()), userId != null))
                    .toList();
            rows.add(new OrderView(
                    order.getOrderNo(),
                    null,
                    OrderViewMappings.bizType(order.getOrderType(),
                            sku == null ? null : sku.getFulfillmentType(),
                            spu == null ? null : spu.getProductType()),
                    OrderViewMappings.platformStatus(SupplyChainOrderStatus.fromCode(order.getOrderStatus())),
                    order.getBuyerSettlementAmount(),
                    order.getQuantity(),
                    order.getDisplaySupplierName(),
                    order.getBuyerNameSnapshot(),
                    order.getProjectNameSnapshot(),
                    order.getBuyerUserId(),
                    // buyerName/payMethod/payTradeNo/receiverName/receiverPhone/receiverAddress/couponAmount/freightAmount：
                    // 商城侧字段，本期恒为空（设计 §3.2）
                    null, null, null, null, null, null, null, null,
                    order.getProductSubtotalCents(),
                    order.getServiceFeeCents(),
                    order.getSalesUnitPriceCents(),
                    quote == null ? null : quote.getDistributionUnitPriceCents(),
                    order.getBuyerSettlementAmount(),
                    null,
                    List.of(item),
                    supplierOrders,
                    List.of(),
                    order.getCreatedAt(),
                    null, null, null, userId == null ? null : terminalReason(order)));
        }
        return rows;
    }

    /** source=SUPPLIER 视图回填：总单号关联、供货模式、单条供应商尝试。 */
    private OrderView withSupplierView(OrderView view, SupplyChainOrderDO order, BOrderDO attempt,
                                       String supplyMode, List<SupplierOrderView> supplierOrders) {
        return new OrderView(view.orderNo(), order.getOrderNo(), view.bizType(),
                OrderViewMappings.supplierStatus(BOrderFulfillmentStatus.fromCode(attempt.getFulfillmentStatus())),
                view.amount(), view.qty(), view.supplierName(), view.distributorName(), view.projectName(),
                view.buyerMobile(), view.buyerName(), view.payMethod(), view.payTradeNo(),
                view.receiverName(), view.receiverPhone(), view.receiverAddress(),
                view.couponAmount(), view.freightAmount(), view.goodsAmount(), view.serviceFee(),
                view.salePrice(), view.distPrice(), view.distributorPayAmount(), view.projectPayAmount(),
                view.items(), supplierOrders, view.traces(), view.createdAt(),
                supplyMode, null, null, view.terminalReasonCode());
    }

    /** 平台终结原因来自平台状态日志，不以某个供应商尝试替代总单状态真相。 */
    private String terminalReason(SupplyChainOrderDO order) {
        if (!java.util.Objects.equals(SupplyChainOrderStatus.FAILED.getCode(), order.getOrderStatus())) return null;
        var rows = statusLogs.selectList(new LambdaQueryWrapper<com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO>()
                .eq(com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO::getOrderId, order.getId())
                .eq(com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO::getToOrderStatus, order.getOrderStatus())
                .orderByDesc(com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO::getId));
        return rows.isEmpty() ? null : rows.getFirst().getReasonCode();
    }

    private OrderItemView toItemView(SupplyChainOrderDO order, StandardPricingQuoteDO quote,
                                     OrderAssets assets) {
        return new OrderItemView(
                order.getSkuCodeSnapshot(),
                order.getSkuNameSnapshot(),
                order.getQuantity(),
                order.getSalesUnitPriceCents(),
                quote == null ? null : quote.getDistributionUnitPriceCents(),
                assets.cardInfos(),
                assets.rechargeInfo(),
                assets.ticketCodes(),
                assets.pickupCodes());
    }

    private SupplierOrderView toSupplierOrderView(BOrderDO attempt, SupplierDO supplier) {
        return toSupplierOrderView(attempt, supplier, true);
    }

    private SupplierOrderView toSupplierOrderView(BOrderDO attempt, SupplierDO supplier, boolean detail) {
        BOrderFulfillmentStatus status = BOrderFulfillmentStatus.fromCode(attempt.getFulfillmentStatus());
        return new SupplierOrderView(
                attempt.getBOrderNo(),
                supplier == null ? null : supplier.getSupplierName(),
                OrderViewMappings.supplierStatus(status),
                attempt.getExpectedSupplierSettlementAmount(),
                attempt.getRequestedQuantity(),
                attempt.getCreatedAt(), detail ? failureViews.read(attempt) : null);
    }

    private RouteStep toStep(BOrderDO attempt, Map<Long, SupplierDO> suppliers,
                             Map<Long, FulfillmentOperationDO> lastOperations, boolean succeeded) {
        SupplierDO supplier = attempt.getSupplierId() == null ? null : suppliers.get(attempt.getSupplierId());
        FulfillmentOperationDO lastOperation = lastOperations.get(attempt.getId());
        String resultClass = lastOperation == null ? null : lastOperation.getResultClass();
        // TODO(设计§3.4)：sc_fulfillment_call_log 仅存请求/响应摘要（digest），无可读报文，
        // request/response 本期返回空；原始报文回放待调用审计能力扩展后补充
        return new RouteStep(
                attempt.getAttemptNo(),
                supplier == null ? null : supplier.getSupplierName(),
                succeeded ? "SUCCESS" : "FAILED",
                attempt.getStartedAt() != null ? attempt.getStartedAt() : attempt.getCreatedAt(),
                attempt.getSupplierSettlementUnitPrice(),
                attempt.getRequestedQuantity(),
                succeeded ? null : OrderViewMappings.routeFailReason(resultClass),
                resultClass,
                null,
                null);
    }

    // ------------------------------------------------------------------ 资产明文（含审计）

    /**
     * 解密订单全部可交付资产并逐条写敏感访问审计（principal=登录用户 userId）。
     * TODO(安全)：明文返回为过渡方案（设计 §3.6），后续版本改为授权查询 + 默认脱敏。
     */
    private OrderAssets loadAssets(SupplyChainOrderDO order, Long userId) {
        if (!SupplyChainOrderType.CARD.name().equals(order.getOrderType())) {
            return OrderAssets.EMPTY;
        }
        FulfillmentRequestDO request = fulfillmentRequestMapper.selectByOrderId(order.getId());
        if (request == null) {
            return OrderAssets.EMPTY;
        }
        List<FulfillmentAssetDO> assets = assetMapper.selectListByFulfillmentRequestId(request.getId());
        LocalDateTime now = databaseClock.now();
        List<CardInfo> cardInfos = new ArrayList<>();
        List<RechargeInfo> rechargeInfos = new ArrayList<>();
        List<String> ticketCodes = new ArrayList<>();
        List<String> pickupCodes = new ArrayList<>();
        for (FulfillmentAssetDO asset : assets) {
            if (!isDeliverable(asset.getLifecycleStatus())) {
                continue;
            }
            DecodedAsset decoded = decryptAsset(order, asset, userId, now);
            JsonNode payload = decoded.payload();
            recordAdminAudit(order, asset, userId, SensitiveAccessAuditResultStatus.SUCCESS.getCode(),
                    "ADMIN_PLAINTEXT_VIEW", now);
            switch (asset.getAssetType() == null ? "" : asset.getAssetType()) {
                case "CARD_CODE_SECRET" -> cardInfos.add(new CardInfo(
                        text(payload, "cardCode"), text(payload, "cardSecret"),
                        asset.getValidFrom(), asset.getValidUntil(),
                        decoded.card() == null ? null : decoded.card().validTimeRaw(),
                        decoded.card() == null ? null : decoded.card().validTimeState()));
                case "ACCOUNT_BOUND_BENEFIT" -> rechargeInfos.add(new RechargeInfo(
                        order.getFulfillmentAccountValue(),
                        text(payload, "voucherCode"),
                        asset.getAssetNo()));
                case "VOUCHER_CODE" -> ticketCodes.add(text(payload, "voucherCode"));
                case "VOUCHER_URL" -> pickupCodes.add(text(payload, "voucherUrl"));
                default -> log.warn("订单前端契约详情遇到未知资产类型 orderNo={} assetType={}",
                        order.getOrderNo(), asset.getAssetType());
            }
        }
        return new OrderAssets(List.copyOf(cardInfos), List.copyOf(rechargeInfos),
                List.copyOf(ticketCodes), List.copyOf(pickupCodes));
    }

    /**
     * 解密边界先校验华昇封闭载荷再返回投影，失败写FAILED审计；finally擦除明文字节。
     * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#72-task7授权查询与有效期展示契约。
     */
    private DecodedAsset decryptAsset(SupplyChainOrderDO order, FulfillmentAssetDO asset, Long userId,
                                  LocalDateTime now) {
        byte[] associatedData = (asset.getBOrderId() + "|" + asset.getSequenceNo() + "|" + asset.getAssetType())
                .getBytes(StandardCharsets.UTF_8);
        byte[] decrypted;
        try {
            decrypted = envelopeCrypto.decrypt(asset.getEncryptedPayload(), asset.getKeyVersion(), associatedData);
        } catch (Exception e) {
            recordAdminAudit(order, asset, userId, SensitiveAccessAuditResultStatus.FAILED.getCode(),
                    "DECRYPT_FAILED", now);
            throw new IllegalStateException("管理端资产解密失败: " + asset.getAssetNo(), e);
        }
        try {
            JsonNode payload = objectMapper.reader()
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(decrypted);
            if (payload == null || !payload.isObject()) throw new IllegalArgumentException("资产载荷须为对象");
            HuashengType201AssetCodec.StoredCard card = null;
            if (payload.has("payloadSchemaVersion") || payload.has("supplierPayload")) {
                card = new HuashengType201AssetCodec().readStoredPayload(decrypted,
                        asset.getSequenceNo(), asset.getAssetType(), asset.getExternalAssetId(),
                        asset.getValidFrom(), asset.getValidUntil());
            }
            return new DecodedAsset(payload, card);
        } catch (Exception e) {
            recordAdminAudit(order, asset, userId, SensitiveAccessAuditResultStatus.FAILED.getCode(),
                    "PAYLOAD_CORRUPTED", now);
            throw new IllegalStateException("管理端资产载荷格式异常: " + asset.getAssetNo(),
                    new IllegalArgumentException("资产载荷校验失败"));
        } finally {
            if (decrypted != null) Arrays.fill(decrypted, (byte) 0);
        }
    }

    /** 严格校验后的内部投影；不允许记录整个载荷。设计见生产闭环设计 §7.2。 */
    private record DecodedAsset(JsonNode payload, HuashengType201AssetCodec.StoredCard card) {
        @Override public String toString() { return "DecodedAsset[redacted]"; }
    }

    /** 管理端明文查看资产的审计留痕：principal=ADMIN_USER，资源=FULFILLMENT_ASSET。 */
    private void recordAdminAudit(SupplyChainOrderDO order, FulfillmentAssetDO asset, Long userId,
                                  short resultStatus, String reasonCode, LocalDateTime now) {
        // 标准资产购买人列已删除（052）：不再计算购买人用户摘要，buyerUserDigest 恒为 NULL（不以零长
        // 数组占位）；principal 保持真实 ADMIN_USER 操作人。设计见
        // docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#1-双入口与共享命令。
        SensitiveAccessAuditDO audit = SensitiveAccessAuditDO.builder()
                .auditNo(businessNoGenerator.next(BusinessNoPrefixes.AUD))
                .accessType(SensitiveAccessAuditAccessType.ASSET_DECRYPT.name())
                .principalType(PRINCIPAL_TYPE_ADMIN_USER)
                .principalId(String.valueOf(userId == null ? 0L : userId))
                .buyerId(asset.getBuyerId())
                .resourceType("FULFILLMENT_ASSET")
                .resourceId(asset.getId() == null ? 0L : asset.getId())
                .scopeCode(AUDIT_SCOPE_ADMIN_ORDER_VIEW)
                .resultStatus(resultStatus)
                .reasonCode(reasonCode)
                .traceId(order.getOrderNo())
                .accessedAt(now)
                .build();
        try {
            auditMapper.insert(audit);
        } catch (Exception e) {
            throw new IllegalStateException("敏感访问审计写入失败，安全拒绝返回: " + order.getOrderNo(), e);
        }
    }

    private static boolean isDeliverable(Short lifecycleStatus) {
        return lifecycleStatus != null
                && (lifecycleStatus == FulfillmentAssetStatus.AVAILABLE.getCode()
                || lifecycleStatus == FulfillmentAssetStatus.DELIVERED.getCode());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    // ------------------------------------------------------------------ 筛选条件

    private LambdaQueryWrapper<SupplyChainOrderDO> baseWrapper(OrderViewPageQuery query) {
        LambdaQueryWrapper<SupplyChainOrderDO> wrapper = new LambdaQueryWrapper<SupplyChainOrderDO>()
                .eq(StringUtils.hasText(query.orderNo()), SupplyChainOrderDO::getOrderNo, query.orderNo())
                .eq(StringUtils.hasText(query.buyerOrderNo()), SupplyChainOrderDO::getBuyerOrderNo,
                        query.buyerOrderNo())
                .eq(StringUtils.hasText(query.distributorOrderNo()), SupplyChainOrderDO::getBuyerOrderNo,
                        query.distributorOrderNo())
                .eq(StringUtils.hasText(query.buyerCode()), SupplyChainOrderDO::getBuyerCodeSnapshot,
                        query.buyerCode())
                .eq(StringUtils.hasText(query.supplierCode()), SupplyChainOrderDO::getDisplaySupplierCode,
                        query.supplierCode())
                .eq(StringUtils.hasText(query.skuCode()), SupplyChainOrderDO::getSkuCodeSnapshot,
                        query.skuCode())
                .like(StringUtils.hasText(query.productName()), SupplyChainOrderDO::getSkuNameSnapshot,
                        query.productName())
                .like(StringUtils.hasText(query.buyerKeyword()), SupplyChainOrderDO::getBuyerUserId,
                        query.buyerKeyword())
                .in(!query.supplierNames().isEmpty(), SupplyChainOrderDO::getDisplaySupplierName,
                        query.supplierNames())
                .in(!query.distributorNames().isEmpty(), SupplyChainOrderDO::getBuyerNameSnapshot,
                        query.distributorNames())
                .ge(query.createFrom() != null, SupplyChainOrderDO::getCreatedAt, query.createFrom())
                .le(query.createTo() != null, SupplyChainOrderDO::getCreatedAt, query.createTo())
                .orderByDesc(SupplyChainOrderDO::getCreatedAt)
                .orderByDesc(SupplyChainOrderDO::getId);
        Long orderIdBySupplierNo = resolveOrderIdBySupplierOrderNo(query.supplierOrderNo());
        if (query.supplierOrderNo() != null && !query.supplierOrderNo().isBlank()) {
            if (orderIdBySupplierNo == null) {
                // B 端单号不存在时结果集为空，应用恒假条件短路
                wrapper.in(SupplyChainOrderDO::getId, List.of(-1L));
            } else {
                wrapper.eq(SupplyChainOrderDO::getId, orderIdBySupplierNo);
            }
        }
        return wrapper;
    }

    /** bizTypes 前端枚举 → OR 组合的 SQL 条件（设计 §4.1 反向推导）。 */
    private void applyBizTypeFilter(LambdaQueryWrapper<SupplyChainOrderDO> wrapper, List<String> bizTypes) {
        if (bizTypes == null || bizTypes.isEmpty()) {
            return;
        }
        Set<String> types = new HashSet<>();
        for (String type : bizTypes) {
            if (type == null || type.isBlank()) {
                continue;
            }
            validateBizType(type.trim());
            types.add(type.trim());
        }
        List<Consumer<LambdaQueryWrapper<SupplyChainOrderDO>>> groups = new ArrayList<>();
        if (types.contains(OrderViewMappings.BIZ_HEMA)) {
            groups.add(w -> w.eq(SupplyChainOrderDO::getOrderType, SupplyChainOrderType.O2O.name()));
        }
        if (types.contains(OrderViewMappings.BIZ_INSTORE)) {
            groups.add(w -> w.eq(SupplyChainOrderDO::getOrderType, SupplyChainOrderType.INSTORE.name()));
        }
        if (types.contains(OrderViewMappings.BIZ_VIRTUAL_RECHARGE)) {
            groups.add(w -> w.eq(SupplyChainOrderDO::getOrderType, SupplyChainOrderType.CARD.name())
                    .inSql(SupplyChainOrderDO::getPlatformSkuId, SUBSQL_RECHARGE_SKU));
        }
        boolean voucher = types.contains(OrderViewMappings.BIZ_VIRTUAL_CARD);
        boolean dining = types.contains(OrderViewMappings.BIZ_DINING);
        boolean movie = types.contains(OrderViewMappings.BIZ_MOVIE);
        if (voucher && dining && movie) {
            groups.add(w -> w.eq(SupplyChainOrderDO::getOrderType, SupplyChainOrderType.CARD.name())
                    .inSql(SupplyChainOrderDO::getPlatformSkuId, SUBSQL_VOUCHER_SKU_ALL));
        } else {
            if (voucher) {
                groups.add(w -> w.eq(SupplyChainOrderDO::getOrderType, SupplyChainOrderType.CARD.name())
                        .inSql(SupplyChainOrderDO::getPlatformSkuId, SUBSQL_VOUCHER_SKU_DEFAULT));
            }
            if (dining) {
                groups.add(w -> w.eq(SupplyChainOrderDO::getOrderType, SupplyChainOrderType.CARD.name())
                        .inSql(SupplyChainOrderDO::getPlatformSkuId, SUBSQL_VOUCHER_SKU_DINING));
            }
            if (movie) {
                groups.add(w -> w.eq(SupplyChainOrderDO::getOrderType, SupplyChainOrderType.CARD.name())
                        .inSql(SupplyChainOrderDO::getPlatformSkuId, SUBSQL_VOUCHER_SKU_MOVIE));
            }
        }
        if (groups.isEmpty()) {
            return;
        }
        wrapper.and(root -> {
            for (int i = 0; i < groups.size(); i++) {
                if (i == 0) {
                    groups.get(0).accept(root);
                } else {
                    root.or(groups.get(i));
                }
            }
        });
    }

    /** statuses 前端枚举 → order_status 码集合（设计 §4.2；WAIT_PAY 永不匹配仅告警）。 */
    private void applyStatusFilter(LambdaQueryWrapper<SupplyChainOrderDO> wrapper, List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return;
        }
        Set<Short> codes = new HashSet<>();
        for (String status : statuses) {
            if (status == null || status.isBlank()) {
                continue;
            }
            switch (status.trim()) {
                case OrderViewMappings.PLATFORM_WAIT_PAY ->
                        log.warn("平台状态筛选 WAIT_PAY 在供应链域永不匹配，已忽略");
                case OrderViewMappings.PLATFORM_PROCESSING -> {
                    codes.add(SupplyChainOrderStatus.CREATED.getCode());
                    codes.add(SupplyChainOrderStatus.PROCESSING.getCode());
                    codes.add(SupplyChainOrderStatus.PARTIALLY_SUCCEEDED.getCode());
                }
                case OrderViewMappings.PLATFORM_MANUAL -> codes.add(SupplyChainOrderStatus.MANUAL.getCode());
                case OrderViewMappings.PLATFORM_SUCCESS -> codes.add(SupplyChainOrderStatus.SUCCEEDED.getCode());
                case OrderViewMappings.PLATFORM_FAILED -> codes.add(SupplyChainOrderStatus.FAILED.getCode());
                case OrderViewMappings.PLATFORM_CLOSED -> codes.add(SupplyChainOrderStatus.CANCELLED.getCode());
                default -> throw new IllegalArgumentException("未知平台订单状态筛选值: " + status);
            }
        }
        if (!codes.isEmpty()) {
            wrapper.in(SupplyChainOrderDO::getOrderStatus, codes);
        }
    }

    private void validateBizType(String type) {
        switch (type) {
            case OrderViewMappings.BIZ_VIRTUAL_CARD, OrderViewMappings.BIZ_VIRTUAL_RECHARGE,
                 OrderViewMappings.BIZ_DINING, OrderViewMappings.BIZ_MOVIE,
                 OrderViewMappings.BIZ_HEMA, OrderViewMappings.BIZ_INSTORE -> {
            }
            default -> throw new IllegalArgumentException("未知业务类型筛选值: " + type);
        }
    }

    // ------------------------------------------------------------------ 批量加载

    private Map<Long, StandardPricingQuoteDO> loadQuotes(List<SupplyChainOrderDO> orders) {
        Set<Long> ids = new HashSet<>();
        for (SupplyChainOrderDO order : orders) {
            if (order.getPricingQuoteId() != null) {
                ids.add(order.getPricingQuoteId());
            }
        }
        Map<Long, StandardPricingQuoteDO> quotes = new HashMap<>();
        if (ids.isEmpty()) {
            return quotes;
        }
        for (StandardPricingQuoteDO quote : quoteMapper.selectBatchIds(ids)) {
            if (quote != null && quote.getId() != null) {
                quotes.put(quote.getId(), quote);
            }
        }
        return quotes;
    }

    private Map<Long, List<BOrderDO>> loadAttempts(List<SupplyChainOrderDO> orders) {
        Set<Long> orderIds = new HashSet<>();
        for (SupplyChainOrderDO order : orders) {
            if (order.getId() != null) {
                orderIds.add(order.getId());
            }
        }
        Map<Long, List<BOrderDO>> byOrder = new HashMap<>();
        if (orderIds.isEmpty()) {
            return byOrder;
        }
        for (BOrderDO attempt : bOrderMapper.selectList(new LambdaQueryWrapper<BOrderDO>()
                .in(BOrderDO::getOrderId, orderIds)
                .orderByAsc(BOrderDO::getAttemptNo)
                .orderByAsc(BOrderDO::getId))) {
            byOrder.computeIfAbsent(attempt.getOrderId(), key -> new ArrayList<>()).add(attempt);
        }
        return byOrder;
    }

    private Map<Long, SupplierDO> loadSuppliers(List<BOrderDO> attempts) {
        Set<Long> ids = new HashSet<>();
        for (BOrderDO attempt : attempts) {
            if (attempt.getSupplierId() != null) {
                ids.add(attempt.getSupplierId());
            }
        }
        return loadSuppliers(ids);
    }

    private Map<Long, SupplierDO> loadSuppliers(Set<Long> ids) {
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

    /** 每个 B 端尝试的最后一次履约操作（按 sequenceNo 最大），用于路由失败原因映射。 */
    private Map<Long, FulfillmentOperationDO> loadLastOperations(List<BOrderDO> attempts) {
        Set<Long> ids = new HashSet<>();
        for (BOrderDO attempt : attempts) {
            if (attempt.getId() != null) {
                ids.add(attempt.getId());
            }
        }
        Map<Long, FulfillmentOperationDO> last = new HashMap<>();
        if (ids.isEmpty()) {
            return last;
        }
        for (FulfillmentOperationDO operation : operationMapper.selectList(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .in(FulfillmentOperationDO::getBOrderId, ids))) {
            FulfillmentOperationDO current = last.get(operation.getBOrderId());
            if (current == null || (operation.getSequenceNo() != null
                    && (current.getSequenceNo() == null
                    || operation.getSequenceNo() > current.getSequenceNo()))) {
                last.put(operation.getBOrderId(), operation);
            }
        }
        return last;
    }

    private Map<Long, PlatformSkuDO> loadSkus(List<SupplyChainOrderDO> orders) {
        Set<Long> ids = new HashSet<>();
        for (SupplyChainOrderDO order : orders) {
            if (order.getPlatformSkuId() != null) {
                ids.add(order.getPlatformSkuId());
            }
        }
        Map<Long, PlatformSkuDO> skus = new HashMap<>();
        if (ids.isEmpty()) {
            return skus;
        }
        for (PlatformSkuDO sku : platformSkuMapper.selectBatchIds(ids)) {
            if (sku != null && sku.getId() != null) {
                skus.put(sku.getId(), sku);
            }
        }
        return skus;
    }

    private Map<Long, PlatformSpuDO> loadSpus(Iterable<PlatformSkuDO> skus) {
        Set<Long> ids = new HashSet<>();
        for (PlatformSkuDO sku : skus) {
            if (sku.getSpuId() != null) {
                ids.add(sku.getSpuId());
            }
        }
        Map<Long, PlatformSpuDO> spus = new HashMap<>();
        if (ids.isEmpty()) {
            return spus;
        }
        for (PlatformSpuDO spu : platformSpuMapper.selectBatchIds(ids)) {
            if (spu != null && spu.getId() != null) {
                spus.put(spu.getId(), spu);
            }
        }
        return spus;
    }

    // ------------------------------------------------------------------ 辅助解析

    private SupplyChainOrderDO requireOrderByNo(String orderNo) {
        SupplyChainOrderDO order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw exception(SUPPLY_CHAIN_ORDER_NOT_FOUND);
        }
        return order;
    }

    private Long resolveOrderIdBySupplierOrderNo(String supplierOrderNo) {
        if (!StringUtils.hasText(supplierOrderNo)) {
            return null;
        }
        BOrderDO attempt = bOrderMapper.selectOne(new LambdaQueryWrapper<BOrderDO>()
                .eq(BOrderDO::getBOrderNo, supplierOrderNo));
        return attempt == null ? null : attempt.getOrderId();
    }

    /** 供货模式：供应商 + SPU 商品类型命中的供货范围行 → 模式子表首行；取不到为空（设计 §4.5）。 */
    private String resolveSupplyMode(Long supplierId, SupplyChainOrderDO order) {
        if (supplierId == null || order.getPlatformSkuId() == null) {
            return null;
        }
        PlatformSkuDO sku = platformSkuMapper.selectById(order.getPlatformSkuId());
        if (sku == null || sku.getSpuId() == null) {
            return null;
        }
        PlatformSpuDO spu = platformSpuMapper.selectById(sku.getSpuId());
        if (spu == null || !StringUtils.hasText(spu.getProductType())) {
            return null;
        }
        SupplierSupplyScopeDO scope = supplyScopeMapper.selectOne(
                new LambdaQueryWrapper<SupplierSupplyScopeDO>()
                        .eq(SupplierSupplyScopeDO::getSupplierId, supplierId)
                        .eq(SupplierSupplyScopeDO::getProductType, spu.getProductType())
                        .last("LIMIT 1"));
        if (scope == null || scope.getId() == null) {
            return null;
        }
        List<SupplierSupplyScopeModeDO> modes =
                supplyScopeModeMapper.selectByScopeIds(List.of(scope.getId()));
        return modes.isEmpty() ? null : modes.get(0).getSupplyMode();
    }

    /** 路由耗时：首个路由轮次计算完成时间 → 总单终态时间；任一缺失则为空。 */
    private Long resolveDurationSec(SupplyChainOrderDO order) {
        if (order.getCompletedAt() == null) {
            return null;
        }
        SupplierRoutingRoundDO firstRound = routingRoundMapper.selectList(
                        new LambdaQueryWrapper<SupplierRoutingRoundDO>()
                                .eq(SupplierRoutingRoundDO::getOrderId, order.getId())
                                .orderByAsc(SupplierRoutingRoundDO::getRoundSequence)
                                .orderByAsc(SupplierRoutingRoundDO::getId)
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (firstRound == null || firstRound.getComputedAt() == null) {
            return null;
        }
        return Duration.between(firstRound.getComputedAt(), order.getCompletedAt()).getSeconds();
    }

    private String resolveBizType(SupplyChainOrderDO order) {
        if (!SupplyChainOrderType.CARD.name().equals(order.getOrderType())) {
            return OrderViewMappings.bizType(order.getOrderType(), null, null);
        }
        PlatformSkuDO sku = order.getPlatformSkuId() == null ? null
                : platformSkuMapper.selectById(order.getPlatformSkuId());
        PlatformSpuDO spu = sku == null || sku.getSpuId() == null ? null
                : platformSpuMapper.selectById(sku.getSpuId());
        return OrderViewMappings.bizType(order.getOrderType(),
                sku == null ? null : sku.getFulfillmentType(),
                spu == null ? null : spu.getProductType());
    }

    private static void validateTimeRange(OrderViewPageQuery query) {
        if (query.createFrom() != null && query.createTo() != null
                && query.createTo().isBefore(query.createFrom())) {
            throw exception(ORDER_QUERY_TIME_RANGE_INVALID);
        }
    }

    /** 前端契约分页条件；见设计 §3.2（在既有原生参数上追加前端参数）。 */
    public record OrderViewPageQuery(
            String orderNo, String buyerOrderNo, String buyerCode, String supplierCode, String skuCode,
            List<String> bizTypes, List<String> statuses,
            List<String> supplierNames, List<String> distributorNames,
            String productName, String buyerKeyword,
            String supplierOrderNo, String distributorOrderNo,
            LocalDateTime createFrom, LocalDateTime createTo,
            Integer pageNo, Integer pageSize) {

        public OrderViewPageQuery {
            bizTypes = bizTypes == null ? List.of() : bizTypes;
            statuses = statuses == null ? List.of() : statuses;
            supplierNames = supplierNames == null ? List.of() : supplierNames;
            distributorNames = distributorNames == null ? List.of() : distributorNames;
        }
    }

    /** 前端 OrderVO 对齐视图；金额单位为分。商城侧字段（buyerName/payMethod 等）本期为空。 */
    public record OrderView(
            @Schema(description = "供应链中台订单号") String orderNo,
            @Schema(description = "source=SUPPLIER时的关联平台总单号") String relatedPlatformOrderNo,
            @Schema(description = "业务类型：VIRTUAL_CARD/VIRTUAL_RECHARGE/DINING/MOVIE/HEMA/INSTORE") String bizType,
            @Schema(description = "平台订单状态：WAIT_PAY/PROCESSING/SUCCESS/FAILED/CLOSED/MANUAL；供应商视图保留MANUAL/CANCELLED") String status,
            @Schema(description = "采购商应结算订单金额，单位为分") Long amount,
            @Schema(description = "数量") Long qty,
            @Schema(description = "供应商名称快照") String supplierName,
            @Schema(description = "分销商名称快照") String distributorName,
            @Schema(description = "受理时项目名称快照；CARD场景有值，非项目场景可空") String projectName,
            @Schema(description = "下单人手机号（采购商用户标识）") String buyerMobile,
            @Schema(description = "商城侧下单人姓名；本期为空") String buyerName,
            @Schema(description = "商城侧支付方式；本期为空") String payMethod,
            @Schema(description = "商城侧支付流水号；本期为空") String payTradeNo,
            @Schema(description = "商城侧收货人；虚拟履约本期为空") String receiverName,
            @Schema(description = "商城侧收货手机号；本期为空") String receiverPhone,
            @Schema(description = "商城侧收货地址；本期为空") String receiverAddress,
            @Schema(description = "商城侧优惠金额；本期为空") Long couponAmount,
            @Schema(description = "商城侧运费；本期为空") Long freightAmount,
            @Schema(description = "商品小计，单位为分") Long goodsAmount,
            @Schema(description = "平台服务费，单位为分") Long serviceFee,
            @Schema(description = "商品销售单价，单位为分") Long salePrice,
            @Schema(description = "报价凭证冻结的分销单价，单位为分") Long distPrice,
            @Schema(description = "分销商应付金额，单位为分") Long distributorPayAmount,
            @Schema(description = "项目应付金额；语义待定本期为空") Long projectPayAmount,
            @Schema(description = "商品行；CARD场景单商品单行") List<OrderItemView> items,
            @Schema(description = "供应商履约尝试列表") List<SupplierOrderView> supplierOrders,
            @Schema(description = "物流轨迹；虚拟履约本期为空数组") List<Object> traces,
            @Schema(description = "订单受理时间") LocalDateTime createdAt,
            @Schema(description = "供货模式；source=SUPPLIER时按供货范围配置解析") String supplyMode,
            @Schema(description = "发货时间；本期为空") LocalDateTime shipTime,
            @Schema(description = "交付方式；本期为空") String deliveryMode,
            @Schema(description = "平台终结原因码；仅失败详情返回可靠事实") String terminalReasonCode) {
    }

    /** 前端商品行；资产字段仅在详情接口返回明文（TODO(安全)：过渡方案）。 */
    public record OrderItemView(
            @Schema(description = "平台SKU编码快照") String skuCode,
            @Schema(description = "平台SKU名称快照") String skuName,
            @Schema(description = "数量") Long qty,
            @Schema(description = "销售单价，单位为分") Long salePrice,
            @Schema(description = "分销单价，单位为分") Long distPrice,
            @Schema(description = "卡号卡密资产明文；仅卡密类返回") List<CardInfo> cardInfos,
            @Schema(description = "直充资产明文；仅直充/账户权益返回") List<RechargeInfo> rechargeInfo,
            @Schema(description = "券码资产明文；仅券码类返回") List<String> ticketCode,
            @Schema(description = "券链接资产明文；仅券链接类返回") List<String> pickupCode) {
    }

    /**
     * 管理端授权卡号卡密投影（含敏感审计），有效期仅来自受保护payload，不推算日期。
     * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#72-task7授权查询与有效期展示契约。
     */
    public record CardInfo(
            @Schema(description = "卡号明文") String cardCode,
            @Schema(description = "卡密明文") String cardSecret,
            @Schema(description = "有效期开始时间") LocalDateTime validFrom,
            @Schema(description = "有效期结束时间") LocalDateTime validUntil,
            @Schema(description = "供应商批次有效期原文，不保证日期格式") String validTimeRaw,
            @Schema(description = "供应商有效期字段状态：ABSENT字段缺失，NULL显式null，VALUE字符串（包括空字符串）；旧资产不提供")
            SupplierValidTimeState validTimeState) {
        @Override public String toString() { return "CardInfo[sensitive values omitted]"; }
    }

    /** 直充/账户权益资产明文。 */
    public record RechargeInfo(
            @Schema(description = "充值账号（总表履约目标手机号）") String accountValue,
            @Schema(description = "凭证号明文") String voucherCode,
            @Schema(description = "资产编号") String assetNo) {
    }

    /** 供应商履约尝试前端视图。 */
    public record SupplierOrderView(
            @Schema(description = "B端履约尝试编号") String supplierOrderNo,
            @Schema(description = "供应商名称") String supplierName,
            @Schema(description = "供应商订单状态：WAIT_SHIP/SHIPPED/FINISHED/FAILED/MANUAL/CANCELLED") String status,
            @Schema(description = "预计供应商结算金额，单位为分") Long amount,
            @Schema(description = "请求履约数量") Long qty,
            @Schema(description = "尝试创建时间") LocalDateTime createdAt,
            @Schema(description = "失败处置详情；列表为空") OrderFailureViewQuery.View failureClosure) {
    }

    /** 前端 OrderRouteRecordVO 对齐视图。 */
    public record OrderRouteRecordView(
            @Schema(description = "供应链总订单号") String orderNo,
            @Schema(description = "商品名称快照") String productName,
            @Schema(description = "商品类型（bizType 映射）") String productType,
            @Schema(description = "路由尝试总数") Integer tryCount,
            @Schema(description = "失败尝试数") Integer failCount,
            @Schema(description = "路由结果：SUCCESS/FAILED；未进入路由为空") String status,
            @Schema(description = "最终供应商名称") String finalSupplierName,
            @Schema(description = "最终供应商订单号") String supplierOrderNo,
            @Schema(description = "路由总耗时秒数；未终态为空") Long durationSec,
            @Schema(description = "路由步骤，按尝试序号升序") List<RouteStep> steps) {
    }

    /** 单次供应商尝试步骤。 */
    public record RouteStep(
            @Schema(description = "尝试序号") Integer attemptNo,
            @Schema(description = "供应商名称") String supplierName,
            @Schema(description = "结果：SUCCESS/FAILED") String result,
            @Schema(description = "尝试时间") LocalDateTime time,
            @Schema(description = "供货单价，单位为分") Long supplyPrice,
            @Schema(description = "分配数量") Long assignQty,
            @Schema(description = "失败原因：API_TIMEOUT/SUPPLIER_REJECT/API_ERROR") String failReason,
            @Schema(description = "原始结果分类码") String remark,
            @Schema(description = "请求报文；调用审计仅存摘要，本期为空") String request,
            @Schema(description = "响应报文；调用审计仅存摘要，本期为空") String response) {
    }

    /** 内部资产聚合，避免列表路径触碰资产。 */
    private record OrderAssets(List<CardInfo> cardInfos, List<RechargeInfo> rechargeInfo,
                               List<String> ticketCodes, List<String> pickupCodes) {
        static final OrderAssets EMPTY = new OrderAssets(List.of(), List.of(), List.of(), List.of());
    }
}
