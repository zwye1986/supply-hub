package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAcceptanceReceiptDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SupplierRoutingRecordDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SupplierRoutingRoundDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAcceptanceReceiptMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SupplierRoutingRecordMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SupplierRoutingRoundMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentIdempotencyKeys;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentInitialStatuses;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOutboxCodes;
import com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.OrderStatusLogMapper;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.OrderStatusAuditCodes;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryDecision;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryFact;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryPolicyRegistry;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService;
import com.aifuli.module.supplychain.routing.domain.RoutingCandidate;
import com.aifuli.module.supplychain.routing.domain.RoutingDecisionStatus;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.routing.domain.RoutingRecordDecisionCode;
import com.aifuli.module.supplychain.routing.domain.RoutingSubjectType;
import com.aifuli.module.supplychain.routing.domain.RoutingType;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductSellabilityService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.QuoteConsumptionContext;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService.FrozenQuote;
import com.aifuli.framework.common.exception.ServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 标准商品受理回执、总订单、路由、BOrder 和资金冻结的原子编排。
 *
 * <p>稳定编码目录见
 * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}；
 * PROCESSING 回执恢复语义见
 * {@code docs/superpowers/plans/2026-08-11-supply-chain-m1-qnm-vertical-slice.md#task-9}；
 * 一期不因可用余额不足拒单，缺账户拒绝见
 * {@code docs/superpowers/specs/2026-08-17-supply-chain-phase1-buyer-account-overdraft.md}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FulfillmentAcceptanceLifecycle {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final FulfillmentAcceptanceReceiptMapper receiptMapper;
    private final SupplyChainOrderMapper orderMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final FulfillmentRequestMapper requestMapper;
    private final SupplierRoutingRoundMapper routingRoundMapper;
    private final SupplierRoutingRecordMapper routingRecordMapper;
    private final BOrderMapper bOrderMapper;
    private final FulfillmentOperationMapper operationMapper;
    private final BuyerListingMapper listingMapper;
    private final BuyerListingVersionMapper listingVersionMapper;
    private final BuyerListingAvailabilityMapper listingAvailabilityMapper;
    private final PlatformSkuMapper skuMapper;
    private final ProductSellabilityService productSellabilityService;
    private final SkuPurchaseLimitService purchaseLimitService;
    private final StandardPricingQuoteService pricingQuoteService;
    private final SupplierRoutingApplicationService routingService;
    private final FundReservationService fundReservationService;
    private final OutboxEventRepository outboxRepository;
    private final BusinessNoGenerator businessNoGenerator;
    private final ObjectMapper objectMapper;
    private final OrderSummaryPolicyRegistry orderSummaryPolicyRegistry;
    private final ObjectProvider<AcceptanceFailureInjector> failureInjector;

    /**
     * 在敏感目标加密前，根据采购商商品关系解析平台 SKU 持有的权威 Schema。
     *
     * <p>这里只读取 SKU 的稳定协议元数据，不以当前可售状态改变请求指纹；Listing 版本、商品可售、库存和路由等
     * 业务条件仍在受理事务内校验。
     * 无法解析时返回不可用快照，让 Lifecycle 按原有业务拒绝顺序收敛，避免把预读取失败变成 HTTP 异常。
     * 设计见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#72-fulfillmentrequest路由与-supplierorder}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public TargetSchemaSnapshot resolveTargetSchema(Long buyerId, String buyerSkuCode) {
        log.debug("[履约][lifecycle] resolveTargetSchema 入口 buyerId={}, buyerSkuCode={}", buyerId, buyerSkuCode);
        BuyerListingDO listing = listingMapper.selectByBuyerAndSkuCode(buyerId, buyerSkuCode);
        if (listing == null) {
            return TargetSchemaSnapshot.unavailable();
        }
        PlatformSkuDO sku = skuMapper.selectById(listing.getPlatformSkuId());
        if (sku == null || sku.getTargetSchemaCode() == null || sku.getTargetSchemaVersion() == null) {
            return TargetSchemaSnapshot.unavailable();
        }
        return new TargetSchemaSnapshot(sku.getTargetSchemaCode(), sku.getTargetSchemaVersion(), true);
    }

    /**
     * 抢占受理回执：按 (buyerId, buyerOrderNo) 幂等创建 PROCESSING 回执或回放已有终态。
     *
     * <p>首次创建不加行锁，靠唯一索引裁决并发；指纹一致且仍 PROCESSING 时允许本次重试继续 accept。
     * 详见 {@code docs/superpowers/plans/2026-08-11-supply-chain-m1-qnm-vertical-slice.md#task-9}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ClaimResult claim(PreparedAcceptance command) {
        log.info("[履约][lifecycle] claim 入口 projectId={}, buyerId={}, buyerOrderNo={}, buyerSkuCode={}, quantity={}, traceId={}",
                command.projectId(), command.buyerId(), command.buyerOrderNo(), command.buyerSkuCode(),
                command.quantity(), command.traceId());
        // 首次探测不加锁：REPEATABLE READ 下对不存在唯一键 FOR UPDATE
        // 会让并发首请求同时持有间隙锁，再插入时形成死锁。唯一索引才是创建竞争裁决者。
        FulfillmentAcceptanceReceiptDO existing = receiptMapper.selectByBusinessKey(
                command.buyerId(), command.buyerOrderNo());
        if (existing == null) {
            LocalDateTime now = now();
            FulfillmentAcceptanceReceiptDO receipt = FulfillmentAcceptanceReceiptDO.builder()
                    .receiptNo(businessNoGenerator.next(BusinessNoPrefixes.RCP))
                    .buyerId(command.buyerId()).projectId(command.projectId()).buyerOrderNo(command.buyerOrderNo())
                    .buyerUserId(command.buyerUserId()).buyerSkuCode(command.buyerSkuCode())
                    .quantity(command.quantity()).targetSchemaCode(command.targetSchemaCode())
                    .targetDigest(command.targetDigest()).invariantFingerprint(command.invariantFingerprint())
                    .requestFingerprint(command.requestFingerprint())
                    .listingVersionNo(command.listingVersionNo()).attemptNo(1)
                    .status(AcceptanceReceiptStatus.PROCESSING.getCode())
                    .createdAt(now).updatedAt(now).version(0L).build();
            try {
                receiptMapper.insert(receipt);
            } catch (DuplicateKeyException exception) {
                throw exception;
            }
            log.info("[履约][lifecycle] claim 新建回执 receiptId={}, receiptNo={}", receipt.getId(), receipt.getReceiptNo());
            return new ClaimResult(receipt.getId(), true, false);
        }
        existing = receiptMapper.selectByBusinessKeyForUpdate(command.buyerId(), command.buyerOrderNo());
        if (existing == null) {
            throw new IllegalStateException("Acceptance receipt disappeared during row-lock acquisition");
        }
        if (Arrays.equals(existing.getRequestFingerprint(), command.requestFingerprint())) {
            // 同一请求重试：回执仍处于 PROCESSING 说明上一次 accept 未提交，
            // 由本次重试重新执行 accept（幂等，行锁串行化）；已终态则直接回放快照。
            if (existing.getStatus() == AcceptanceReceiptStatus.PROCESSING.getCode()) {
                return new ClaimResult(existing.getId(), true, false);
            }
            if (isReleasedQuotaRetry(existing, command)) {
                reopenRetryable(existing, command);
                return new ClaimResult(existing.getId(), true, false);
            }
            return new ClaimResult(existing.getId(), false, true);
        }
        if (!Arrays.equals(existing.getInvariantFingerprint(), command.invariantFingerprint())) {
            throw new IdempotencyConflictException();
        }
        if (existing.getStatus() == AcceptanceReceiptStatus.REJECTED_RETRYABLE.getCode()
                && command.previousReceiptVersion() != null
                && Objects.equals(command.previousReceiptVersion(), existing.getVersion())
                && command.listingVersionNo() != existing.getListingVersionNo()) {
            reopenRetryable(existing, command);
            return new ClaimResult(existing.getId(), true, false);
        }
        throw new IdempotencyConflictException();
    }

    /**
     * 受理主路径：校验可售 Listing、路由供应商、建总订单/BOrder 并冻结资金。
     *
     * <p>本方法只写本地事务，不调用供应商适配器。一期冻结不检查可用余额；缺账户映射为
     * {@link AcceptanceRejectionCode#BUYER_ACCOUNT_NOT_OPENED}。详见
     * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#7.2-标准履约受理}、
     * {@code docs/superpowers/specs/2026-08-17-supply-chain-phase1-buyer-account-overdraft.md}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public AcceptanceSnapshot accept(long receiptId, PreparedAcceptance command) {
        log.info("[履约][lifecycle] accept 入口 receiptId={}, buyerOrderNo={}, traceId={}",
                receiptId, command.buyerOrderNo(), command.traceId());
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectByIdForUpdate(receiptId);
        if (receipt == null) {
            throw new IdempotencyConflictException();
        }
        // 并发重试场景：另一线程已在本事务之外完成 accept 并提交，直接回放终态快照。
        if (receipt.getStatus() == AcceptanceReceiptStatus.ACCEPTED.getCode()) {
            return snapshot(receiptId, true);
        }
        requireProcessingReceipt(receipt, command);
        if (command.quantity() != 1) {
            throw rejection(receiptId, null, AcceptanceReceiptStatus.REJECTED_FINAL,
                    AcceptanceRejectionCode.QUANTITY_NOT_SUPPORTED);
        }
        if (!command.targetValid()) {
            throw rejection(receiptId, null, AcceptanceReceiptStatus.REJECTED_FINAL,
                    AcceptanceRejectionCode.INVALID_TARGET);
        }

        LocalDateTime now = now();
        LockedListing locked = lockListingAndSku(command);
        ListingSnapshot listing = requireSaleableListing(command, now, locked);
        FrozenQuote frozenQuote = pricingQuoteService.requireForFulfillment(new QuoteConsumptionContext(
                command.pricingQuoteToken(), command.buyerId(), command.projectId(), listing.sku().getId(),
                command.buyerOrderNo(), command.buyerUserId(), command.quantity(), command.paidAt()));
        SkuPurchaseLimitService.Violation limitViolation = purchaseLimitService.validateLockedSku(
                command.projectId(), command.buyerUserId(), command.fulfillmentAccountValue(),
                listing.sku(), command.quantity(), now);
        if (limitViolation == SkuPurchaseLimitService.Violation.ORDER_LIMIT_EXCEEDED) {
            throw rejection(receiptId, listing.version().getId(), AcceptanceReceiptStatus.REJECTED_FINAL,
                    AcceptanceRejectionCode.ORDER_PURCHASE_LIMIT_EXCEEDED);
        }
        if (limitViolation == SkuPurchaseLimitService.Violation.USER_LIMIT_EXCEEDED) {
            throw rejection(receiptId, listing.version().getId(), AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.USER_PURCHASE_LIMIT_EXCEEDED);
        }
        if (limitViolation == SkuPurchaseLimitService.Violation.PHONE_BIND_TARGET_REQUIRED) {
            throw rejection(receiptId, listing.version().getId(), AcceptanceReceiptStatus.REJECTED_FINAL,
                    AcceptanceRejectionCode.PHONE_BIND_TARGET_REQUIRED);
        }
        if (limitViolation == SkuPurchaseLimitService.Violation.PHONE_BIND_LIMIT_EXCEEDED) {
            throw rejection(receiptId, listing.version().getId(), AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.PHONE_BIND_PURCHASE_LIMIT_EXCEEDED);
        }
        SupplierRoutingApplicationService.RoutingSnapshot routing = route(command, listing.sku(), now);
        if (!routing.decision().selected()) {
            throw rejection(receiptId, listing.version().getId(), AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.NO_ROUTABLE_SUPPLIER);
        }
        RoutingCandidate selected = routing.decision().selectedCandidate();
        long buyerAmount = Math.multiplyExact(frozenQuote.distributionUnitPriceCents(), command.quantity());
        OrderSummaryFact summaryFact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT, (short) 0,
                command.quantity(), 0L, null);
        OrderSummaryDecision summary = orderSummaryPolicyRegistry
                .require(SupplyChainOrderTypes.ORDER_TYPE_CARD).decide(summaryFact);

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .orderNo(businessNoGenerator.next(BusinessNoPrefixes.SC))
                .orderType(SupplyChainOrderTypes.ORDER_TYPE_CARD)
                .buyerId(command.buyerId()).projectId(command.projectId())
                .pricingQuoteId(frozenQuote.quoteId()).pricingVersionId(frozenQuote.pricingVersionId())
                .buyerCodeSnapshot(command.buyerCode())
                .buyerNameSnapshot(command.buyerName()).buyerUserId(command.buyerUserId())
                .fulfillmentAccountValue(command.fulfillmentAccountValue())
                .buyerOrderNo(command.buyerOrderNo()).acceptanceReceiptId(receiptId)
                .projectCodeSnapshot(command.projectCode())
                .projectNameSnapshot(command.projectName())
                .displaySupplierId(selected.supplierId()).displaySupplierCode(selected.supplierCode())
                .displaySupplierName(selected.supplierName()).buyerSettlementAmount(buyerAmount)
                .salesUnitPriceCents(frozenQuote.salesUnitPriceCents())
                .productSubtotalCents(frozenQuote.productSubtotalCents())
                .serviceFeeCents(frozenQuote.serviceFeeCents())
                .userPayableAmountCents(frozenQuote.userPayableAmountCents())
                .refundableServiceFeeCents(frozenQuote.serviceFeeCents())
                .taxMetadataJson(frozenQuote.taxMetadataJson())
                .currency(Money.CURRENCY_CNY).platformSkuId(listing.sku().getId())
                .skuCodeSnapshot(listing.sku().getSkuCode()).skuNameSnapshot(listing.sku().getSkuName())
                .quantity(command.quantity()).buyerOrderDigest(command.buyerOrderDigest())
                .orderStatus(summary.orderStatus()).aftersaleStatus(summary.aftersaleStatus())
                .businessStage(summary.businessStage()).detailType(SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT)
                .detailVersion(0L).requestFingerprint(command.requestFingerprint())
                .version(0L).createdAt(now).updatedAt(now).build();
        orderMapper.insert(order);
        pricingQuoteService.markConsumed(frozenQuote.quoteId(), order.getId(), now);
        inject(AcceptanceFailureInjector.Stage.ORDER_INSERTED);

        FulfillmentRequestDO fulfillmentRequest = FulfillmentRequestDO.builder()
                .requestNo(businessNoGenerator.next(BusinessNoPrefixes.FR)).orderId(order.getId())
                .buyerId(command.buyerId()).listingId(listing.listing().getId())
                .listingVersionId(listing.version().getId()).platformSkuId(listing.sku().getId())
                .targetSchemaCode(command.targetSchemaCode()).targetSchemaVersion(command.targetSchemaVersion())
                .targetCiphertext(command.targetCiphertext()).targetDigest(command.targetDigest())
                .targetKeyVersion(command.targetKeyVersion()).requestedQuantity(command.quantity())
                .fulfilledQuantity(0L).status(FulfillmentInitialStatuses.REQUEST_PENDING).aggregateVersion(0L)
                .createdAt(now).updatedAt(now).build();
        requestMapper.insert(fulfillmentRequest);
        if (orderMapper.attachDetail(order.getId(), fulfillmentRequest.getId(), 0L, now) != 1) {
            throw new IllegalStateException("Supply-chain order detail attachment lost invariant");
        }
        inject(AcceptanceFailureInjector.Stage.REQUEST_ATTACHED);

        RoutingPersistence routingPersistence = persistRouting(command, order, fulfillmentRequest, routing, now);
        inject(AcceptanceFailureInjector.Stage.ROUTING_RECORDED);

        BOrderDO bOrder = createBOrder(order, fulfillmentRequest, selected, now);
        bOrderMapper.insert(bOrder);
        if (requestMapper.activateBOrder(fulfillmentRequest.getId(), bOrder.getId(), now) != 1
                || routingRecordMapper.attachBOrder(routingPersistence.selectedRecordId(), bOrder.getId()) != 1) {
            throw new IllegalStateException("BOrder activation lost aggregate invariant");
        }
        if (orderMapper.advanceDetailVersion(order.getId(), 0L, 1L, now) != 1) {
            throw new IllegalStateException("Supply-chain order detail version lost monotonicity");
        }
        FulfillmentOperationDO operation = createOperation(bOrder, listing.sku(), now);
        operationMapper.insert(operation);
        inject(AcceptanceFailureInjector.Stage.B_ORDER_CREATED);

        FundReservationService.ReservationSnapshot reservation;
        try {
            reservation = fundReservationService.freeze(new FundReservationService.FreezeCommand(
                    command.buyerId(), FundReferenceType.CARD_ORDER.code(), fulfillmentRequest.getRequestNo(),
                    Money.cny(buyerAmount), order.getOrderNo(), command.traceId(), receipt.getReceiptNo()));
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().contains("account does not exist")) {
                log.warn("[履约][lifecycle] 资金账户未开户 buyerId={}, requestNo={}",
                        command.buyerId(), fulfillmentRequest.getRequestNo());
                throw rejection(receiptId, listing.version().getId(), AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                        AcceptanceRejectionCode.BUYER_ACCOUNT_NOT_OPENED);
            }
            throw exception;
        }
        inject(AcceptanceFailureInjector.Stage.FUNDS_FROZEN);

        appendAcceptanceEvents(command, order, fulfillmentRequest, bOrder, operation, reservation, now);
        inject(AcceptanceFailureInjector.Stage.OUTBOX_APPENDED);

        if (receiptMapper.markAccepted(receiptId, listing.version().getId(), order.getId(),
                order.getOrderNo(), fulfillmentRequest.getRequestNo(), now) != 1) {
            throw new IllegalStateException("Acceptance receipt was not PROCESSING at commit");
        }
        statusLogMapper.insert(OrderStatusLogDO.builder().orderId(order.getId()).detailVersion(1L)
                .toOrderStatus(summary.orderStatus()).toAftersaleStatus(summary.aftersaleStatus())
                .businessStage(summary.businessStage())
                .sourceEventType(OrderStatusAuditCodes.SOURCE_FULFILLMENT_ACCEPTED)
                .sourceEventNo(receipt.getReceiptNo()).reasonCode(OrderStatusAuditCodes.REASON_ACCEPTED)
                .traceId(command.traceId()).createdAt(now).build());
        log.info("[履约][lifecycle] accept 完成 receiptId={}, receiptNo={}, orderNo={}",
                receiptId, receipt.getReceiptNo(), order.getOrderNo());
        return snapshot(receiptId, false);
    }

    /**
     * 业务拒绝：PROCESSING 回执标记拒绝码后回放快照。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public AcceptanceSnapshot reject(BusinessRejection rejection) {
        log.info("[履约][lifecycle] reject 入口 receiptId={}, code={}, receiptStatus={}",
                rejection.receiptId(), rejection.rejectionCode(), rejection.receiptStatus());
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectByIdForUpdate(rejection.receiptId());
        if (receipt == null) {
            throw new IllegalStateException("Acceptance receipt disappeared before rejection");
        }
        if (receipt.getStatus() == AcceptanceReceiptStatus.PROCESSING.getCode()) {
            int updated = receiptMapper.markRejected(receipt.getId(), rejection.listingVersionId(),
                    rejection.receiptStatus().getCode(), rejection.rejectionCode().name(), now());
            if (updated != 1) {
                throw new IllegalStateException("Acceptance rejection lost receipt state");
            }
        }
        return snapshot(receipt.getId(), false);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public AcceptanceSnapshot replay(long receiptId) {
        return snapshot(receiptId, true);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public AcceptanceSnapshot replayByBusinessKey(Long buyerId, String buyerOrderNo) {
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectByBusinessKey(buyerId, buyerOrderNo);
        if (receipt == null) {
            throw new IllegalStateException("Acceptance receipt does not exist after duplicate claim");
        }
        return snapshot(receipt.getId(), true);
    }

    /**
     * 在事务建立普通查询快照前按固定顺序锁定 Listing 与平台 SKU。
     *
     * <p>MySQL REPEATABLE READ 会让锁前的一致性读固定历史快照；若随后才锁 SKU，等待事务仍可能看不到
     * 前序事务刚提交的订单。这里刻意只使用锁定读，并把后续所有可售与额度查询放在锁后。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。</p>
     */
    private LockedListing lockListingAndSku(PreparedAcceptance command) {
        BuyerListingDO listing = listingMapper.selectByBuyerAndSkuCodeForUpdate(
                command.buyerId(), command.buyerSkuCode());
        if (listing == null) {
            throw rejection(command.receiptIdHint(), null, AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.LISTING_VERSION_EXPIRED);
        }
        PlatformSkuDO sku = skuMapper.selectByIdForUpdate(listing.getPlatformSkuId());
        if (sku == null) {
            throw rejection(command.receiptIdHint(), null, AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.SKU_NOT_SALEABLE);
        }
        return new LockedListing(listing, sku);
    }

    private ListingSnapshot requireSaleableListing(PreparedAcceptance command, LocalDateTime now,
                                                    LockedListing locked) {
        BuyerListingDO listing = locked.listing();
        BuyerListingVersionDO version = listingVersionMapper.selectByListingAndVersionNo(
                listing.getId(), command.listingVersionNo());
        if (version == null || listing.getStatus() != BuyerListingStatus.PUBLISHED.getCode()
                || !version.getId().equals(listing.getCurrentVersionId())
                || version.getValidFrom().isAfter(now)
                || (version.getValidTo() != null && !version.getValidTo().isAfter(now))) {
            throw rejection(command.receiptIdHint(), version == null ? null : version.getId(),
                    AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.LISTING_VERSION_EXPIRED);
        }
        BuyerListingAvailabilityDO availability = listingAvailabilityMapper.selectByListingId(listing.getId());
        if (availability == null || availability.getAvailability() != ListingAvailability.AVAILABLE.getCode()) {
            throw rejection(command.receiptIdHint(), version.getId(), AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.INVENTORY_INSUFFICIENT);
        }
        PlatformSkuDO sku;
        try {
            sku = productSellabilityService.requireSellable(locked.sku()).sku();
        } catch (ServiceException notSellable) {
            // 设计要求把不可售统一转为 SKU_NOT_SALEABLE 回执而非 HTTP 500，但必须保留原始错误码作为因果，
            // 见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#31-spu-与-sku-状态解耦与有效可售计算
            log.warn("平台 SKU 不可售，受理转拒绝回执 listingId={} platformSkuId={} errorCode={}",
                    listing.getId(), listing.getPlatformSkuId(), notSellable.getCode(), notSellable);
            throw rejection(command.receiptIdHint(), version.getId(), AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.SKU_NOT_SALEABLE);
        }
        if (!command.targetSchemaCode().equals(sku.getTargetSchemaCode())
                || command.targetSchemaVersion() != sku.getTargetSchemaVersion()) {
            throw rejection(command.receiptIdHint(), version.getId(), AcceptanceReceiptStatus.REJECTED_FINAL,
                    AcceptanceRejectionCode.INVALID_TARGET);
        }
        return new ListingSnapshot(listing, version, sku);
    }

    private SupplierRoutingApplicationService.RoutingSnapshot route(
            PreparedAcceptance command, PlatformSkuDO sku, LocalDateTime now) {
        try {
            return routingService.route(new RoutingInput(command.buyerId(), command.projectId(), sku.getId(),
                    sku.getFulfillmentType(), command.quantity(), now, Map.of()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw rejection(command.receiptIdHint(), null, AcceptanceReceiptStatus.REJECTED_RETRYABLE,
                    AcceptanceRejectionCode.NO_ROUTABLE_SUPPLIER);
        }
    }

    private RoutingPersistence persistRouting(PreparedAcceptance command, SupplyChainOrderDO order,
                                              FulfillmentRequestDO request,
                                              SupplierRoutingApplicationService.RoutingSnapshot routing,
                                              LocalDateTime now) {
        List<RoutingCandidate> candidates = routing.decision().orderedCandidates();
        SupplierRoutingRoundDO round = SupplierRoutingRoundDO.builder()
                .routingRoundNo(businessNoGenerator.next(BusinessNoPrefixes.RR)).buyerId(command.buyerId())
                .routingSubjectType(RoutingSubjectType.STANDARD_REQUEST.code())
                .routingSubjectKey(command.buyerOrderNo())
                .routingType(RoutingType.STANDARD_PRODUCT.code()).orderId(order.getId())
                .fulfillmentRequestId(request.getId()).roundSequence(1)
                .policyId(routing.policyId()).policyVersionId(routing.policyVersionId())
                .matchedRuleId(routing.ruleId()).routingInputDigest(routingInputDigest(command, routing))
                .snapshotSchemaVersion(1).factSnapshotJson(factSnapshotJson(routing))
                .candidateCount(candidates.size()).decisionStatus(RoutingDecisionStatus.SELECTED.getCode())
                .decisionReasonCode(routing.decision().reasonCode())
                .decisionReasonMessage("按已发布路由规则选中首个合格供应商")
                .computedAt(now).createdAt(now).build();
        routingRoundMapper.insert(round);
        Long selectedRecordId = null;
        for (int index = 0; index < candidates.size(); index++) {
            RoutingCandidate candidate = candidates.get(index);
            boolean selected = candidate.equals(routing.decision().selectedCandidate());
            String reason = selected ? RoutingRecordDecisionCode.SELECTED.name()
                    : candidate.eligible()
                    ? RoutingRecordDecisionCode.ELIGIBLE_NOT_SELECTED.name()
                    : candidate.rejectionReasons().getFirst();
            SupplierRoutingRecordDO record = SupplierRoutingRecordDO.builder()
                    .routingRoundId(round.getId()).candidateSequence(index + 1)
                    .supplierId(candidate.supplierId()).supplierCodeSnapshot(candidate.supplierCode())
                    .supplierNameSnapshot(candidate.supplierName()).offerId(candidate.offerId())
                    .offerVersionId(candidate.offerVersionId()).connectorVersionId(candidate.connectorVersionId())
                    .inventorySourceId(candidate.inventorySourceId())
                    .configuredPriority(candidate.candidatePriority())
                    .availabilityClass(candidate.availabilityClass())
                    .supplierSettlementUnitPrice(candidate.supplierSettlementUnitPrice())
                    .factCapturedAt(candidate.latestFactAt()).checkSnapshotSchemaVersion(1)
                    .checkResultJson(checkResultJson(candidate)).eligibleFlag(candidate.eligible())
                    .decisionReasonCode(reason).decisionReasonMessage(reason)
                    .selectedFlag(selected ? Boolean.TRUE : null).createdAt(now).build();
            routingRecordMapper.insert(record);
            if (selected) {
                selectedRecordId = record.getId();
            }
        }
        if (selectedRecordId == null) {
            throw new IllegalStateException("Selected route was not persisted");
        }
        return new RoutingPersistence(round.getId(), selectedRecordId);
    }

    private BOrderDO createBOrder(SupplyChainOrderDO order, FulfillmentRequestDO request,
                                  RoutingCandidate selected, LocalDateTime now) {
        String bOrderNo = businessNoGenerator.next(BusinessNoPrefixes.BO);
        // billno 独立于内部 bOrderNo，便于多供应商路由与重放时供应商侧单号稳定唯一。
        String billno = businessNoGenerator.next(BusinessNoPrefixes.BILL);
        long expectedAmount = Math.multiplyExact(selected.supplierSettlementUnitPrice(), order.getQuantity());
        return BOrderDO.builder().bOrderNo(bOrderNo).fulfillmentRequestId(request.getId())
                .orderId(order.getId()).attemptNo(1).supplierId(selected.supplierId())
                .offerId(selected.offerId()).offerVersionId(selected.offerVersionId())
                .connectorVersionId(selected.connectorVersionId()).inventorySourceId(selected.inventorySourceId())
                .billno(billno).idempotencyKey(FulfillmentIdempotencyKeys.bOrderFulfill(
                        selected.supplierCode(), bOrderNo))
                .requestedQuantity(order.getQuantity()).fulfilledQuantity(0L)
                .supplierSettlementUnitPrice(selected.supplierSettlementUnitPrice())
                .expectedSupplierSettlementAmount(expectedAmount).currency(Money.CURRENCY_CNY)
                .fulfillmentStatus(FulfillmentInitialStatuses.B_ORDER_PENDING)
                .settlementStatus(FulfillmentInitialStatuses.B_ORDER_SETTLEMENT_PENDING)
                .activeFlag(Boolean.TRUE).version(0L).createdAt(now).updatedAt(now).build();
    }

    private FulfillmentOperationDO createOperation(BOrderDO bOrder, PlatformSkuDO sku, LocalDateTime now) {
        AdapterStepCode step = AdapterStepCode.forFulfillmentType(sku.getFulfillmentType());
        String idempotencyKey = FulfillmentIdempotencyKeys.operation(bOrder.getBOrderNo(), 1);
        return FulfillmentOperationDO.builder().operationNo(businessNoGenerator.next(BusinessNoPrefixes.OP))
                .bOrderId(bOrder.getId()).operationKind(FulfillmentOperationKind.FULFILL.name())
                .adapterStepCode(step.name())
                .sequenceNo(1).idempotencyKey(idempotencyKey)
                .configDigest(sha256((idempotencyKey + "|" + bOrder.getOfferVersionId()
                        + "|" + bOrder.getConnectorVersionId()).getBytes(StandardCharsets.UTF_8)))
                .status(FulfillmentInitialStatuses.OPERATION_PENDING).attemptCount(0)
                .createdAt(now).updatedAt(now).version(0L).build();
    }

    private void appendAcceptanceEvents(PreparedAcceptance command, SupplyChainOrderDO order,
                                        FulfillmentRequestDO request, BOrderDO bOrder,
                                        FulfillmentOperationDO operation,
                                        FundReservationService.ReservationSnapshot reservation,
                                        LocalDateTime now) {
        outboxRepository.appendLocal(FulfillmentOutboxCodes.AGGREGATE_B_ORDER, bOrder.getBOrderNo(), 0,
                FulfillmentOutboxCodes.EVENT_FULFILLMENT_OPERATION_READY,
                FulfillmentOutboxCodes.TARGET_FULFILLMENT_OPERATION_WORKER,
                Map.of("operationNo", operation.getOperationNo(), "bOrderNo", bOrder.getBOrderNo()),
                command.traceId(), order.getOrderNo(), command.buyerOrderNo(), now);
        outboxRepository.appendLocal(FulfillmentOutboxCodes.AGGREGATE_SUPPLY_CHAIN_ORDER, order.getOrderNo(), 1,
                FulfillmentOutboxCodes.EVENT_SUPPLY_CHAIN_ORDER_CHANGED,
                FulfillmentOutboxCodes.TARGET_SUPPLY_CHAIN_ORDER_EVENT,
                Map.of("orderNo", order.getOrderNo(),
                        "orderStatus", FulfillmentOutboxCodes.PAYLOAD_ORDER_STATUS_PROCESSING),
                command.traceId(), order.getOrderNo(), operation.getOperationNo(), now);
        outboxRepository.appendLocal(FulfillmentOutboxCodes.AGGREGATE_BUYER_ORDER, order.getOrderNo(), 1,
                FulfillmentOutboxCodes.EVENT_BUYER_ORDER_SUMMARY_CHANGED,
                FulfillmentOutboxCodes.TARGET_BUYER_EVENT_RPC,
                Map.of("orderNo", order.getOrderNo(), "buyerOrderNo", command.buyerOrderNo(),
                        "fulfillmentRequestNo", request.getRequestNo(),
                        "reservationNo", reservation.reservationNo()),
                command.traceId(), order.getOrderNo(), operation.getOperationNo(), now);
    }

    private AcceptanceSnapshot snapshot(long receiptId, boolean replay) {
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectById(receiptId);
        if (receipt == null) {
            throw new IllegalStateException("Acceptance receipt does not exist");
        }
        SupplyChainOrderDO order = receipt.getResultOrderId() == null
                ? null : orderMapper.selectById(receipt.getResultOrderId());
        return new AcceptanceSnapshot(receipt.getReceiptNo(), receipt.getBuyerOrderNo(),
                receipt.getStatus(), receipt.getVersion(), receipt.getResultOrderNo(),
                receipt.getResultFulfillmentRequestNo(), receipt.getRejectionCode(),
                order == null ? null : order.getDisplaySupplierCode(),
                order == null ? null : order.getDisplaySupplierName(), receipt.getAcceptedAt(), replay);
    }

    private void requireProcessingReceipt(FulfillmentAcceptanceReceiptDO receipt,
                                          PreparedAcceptance command) {
        if (receipt == null || receipt.getStatus() != AcceptanceReceiptStatus.PROCESSING.getCode()
                || !Arrays.equals(receipt.getRequestFingerprint(), command.requestFingerprint())) {
            throw new IdempotencyConflictException();
        }
        command.receiptIdHint(receipt.getId());
    }

    private boolean isReleasedQuotaRetry(FulfillmentAcceptanceReceiptDO receipt, PreparedAcceptance command) {
        return receipt.getStatus() == AcceptanceReceiptStatus.REJECTED_RETRYABLE.getCode()
                && (AcceptanceRejectionCode.USER_PURCHASE_LIMIT_EXCEEDED.name().equals(receipt.getRejectionCode())
                || AcceptanceRejectionCode.PHONE_BIND_PURCHASE_LIMIT_EXCEEDED.name().equals(receipt.getRejectionCode()))
                && command.previousReceiptVersion() != null
                && Objects.equals(command.previousReceiptVersion(), receipt.getVersion());
    }

    private void reopenRetryable(FulfillmentAcceptanceReceiptDO receipt, PreparedAcceptance command) {
        log.info("[履约][lifecycle] reopenRetryable 重开受理回执 receiptId={}, version={}, listingVersionNo={}",
                receipt.getId(), receipt.getVersion(), command.listingVersionNo());
        int updated = receiptMapper.reopenRetryable(receipt.getId(), receipt.getVersion(),
                command.listingVersionNo(), command.requestFingerprint(), now());
        if (updated != 1) {
            throw new IdempotencyConflictException();
        }
    }

    private byte[] routingInputDigest(PreparedAcceptance command,
                                      SupplierRoutingApplicationService.RoutingSnapshot routing) {
        String canonical = command.buyerCode() + '|' + command.buyerSkuCode() + '|'
                + command.quantity() + '|' + routing.policyVersionNo() + '|'
                + HexFormat.of().formatHex(routing.configDigest());
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private String factSnapshotJson(SupplierRoutingApplicationService.RoutingSnapshot routing) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (RoutingCandidate candidate : routing.decision().orderedCandidates()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("supplierCode", candidate.supplierCode());
            item.put("eligible", candidate.eligible());
            item.put("availabilityClass", candidate.availabilityClass());
            item.put("rejectionReasons", candidate.rejectionReasons());
            candidates.add(item);
        }
        return writeJson(Map.of("schemaVersion", 1, "candidates", candidates));
    }

    private String checkResultJson(RoutingCandidate candidate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inventory", candidate.inventoryFactStatus());
        result.put("balance", candidate.balanceFactStatus());
        result.put("health", candidate.healthFactStatus());
        result.put("reasons", candidate.rejectionReasons());
        return writeJson(result);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Routing audit JSON serialization failed", exception);
        }
    }

    private void inject(AcceptanceFailureInjector.Stage stage) {
        AcceptanceFailureInjector injector = failureInjector.getIfAvailable();
        if (injector != null) {
            injector.after(stage);
        }
    }

    private static BusinessRejection rejection(long receiptId, Long listingVersionId,
                                               AcceptanceReceiptStatus status,
                                               AcceptanceRejectionCode code) {
        return new BusinessRejection(receiptId, listingVersionId, status, code);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    public record ClaimResult(long receiptId, boolean executeAcceptance, boolean replay) {
    }

    public record AcceptanceSnapshot(String receiptNo, String buyerOrderNo, short status,
                                     long receiptVersion, String orderNo, String fulfillmentRequestNo,
                                     String rejectionCode, String supplierCode, String supplierName,
                                     LocalDateTime acceptedAt, boolean idempotentReplay) {
    }

    /** 加密与请求指纹准备阶段使用的平台 SKU Schema 快照。 */
    public record TargetSchemaSnapshot(String schemaCode, int schemaVersion, boolean available) {
        private static TargetSchemaSnapshot unavailable() {
            return new TargetSchemaSnapshot("", 0, false);
        }
    }

    public static final class PreparedAcceptance {
        private final Long projectId;
        private final String projectCode;
        private final String projectName;
        private final Long buyerId;
        private final String buyerCode;
        private final String buyerName;
        private final String buyerOrderNo;
        private final String buyerUserId;
        private final String fulfillmentAccountValue;
        private final String buyerSkuCode;
        private final int listingVersionNo;
        private final long quantity;
        private final String pricingQuoteToken;
        private final LocalDateTime paidAt;
        private final String targetSchemaCode;
        private final int targetSchemaVersion;
        private final byte[] targetCiphertext;
        private final byte[] targetDigest;
        private final String targetKeyVersion;
        private final boolean targetValid;
        private final byte[] invariantFingerprint;
        private final byte[] requestFingerprint;
        private final byte[] buyerOrderDigest;
        private final Long previousReceiptVersion;
        private final String traceId;
        private long receiptIdHint;

        public PreparedAcceptance(Long projectId, String projectCode, String projectName,
                                  Long buyerId, String buyerCode, String buyerName,
                                  String buyerOrderNo, String buyerUserId, String fulfillmentAccountValue,
                                  String buyerSkuCode,
                                  int listingVersionNo, long quantity, String pricingQuoteToken,
                                  LocalDateTime paidAt, String targetSchemaCode,
                                  int targetSchemaVersion, byte[] targetCiphertext, byte[] targetDigest,
                                  String targetKeyVersion, boolean targetValid,
                                  byte[] invariantFingerprint, byte[] requestFingerprint,
                                  byte[] buyerOrderDigest, Long previousReceiptVersion, String traceId) {
            this.projectId = projectId;
            this.projectCode = projectCode;
            this.projectName = projectName;
            this.buyerId = buyerId;
            this.buyerCode = buyerCode;
            this.buyerName = buyerName;
            this.buyerOrderNo = buyerOrderNo;
            this.buyerUserId = buyerUserId;
            this.fulfillmentAccountValue = fulfillmentAccountValue;
            this.buyerSkuCode = buyerSkuCode;
            this.listingVersionNo = listingVersionNo;
            this.quantity = quantity;
            this.pricingQuoteToken = pricingQuoteToken;
            this.paidAt = paidAt;
            this.targetSchemaCode = targetSchemaCode;
            this.targetSchemaVersion = targetSchemaVersion;
            this.targetCiphertext = targetCiphertext == null ? null : targetCiphertext.clone();
            this.targetDigest = targetDigest.clone();
            this.targetKeyVersion = targetKeyVersion;
            this.targetValid = targetValid;
            this.invariantFingerprint = invariantFingerprint.clone();
            this.requestFingerprint = requestFingerprint.clone();
            this.buyerOrderDigest = buyerOrderDigest.clone();
            this.previousReceiptVersion = previousReceiptVersion;
            this.traceId = traceId;
        }

        public Long projectId() { return projectId; }
        public String projectCode() { return projectCode; }
        public String projectName() { return projectName; }
        public Long buyerId() { return buyerId; }
        public String buyerCode() { return buyerCode; }
        public String buyerName() { return buyerName; }
        public String buyerOrderNo() { return buyerOrderNo; }
        public String buyerUserId() { return buyerUserId; }
        public String fulfillmentAccountValue() { return fulfillmentAccountValue; }
        public String buyerSkuCode() { return buyerSkuCode; }
        public int listingVersionNo() { return listingVersionNo; }
        public long quantity() { return quantity; }
        public String pricingQuoteToken() { return pricingQuoteToken; }
        public LocalDateTime paidAt() { return paidAt; }
        public String targetSchemaCode() { return targetSchemaCode; }
        public int targetSchemaVersion() { return targetSchemaVersion; }
        public byte[] targetCiphertext() { return targetCiphertext == null ? null : targetCiphertext.clone(); }
        public byte[] targetDigest() { return targetDigest.clone(); }
        public String targetKeyVersion() { return targetKeyVersion; }
        public boolean targetValid() { return targetValid; }
        public byte[] invariantFingerprint() { return invariantFingerprint.clone(); }
        public byte[] requestFingerprint() { return requestFingerprint.clone(); }
        public byte[] buyerOrderDigest() { return buyerOrderDigest.clone(); }
        public Long previousReceiptVersion() { return previousReceiptVersion; }
        public String traceId() { return traceId; }
        public long receiptIdHint() { return receiptIdHint; }
        private void receiptIdHint(long value) { this.receiptIdHint = value; }
    }

    public static final class BusinessRejection extends RuntimeException {
        private final long receiptId;
        private final Long listingVersionId;
        private final AcceptanceReceiptStatus receiptStatus;
        private final AcceptanceRejectionCode rejectionCode;

        BusinessRejection(long receiptId, Long listingVersionId,
                          AcceptanceReceiptStatus receiptStatus, AcceptanceRejectionCode rejectionCode) {
            super(rejectionCode.name(), null, false, false);
            this.receiptId = receiptId;
            this.listingVersionId = listingVersionId;
            this.receiptStatus = receiptStatus;
            this.rejectionCode = rejectionCode;
        }

        public long receiptId() { return receiptId; }
        public Long listingVersionId() { return listingVersionId; }
        public AcceptanceReceiptStatus receiptStatus() { return receiptStatus; }
        public AcceptanceRejectionCode rejectionCode() { return rejectionCode; }
    }

    public static final class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException() {
            super("Acceptance business key payload conflicts with the original request");
        }
    }

    private record ListingSnapshot(BuyerListingDO listing, BuyerListingVersionDO version,
                                   PlatformSkuDO sku) {
    }

    private record LockedListing(BuyerListingDO listing, PlatformSkuDO sku) {
    }

    private record RoutingPersistence(Long roundId, Long selectedRecordId) {
    }
}
