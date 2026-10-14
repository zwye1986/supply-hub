package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 华昇生产履约出站执行器。
 *
 * <p>职责：
 * 实现了 {@link OperationExecutorPort}，专门负责执行 {@link HuashengOrderV1Metadata#TYPE} 适配器的出站调用。
 * 将出站凭证 {@link OperationTicket} 转化为华昇授权上下文并委派给 {@link HuashengCommunicationFacade}。
 * 产出中间领域事实 {@link HuashengStepFact} 挂载在 {@link OperationOutcome} 上返回。
 * 绝不向外暴露内部 carrier、Throwable 或资金动作；通用中间结果统一为 ACCEPTED，
 * 各种通信与业务错误统一为 RESULT_UNKNOWN，绝不冒充 REJECTED 或直接成功。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#31-边界与责任矩阵}。</p>
 */
@Slf4j
public class HuashengOperationExecutor implements OperationExecutorPort {

    private final HuashengCommunicationFacade facade;
    private final HuashengCallContextFactory contextFactory;
    private final HuashengType201AssetCodec assetCodec = new HuashengType201AssetCodec();

    public HuashengOperationExecutor(HuashengCommunicationFacade facade) {
        this(facade, new HuashengCallContextFactory());
    }

    public HuashengOperationExecutor(HuashengCommunicationFacade facade, HuashengCallContextFactory contextFactory) {
        this.facade = Objects.requireNonNull(facade, "facade cannot be null");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory cannot be null");
    }

    @Override
    public boolean supports(String adapterType) {
        return HuashengOrderV1Metadata.TYPE.equals(adapterType);
    }

    @Override
    public OperationOutcome execute(OperationTicket ticket) {
        Objects.requireNonNull(ticket, "ticket cannot be null");
        if (!supports(ticket.adapterType())) {
            throw new IllegalArgumentException("Unsupported adapterType: " + ticket.adapterType());
        }
        if (ticket.adapterStepCode() == null) {
            throw new IllegalArgumentException("ticket.adapterStepCode cannot be null");
        }

        HuashengAuthorizedCallContext callContext;
        try {
            callContext = contextFactory.createContext(ticket);
        } catch (IllegalArgumentException e) {
            log.warn("[华昇执行器][上下文构建拒绝] operationNo={}, msg={}", ticket.operationNo(), e.getMessage());
            throw e;
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(10, ticket.timeoutSeconds()));
        HuashengCallBudget budget = new HuashengCallBudget(2, deadlineNanos);

        HuashengExpectedOrder expectedOrder = new HuashengExpectedOrder(
                ticket.billno(),
                Optional.ofNullable(ticket.externalOrderNo()),
                (int) ticket.quantity()
        );

        return switch (ticket.adapterStepCode()) {
            case PLACE_ORDER -> executePlace(ticket, callContext, expectedOrder, budget);
            case CONFIRM_ORDER -> executeConfirm(ticket, callContext, expectedOrder, budget);
            case QUERY_ORDER -> executeQuery(ticket, callContext, expectedOrder, budget);
            case TAKE_CARD -> executeTake(ticket, callContext, expectedOrder, budget);
            default -> throw new IllegalArgumentException("Unsupported Huasheng step: " + ticket.adapterStepCode());
        };
    }

    private OperationOutcome executePlace(OperationTicket ticket, HuashengAuthorizedCallContext ctx,
                                          HuashengExpectedOrder expected, HuashengCallBudget budget) {
        long couponId;
        try {
            couponId = Long.parseLong(ticket.externalSkuCode());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid couponId in externalSkuCode: " + ticket.externalSkuCode());
        }

        int expireTime = HuashengOrderV1Metadata.DEFAULT_EXPIRE_SECONDS;
        String expireStr = ticket.extraParameters().get("expireTime");
        if (expireStr != null) {
            try {
                expireTime = Integer.parseInt(expireStr);
            } catch (Exception ignored) {}
        }

        String outMobile = ticket.extraParameters().get("outMobile");
        if (outMobile == null) {
            outMobile = ticket.rechargeAccount();
        }

        HuashengPlaceRequest request = new HuashengPlaceRequest(
                ticket.billno(),
                couponId,
                (int) ticket.quantity(),
                expireTime,
                ticket.callbackUrl(),
                outMobile
        );

        try {
            HuashengCallResult<HuashengOrderResponse> result = facade.place(ctx, request, expected, budget);
            return handleOrderResult(HuashengStepFact.Endpoint.PLACE, ticket, result);
        } catch (Exception e) {
            log.warn("[华昇执行器][PLACE异常] operationNo={}, error={}", ticket.operationNo(), e.getMessage());
            return buildUnknownOutcome(HuashengStepFact.Endpoint.PLACE, ticket, null, e.getMessage(), List.of(),
                    e instanceof HuashengProtocolException pe ? pe : null);
        }
    }

    private OperationOutcome executeConfirm(OperationTicket ticket, HuashengAuthorizedCallContext ctx,
                                            HuashengExpectedOrder expected, HuashengCallBudget budget) {
        String orderSn = ticket.externalOrderNo();
        if (orderSn == null || orderSn.isBlank()) {
            throw new IllegalArgumentException("externalOrderNo (orderSn) is required for CONFIRM_ORDER");
        }
        HuashengConfirmRequest request = new HuashengConfirmRequest(orderSn);

        try {
            HuashengCallResult<HuashengOrderResponse> result = facade.confirm(ctx, request, expected, budget);
            return handleOrderResult(HuashengStepFact.Endpoint.CONFIRM, ticket, result);
        } catch (Exception e) {
            log.warn("[华昇执行器][CONFIRM异常] operationNo={}, error={}", ticket.operationNo(), e.getMessage());
            return buildUnknownOutcome(HuashengStepFact.Endpoint.CONFIRM, ticket, null, e.getMessage(), List.of(),
                    e instanceof HuashengProtocolException pe ? pe : null);
        }
    }

    private OperationOutcome executeQuery(OperationTicket ticket, HuashengAuthorizedCallContext ctx,
                                          HuashengExpectedOrder expected, HuashengCallBudget budget) {
        HuashengQueryRequest request;
        if (ticket.externalOrderNo() != null && !ticket.externalOrderNo().isBlank()) {
            request = HuashengQueryRequest.byOrderSn(ticket.externalOrderNo());
        } else {
            request = HuashengQueryRequest.byOutOrderSn(ticket.billno());
        }

        try {
            HuashengCallResult<HuashengOrderResponse> result = facade.query(ctx, request, expected, budget);
            return handleOrderResult(HuashengStepFact.Endpoint.QUERY, ticket, result);
        } catch (Exception e) {
            log.warn("[华昇执行器][QUERY异常] operationNo={}, error={}", ticket.operationNo(), e.getMessage());
            return buildUnknownOutcome(HuashengStepFact.Endpoint.QUERY, ticket, null, e.getMessage(), List.of(),
                    e instanceof HuashengProtocolException pe ? pe : null);
        }
    }

    private OperationOutcome executeTake(OperationTicket ticket, HuashengAuthorizedCallContext ctx,
                                         HuashengExpectedOrder expected, HuashengCallBudget budget) {
        String orderSn = ticket.externalOrderNo();
        if (orderSn == null || orderSn.isBlank()) {
            throw new IllegalArgumentException("externalOrderNo (orderSn) is required for TAKE_CARD");
        }
        HuashengTakeRequest request = new HuashengTakeRequest(orderSn);

        try {
            HuashengCallResult<HuashengTakeResponse> result = facade.take(ctx, request, expected, budget);
            return handleTakeResult(ticket, result);
        } catch (Exception e) {
            log.warn("[华昇执行器][TAKE异常] operationNo={}, error={}", ticket.operationNo(), e.getMessage());
            return buildUnknownOutcome(HuashengStepFact.Endpoint.TAKE, ticket, null, e.getMessage(), List.of(),
                    e instanceof HuashengProtocolException pe ? pe : null);
        }
    }

    private OperationOutcome handleOrderResult(
            HuashengStepFact.Endpoint endpoint,
            OperationTicket ticket,
            HuashengCallResult<HuashengOrderResponse> result
    ) {
        List<HuashengStepFact.AttemptObservation> attempts = toAttemptObservations(result.attempts());
        CallDigestFact digest = buildCallDigest(ticket, result.attempts());

        if (result instanceof HuashengCallResult.Parsed<HuashengOrderResponse> parsed) {
            HuashengOrderResponse res = parsed.data();
            String rawPrice = res.totalPrice() != null ? res.totalPrice().rawYuan() : null;
            Long centsPrice = res.totalPrice() != null ? res.totalPrice().minorUnits() : null;

            HuashengStepFact.Status status = res.status() != null ? switch (res.status()) {
                case WAIT -> HuashengStepFact.Status.WAIT;
                case SUCCESS -> HuashengStepFact.Status.SUCCESS;
                case FAILED -> HuashengStepFact.Status.FAILED;
                case CANCEL -> HuashengStepFact.Status.CANCEL;
            } : HuashengStepFact.Status.UNKNOWN;

            HuashengStepFact.RefundStatus refundStatus = res.refundStatus().map(r -> switch (r) {
                case NONE -> HuashengStepFact.RefundStatus.NONE;
                case WAIT -> HuashengStepFact.RefundStatus.WAIT;
                case SUCCESS -> HuashengStepFact.RefundStatus.SUCCESS;
            }).orElse(HuashengStepFact.RefundStatus.ABSENT);

            HuashengStepFact.IsTake isTake = res.isTake().map(t -> switch (t) {
                case N -> HuashengStepFact.IsTake.N;
                case Y -> HuashengStepFact.IsTake.Y;
            }).orElse(HuashengStepFact.IsTake.ABSENT);

            HuashengStepFact stepFact = new HuashengStepFact(
                    endpoint,
                    HuashengStepFact.StepResult.ACCEPTED,
                    res.outOrderSn() != null ? res.outOrderSn() : ticket.billno(),
                    res.orderSn(),
                    res.num(),
                    rawPrice,
                    centsPrice,
                    status,
                    refundStatus,
                    isTake,
                    null,
                    attempts
            );

            return OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.ACCEPTED)
                    .externalOrderNo(res.orderSn())
                    .reportedSettlementAmount(centsPrice)
                    .protocolFact(stepFact)
                    .callDigest(digest)
                    .build();
        }

        return mapFailureResult(endpoint, ticket, result, attempts, digest);
    }

    private OperationOutcome handleTakeResult(
            OperationTicket ticket,
            HuashengCallResult<HuashengTakeResponse> result
    ) {
        List<HuashengStepFact.AttemptObservation> attempts = toAttemptObservations(result.attempts());
        CallDigestFact digest = buildCallDigest(ticket, result.attempts());

        if (result instanceof HuashengCallResult.Parsed<HuashengTakeResponse> parsed) {
            HuashengTakeResponse res = parsed.data();
            List<HuashengProtocolCard> cards = res.list();
            if (cards != null && cards.size() == 1 && (cards.get(0).type() == HuashengCardType.T201
                    || cards.get(0).type() == HuashengCardType.T202)) {
                try {
                    OperationAssetFact assetFact = assetCodec.toFact(cards.get(0));
                    HuashengStepFact stepFact = new HuashengStepFact(
                            HuashengStepFact.Endpoint.TAKE,
                            HuashengStepFact.StepResult.ACCEPTED,
                            ticket.billno(),
                            res.orderSn(),
                            res.num(),
                            null, // TAKE 端点严禁自生金额
                            null,
                            HuashengStepFact.Status.SUCCESS,
                            HuashengStepFact.RefundStatus.ABSENT,
                            HuashengStepFact.IsTake.Y,
                            assetFact.protectedPayload(),
                            attempts
                    );

                    return OperationOutcome.builder()
                            .outcomeClass(OperationOutcomeClass.ACCEPTED)
                            .externalOrderNo(res.orderSn())
                            .reportedSettlementAmount(null) // 严格不补金额
                            .assets(List.of(assetFact))
                            .protocolFact(stepFact)
                            .callDigest(digest)
                            .build();
                } catch (Exception e) {
                    log.warn("[华昇执行器][TAKE卡密转资产事实失败] orderSn={}, error={}", res.orderSn(), e.getMessage());
                }
            }

            // 非201／202、多卡或资产转换失败，转RESULT_UNKNOWN；生产闭环设计 §7.3。
            HuashengStepFact stepFact = new HuashengStepFact(
                    HuashengStepFact.Endpoint.TAKE,
                    HuashengStepFact.StepResult.RESULT_UNKNOWN,
                    ticket.billno(),
                    res.orderSn(),
                    res.num(),
                    null,
                    null,
                    HuashengStepFact.Status.UNKNOWN,
                    HuashengStepFact.RefundStatus.ABSENT,
                    HuashengStepFact.IsTake.ABSENT,
                    null,
                    attempts
            );

            return OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.RESULT_UNKNOWN)
                    .externalOrderNo(res.orderSn())
                    .externalErrorMessage("TAKE响应未提供唯一有效201／202卡片")
                    .protocolFact(stepFact)
                    .callDigest(digest)
                    .build();
        }

        return mapFailureResult(HuashengStepFact.Endpoint.TAKE, ticket, result, attempts, digest);
    }

    private OperationOutcome mapFailureResult(
            HuashengStepFact.Endpoint endpoint,
            OperationTicket ticket,
            HuashengCallResult<?> result,
            List<HuashengStepFact.AttemptObservation> attempts,
            CallDigestFact digest
    ) {
        String errorCode = null;
        String errorMessage = null;
        Integer rawErrorCode = null;
        HuashengSecurityReason securityReason = null;
        String supplierTraceId = null;

        if (result instanceof HuashengCallResult.BusinessError<?> be) {
            rawErrorCode = be.error().rawCode();
            errorCode = String.valueOf(be.error().rawCode());
            errorMessage = be.error().knownErrorKind().name();
            if (be.error().controlledSecurityReason().isPresent()) {
                securityReason = be.error().controlledSecurityReason().get();
            }
            if (be.error().supplierTraceId().isPresent()) {
                supplierTraceId = be.error().supplierTraceId().get();
            }
        } else if (result instanceof HuashengCallResult.HttpFailure<?> hf) {
            errorCode = "HTTP_" + hf.diagnostics().reasonCode().name();
        } else if (result instanceof HuashengCallResult.TransportFailure<?> tf) {
            errorCode = "TRANSPORT_" + tf.diagnostics().reasonCode().name();
        } else if (result instanceof HuashengCallResult.InvalidResponse<?> ir) {
            errorCode = "INVALID_RESPONSE";
        } else if (result instanceof HuashengCallResult.LocalFailure<?> lf) {
            errorCode = "LOCAL_FAILURE";
        }

        HuashengStepFact stepFact = new HuashengStepFact(
                endpoint,
                HuashengStepFact.StepResult.RESULT_UNKNOWN,
                ticket.billno(),
                ticket.externalOrderNo(),
                (int) ticket.quantity(),
                null,
                null,
                HuashengStepFact.Status.UNKNOWN,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null,
                attempts,
                rawErrorCode,
                securityReason,
                supplierTraceId
        );

        return OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.RESULT_UNKNOWN)
                .externalOrderNo(ticket.externalOrderNo())
                .externalErrorCode(errorCode)
                .externalErrorMessage(errorMessage)
                .protocolFact(stepFact)
                .callDigest(digest)
                .build();
    }

    private OperationOutcome buildUnknownOutcome(
            HuashengStepFact.Endpoint endpoint,
            OperationTicket ticket,
            String errorCode,
            String errorMessage,
            List<HuashengStepFact.AttemptObservation> attempts
    ) {
        return buildUnknownOutcome(endpoint, ticket, errorCode, errorMessage, attempts, null);
    }

    /**
     * 异常路径投影：HuashengProtocolException 的诊断证据（含 TOKEN 端点业务拒绝码）不再丢失，
     * 转为受控错误事实供错误分类决策使用；无证据时保持无码未知，关闭无副作用快捷路径。
     * 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#41-决策输入与所有权。
     */
    private OperationOutcome buildUnknownOutcome(
            HuashengStepFact.Endpoint endpoint,
            OperationTicket ticket,
            String errorCode,
            String errorMessage,
            List<HuashengStepFact.AttemptObservation> attempts,
            HuashengProtocolException protocolException
    ) {
        Integer rawErrorCode = null;
        List<HuashengStepFact.AttemptObservation> mergedAttempts = attempts;
        if (protocolException != null && protocolException.diagnostics().evidence().isPresent()) {
            var evidence = protocolException.diagnostics().evidence().get();
            if (evidence.rawBusinessCode().isPresent()) {
                rawErrorCode = evidence.rawBusinessCode().getAsInt();
            }
            if (!evidence.wasBusinessCallStarted()) {
                mergedAttempts = new java.util.ArrayList<>(attempts);
                mergedAttempts.add(new HuashengStepFact.AttemptObservation(
                        evidence.endpoint() != null ? evidence.endpoint().name() : null,
                        evidence.httpStatus().isPresent() ? evidence.httpStatus().getAsInt() : null,
                        evidence.rawBusinessCode().isPresent() ? evidence.rawBusinessCode().getAsInt() : null,
                        evidence.durationMs(),
                        evidence.requestBytes(),
                        evidence.responseBytes(),
                        evidence.physicalSendCount(),
                        evidence.wasBusinessCallStarted()
                ));
            }
        }
        HuashengStepFact stepFact = new HuashengStepFact(
                endpoint,
                HuashengStepFact.StepResult.RESULT_UNKNOWN,
                ticket.billno(),
                ticket.externalOrderNo(),
                (int) ticket.quantity(),
                null,
                null,
                HuashengStepFact.Status.UNKNOWN,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null,
                mergedAttempts,
                rawErrorCode,
                null,
                null
        );

        return OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.RESULT_UNKNOWN)
                .externalOrderNo(ticket.externalOrderNo())
                .externalErrorCode(errorCode != null ? errorCode : (rawErrorCode != null ? String.valueOf(rawErrorCode) : null))
                .externalErrorMessage(errorMessage)
                .protocolFact(stepFact)
                .build();
    }

    private List<HuashengStepFact.AttemptObservation> toAttemptObservations(List<HuashengExchangeEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) return List.of();
        return evidences.stream()
                .map(e -> new HuashengStepFact.AttemptObservation(
                        e.endpoint() != null ? e.endpoint().name() : null,
                        e.httpStatus().isPresent() ? e.httpStatus().getAsInt() : null,
                        e.rawBusinessCode().isPresent() ? e.rawBusinessCode().getAsInt() : null,
                        e.durationMs(),
                        e.requestBytes(),
                        e.responseBytes(),
                        e.physicalSendCount(),
                        e.wasBusinessCallStarted()
                ))
                .toList();
    }

    private CallDigestFact buildCallDigest(OperationTicket ticket, List<HuashengExchangeEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) return null;
        HuashengExchangeEvidence last = evidences.get(evidences.size() - 1);
        return CallDigestFact.builder()
                .endpointCode(last.endpoint() != null ? last.endpoint().name() : null)
                .durationMs(last.durationMs())
                .httpStatus(last.httpStatus().isPresent() ? last.httpStatus().getAsInt() : null)
                .traceId(ticket.traceId())
                .build();
    }
}
