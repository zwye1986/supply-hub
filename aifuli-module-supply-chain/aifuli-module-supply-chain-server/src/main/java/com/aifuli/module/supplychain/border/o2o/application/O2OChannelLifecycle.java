package com.aifuli.module.supplychain.border.o2o.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.border.o2o.dal.dataobject.O2OChannelDetailDO;
import com.aifuli.module.supplychain.border.o2o.dal.dataobject.O2OChannelUserBindingDO;
import com.aifuli.module.supplychain.border.o2o.dal.dataobject.O2OOperationDO;
import com.aifuli.module.supplychain.border.o2o.dal.dataobject.O2ORefundCaseDO;
import com.aifuli.module.supplychain.border.o2o.dal.mysql.O2OChannelDetailMapper;
import com.aifuli.module.supplychain.border.o2o.dal.mysql.O2OChannelUserBindingMapper;
import com.aifuli.module.supplychain.border.o2o.dal.mysql.O2OOperationMapper;
import com.aifuli.module.supplychain.border.o2o.dal.mysql.O2ORefundCaseMapper;
import com.aifuli.module.supplychain.border.o2o.domain.O2ODetailStatus;
import com.aifuli.module.supplychain.border.o2o.domain.O2OFundStatus;
import com.aifuli.module.supplychain.border.o2o.domain.O2OIdempotencyKeys;
import com.aifuli.module.supplychain.border.o2o.domain.O2OOperationStatus;
import com.aifuli.module.supplychain.border.o2o.domain.O2OOperationType;
import com.aifuli.module.supplychain.border.o2o.domain.O2OOutboxCodes;
import com.aifuli.module.supplychain.border.o2o.domain.O2OPaymentStatus;
import com.aifuli.module.supplychain.border.o2o.domain.O2ORefundStatus;
import com.aifuli.module.supplychain.border.o2o.domain.O2ORefundType;
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
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierChannelBindingDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierChannelBindingMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Map;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_ACCOUNT_NOT_OPENED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.O2O_CHANNEL_USER_BINDING_MISSING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.O2O_DETAIL_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.O2O_IDENTITY_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.O2O_REFUND_AMOUNT_EXCEEDED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.O2O_SUPPLIER_CHANNEL_BINDING_NOT_FOUND;

/**
 * O2O 渠道支付预下单、收银台抢占、支付确认与退款的原子编排。
 *
 * <p>对标标准履约 {@code FulfillmentAcceptanceLifecycle}：所有本地业务事务显式使用
 * {@code supplyChainTransactionManager}；HTTP/RPC/收银台/渠道网络调用一律位于数据库事务外，
 * 由 Facade 在事务外调用 Port，再回写 {@link #recordCashierOrder}。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等}；
 * 一期不因可用余额不足拒单，缺账户拒绝见
 * {@code docs/superpowers/specs/2026-08-17-supply-chain-phase1-buyer-account-overdraft.md}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class O2OChannelLifecycle {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final O2OChannelUserBindingMapper bindingMapper;
    private final O2OChannelDetailMapper detailMapper;
    private final O2OOperationMapper operationMapper;
    private final O2ORefundCaseMapper refundCaseMapper;
    private final SupplyChainOrderMapper orderMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final SupplierChannelBindingMapper supplierChannelBindingMapper;
    private final SupplierMapper supplierMapper;
    private final FundReservationService fundReservationService;
    private final OutboxEventRepository outboxRepository;
    private final BusinessNoGenerator businessNoGenerator;
    private final OrderSummaryPolicyRegistry orderSummaryPolicyRegistry;
    private final O2ORefundSequencer refundSequencer;

    // ---- 方法在后续编辑中追加 ----

    /**
     * 第一段：按 (channelCode, transactionId) 幂等建单、冻结 BuyerAccount 并创建 PREORDER Operation。
     *
     * <p>不变量：渠道用户绑定必须已启用；同一交易重复请求走幂等回放，金额不一致抛身份冲突；
     * 资金冻结失败整笔事务回滚。本方法只写本地事务，不调用收银台或渠道网络。
     * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public PreparedPayment createPaymentPreparation(PreparePaymentCommand command) {
        log.info("[O2O][lifecycle] createPaymentPreparation 入口 channelCode={}, transactionId={}, channelUserId={}, payAmount={}, merchantId={}, traceId={}",
                command.channelCode(), command.transactionId(), command.channelUserId(),
                command.payAmount(), command.externalMerchantId(), command.traceId());
        O2OChannelUserBindingDO binding = bindingMapper.selectActiveByChannelUser(
                command.channelCode(), command.channelUserId());
        if (binding == null) {
            log.warn("[O2O][lifecycle] 渠道用户绑定缺失 channelCode={}, channelUserId={}",
                    command.channelCode(), command.channelUserId());
            throw exception(O2O_CHANNEL_USER_BINDING_MISSING);
        }
        O2OChannelDetailDO existing = detailMapper.selectByChannelTransaction(
                command.channelCode(), command.transactionId());
        if (existing != null) {
            log.info("[O2O][lifecycle] 幂等回放已有交易 transactionId={}, outTradeNo={}, channelDetailNo={}",
                    command.transactionId(), existing.getOutTradeNo(), existing.getChannelDetailNo());
            return replayExistingPayment(existing, command);
        }
        SupplierChannelBindingDO channelBinding = supplierChannelBindingMapper.selectCurrentEnabled(
                command.channelCode(), command.externalMerchantId());
        if (channelBinding == null) {
            throw exception(O2O_SUPPLIER_CHANNEL_BINDING_NOT_FOUND);
        }
        SupplierDO supplier = supplierMapper.selectById(channelBinding.getSupplierId());
        if (supplier == null) {
            throw exception(O2O_SUPPLIER_CHANNEL_BINDING_NOT_FOUND);
        }
        LocalDateTime now = now();
        byte[] requestFingerprint = sha256(canonicalPaymentRequest(command));
        OrderSummaryFact summaryFact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_O2O,
                SupplyChainOrderTypes.DETAIL_TYPE_O2O_CHANNEL, O2ODetailStatus.CREATED.getCode(),
                1L, 0L, null);
        OrderSummaryDecision summary = orderSummaryPolicyRegistry
                .require(SupplyChainOrderTypes.ORDER_TYPE_O2O).decide(summaryFact);

        String orderNo = businessNoGenerator.next(BusinessNoPrefixes.SC);
        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .orderNo(orderNo).orderType(SupplyChainOrderTypes.ORDER_TYPE_O2O)
                .buyerId(binding.getBuyerId()).buyerCodeSnapshot(null).buyerNameSnapshot(null)
                .buyerUserId(binding.getBuyerUserId()).buyerOrderNo(command.transactionId())
                .displaySupplierId(supplier.getId()).displaySupplierCode(supplier.getSupplierCode())
                .displaySupplierName(supplier.getSupplierName())
                .buyerSettlementAmount(command.payAmount()).currency(Money.CURRENCY_CNY)
                .platformSkuId(null).skuCodeSnapshot(null).skuNameSnapshot(command.subject())
                .quantity(1L).buyerOrderDigest(null).orderStatus(summary.orderStatus())
                .aftersaleStatus(summary.aftersaleStatus()).businessStage(summary.businessStage())
                .detailType(SupplyChainOrderTypes.DETAIL_TYPE_O2O_CHANNEL).detailId(null)
                .detailVersion(0L).requestFingerprint(requestFingerprint).version(0L)
                .createdAt(now).updatedAt(now).build();
        orderMapper.insert(order);

        String detailNo = businessNoGenerator.next(BusinessNoPrefixes.O2D);
        String outTradeNo = businessNoGenerator.next(BusinessNoPrefixes.O2D);
        O2OChannelDetailDO detail = O2OChannelDetailDO.builder()
                .channelDetailNo(detailNo).orderId(order.getId()).buyerId(binding.getBuyerId())
                .buyerUserId(binding.getBuyerUserId()).channelCode(command.channelCode())
                .channelUserBindingId(binding.getId()).channelUserId(binding.getChannelUserId())
                .transactionId(command.transactionId()).supplierChannelBindingId(channelBinding.getId())
                .supplierId(supplier.getId()).supplierCodeSnapshot(supplier.getSupplierCode())
                .supplierNameSnapshot(supplier.getSupplierName())
                .externalStoreId(command.externalStoreId()).externalStoreName(command.externalStoreName())
                .storeSnapshotJson(null).outTradeNo(outTradeNo).channelPayAmount(command.payAmount())
                .buyerPaidAmount(null).buyerSettlementAmount(command.payAmount()).currency(Money.CURRENCY_CNY)
                .cashierReference(null).paymentStatus(O2OPaymentStatus.PENDING.getCode())
                .fundStatus(O2OFundStatus.RESERVED.getCode()).channelFulfillmentStatus((short) 0)
                .totalChannelRefundAmount(0L).totalBuyerRefundAmount(0L).totalAccountReturnAmount(0L)
                .activeRefundCaseId(null).status(O2ODetailStatus.CREATED.getCode()).version(0L)
                .createdAt(now).updatedAt(now).build();
        detailMapper.insert(detail);
        if (orderMapper.attachDetail(order.getId(), detail.getId(), 0L, now) != 1) {
            throw new IllegalStateException("O2O order detail attachment lost invariant");
        }

        FundReservationService.ReservationSnapshot reservation;
        try {
            reservation = fundReservationService.freeze(new FundReservationService.FreezeCommand(
                    binding.getBuyerId(), FundReferenceType.O2O_ORDER.code(), detailNo,
                    Money.cny(command.payAmount()), orderNo, command.traceId(), detailNo));
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("account does not exist")) {
                log.warn("[O2O][lifecycle] 资金账户未开户 buyerId={}, payAmount={}",
                        binding.getBuyerId(), command.payAmount());
                throw exception(BUYER_ACCOUNT_NOT_OPENED);
            }
            throw ex;
        }

        String operationNo = businessNoGenerator.next(BusinessNoPrefixes.O2OP);
        O2OOperationDO operation = O2OOperationDO.builder()
                .operationNo(operationNo).o2oDetailId(detail.getId()).refundCaseId(null)
                .operationType(O2OOperationType.PREORDER.name())
                .idempotencyKey(O2OIdempotencyKeys.preorder(command.channelCode(), command.transactionId()))
                .requestFingerprint(requestFingerprint).externalOperationNo(null)
                .status(O2OOperationStatus.PENDING.getCode()).resultClass(null).attemptCount(0)
                .nextRecoverAt(null).responseDigest(null).version(0L).createdAt(now).updatedAt(now)
                .completedAt(null).build();
        operationMapper.insert(operation);

        appendOrderSummaryEvent(order, detail, command.traceId(), now);
        appendStatusLog(order, 1L, summary, OrderStatusAuditCodes.SOURCE_O2O_ACCEPTED,
                detailNo, OrderStatusAuditCodes.REASON_ACCEPTED, command.traceId(), now);
        log.info("[O2O][lifecycle] createPaymentPreparation 完成 orderNo={}, channelDetailNo={}, outTradeNo={}, invocationId={}, reservationNo={}",
                orderNo, detailNo, outTradeNo, operationNo, reservation.reservationNo());
        return new PreparedPayment(order.getId(), order.getOrderNo(), detail.getId(),
                detail.getChannelDetailNo(), detail.getOutTradeNo(), operationNo,
                operation.getVersion(), command.payAmount(), reservation.reservationNo(), false);
    }

    private PreparedPayment replayExistingPayment(O2OChannelDetailDO existing, PreparePaymentCommand command) {
        if (existing.getChannelPayAmount() != command.payAmount()) {
            throw exception(O2O_IDENTITY_CONFLICT);
        }
        O2OOperationDO operation = operationMapper.selectByIdempotencyKey(
                O2OIdempotencyKeys.preorder(command.channelCode(), command.transactionId()));
        return new PreparedPayment(existing.getOrderId(), null, existing.getId(),
                existing.getChannelDetailNo(), existing.getOutTradeNo(),
                operation == null ? null : operation.getOperationNo(),
                operation == null ? 0L : operation.getVersion(),
                existing.getChannelPayAmount(), null, true);
    }

    /**
     * 第二段：CAS 抢占 PREORDER（待执行→执行中），详情推进为收银台创建中。
     *
     * <p>抢占失败只能查单，禁止再次调用收银台。详见
     * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ClaimResult claimPaymentPreparation(String invocationId, long expectedVersion) {
        log.info("[O2O][lifecycle] claimPaymentPreparation 入口 invocationId={}, expectedVersion={}",
                invocationId, expectedVersion);
        O2OOperationDO operation = operationMapper.selectByOperationNo(invocationId);
        if (operation == null) {
            throw new IllegalStateException("PREORDER operation not found: " + invocationId);
        }
        LocalDateTime now = now();
        int claimed = operationMapper.claimPreorder(operation.getId(), expectedVersion, now);
        if (claimed != 1) {
            log.info("[O2O][lifecycle] 抢占失败，禁止调收银台 invocationId={}, currentVersion={}",
                    invocationId, operation.getVersion());
            return new ClaimResult(false, operation.getO2oDetailId(), operation.getVersion());
        }
        O2OChannelDetailDO detail = detailMapper.selectById(operation.getO2oDetailId());
        if (detail == null) {
            throw new IllegalStateException("O2O detail disappeared during claim");
        }
        detailMapper.advanceToCashierCreating(detail.getId(), detail.getVersion(),
                O2OPaymentStatus.PROCESSING.getCode(), O2OFundStatus.RESERVED.getCode(), now);
        log.info("[O2O][lifecycle] 抢占成功 invocationId={}, detailId={}", invocationId, detail.getId());
        return new ClaimResult(true, detail.getId(), operation.getVersion());
    }

    /**
     * 第四段：按 invocationId + expectedVersion 记录收银台建单结果。
     *
     * <p>CREATED 回写 cashierReference；REJECTED/UNKNOWN 只推进 Operation 状态，保持原 outTradeNo。
     * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void recordCashierOrder(String invocationId, long expectedVersion,
                                    BuyerCashierPort.CreateCashierOrderResult result) {
        log.info("[O2O][lifecycle] recordCashierOrder 入口 invocationId={}, expectedVersion={}, cashierStatus={}, cashierOrderNo={}",
                invocationId, expectedVersion, result.status(), result.cashierOrderNo());
        O2OOperationDO operation = operationMapper.selectByOperationNo(invocationId);
        if (operation == null) {
            throw new IllegalStateException("PREORDER operation not found: " + invocationId);
        }
        LocalDateTime now = now();
        short opStatus = switch (result.status()) {
            case CREATED -> O2OOperationStatus.RUNNING.getCode();
            case REJECTED -> O2OOperationStatus.FAILED.getCode();
            case UNKNOWN -> O2OOperationStatus.UNKNOWN.getCode();
        };
        operationMapper.recordResult(operation.getId(), expectedVersion, opStatus,
                result.cashierOrderNo(), result.status().name(), now);
        if (result.status() == BuyerCashierPort.CashierOrderStatus.CREATED) {
            O2OChannelDetailDO detail = detailMapper.selectById(operation.getO2oDetailId());
            if (detail != null) {
                detailMapper.recordCashierReference(detail.getId(), detail.getVersion(),
                        result.cashierOrderNo(), now);
            }
        }
        log.info("[O2O][lifecycle] recordCashierOrder 完成 invocationId={}, opStatus={}",
                invocationId, opStatus);
    }

    /**
     * 支付回调确认：确认 FundReservation、更新详情支付/资金状态，并写渠道支付通知 Outbox。
     *
     * <p>按 outTradeNo 加载已固化归属；实付金额必须等于渠道应付金额，否则身份冲突。
     * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void confirmBuyerPayment(String outTradeNo, long buyerPaidAmount,
                                     String cashierReference, String traceId) {
        log.info("[O2O][lifecycle] confirmBuyerPayment 入口 outTradeNo={}, buyerPaidAmount={}, cashierReference={}, traceId={}",
                outTradeNo, buyerPaidAmount, cashierReference, traceId);
        O2OChannelDetailDO detail = detailMapper.selectByOutTradeNo(outTradeNo);
        if (detail == null) {
            log.warn("[O2O][lifecycle] 支付确认找不到详情 outTradeNo={}", outTradeNo);
            throw exception(O2O_DETAIL_NOT_FOUND);
        }
        if (buyerPaidAmount != detail.getChannelPayAmount()) {
            throw exception(O2O_IDENTITY_CONFLICT);
        }
        SupplyChainOrderDO order = orderMapper.selectById(detail.getOrderId());
        if (order == null) {
            throw exception(O2O_DETAIL_NOT_FOUND);
        }
        fundReservationService.commit(detail.getChannelDetailNo(), order.getOrderNo(),
                traceId, detail.getChannelDetailNo());
        LocalDateTime now = now();
        detailMapper.confirmPayment(detail.getId(), detail.getVersion(),
                O2OPaymentStatus.PAID.getCode(), O2OFundStatus.COMMITTED.getCode(),
                buyerPaidAmount, cashierReference, now);
        advanceDetailStatus(detail, order, O2ODetailStatus.COMPLETED, traceId,
                OrderStatusAuditCodes.SOURCE_O2O_PAID, OrderStatusAuditCodes.REASON_PAID, now);
        outboxRepository.appendLocal(O2OOutboxCodes.AGGREGATE_O2O_DETAIL, detail.getChannelDetailNo(), 2,
                O2OOutboxCodes.EVENT_O2O_PAYMENT_NOTIFY, O2OOutboxCodes.TARGET_TAOGAO_FLASH_CHANNEL,
                Map.of("channelCode", detail.getChannelCode(), "transactionId", detail.getTransactionId(),
                        "outTradeNo", detail.getOutTradeNo(), "payAmount", detail.getChannelPayAmount(),
                        "paymentStatus", "PAID"),
                traceId, order.getOrderNo(), detail.getChannelDetailNo(), now);
        log.info("[O2O][lifecycle] confirmBuyerPayment 完成 outTradeNo={}, transactionId={}, orderNo={}",
                outTradeNo, detail.getTransactionId(), order.getOrderNo());
    }

    /**
     * 退款受理：按 (channelCode,refundNo) 全局幂等创建退款 Case、推进售后事实并写采购商退款 Outbox。
     *
     * <p>TODO 退款资金预留与账户返还的账本口径待与第 13 章对账设计对齐后细化；
     * 本期联调骨架仅创建退款 Case 与通知 Outbox，资金返还由 reportRefundResult 回调驱动。
     * 详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#13。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String acceptRefund(AcceptRefundCommand command) {
        log.info("[O2O][lifecycle] acceptRefund 入口 channelCode={}, channelRefundNo={}, transactionId={}, refundType={}, refundAmount={}, traceId={}",
                command.channelCode(), command.channelRefundNo(), command.transactionId(),
                command.refundType(), command.channelRefundAmount(), command.traceId());
        O2ORefundCaseDO existingCase = refundCaseMapper.selectByChannelRefundNo(
                command.channelCode(), command.channelRefundNo());
        if (existingCase != null) {
            log.info("[O2O][lifecycle] 退款幂等回放 channelRefundNo={}, refundCaseNo={}",
                    command.channelRefundNo(), existingCase.getRefundCaseNo());
            return existingCase.getRefundCaseNo();
        }
        O2OChannelDetailDO detail = detailMapper.selectByChannelTransaction(
                command.channelCode(), command.transactionId());
        if (detail == null) {
            throw exception(O2O_DETAIL_NOT_FOUND);
        }
        long refundable = detail.getChannelPayAmount() - detail.getTotalChannelRefundAmount();
        if (command.channelRefundAmount() > refundable) {
            throw exception(O2O_REFUND_AMOUNT_EXCEEDED);
        }
        O2ORefundType refundType;
        try {
            refundType = O2ORefundType.valueOf(command.refundType());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid O2O refund type: " + command.refundType());
        }
        LocalDateTime now = now();
        int sequence = refundSequencer.nextSequence(detail.getId());
        String refundCaseNo = businessNoGenerator.next(BusinessNoPrefixes.ORC);
        O2ORefundCaseDO refundCase = O2ORefundCaseDO.builder()
                .refundCaseNo(refundCaseNo).o2oDetailId(detail.getId()).orderId(detail.getOrderId())
                .buyerId(detail.getBuyerId()).channelCode(command.channelCode())
                .channelRefundNo(command.channelRefundNo()).refundSequence(sequence)
                .refundType(refundType.name()).channelRefundAmount(command.channelRefundAmount())
                .buyerActualRefundAmount(null).accountReturnAmount(null).currency(Money.CURRENCY_CNY)
                .cashierRefundReference(null)
                .requestFingerprint(sha256(canonicalRefundRequest(command)))
                .status(O2ORefundStatus.QUEUED.getCode()).buyerRefundingFlag(null)
                .resultReasonCode(null).version(0L).createdAt(now).updatedAt(now).completedAt(null)
                .build();
        refundCaseMapper.insert(refundCase);
        log.info("[O2O][lifecycle] acceptRefund 完成 refundCaseNo={}, sequence={}", refundCaseNo, sequence);
        return refundCaseNo;
    }

    /**
     * 退款结果回调：确认或释放退款预留、返还 BuyerAccount、写反向流水并写通知 Outbox。
     *
     * <p>TODO 账户返还账本写入待与 AccountLedgerWriter 对齐后细化；本期联调骨架推进退款 Case 状态。
     * 详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void applyBuyerRefundResult(String middleRefundNo, String refundStatus,
                                        Long buyerActualRefundAmount, String cashierRefundReference,
                                        String traceId) {
        log.info("[O2O][lifecycle] applyBuyerRefundResult 入口 middleRefundNo={}, refundStatus={}, actualAmount={}, cashierRefundReference={}, traceId={}",
                middleRefundNo, refundStatus, buyerActualRefundAmount, cashierRefundReference, traceId);
        O2ORefundCaseDO refundCase = refundCaseMapper.selectByRefundCaseNo(middleRefundNo);
        if (refundCase == null) {
            log.warn("[O2O][lifecycle] 退款结果找不到 Case middleRefundNo={}", middleRefundNo);
            throw exception(O2O_DETAIL_NOT_FOUND);
        }
        O2OChannelDetailDO detail = detailMapper.selectById(refundCase.getO2oDetailId());
        if (detail == null) {
            throw exception(O2O_DETAIL_NOT_FOUND);
        }
        SupplyChainOrderDO order = orderMapper.selectById(detail.getOrderId());
        LocalDateTime now = now();
        O2ORefundStatus next = mapRefundStatus(refundStatus);
        // TODO: 按 next 状态推进资金返还与累计退款金额，需与 AccountLedgerWriter/对账口径对齐。
        outboxRepository.appendLocal(O2OOutboxCodes.AGGREGATE_O2O_REFUND_CASE, middleRefundNo, 1,
                O2OOutboxCodes.EVENT_O2O_REFUND_NOTIFY, O2OOutboxCodes.TARGET_TAOGAO_FLASH_CHANNEL,
                Map.of("channelCode", refundCase.getChannelCode(), "channelRefundNo", refundCase.getChannelRefundNo(),
                        "transactionId", detail.getTransactionId(), "refundAmount", refundCase.getChannelRefundAmount(),
                        "refundStatus", refundStatus),
                traceId, order == null ? null : order.getOrderNo(), middleRefundNo, now);
        log.info("[O2O][lifecycle] applyBuyerRefundResult 完成 middleRefundNo={}, channelRefundNo={}, mappedStatus={}",
                middleRefundNo, refundCase.getChannelRefundNo(), next.name());
    }

    private static O2ORefundStatus mapRefundStatus(String refundStatus) {
        try {
            return O2ORefundStatus.valueOf(refundStatus);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid refund status: " + refundStatus);
        }
    }

    private static byte[] canonicalRefundRequest(AcceptRefundCommand command) {
        String canonical = command.channelCode() + "|" + command.channelRefundNo() + "|"
                + command.transactionId() + "|" + command.refundType() + "|" + command.channelRefundAmount();
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    /** O2O 退款受理命令。 */
    public record AcceptRefundCommand(String channelCode, String channelRefundNo, String transactionId,
                                       String refundType, long channelRefundAmount, String traceId) {
    }

    private void advanceDetailStatus(O2OChannelDetailDO detail, SupplyChainOrderDO order,
                                      O2ODetailStatus next, String traceId,
                                      String sourceEventType, String reasonCode, LocalDateTime now) {
        OrderSummaryFact fact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_O2O,
                SupplyChainOrderTypes.DETAIL_TYPE_O2O_CHANNEL, next.getCode(), 1L, 1L, null);
        OrderSummaryDecision summary = orderSummaryPolicyRegistry
                .require(SupplyChainOrderTypes.ORDER_TYPE_O2O).decide(fact);
        orderMapper.advanceDetailVersion(order.getId(), order.getDetailVersion(),
                order.getDetailVersion() + 1, now);
        appendStatusLog(order, order.getDetailVersion() + 1, summary, sourceEventType,
                detail.getChannelDetailNo(), reasonCode, traceId, now);
    }

    private void appendOrderSummaryEvent(SupplyChainOrderDO order, O2OChannelDetailDO detail,
                                          String traceId, LocalDateTime now) {
        outboxRepository.appendLocal(O2OOutboxCodes.AGGREGATE_O2O_DETAIL, detail.getChannelDetailNo(), 1,
                O2OOutboxCodes.EVENT_O2O_ORDER_SUMMARY_CHANGED, O2OOutboxCodes.TARGET_BUYER_EVENT_RPC,
                Map.of("orderNo", order.getOrderNo(), "buyerOrderNo", order.getBuyerOrderNo(),
                        "orderType", order.getOrderType(), "outTradeNo", detail.getOutTradeNo()),
                traceId, order.getOrderNo(), detail.getChannelDetailNo(), now);
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

    static byte[] canonicalPaymentRequest(PreparePaymentCommand command) {
        String canonical = command.channelCode() + "|" + command.transactionId() + "|"
                + command.channelUserId() + "|" + command.payAmount() + "|" + command.externalMerchantId();
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    /** O2O 支付预下单命令。 */
    public record PreparePaymentCommand(String channelCode, String transactionId, String channelUserId,
                                         long payAmount, String externalMerchantId, String externalStoreId,
                                         String externalStoreName, String subject, String traceId) {
    }

    /** 第一段建单结果。 */
    public record PreparedPayment(Long orderId, String orderNo, Long detailId, String channelDetailNo,
                                    String outTradeNo, String invocationId, long operationVersion,
                                    long payAmount, String reservationNo, boolean idempotentReplay) {
    }

    /** 第二段抢占结果。 */
    public record ClaimResult(boolean claimed, Long detailId, long operationVersion) {
    }
}
