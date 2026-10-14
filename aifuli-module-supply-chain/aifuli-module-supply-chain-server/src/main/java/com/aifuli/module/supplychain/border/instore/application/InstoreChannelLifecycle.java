package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelUserBindingDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreOperationDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreRefundCaseDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreSupplierOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelUserBindingMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreOperationMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreRefundCaseMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreSupplierOrderMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreIdempotencyKeys;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOperationStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRejectionCode;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOperationType;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOrderStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOutboxCodes;
import com.aifuli.module.supplychain.border.instore.domain.InstorePaymentFlowStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundType;
import com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.OrderStatusLogMapper;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.InstoreOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.InstoreRefundProjection;
import com.aifuli.module.supplychain.border.order.domain.OrderStatusAuditCodes;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryDecision;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryFact;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryPolicyRegistry;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountReturnService;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierChannelBindingDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierChannelBindingMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.aifuli.module.supplychain.infrastructure.identity.PreparedOrderNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.MANUAL;
import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.SUCCESS;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_ACCOUNT_NOT_OPENED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CANCEL_REFUND_FAILED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CHANNEL_USER_BINDING_MISSING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_IDENTITY_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_ORDER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_PAYMENT_FLOW_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_EXCEEDED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_REFUND_CASE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ORDER_STATE_CONFLICT;

/**
 * 到店购渠道建单、收银台抢占、支付确认、取消与退款的原子编排。
 *
 * <p>对标 O2O {@code O2OChannelLifecycle}，适配 1:N 支付流水模型与到店即完成语义：
 * <ul>
 *   <li>{@code createPaymentOrder}：按 (channelCode,order_id)/(channelCode,trade_id) 幂等；联调切片同一订单头至多一笔流水。</li>
 *   <li>{@code confirmBuyerPayment}：支付成功即创建供应商订单并 COMPLETED；已 PAID 幂等回放。</li>
 *   <li>{@code cancelPaymentFlow}：未支付关单 CANCELLED；已支付只落 CANCEL_REFUND Case。</li>
 *   <li>{@code queryPaymentStatus}/{@code queryRefundStatus}：只读投影，不上 HTTP。</li>
 *   <li>无 CHANNEL_NOTIFY Outbox（旅通 pull 模型）。</li>
 * </ul>
 * 所有本地业务事务显式使用 {@code supplyChainTransactionManager}；HTTP/RPC/收银台网络调用一律位于数据库事务外。
 * 当前按联调切片冻结：1 采购单 : 1 流水、已支付取消只落 CANCEL_REFUND Case、查询只读不上 HTTP。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstoreChannelLifecycle {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final InstoreChannelUserBindingMapper bindingMapper;
    private final InstoreChannelOrderMapper instoreOrderMapper;
    private final InstorePaymentFlowMapper paymentFlowMapper;
    private final InstoreOperationMapper operationMapper;
    private final InstoreRefundCaseMapper refundCaseMapper;
    private final InstoreSupplierOrderMapper supplierOrderMapper;
    private final SupplyChainOrderMapper orderMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final SupplierChannelBindingMapper supplierChannelBindingMapper;
    private final SupplierMapper supplierMapper;
    private final FundReservationService fundReservationService;
    private final OutboxEventRepository outboxRepository;
    private final BusinessNoGenerator businessNoGenerator;
    private final OrderSummaryPolicyRegistry orderSummaryPolicyRegistry;
    private final InstoreRefundSequencer refundSequencer;
    private final BuyerAccountReturnService accountReturnService;
    private final InstoreOrderSummaryPolicy instoreOrderSummaryPolicy;

    /** 保持原有回放合同的短只读事务；完成后才可取号。设计见 {@code docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#53-o2o-与-instore}。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public PreparedPayment tryReplayPayment(CreatePaymentOrderCommand command) {
        InstorePaymentFlowDO existing = paymentFlowMapper.selectByChannelTrade(
                command.channelCode(), command.tradeId());
        return existing == null ? null : replayExistingFlow(existing, command);
    }

    /**
     * 第一段：按 (channelCode,order_id) 幂等建/复用订单头，按 (channelCode,trade_id) 幂等建支付流水，
     * 冻结资金并创建 PREORDER 操作。
     *
     * <p>不变量：渠道用户绑定必须已启用；同一交易重复请求走幂等回放，金额不一致抛身份冲突；
     * 联调切片同一订单头已有任意流水则拒绝新 trade_id；资金冻结失败整笔事务回滚。本方法只写本地事务，不调用收银台或渠道网络。
     * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public PreparedPayment createPaymentOrder(CreatePaymentOrderCommand command, PreparedOrderNumbers numbers) {
        log.info("[INSTORE][lifecycle] createPaymentOrder 入口 channelCode={}, channelOrderId={}, tradeId={}, payAmount={}, traceId={}",
                command.channelCode(), command.channelOrderId(), command.tradeId(), command.payAmount(), command.traceId());

        // 支付流水幂等回放（同一 trade_id 重复建单）
        InstorePaymentFlowDO existingFlow = paymentFlowMapper.selectByChannelTrade(
                command.channelCode(), command.tradeId());
        if (existingFlow != null) {
            log.info("[INSTORE][lifecycle] 支付流水幂等回放 tradeId={}, paymentFlowNo={}",
                    command.tradeId(), existingFlow.getPaymentFlowNo());
            return replayExistingFlow(existingFlow, command);
        }

        // 按项目会员业务身份解析稳定绑定；externalTenantId 只作为已发布 CASHIER 档案的路由快照。
        InstoreChannelUserBindingDO binding = bindingMapper.selectByProjectUser(
                command.projectId(), command.buyerUserId(), command.channelCode());
        if (binding == null || !Short.valueOf((short) 10).equals(binding.getStatus())) {
            log.warn("[INSTORE][lifecycle] 项目会员绑定缺失或停用 projectId={}, channelCode={}",
                    command.projectId(), command.channelCode());
            throw exception(INSTORE_CHANNEL_USER_BINDING_MISSING);
        }
        if (!binding.getBuyerId().equals(command.buyerId())
                || !command.projectId().equals(binding.getProjectId())
                || !command.buyerUserId().equals(binding.getBuyerUserId())
                || !command.buyerUserId().equals(command.userCode())) {
            log.warn("[INSTORE][lifecycle] 绑定与项目会员作用域不一致 bindingBuyerId={}, commandBuyerId={}, "
                            + "bindingProjectId={}, commandProjectId={}",
                    binding.getBuyerId(), command.buyerId(), binding.getProjectId(), command.projectId());
            throw exception(INSTORE_TENANT_SCOPE_CONFLICT);
        }

        // 解析固定商户绑定
        SupplierChannelBindingDO channelBinding = supplierChannelBindingMapper.selectCurrentEnabled(
                command.channelCode(), command.externalMerchantId());
        if (channelBinding == null) {
            throw exception(INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND);
        }
        SupplierDO supplier = supplierMapper.selectById(channelBinding.getSupplierId());
        if (supplier == null) {
            throw exception(INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND);
        }

        LocalDateTime now = now();

        // 订单头：按 (channelCode,order_id) 幂等建或复用
        InstoreChannelOrderDO instoreOrder = instoreOrderMapper.selectByChannelOrder(
                command.channelCode(), command.channelOrderId());
        SupplyChainOrderDO order;
        boolean orderHeadNewlyCreated;
        if (instoreOrder == null) {
            SupplyChainOrderWithHead created = createBuyerOrderAndHead(command, binding, channelBinding, supplier, now, numbers);
            order = created.order();
            instoreOrder = created.instoreOrder();
            orderHeadNewlyCreated = true;
        } else {
            // 联调切片 1:1：已有流水的订单头禁止挂新 trade_id
            if (instoreOrder.getTotalAmount() != command.totalAmount()) {
                throw exception(INSTORE_IDENTITY_CONFLICT);
            }
            if (paymentFlowMapper.countByInstoreOrder(instoreOrder.getId()) > 0) {
                log.warn("[INSTORE][lifecycle] 订单头已有支付流水，拒绝新 trade_id channelOrderId={}, tradeId={}",
                        command.channelOrderId(), command.tradeId());
                throw exception(INSTORE_IDENTITY_CONFLICT);
            }
            order = orderMapper.selectById(instoreOrder.getOrderId());
            if (order == null) {
                throw exception(INSTORE_ORDER_NOT_FOUND);
            }
            orderHeadNewlyCreated = false;
            log.info("[INSTORE][lifecycle] 复用已有订单头 instoreOrderNo={}, orderId={}",
                    instoreOrder.getInstoreOrderNo(), order.getId());
        }

        // 创建支付流水
        long flowSequence = paymentFlowMapper.countByInstoreOrder(instoreOrder.getId()) + 1;
        String paymentFlowNo = businessNoGenerator.next(BusinessNoPrefixes.IPF);
        String outTradeNo = businessNoGenerator.next(BusinessNoPrefixes.IPF);
        byte[] requestFingerprint = sha256(canonicalPaymentRequest(command));
        InstorePaymentFlowDO flow = InstorePaymentFlowDO.builder()
                .paymentFlowNo(paymentFlowNo).instoreOrderId(instoreOrder.getId()).orderId(order.getId())
                .flowSequence((int) flowSequence).channelCode(command.channelCode()).tradeId(command.tradeId())
                .userCode(command.userCode()).payAmount(command.payAmount()).orderType(command.orderType())
                .payStatus(InstorePaymentFlowStatus.PENDING.getCode()).outTradeNo(outTradeNo)
                .cashierUrl(null).cashierReference(null).buyerPaidAmount(null).payTime(null)
                .externalTransactionId(null).fundReservationNo(null).supplierOrderId(null)
                .requestFingerprint(requestFingerprint).version(0L).createdAt(now).updatedAt(now)
                .completedAt(null).build();
        paymentFlowMapper.insert(flow);

        // 冻结资金（按支付流水号冻结，referenceType=INSTORE_ORDER）
        FundReservationService.ReservationSnapshot reservation;
        try {
            reservation = fundReservationService.freeze(new FundReservationService.FreezeCommand(
                    binding.getBuyerId(), FundReferenceType.INSTORE_ORDER.code(), paymentFlowNo,
                    Money.cny(command.payAmount()), order.getOrderNo(), command.traceId(), paymentFlowNo, binding.getProjectId()));
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("account does not exist")) {
                log.warn("[INSTORE][lifecycle] 资金账户未开户 buyerId={}, tradeId={}",
                        binding.getBuyerId(), command.tradeId());
                throw exception(BUYER_ACCOUNT_NOT_OPENED);
            }
            throw ex;
        }
        // 回填资金预留号
        updateFundReservationNo(flow.getId(), reservation.reservationNo(), now);

        // 创建 PREORDER 操作
        String operationNo = businessNoGenerator.next(BusinessNoPrefixes.IOP);
        InstoreOperationDO operation = InstoreOperationDO.builder()
                .operationNo(operationNo).instoreOrderId(instoreOrder.getId()).paymentFlowId(flow.getId())
                .refundCaseId(null).supplierOrderId(null)
                .operationType(InstoreOperationType.PREORDER.name())
                .idempotencyKey(InstoreIdempotencyKeys.preorder(command.channelCode(), command.tradeId()))
                .requestFingerprint(requestFingerprint).externalOperationNo(null)
                .status(InstoreOperationStatus.PENDING.getCode()).resultClass(null).attemptCount(0)
                .nextRecoverAt(null).responseDigest(null).version(0L).createdAt(now).updatedAt(now)
                .completedAt(null).build();
        operationMapper.insert(operation);

        if (orderHeadNewlyCreated) {
            appendOrderSummaryEvent(order, instoreOrder, command.traceId(), now);
            OrderSummaryDecision summary = decideOrderSummary(InstoreOrderStatus.CREATED);
            // 受理投影必须与 sc_order_status_log.uk_sc_order_status_version 对齐：
            // 日志写 detail_version=1 的同时把总表从 0 CAS 到 1。否则取消/支付确认再写 1 会撞键。
            // 见 docs/集成测试.md 受理后 detail_version=1，以及
            // docs/superpowers/specs/2026-08-07-supply-chain-phase1-database-ddl.md 状态日志唯一键。
            if (orderMapper.advanceDetailVersion(order.getId(), 0L, 1L, now) != 1) {
                throw new IllegalStateException("INSTORE order detail version lost monotonicity");
            }
            order.setDetailVersion(1L);
            appendStatusLog(order, 1L, summary, OrderStatusAuditCodes.SOURCE_INSTORE_ACCEPTED,
                    instoreOrder.getInstoreOrderNo(), OrderStatusAuditCodes.REASON_ACCEPTED,
                    command.traceId(), now);
        }
        log.info("[INSTORE][lifecycle] createPaymentOrder 完成 orderNo={}, instoreOrderNo={}, paymentFlowNo={}, outTradeNo={}, invocationId={}, reservationNo={}",
                order.getOrderNo(), instoreOrder.getInstoreOrderNo(), paymentFlowNo, outTradeNo,
                operationNo, reservation.reservationNo());
        return new PreparedPayment(order.getId(), order.getOrderNo(), instoreOrder.getId(),
                instoreOrder.getInstoreOrderNo(), flow.getId(), paymentFlowNo, outTradeNo, operationNo,
                operation.getVersion(), command.projectId(), command.cashierProfileVersionId(),
                command.buyerUserId(), command.subject(), command.payAmount(),
                reservation.reservationNo(), null, InstorePaymentFlowStatus.PENDING.getCode(), false);
    }

    private PreparedPayment replayExistingFlow(InstorePaymentFlowDO existing, CreatePaymentOrderCommand command) {
        if (existing.getPayAmount() != command.payAmount()) {
            throw exception(INSTORE_IDENTITY_CONFLICT);
        }
        InstoreChannelOrderDO instoreOrder = instoreOrderMapper.selectById(existing.getInstoreOrderId());
        if (instoreOrder == null) {
            throw exception(INSTORE_ORDER_NOT_FOUND);
        }
        if (!command.projectId().equals(instoreOrder.getProjectId())
                || !command.cashierProfileVersionId().equals(instoreOrder.getCashierProfileVersionId())
                || !command.buyerUserId().equals(instoreOrder.getBuyerUserId())) {
            throw exception(INSTORE_IDENTITY_CONFLICT);
        }
        InstoreOperationDO operation = operationMapper.selectByIdempotencyKey(
                InstoreIdempotencyKeys.preorder(command.channelCode(), command.tradeId()));
        return new PreparedPayment(existing.getOrderId(), null, existing.getInstoreOrderId(), null,
                existing.getId(), existing.getPaymentFlowNo(), existing.getOutTradeNo(),
                operation == null ? null : operation.getOperationNo(),
                operation == null ? 0L : operation.getVersion(),
                instoreOrder.getProjectId(), instoreOrder.getCashierProfileVersionId(),
                instoreOrder.getBuyerUserId(), command.subject(), existing.getPayAmount(),
                existing.getFundReservationNo(), existing.getCashierUrl(), existing.getPayStatus(), true);
    }

    private SupplyChainOrderWithHead createBuyerOrderAndHead(CreatePaymentOrderCommand command,
                                                              InstoreChannelUserBindingDO binding,
                                                              SupplierChannelBindingDO channelBinding,
                                                              SupplierDO supplier,
                                                              LocalDateTime now, PreparedOrderNumbers numbers) {
        OrderSummaryDecision summary = decideOrderSummary(InstoreOrderStatus.CREATED);
        String orderNo = java.util.Objects.requireNonNull(numbers, "预分配订单号码不可为空").orderNo();
        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .orderNo(orderNo).orderType(SupplyChainOrderTypes.ORDER_TYPE_INSTORE)
                .buyerId(binding.getBuyerId()).buyerCodeSnapshot(command.userCode()).buyerNameSnapshot(command.userCode())
                .buyerUserId(binding.getBuyerUserId()).buyerOrderNo(command.channelOrderId())
                .acceptanceReceiptId(null)
                .displaySupplierId(supplier.getId()).displaySupplierCode(supplier.getSupplierCode())
                .displaySupplierName(supplier.getSupplierName())
                .buyerSettlementAmount(command.totalAmount()).currency(Money.CURRENCY_CNY)
                .platformSkuId(null).skuCodeSnapshot(null).skuNameSnapshot(null)
                .quantity(null).buyerOrderDigest(null)
                .orderStatus(summary.orderStatus()).aftersaleStatus(summary.aftersaleStatus())
                .businessStage(summary.businessStage())
                .detailType(SupplyChainOrderTypes.DETAIL_TYPE_INSTORE_CHANNEL).detailId(null)
                .detailVersion(0L).requestFingerprint(sha256(canonicalPaymentRequest(command)))
                .version(0L).createdAt(now).updatedAt(now).build();
        orderMapper.insert(order);

        String instoreOrderNo = businessNoGenerator.next(BusinessNoPrefixes.ISC);
        InstoreChannelOrderDO instoreOrder = InstoreChannelOrderDO.builder()
                .instoreOrderNo(instoreOrderNo).orderId(order.getId()).buyerId(binding.getBuyerId())
                .projectId(command.projectId()).cashierProfileVersionId(command.cashierProfileVersionId())
                .sourceSystem(command.sourceSystem()).externalTenantId(command.externalTenantId())
                .buyerUserId(binding.getBuyerUserId()).channelCode(command.channelCode())
                .channelUserBindingId(binding.getId()).channelUserId(null)
                .channelOrderId(command.channelOrderId()).companyCode(command.companyCode())
                .supplierChannelBindingId(channelBinding.getId()).supplierId(supplier.getId())
                .supplierCodeSnapshot(supplier.getSupplierCode())
                .supplierNameSnapshot(supplier.getSupplierName())
                .externalStoreId(command.shopCode()).externalStoreName(command.shopName())
                .storeSnapshotJson(null).totalAmount(command.totalAmount()).totalPaidAmount(0L)
                .currency(Money.CURRENCY_CNY).totalChannelRefundAmount(0L).totalBuyerRefundAmount(0L)
                .totalAccountReturnAmount(0L).activeRefundCaseId(null)
                .orderStatus(InstoreOrderStatus.CREATED.getCode()).version(0L).createdAt(now)
                .updatedAt(now).completedAt(null).build();
        instoreOrderMapper.insert(instoreOrder);
        if (orderMapper.attachDetail(order.getId(), instoreOrder.getId(), 0L, now) != 1) {
            throw new IllegalStateException("INSTORE order detail attachment lost invariant");
        }
        return new SupplyChainOrderWithHead(order, instoreOrder);
    }

    /**
     * 第二段：CAS 抢占 PREORDER（待执行→执行中），支付流水推进为收银台创建中。
     *
     * <p>抢占失败只能查单，禁止再次调用收银台。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ClaimResult claimPaymentPreparation(String invocationId, long expectedVersion) {
        log.info("[INSTORE][lifecycle] claimPaymentPreparation 入口 invocationId={}, expectedVersion={}",
                invocationId, expectedVersion);
        InstoreOperationDO operation = operationMapper.selectByOperationNo(invocationId);
        if (operation == null) {
            throw new IllegalStateException("PREORDER operation not found: " + invocationId);
        }
        LocalDateTime now = now();
        int claimed = operationMapper.claimPreorder(operation.getId(), expectedVersion, now);
        if (claimed != 1) {
            log.info("[INSTORE][lifecycle] 抢占失败，禁止调收银台 invocationId={}, currentVersion={}",
                    invocationId, operation.getVersion());
            return new ClaimResult(false, operation.getPaymentFlowId(), operation.getVersion());
        }
        InstorePaymentFlowDO flow = paymentFlowMapper.selectById(operation.getPaymentFlowId());
        if (flow == null) {
            throw new IllegalStateException("Payment flow disappeared during claim");
        }
        if (paymentFlowMapper.advanceToCashierCreating(flow.getId(), flow.getVersion(),
                InstorePaymentFlowStatus.PROCESSING.getCode(), null, now) != 1) {
            throw exception(ORDER_STATE_CONFLICT);
        }
        log.info("[INSTORE][lifecycle] 抢占成功 invocationId={}, paymentFlowId={}",
                invocationId, flow.getId());
        return new ClaimResult(true, flow.getId(), expectedVersion + 1);
    }

    /**
     * 第四段：按 invocationId + expectedVersion 记录收银台建单结果。
     *
     * <p>CREATED 回写 cashierReference；REJECTED/UNKNOWN 只推进 Operation 状态，保持原 outTradeNo。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void recordCashierOrder(String invocationId, long expectedVersion,
                                    BuyerCashierPort.CreateCashierOrderResult result) {
        log.info("[INSTORE][lifecycle] recordCashierOrder 入口 invocationId={}, expectedVersion={}, cashierStatus={}, cashierOrderNo={}",
                invocationId, expectedVersion, result.status(), result.cashierOrderNo());
        InstoreOperationDO operation = operationMapper.selectByOperationNo(invocationId);
        if (operation == null) {
            throw new IllegalStateException("PREORDER operation not found: " + invocationId);
        }
        LocalDateTime now = now();
        short opStatus = switch (result.status()) {
            case CREATED -> InstoreOperationStatus.RUNNING.getCode();
            case REJECTED -> InstoreOperationStatus.FAILED.getCode();
            case UNKNOWN -> InstoreOperationStatus.UNKNOWN.getCode();
        };
        if (operationMapper.recordResult(operation.getId(), expectedVersion, opStatus,
                result.cashierOrderNo(), result.status().name(), now) != 1) {
            throw exception(ORDER_STATE_CONFLICT);
        }
        if (result.status() == BuyerCashierPort.CashierOrderStatus.CREATED) {
            InstorePaymentFlowDO flow = paymentFlowMapper.selectById(operation.getPaymentFlowId());
            if (flow == null || paymentFlowMapper.recordCashierOrder(
                    flow.getId(), flow.getVersion(), InstorePaymentFlowStatus.PROCESSING.getCode(),
                    result.launchDescriptor(), result.cashierOrderNo(), now) != 1) {
                throw exception(ORDER_STATE_CONFLICT);
            }
        }
        log.info("[INSTORE][lifecycle] recordCashierOrder 完成 invocationId={}, opStatus={}",
                invocationId, opStatus);
    }

    /**
     * 支付回调确认：确认 FundReservation、创建固定商户供应商订单（SUCCEEDED，到店即完成）、
     * 推进支付流水为已支付、推进订单头为已完成，并写采购商订单摘要变更 Outbox。
     *
     * <p>按 outTradeNo 加载已固化归属；实付金额必须等于流水应付金额，否则身份冲突。
     * 流水已 PAID 且金额一致则幂等返回，不重复提交资金、不重复建供应商订单。
     * 到店即完成，无 225 推送与渠道通知收敛。
     * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void confirmBuyerPayment(String outTradeNo, long buyerPaidAmount,
                                     String cashierReference, String externalTransactionId,
                                     String traceId) {
        log.info("[INSTORE][lifecycle] confirmBuyerPayment 入口 outTradeNo={}, buyerPaidAmount={}, cashierReference={}, traceId={}",
                outTradeNo, buyerPaidAmount, cashierReference, traceId);
        InstorePaymentFlowDO flow = paymentFlowMapper.selectByOutTradeNo(outTradeNo);
        if (flow == null) {
            log.warn("[INSTORE][lifecycle] 支付确认找不到流水 outTradeNo={}", outTradeNo);
            throw exception(INSTORE_PAYMENT_FLOW_NOT_FOUND);
        }
        if (buyerPaidAmount != flow.getPayAmount()) {
            throw exception(INSTORE_IDENTITY_CONFLICT);
        }
        if (flow.getPayStatus() == InstorePaymentFlowStatus.PAID.getCode()) {
            log.info("[INSTORE][lifecycle] 支付确认幂等回放 outTradeNo={}, paymentFlowNo={}",
                    outTradeNo, flow.getPaymentFlowNo());
            return;
        }
        if (isTerminalPaymentFlowStatus(flow.getPayStatus())) {
            throw exception(ORDER_STATE_CONFLICT);
        }
        InstoreChannelOrderDO instoreOrder = instoreOrderMapper.selectById(flow.getInstoreOrderId());
        if (instoreOrder == null) {
            throw exception(INSTORE_ORDER_NOT_FOUND);
        }
        SupplyChainOrderDO order = orderMapper.selectById(flow.getOrderId());
        if (order == null) {
            throw exception(INSTORE_ORDER_NOT_FOUND);
        }
        if (isTerminalInstoreOrderStatus(instoreOrder.getOrderStatus())) {
            log.warn("[INSTORE][lifecycle] 订单头状态不允许支付确认 outTradeNo={}, orderStatus={}",
                    outTradeNo, instoreOrder.getOrderStatus());
            throw exception(ORDER_STATE_CONFLICT);
        }

        // 确认资金
        fundReservationService.commit(flow.getFundReservationNo(), order.getOrderNo(),
                traceId, flow.getPaymentFlowNo());

        LocalDateTime now = now();
        // 创建固定商户供应商订单（到店即完成，SUCCEEDED）
        InstoreSupplierOrderDO supplierOrder = createFixedMerchantSupplierOrder(flow, instoreOrder, order, now);
        supplierOrderMapper.insert(supplierOrder);
        // 回填供应商订单号到支付流水
        paymentFlowMapper.attachSupplierOrder(flow.getId(), flow.getVersion(), supplierOrder.getId(), now);
        // 推进支付流水为已支付
        paymentFlowMapper.confirmPayment(flow.getId(), flow.getVersion(),
                InstorePaymentFlowStatus.PAID.getCode(), buyerPaidAmount, externalTransactionId, now, now);

        // 推进订单头为已完成，累计已支付金额
        long newTotalPaid = instoreOrder.getTotalPaidAmount() + buyerPaidAmount;
        instoreOrderMapper.advanceStatus(instoreOrder.getId(), instoreOrder.getVersion(),
                InstoreOrderStatus.COMPLETED.getCode(), newTotalPaid, now);
        // 推进总表公共状态
        advanceOrderSummary(instoreOrder, order, InstoreOrderStatus.COMPLETED, traceId,
                OrderStatusAuditCodes.SOURCE_INSTORE_PAID, OrderStatusAuditCodes.REASON_PAID, now);
        // 写采购商订单摘要变更 Outbox（无渠道通知 Outbox，pull 模型）
        appendOrderSummaryEvent(order, instoreOrder, traceId, now);
        log.info("[INSTORE][lifecycle] confirmBuyerPayment 完成 outTradeNo={}, tradeId={}, orderNo={}, supplierOrderNo={}",
                outTradeNo, flow.getTradeId(), order.getOrderNo(), supplierOrder.getSupplierOrderNo());
    }

    private InstoreSupplierOrderDO createFixedMerchantSupplierOrder(InstorePaymentFlowDO flow,
                                                                     InstoreChannelOrderDO instoreOrder,
                                                                     SupplyChainOrderDO order,
                                                                     LocalDateTime now) {
        String supplierOrderNo = businessNoGenerator.next(BusinessNoPrefixes.ISO);
        return InstoreSupplierOrderDO.builder()
                .supplierOrderNo(supplierOrderNo).orderId(order.getId()).instoreOrderId(instoreOrder.getId())
                .paymentFlowId(flow.getId()).buyerId(instoreOrder.getBuyerId())
                .supplierId(instoreOrder.getSupplierId())
                .supplierChannelBindingId(instoreOrder.getSupplierChannelBindingId())
                .supplierCodeSnapshot(instoreOrder.getSupplierCodeSnapshot())
                .supplierNameSnapshot(instoreOrder.getSupplierNameSnapshot())
                .channelCode(instoreOrder.getChannelCode()).channelOrderId(instoreOrder.getChannelOrderId())
                .tradeId(flow.getTradeId()).settlementMode("CHANNEL_AMOUNT")
                .channelPayAmount(flow.getPayAmount()).supplierSettlementAmount(flow.getPayAmount())
                .currency(Money.CURRENCY_CNY)
                .fulfillmentStatus((short) 30) // 到店即完成，SUCCEEDED
                .settlementStatus((short) 0).externalFulfillmentNo(null)
                .activeFlag(null).effectiveSuccessFlag((byte) 1)
                .version(0L).createdAt(now).updatedAt(now).completedAt(now).build();
    }

    /**
     * 取消支付流水：旅通接口④。未支付则关单释放资金；已支付则只落 CANCEL_REFUND Case（联调切片不动账本）。
     *
     * <p>未支付已 CANCELLED 幂等回放，不再 release。已支付同 trade_id 用稳定
     * {@link InstoreIdempotencyKeys#cancelRefundChannelNo(String)} 回放；若已有 CHANNEL_REFUND 则拒绝。
     * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public CancelPaymentFlowResult cancelPaymentFlow(CancelPaymentFlowCommand command) {
        log.info("[INSTORE][lifecycle] cancelPaymentFlow 入口 channelCode={}, tradeId={}, traceId={}",
                command.channelCode(), command.tradeId(), command.traceId());
        InstorePaymentFlowDO flow = paymentFlowMapper.selectByChannelTrade(
                command.channelCode(), command.tradeId());
        if (flow == null) {
            throw exception(INSTORE_PAYMENT_FLOW_NOT_FOUND);
        }
        InstoreChannelOrderDO instoreOrder = instoreOrderMapper.selectById(flow.getInstoreOrderId());
        if (instoreOrder == null) {
            throw exception(INSTORE_ORDER_NOT_FOUND);
        }
        verifyProjectScope(command.expectedProjectId(), instoreOrder);
        SupplyChainOrderDO order = orderMapper.selectById(flow.getOrderId());
        if (order == null) {
            throw exception(INSTORE_ORDER_NOT_FOUND);
        }
        LocalDateTime now = now();

        if (flow.getPayStatus() != InstorePaymentFlowStatus.PAID.getCode()) {
            if (flow.getPayStatus() == InstorePaymentFlowStatus.CANCELLED.getCode()) {
                log.info("[INSTORE][lifecycle] 未支付取消幂等回放 tradeId={}, outTradeNo={}",
                        command.tradeId(), flow.getOutTradeNo());
                return new CancelPaymentFlowResult(flow.getOutTradeNo(), null, false, false, null);
            }
            if (isTerminalPaymentFlowStatus(flow.getPayStatus())) {
                throw exception(ORDER_STATE_CONFLICT);
            }
            // 未支付：关单释放资金；订单头无已支付流水则 CANCELLED
            fundReservationService.release(flow.getFundReservationNo(), order.getOrderNo(),
                    command.traceId(), flow.getPaymentFlowNo());
            paymentFlowMapper.cancelFlow(flow.getId(), flow.getVersion(),
                    InstorePaymentFlowStatus.CANCELLED.getCode(), now);
            long paidFlows = countPaidFlowsByInstoreOrder(instoreOrder.getId());
            if (paidFlows == 0) {
                instoreOrderMapper.advanceStatus(instoreOrder.getId(), instoreOrder.getVersion(),
                        InstoreOrderStatus.CANCELLED.getCode(), null, now);
                advanceOrderSummary(instoreOrder, order, InstoreOrderStatus.CANCELLED, command.traceId(),
                        OrderStatusAuditCodes.SOURCE_INSTORE_CANCELLED, OrderStatusAuditCodes.REASON_REFUND, now);
            }
            log.info("[INSTORE][lifecycle] 取消未支付流水完成 tradeId={}, outTradeNo={}",
                    command.tradeId(), flow.getOutTradeNo());
            return new CancelPaymentFlowResult(flow.getOutTradeNo(), null, false, false, null);
        }

        // 已支付：落 CANCEL_REFUND Case 并与接口⑤共用同一串行派发闭环
        AcceptRefundOutcome outcome = createCancelRefundCase(flow, instoreOrder, order, command.traceId(), now);
        log.info("[INSTORE][lifecycle] 取消已支付流水转退款完成 tradeId={}, refundCaseNo={}, claimed={}",
                command.tradeId(), outcome.refundCaseNo(), outcome.claimedForDispatch());
        return new CancelPaymentFlowResult(flow.getOutTradeNo(), outcome.refundCaseNo(),
                true, outcome.claimedForDispatch(), outcome.operationNo());
    }

    private AcceptRefundOutcome createCancelRefundCase(InstorePaymentFlowDO flow, InstoreChannelOrderDO instoreOrder,
                                                       SupplyChainOrderDO order, String traceId, LocalDateTime now) {
        String channelRefundNo = InstoreIdempotencyKeys.cancelRefundChannelNo(flow.getTradeId());
        InstoreRefundCaseDO existingCancel = refundCaseMapper.selectByChannelRefundNo(
                flow.getChannelCode(), channelRefundNo);
        if (existingCancel == null) {
            existingCancel = refundCaseMapper.selectByFlowAndType(
                    flow.getId(), InstoreRefundType.CANCEL_REFUND.name());
        }
        if (existingCancel != null) {
            log.info("[INSTORE][lifecycle] 取消转退款幂等回放 tradeId={}, refundCaseNo={}, status={}",
                    flow.getTradeId(), existingCancel.getRefundCaseNo(), existingCancel.getStatus());
            return claimOrQueue(existingCancel, instoreOrder, flow.getOutTradeNo(), traceId);
        }
        InstoreRefundCaseDO channelRefund = refundCaseMapper.selectByFlowAndType(
                flow.getId(), InstoreRefundType.CHANNEL_REFUND.name());
        if (channelRefund != null) {
            log.warn("[INSTORE][lifecycle] 已有渠道退款，取消转退款失败 tradeId={}, flowId={}",
                    flow.getTradeId(), flow.getId());
            throw exception(INSTORE_CANCEL_REFUND_FAILED);
        }
        int sequence = refundSequencer.nextSequence(flow.getId());
        String refundCaseNo = businessNoGenerator.next(BusinessNoPrefixes.IRC);
        InstoreRefundCaseDO refundCase = InstoreRefundCaseDO.builder()
                .refundCaseNo(refundCaseNo).instoreOrderId(instoreOrder.getId()).paymentFlowId(flow.getId())
                .orderId(order.getId()).buyerId(instoreOrder.getBuyerId()).channelCode(flow.getChannelCode())
                .channelRefundNo(channelRefundNo)
                .refundSequence(sequence).refundType(InstoreRefundType.CANCEL_REFUND.name())
                .channelRefundAmount(flow.getPayAmount()).buyerActualRefundAmount(null)
                .accountReturnAmount(null).currency(Money.CURRENCY_CNY).cashierRefundReference(null)
                .requestFingerprint(sha256(channelRefundNo.getBytes(StandardCharsets.UTF_8)))
                .status(InstoreRefundStatus.QUEUED.getCode()).buyerRefundingFlag(null)
                .resultReasonCode(null).version(0L).createdAt(now).updatedAt(now).completedAt(null).build();
        try {
            refundCaseMapper.insert(refundCase);
        } catch (DuplicateKeyException duplicate) {
            // 并发同 trade_id 取消：UK(channelCode, channel_refund_no) 冲突后回读回放
            InstoreRefundCaseDO raced = refundCaseMapper.selectByChannelRefundNo(
                    flow.getChannelCode(), channelRefundNo);
            if (raced == null) {
                throw duplicate;
            }
            log.info("[INSTORE][lifecycle] 取消转退款并发撞键回放 tradeId={}, refundCaseNo={}",
                    flow.getTradeId(), raced.getRefundCaseNo());
            return claimOrQueue(raced, instoreOrder, flow.getOutTradeNo(), traceId);
        }
        log.info("[INSTORE][lifecycle] 取消转退款落 Case 完成 tradeId={}, refundCaseNo={}",
                flow.getTradeId(), refundCaseNo);
        return claimOrQueue(refundCase, instoreOrder, flow.getOutTradeNo(), traceId);
    }

    /**
     * 只读查询支付流水状态（中台内部 pay_status），不上 HTTP、不映射旅通 0/1/2。
     *
     * <p>详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public PaymentStatusView queryPaymentStatus(String channelCode, String tradeId) {
        log.info("[INSTORE][lifecycle] queryPaymentStatus 入口 channelCode={}, tradeId={}", channelCode, tradeId);
        InstorePaymentFlowDO flow = paymentFlowMapper.selectByChannelTrade(channelCode, tradeId);
        if (flow == null) {
            throw exception(INSTORE_PAYMENT_FLOW_NOT_FOUND);
        }
        return new PaymentStatusView(flow.getPaymentFlowNo(), flow.getOutTradeNo(), flow.getTradeId(),
                flow.getPayStatus(), flow.getPayAmount(), flow.getBuyerPaidAmount(),
                flow.getCashierReference(), flow.getExternalTransactionId(),
                flow.getPayTime() != null ? databaseClock.toInstant(flow.getPayTime()).getEpochSecond() : null);
    }

    /**
     * 只读查询退款 Case 状态（中台内部 status），不上 HTTP。
     *
     * <p>详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public RefundStatusView queryRefundStatus(String channelCode, String channelRefundNo) {
        log.info("[INSTORE][lifecycle] queryRefundStatus 入口 channelCode={}, channelRefundNo={}",
                channelCode, channelRefundNo);
        InstoreRefundCaseDO refundCase = refundCaseMapper.selectByChannelRefundNo(channelCode, channelRefundNo);
        if (refundCase == null) {
            throw exception(INSTORE_REFUND_CASE_NOT_FOUND);
        }
        InstorePaymentFlowDO flow = paymentFlowMapper.selectById(refundCase.getPaymentFlowId());
        String outTradeNo = flow != null ? flow.getOutTradeNo() : null;
        Long refundTime = refundCase.getCompletedAt() != null
                ? databaseClock.toInstant(refundCase.getCompletedAt()).getEpochSecond() : null;
        return new RefundStatusView(refundCase.getRefundCaseNo(), refundCase.getChannelRefundNo(),
                refundCase.getRefundType(), refundCase.getStatus(), refundCase.getChannelRefundAmount(),
                refundCase.getBuyerActualRefundAmount(), outTradeNo, refundTime, refundCase.getResultReasonCode());
    }

    /**
     * 退款受理（接口⑤）：锁定订单头后按"已成功累计 + 在途（排队中/退款中）"校验可退金额，
     * 落 QUEUED Case；同一支付流水无活动退款时抢占派发（QUEUED→BUYER_REFUNDING + 置活动标记 +
     * 订单头 activeRefundCaseId + BUYER_REFUND Operation），有活动退款时排队等待。
     *
     * <p>幂等：同 (channelCode, channelRefundNo) 回放已有 Case；回放的 QUEUED Case 仍可被抢占派发。
     * 调用方（Facade）在事务提交后按返回的 operationNo 外呼收银台。
     * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量} 第 4 条。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public AcceptRefundOutcome acceptRefund(AcceptRefundCommand command) {
        log.info("[INSTORE][lifecycle] acceptRefund 入口 channelCode={}, channelRefundNo={}, tradeId={}, refundType={}, refundAmount={}, traceId={}",
                command.channelCode(), command.channelRefundNo(), command.tradeId(),
                command.refundType(), command.channelRefundAmount(), command.traceId());
        requirePositiveRefundAmount(command.channelRefundAmount());
        byte[] requestFingerprint = sha256(canonicalRefundRequest(command));
        InstoreRefundCaseDO existingCase = refundCaseMapper.selectByChannelRefundNo(
                command.channelCode(), command.channelRefundNo());
        if (existingCase != null) {
            if (!Arrays.equals(existingCase.getRequestFingerprint(), requestFingerprint)) {
                log.warn("[INSTORE][lifecycle] 退款幂等请求指纹冲突 channelCode={}, channelRefundNo={}, refundCaseNo={}",
                        command.channelCode(), command.channelRefundNo(), existingCase.getRefundCaseNo());
                throw exception(INSTORE_IDENTITY_CONFLICT);
            }
            log.info("[INSTORE][lifecycle] 退款幂等回放 channelRefundNo={}, refundCaseNo={}, status={}",
                    command.channelRefundNo(), existingCase.getRefundCaseNo(), existingCase.getStatus());
            InstoreChannelOrderDO replayOrder = lockRefundOrderHead(existingCase.getInstoreOrderId());
            verifyProjectScope(command.expectedProjectId(), replayOrder);
            InstorePaymentFlowDO replayFlow = paymentFlowMapper.selectById(existingCase.getPaymentFlowId());
            return claimOrQueue(existingCase, replayOrder,
                    replayFlow != null ? replayFlow.getOutTradeNo() : null, command.traceId());
        }
        InstorePaymentFlowDO flow = paymentFlowMapper.selectByChannelTrade(
                command.channelCode(), command.tradeId());
        if (flow == null) {
            throw exception(INSTORE_PAYMENT_FLOW_NOT_FOUND);
        }
        // 锁定订单头，串行化在途金额校验（并发受理第二个事务在前者提交后才能读到其 QUEUED Case）
        InstoreChannelOrderDO lockedOrder = lockRefundOrderHead(flow.getInstoreOrderId());
        verifyProjectScope(command.expectedProjectId(), lockedOrder);
        requireRefundable(flow, lockedOrder, command.channelRefundAmount());
        InstoreRefundType refundType;
        try {
            refundType = InstoreRefundType.valueOf(command.refundType());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid instore refund type: " + command.refundType());
        }
        LocalDateTime now = now();
        int sequence = refundSequencer.nextSequence(flow.getId());
        String refundCaseNo = businessNoGenerator.next(BusinessNoPrefixes.IRC);
        InstoreRefundCaseDO refundCase = InstoreRefundCaseDO.builder()
                .refundCaseNo(refundCaseNo).instoreOrderId(lockedOrder.getId()).paymentFlowId(flow.getId())
                .orderId(flow.getOrderId()).buyerId(lockedOrder.getBuyerId()).channelCode(command.channelCode())
                .channelRefundNo(command.channelRefundNo()).refundSequence(sequence)
                .refundType(refundType.name()).channelRefundAmount(command.channelRefundAmount())
                .buyerActualRefundAmount(null).accountReturnAmount(null).currency(Money.CURRENCY_CNY)
                .cashierRefundReference(null)
                .requestFingerprint(requestFingerprint)
                .status(InstoreRefundStatus.QUEUED.getCode()).buyerRefundingFlag(null)
                .resultReasonCode(null).version(0L).createdAt(now).updatedAt(now).completedAt(null).build();
        refundCaseMapper.insert(refundCase);
        log.info("[INSTORE][lifecycle] acceptRefund 完成 refundCaseNo={}, sequence={}",
                refundCaseNo, sequence);
        return claimOrQueue(refundCase, lockedOrder, flow.getOutTradeNo(), command.traceId());
    }

    /** 行锁加载订单头：退款受理与幂等回放派发共用，串行化在途金额校验与活动指针维护。 */
    private InstoreChannelOrderDO lockRefundOrderHead(Long instoreOrderId) {
        InstoreChannelOrderDO lockedOrder = instoreOrderMapper.selectByIdForUpdate(instoreOrderId);
        if (lockedOrder == null) {
            throw exception(INSTORE_ORDER_NOT_FOUND);
        }
        return lockedOrder;
    }

    /** 校验可信 projectId 与订单固化的项目归属一致，防止跨租户状态修改。 */
    private static void verifyProjectScope(Long expectedProjectId, InstoreChannelOrderDO order) {
        if (expectedProjectId == null || !expectedProjectId.equals(order.getProjectId())) {
            throw exception(INSTORE_TENANT_SCOPE_CONFLICT);
        }
    }

    /**
     * 可退金额校验：可退 = 已支付累计 - 已成功退款累计 - 在途（排队中/退款中）总额。
     * 在途含本流水所有未收敛 Case，防止并发排队导致超退。
     */
    private void requireRefundable(InstorePaymentFlowDO flow, InstoreChannelOrderDO lockedOrder,
                                    long refundAmount) {
        long inflight = refundCaseMapper.selectInflightByFlow(flow.getId()).stream()
                .mapToLong(InstoreRefundCaseDO::getChannelRefundAmount).sum();
        long refundable = lockedOrder.getTotalPaidAmount()
                - lockedOrder.getTotalChannelRefundAmount() - inflight;
        if (refundAmount > refundable) {
            throw exception(INSTORE_REFUND_AMOUNT_EXCEEDED);
        }
    }

    /**
     * 所有退款受理路径（含幂等回放）共享的正数金额不变量，必须在读取或派发已有 Case 前执行。
     * 详见 {@code docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#4-退款金额不变量与响应语义}。
     */
    private static void requirePositiveRefundAmount(long refundAmount) {
        if (refundAmount <= 0) {
            throw exception(INSTORE_REFUND_AMOUNT_INVALID);
        }
    }

    /**
     * 无活动退款则抢占派发（CAS + uk_sc_instore_refund_active 唯一键兜底），否则保持排队。
     * 抢占成功同时创建 RUNNING 状态的 BUYER_REFUND Operation（受理事务内即派发外呼，无需二次抢占）
     * 并回指订单头 activeRefundCaseId；setActiveRefundCase 在行锁内执行，CAS 失败即事务并发冲突，
     * 由外层乐观锁异常兜底回滚。
     */
    private AcceptRefundOutcome claimOrQueue(InstoreRefundCaseDO refundCase, InstoreChannelOrderDO order,
                                             String outTradeNo, String traceId) {
        InstoreRefundCaseDO active = refundCaseMapper.selectActiveByFlow(refundCase.getPaymentFlowId());
        if (active != null && !active.getId().equals(refundCase.getId())) {
            log.info("[INSTORE][lifecycle] 已有活动退款，保持排队 refundCaseNo={}, activeRefundCaseNo={}",
                    refundCase.getRefundCaseNo(), active.getRefundCaseNo());
            return new AcceptRefundOutcome(refundCase.getRefundCaseNo(), outTradeNo, false, null, 0L,
                    refundCase.getStatus());
        }
        if (refundCase.getStatus() == null
                || refundCase.getStatus() != InstoreRefundStatus.QUEUED.getCode()) {
            // 已在退款中或已终态：回放当前状态，不重复抢占
            return new AcceptRefundOutcome(refundCase.getRefundCaseNo(), outTradeNo, false, null, 0L,
                    refundCase.getStatus());
        }
        LocalDateTime now = now();
        if (refundCaseMapper.claimDispatch(refundCase.getId(), refundCase.getVersion(), now) != 1) {
            // CAS 失败：并发抢占（如 Timer 恢复），本 Case 保持排队
            log.info("[INSTORE][lifecycle] 退款抢占 CAS 失败保持排队 refundCaseNo={}",
                    refundCase.getRefundCaseNo());
            return new AcceptRefundOutcome(refundCase.getRefundCaseNo(), outTradeNo, false, null, 0L,
                    refundCase.getStatus());
        }
        String operationNo = businessNoGenerator.next(BusinessNoPrefixes.IOP);
        InstoreOperationDO operation = InstoreOperationDO.builder()
                .operationNo(operationNo).instoreOrderId(refundCase.getInstoreOrderId())
                .paymentFlowId(refundCase.getPaymentFlowId()).refundCaseId(refundCase.getId())
                .supplierOrderId(null).operationType(InstoreOperationType.BUYER_REFUND.name())
                .idempotencyKey(InstoreIdempotencyKeys.buyerRefund(refundCase.getRefundCaseNo()))
                .requestFingerprint(refundCase.getRequestFingerprint()).externalOperationNo(null)
                .status(InstoreOperationStatus.RUNNING.getCode()).resultClass(null).attemptCount(0)
                .nextRecoverAt(null).responseDigest(null).version(0L)
                .createdAt(now).updatedAt(now).completedAt(null).build();
        operationMapper.insert(operation);
        instoreOrderMapper.setActiveRefundCase(order.getId(), order.getVersion(), refundCase.getId(), now);
        log.info("[INSTORE][lifecycle] 退款抢占派发 refundCaseNo={}, operationNo={}",
                refundCase.getRefundCaseNo(), operationNo);
        return new AcceptRefundOutcome(refundCase.getRefundCaseNo(), outTradeNo, true, operationNo,
                operation.getVersion(), InstoreRefundStatus.BUYER_REFUNDING.getCode());
    }

    /**
     * 退款结果收敛（回调与权威查询共用）：终态幂等回放；SUCCESS 时实际金额必须等于申请金额，
     * 缺失或不一致转 MANUAL 且不返还、不累计；金额一致时在外层事务内调用
     * {@link BuyerAccountReturnService} 返还采购商账户，随后推进 Case 终态、订单累计、
     * 清活动指针、投影售后状态并抢占下一个排队 Case（串行锁语义：终态即释放，队列继续）。
     * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量} 第 6/7 条。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public RefundSettlementOutcome applyBuyerRefundResult(String refundCaseNo, String refundStatus,
                                                          Long buyerActualRefundAmount,
                                                          String cashierRefundReference,
                                                          String resultReasonCode, String traceId) {
        log.info("[INSTORE][lifecycle] applyBuyerRefundResult 入口 refundCaseNo={}, refundStatus={}, actualAmount={}, cashierRefundReference={}, traceId={}",
                refundCaseNo, refundStatus, buyerActualRefundAmount, cashierRefundReference, traceId);
        InstoreRefundCaseDO refundCase = refundCaseMapper.selectByRefundCaseNo(refundCaseNo);
        if (refundCase == null) {
            log.warn("[INSTORE][lifecycle] 退款结果找不到 Case refundCaseNo={}", refundCaseNo);
            throw exception(INSTORE_REFUND_CASE_NOT_FOUND);
        }
        if (isTerminalRefundStatus(refundCase.getStatus())) {
            log.info("[INSTORE][lifecycle] 退款终态幂等回放 refundCaseNo={}, status={}",
                    refundCaseNo, refundCase.getStatus());
            return new RefundSettlementOutcome(refundCase.getStatus(), false, false, null, null, 0L);
        }
        InstorePaymentFlowDO flow = paymentFlowMapper.selectById(refundCase.getPaymentFlowId());
        if (flow == null) {
            throw exception(INSTORE_PAYMENT_FLOW_NOT_FOUND);
        }
        InstoreChannelOrderDO instoreOrder = instoreOrderMapper.selectById(refundCase.getInstoreOrderId());
        if (instoreOrder == null) {
            throw exception(INSTORE_ORDER_NOT_FOUND);
        }
        SupplyChainOrderDO order = orderMapper.selectById(refundCase.getOrderId());
        if (order == null) {
            throw exception(INSTORE_ORDER_NOT_FOUND);
        }
        InstoreRefundStatus next = mapRefundStatus(refundStatus);
        LocalDateTime now = now();
        if (next == InstoreRefundStatus.SUCCESS) {
            // Long 装箱后不可用 != 比较（引用比较）；须用 equals 做金额一致判定
            boolean mismatch = buyerActualRefundAmount == null
                    || !buyerActualRefundAmount.equals(refundCase.getChannelRefundAmount());
            if (mismatch) {
                return settleManual(refundCase, flow, instoreOrder, order,
                        cashierRefundReference, traceId, now);
            }
            accountReturnService.returnFunds(new BuyerAccountReturnService.ReturnCommand(
                    fundReservationService.requireBuyerIdForReservation(flow.getFundReservationNo()),
                    refundCase.getRefundCaseNo(), buyerActualRefundAmount,
                    order.getOrderNo(), FundReferenceType.INSTORE_ORDER.code(),
                    instoreOrder.getInstoreOrderNo(), traceId, refundCase.getRefundCaseNo()));
            if (refundCaseMapper.completeRefund(refundCase.getId(), refundCase.getVersion(),
                    SUCCESS.getCode(), buyerActualRefundAmount, buyerActualRefundAmount,
                    cashierRefundReference, null, now) != 1) {
                throw exception(ORDER_STATE_CONFLICT);
            }
            if (instoreOrderMapper.accumulateRefundAmounts(instoreOrder.getId(), instoreOrder.getVersion(),
                    refundCase.getChannelRefundAmount(), buyerActualRefundAmount,
                    buyerActualRefundAmount, now) != 1) {
                throw exception(ORDER_STATE_CONFLICT);
            }
            return settleTerminal(refundCase, flow, instoreOrder, order, SUCCESS.getCode(), false,
                    buyerActualRefundAmount, buyerActualRefundAmount,
                    instoreOrder.getVersion() + 1, cashierRefundReference, traceId, now);
        }
        if (next == InstoreRefundStatus.FAILED) {
            if (refundCaseMapper.completeRefund(refundCase.getId(), refundCase.getVersion(),
                    InstoreRefundStatus.FAILED.getCode(), null, null,
                    cashierRefundReference, resultReasonCode, now) != 1) {
                throw exception(ORDER_STATE_CONFLICT);
            }
            return settleTerminal(refundCase, flow, instoreOrder, order,
                    InstoreRefundStatus.FAILED.getCode(), false, null, null,
                    instoreOrder.getVersion(), cashierRefundReference, traceId, now);
        }
        // PROCESSING/其它非终态：仍在收银台处理，保持 BUYER_REFUNDING，等待回调或 Timer 权威查询
        log.info("[INSTORE][lifecycle] 退款非终态保持退款中 refundCaseNo={}, refundStatus={}",
                refundCaseNo, refundStatus);
        return new RefundSettlementOutcome(refundCase.getStatus(), false, false, null, null, 0L);
    }

    /** 金额缺失/不一致：转人工，不返还不累计；售后投影保持处理中（人工属于未收敛协同）。 */
    private RefundSettlementOutcome settleManual(InstoreRefundCaseDO refundCase, InstorePaymentFlowDO flow,
                                                 InstoreChannelOrderDO instoreOrder, SupplyChainOrderDO order,
                                                 String cashierRefundReference, String traceId,
                                                 LocalDateTime now) {
        log.warn("[INSTORE][lifecycle] 退款实际金额与申请不一致转人工 refundCaseNo={}, applied={}, actual={}",
                refundCase.getRefundCaseNo(), refundCase.getChannelRefundAmount(),
                refundCase.getBuyerActualRefundAmount());
        if (refundCaseMapper.completeRefund(refundCase.getId(), refundCase.getVersion(),
                MANUAL.getCode(), null, null, cashierRefundReference,
                InstoreRejectionCode.REFUND_AMOUNT_MISMATCH.name(), now) != 1) {
            throw exception(ORDER_STATE_CONFLICT);
        }
        return settleTerminal(refundCase, flow, instoreOrder, order, MANUAL.getCode(), true,
                null, null, instoreOrder.getVersion(), cashierRefundReference, traceId, now);
    }

    /**
     * 终态收尾：清活动指针、投影售后、写退款 Case 通知 Outbox，并抢占下一个排队 Case
     * （串行锁语义：终态即释放，队列继续）。orderVersion 为当前订单头有效版本
     * （SUCCESS 分支已由累计推进 +1，FAILED/MANUAL 分支保持原版本）。
     */
    private RefundSettlementOutcome settleTerminal(InstoreRefundCaseDO refundCase, InstorePaymentFlowDO flow,
                                                   InstoreChannelOrderDO instoreOrder, SupplyChainOrderDO order,
                                                   short finalStatus, boolean amountMismatch,
                                                   Long actualAmount, Long accountReturnAmount,
                                                   long orderVersion, String cashierRefundReference,
                                                   String traceId, LocalDateTime now) {
        instoreOrderMapper.setActiveRefundCase(instoreOrder.getId(), orderVersion, null, now);
        projectRefundAftersale(refundCase, flow, instoreOrder, order, finalStatus, traceId, now);
        appendRefundCaseEvent(refundCase, flow, order, finalStatus, actualAmount,
                accountReturnAmount, traceId, now);
        ClaimedNext next = claimNextQueuedRefund(flow, instoreOrder, orderVersion + 1, traceId, now);
        log.info("[INSTORE][lifecycle] applyBuyerRefundResult 完成 refundCaseNo={}, finalStatus={}, nextClaimed={}",
                refundCase.getRefundCaseNo(), finalStatus, next != null ? next.refundCaseNo() : null);
        return new RefundSettlementOutcome(finalStatus, amountMismatch, next != null,
                next != null ? next.refundCaseNo() : null,
                next != null ? next.operationNo() : null,
                next != null ? next.operationVersion() : 0L);
    }

    /**
     * 按未收敛 Case 与累计退款投影总表售后状态（detailVersion 单调递增）。
     * 累计口径：订单头内存累计 + 本 Case 金额（SUCCESS 才计入；FAILED/MANUAL 不累计）。
     */
    private void projectRefundAftersale(InstoreRefundCaseDO refundCase, InstorePaymentFlowDO flow,
                                        InstoreChannelOrderDO instoreOrder, SupplyChainOrderDO order,
                                        short finalStatus, String traceId, LocalDateTime now) {
        boolean hasOpen = !refundCaseMapper.selectOpenByFlow(flow.getId()).isEmpty();
        long channelDelta = finalStatus == SUCCESS.getCode()
                && refundCase.getChannelRefundAmount() != null ? refundCase.getChannelRefundAmount() : 0L;
        InstoreRefundProjection projection = new InstoreRefundProjection(hasOpen,
                instoreOrder.getTotalChannelRefundAmount() + channelDelta,
                instoreOrder.getTotalPaidAmount());
        OrderSummaryDecision decision = instoreOrderSummaryPolicy.decide(
                buildSummaryFact(instoreOrder), projection);
        if (orderMapper.advanceAftersaleStatus(order.getId(), order.getDetailVersion(),
                order.getDetailVersion() + 1, decision.aftersaleStatus(), now) != 1) {
            throw exception(ORDER_STATE_CONFLICT);
        }
        appendStatusLog(order, order.getDetailVersion() + 1, decision,
                OrderStatusAuditCodes.SOURCE_INSTORE_REFUND_RESULT,
                instoreOrder.getInstoreOrderNo(), OrderStatusAuditCodes.REASON_REFUND, traceId, now);
    }

    /** 终态即释放串行锁：抢占下一个排队 Case（按 refund_sequence），创建 BUYER_REFUND Operation 并回指活动指针。 */
    private ClaimedNext claimNextQueuedRefund(InstorePaymentFlowDO flow, InstoreChannelOrderDO instoreOrder,
                                              long orderVersion, String traceId, LocalDateTime now) {
        InstoreRefundCaseDO next = refundCaseMapper.selectNextQueuedByFlow(flow.getId());
        if (next == null) {
            return null;
        }
        if (refundCaseMapper.claimDispatch(next.getId(), next.getVersion(), now) != 1) {
            return null;
        }
        String operationNo = businessNoGenerator.next(BusinessNoPrefixes.IOP);
        InstoreOperationDO operation = InstoreOperationDO.builder()
                .operationNo(operationNo).instoreOrderId(next.getInstoreOrderId())
                .paymentFlowId(flow.getId()).refundCaseId(next.getId()).supplierOrderId(null)
                .operationType(InstoreOperationType.BUYER_REFUND.name())
                .idempotencyKey(InstoreIdempotencyKeys.buyerRefund(next.getRefundCaseNo()))
                .requestFingerprint(next.getRequestFingerprint()).externalOperationNo(null)
                .status(InstoreOperationStatus.RUNNING.getCode()).resultClass(null).attemptCount(0)
                .nextRecoverAt(null).responseDigest(null).version(0L)
                .createdAt(now).updatedAt(now).completedAt(null).build();
        operationMapper.insert(operation);
        instoreOrderMapper.setActiveRefundCase(instoreOrder.getId(), orderVersion, next.getId(), now);
        log.info("[INSTORE][lifecycle] 退款终态派发下一个排队 Case nextRefundCaseNo={}, operationNo={}",
                next.getRefundCaseNo(), operationNo);
        return new ClaimedNext(next.getRefundCaseNo(), operationNo, operation.getVersion() + 1);
    }

    /** 退款 Case 终态通知 Outbox：金额与终态随事件外发，供采购项目侧对账。 */
    private void appendRefundCaseEvent(InstoreRefundCaseDO refundCase, InstorePaymentFlowDO flow,
                                       SupplyChainOrderDO order, short finalStatus, Long actualAmount,
                                       Long accountReturnAmount, String traceId, LocalDateTime now) {
        outboxRepository.appendLocal(InstoreOutboxCodes.AGGREGATE_INSTORE_REFUND_CASE,
                refundCase.getRefundCaseNo(), 1,
                InstoreOutboxCodes.EVENT_INSTORE_ORDER_SUMMARY_CHANGED, InstoreOutboxCodes.TARGET_BUYER_EVENT_RPC,
                Map.of("channelCode", refundCase.getChannelCode(),
                        "channelRefundNo", refundCase.getChannelRefundNo(),
                        "tradeId", flow.getTradeId(),
                        "refundAmount", refundCase.getChannelRefundAmount(),
                        "finalStatus", finalStatus,
                        "actualAmount", actualAmount == null ? 0L : actualAmount,
                        "accountReturnAmount", accountReturnAmount == null ? 0L : accountReturnAmount),
                traceId, order.getOrderNo(), refundCase.getRefundCaseNo(), now);
    }

    /** 退款 Case 终态：成功/失败/已完成/人工处理，幂等回放与外呼跳过共用。 */
    private static boolean isTerminalRefundStatus(Short status) {
        return status != null
                && (status == SUCCESS.getCode()
                || status == InstoreRefundStatus.FAILED.getCode()
                || status == InstoreRefundStatus.COMPLETED.getCode()
                || status == MANUAL.getCode());
    }

    /** 收银台/查询侧退款状态到本地枚举：PROCESSING 表示仍在处理（非终态），返回 null 由调用方保持现状。 */
    private static InstoreRefundStatus mapRefundStatus(String refundStatus) {
        if ("PROCESSING".equals(refundStatus)) {
            return null;
        }
        try {
            return InstoreRefundStatus.valueOf(refundStatus);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid refund status: " + refundStatus);
        }
    }

    private static byte[] canonicalRefundRequest(AcceptRefundCommand command) {
        String canonical = command.channelCode() + "|" + command.channelRefundNo() + "|"
                + command.tradeId() + "|" + command.refundType() + "|" + command.channelRefundAmount();
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private long countPaidFlowsByInstoreOrder(Long instoreOrderId) {
        return paymentFlowMapper.selectCount(new LambdaQueryWrapper<InstorePaymentFlowDO>()
                .eq(InstorePaymentFlowDO::getInstoreOrderId, instoreOrderId)
                .eq(InstorePaymentFlowDO::getPayStatus, InstorePaymentFlowStatus.PAID.getCode()));
    }

    /** 支付流水终态：已取消/失败/人工，禁止再确认支付或未支付取消。 */
    private static boolean isTerminalPaymentFlowStatus(short payStatus) {
        return payStatus == InstorePaymentFlowStatus.CANCELLED.getCode()
                || payStatus == InstorePaymentFlowStatus.FAILED.getCode()
                || payStatus == InstorePaymentFlowStatus.MANUAL.getCode();
    }

    /** 订单头终态（含已完成）：联调切片禁止第二笔支付确认。 */
    private static boolean isTerminalInstoreOrderStatus(short orderStatus) {
        return orderStatus == InstoreOrderStatus.COMPLETED.getCode()
                || orderStatus == InstoreOrderStatus.CANCELLED.getCode()
                || orderStatus == InstoreOrderStatus.FAILED.getCode()
                || orderStatus == InstoreOrderStatus.MANUAL.getCode();
    }

    private void updateFundReservationNo(Long flowId, String reservationNo, LocalDateTime now) {
        paymentFlowMapper.update(null, new LambdaUpdateWrapper<InstorePaymentFlowDO>()
                .set(InstorePaymentFlowDO::getFundReservationNo, reservationNo)
                .set(InstorePaymentFlowDO::getUpdatedAt, now)
                .eq(InstorePaymentFlowDO::getId, flowId));
    }

    private OrderSummaryDecision decideOrderSummary(InstoreOrderStatus instoreStatus) {
        OrderSummaryFact fact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_INSTORE,
                SupplyChainOrderTypes.DETAIL_TYPE_INSTORE_CHANNEL, instoreStatus.getCode(),
                1L, instoreStatus == InstoreOrderStatus.COMPLETED ? 1L : 0L, null);
        return orderSummaryPolicyRegistry.require(SupplyChainOrderTypes.ORDER_TYPE_INSTORE).decide(fact);
    }

    /** 按订单头组装状态策略 Fact：到店即完成语义（支付成功即已完成）。 */
    private static OrderSummaryFact buildSummaryFact(InstoreChannelOrderDO instoreOrder) {
        InstoreOrderStatus status = InstoreOrderStatus.fromCode(instoreOrder.getOrderStatus());
        return new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_INSTORE,
                SupplyChainOrderTypes.DETAIL_TYPE_INSTORE_CHANNEL, instoreOrder.getOrderStatus(),
                1L, status == InstoreOrderStatus.COMPLETED ? 1L : 0L, null);
    }

    private void advanceOrderSummary(InstoreChannelOrderDO instoreOrder, SupplyChainOrderDO order,
                                      InstoreOrderStatus next, String traceId,
                                      String sourceEventType, String reasonCode, LocalDateTime now) {
        OrderSummaryDecision summary = decideOrderSummary(next);
        long nextVersion = order.getDetailVersion() + 1;
        if (orderMapper.advanceDetailVersion(order.getId(), order.getDetailVersion(),
                nextVersion, now) != 1) {
            throw exception(ORDER_STATE_CONFLICT);
        }
        appendStatusLog(order, nextVersion, summary, sourceEventType,
                instoreOrder.getInstoreOrderNo(), reasonCode, traceId, now);
    }

    private void appendOrderSummaryEvent(SupplyChainOrderDO order, InstoreChannelOrderDO instoreOrder,
                                          String traceId, LocalDateTime now) {
        outboxRepository.appendLocal(InstoreOutboxCodes.AGGREGATE_INSTORE_ORDER,
                instoreOrder.getInstoreOrderNo(), 1,
                InstoreOutboxCodes.EVENT_INSTORE_ORDER_SUMMARY_CHANGED, InstoreOutboxCodes.TARGET_BUYER_EVENT_RPC,
                Map.of("orderNo", order.getOrderNo(), "buyerOrderNo", order.getBuyerOrderNo(),
                        "orderType", order.getOrderType(), "channelOrderId", instoreOrder.getChannelOrderId()),
                traceId, order.getOrderNo(), instoreOrder.getInstoreOrderNo(), now);
    }

    private void appendStatusLog(SupplyChainOrderDO order, long detailVersion,
                                  OrderSummaryDecision summary, String sourceEventType,
                                  String sourceEventNo, String reasonCode, String traceId,
                                  LocalDateTime now) {
        statusLogMapper.insert(OrderStatusLogDO.builder()
                .orderId(order.getId()).detailVersion(detailVersion)
                .toOrderStatus(summary.orderStatus()).toAftersaleStatus(summary.aftersaleStatus())
                .businessStage(summary.businessStage()).sourceEventType(sourceEventType)
                .sourceEventNo(sourceEventNo).reasonCode(reasonCode).traceId(traceId).createdAt(now).build());
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    static byte[] canonicalPaymentRequest(CreatePaymentOrderCommand command) {
        String canonical = command.channelCode()
                + "|" + command.projectId()
                + "|" + command.cashierProfileVersionId()
                + "|" + command.sourceSystem()
                + "|" + command.externalTenantId()
                + "|" + command.channelOrderId()
                + "|" + command.tradeId()
                + "|" + command.buyerUserId()
                + "|" + command.payAmount()
                + "|" + command.externalMerchantId();
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 到店购支付预下单命令。
     *
     * <p>projectId/buyerId/buyerUserId 必须来自可信项目认证上下文；sourceSystem/externalTenantId
     * 必须由 {@code cashierProfileVersionId} 解析，不得由业务 DTO 传入或拼接。
     * 详见 {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.2-项目集成档案}。</p>
     */
    public record CreatePaymentOrderCommand(String channelCode, String channelOrderId, String tradeId,
                                             Long buyerId, Long projectId, Long cashierProfileVersionId,
                                             String sourceSystem, String externalTenantId,
                                             String buyerUserId, String userCode, long payAmount, int orderType,
                                             long totalAmount, String companyCode, String externalMerchantId,
                                             String shopCode, String shopName, String subject, String traceId) {
    }

    /** 第一段建单结果。 */
    public record PreparedPayment(Long orderId, String orderNo, Long instoreOrderId,
                                   String instoreOrderNo, Long paymentFlowId, String paymentFlowNo,
                                   String outTradeNo, String invocationId, long operationVersion,
                                   Long projectId, Long cashierProfileVersionId,
                                   String buyerUserId, String subject, long payAmount,
                                   String reservationNo, String cashierUrl, short payStatus,
                                   boolean idempotentReplay) {
    }

    /** 第二段抢占结果。 */
    public record ClaimResult(boolean claimed, Long paymentFlowId, long operationVersion) {
    }

    /** 取消支付流水命令。 */
    public record CancelPaymentFlowCommand(Long expectedProjectId, String channelCode, String tradeId, String traceId) {
    }

    /** 取消支付流水结果：未支付返回 outTradeNo；已支付返回 outTradeNo + refundCaseNo。 */
    public record CancelPaymentFlowResult(String outTradeNo, String refundCaseNo,
                                          boolean convertedToRefund, boolean claimedForDispatch,
                                          String operationNo) {
    }

    /** 退款受理结果：claimedForDispatch=true 时调用方必须在事务提交后按原 refundCaseNo 外呼收银台。 */
    public record AcceptRefundOutcome(String refundCaseNo, String outTradeNo, boolean claimedForDispatch,
                                      String operationNo, long operationVersion, short refundStatus) {
    }

    /** 退款受理命令。 */
    public record AcceptRefundCommand(Long expectedProjectId, String channelCode, String channelRefundNo, String tradeId,
                                       String refundType, long channelRefundAmount, String traceId) {
    }

    /** 退款收敛结果：amountMismatch=true 表示已转人工未返还；nextClaimed=true 时调用方须外呼下一个 Case。 */
    public record RefundSettlementOutcome(short finalStatus, boolean amountMismatch,
                                          boolean nextClaimed, String nextClaimedRefundCaseNo,
                                          String nextClaimedOperationNo, long nextClaimedOperationVersion) {
    }

    /** 内部：抢占到的下一个排队退款 Case。 */
    private record ClaimedNext(String refundCaseNo, String operationNo, long operationVersion) {
    }

    /** 支付状态只读投影（中台内部 pay_status，非旅通 0/1/2）。 */
    public record PaymentStatusView(String paymentFlowNo, String outTradeNo, String tradeId,
                                     short payStatus, long payAmount, Long buyerPaidAmount,
                                     String cashierReference, String externalTransactionId,
                                     Long payTime) {
    }

    /** 退款状态只读投影（中台内部 status）。 */
    public record RefundStatusView(String refundCaseNo, String channelRefundNo, String refundType,
                                    short status, long channelRefundAmount, Long buyerActualRefundAmount,
                                    String outTradeNo, Long refundTime, String refuseReason) {
    }

    /** 内部组合：总订单 + 渠道订单头。 */
    private record SupplyChainOrderWithHead(SupplyChainOrderDO order, InstoreChannelOrderDO instoreOrder) {
    }
}
