package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengDiagnosticProjector;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengProtectedFailure;
import com.aifuli.module.supplychain.supplieradapter.huasheng.http.HuashengHttpExchange;
import com.aifuli.module.supplychain.supplieradapter.huasheng.http.HuashengHttpTransportPort;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengBusinessError;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengConfirmRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExpectedOrder;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderResponse;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengParseFact;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengPlaceRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengQueryRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengRequestBuilder;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTakeRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTakeResponse;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTransportContext;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengWireRequest;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 华昇通信门面应用服务。
 *
 * <p>职责：
 * 组合 {@link HuashengTokenManager}、{@link HuashengRequestBuilder}、{@link HuashengHttpTransportPort} 与安全日志，
 * 公开 {@link #place}、{@link #confirm}、{@link #query}、{@link #take} 四大通信入口；
 * 严格编排有界 61 刷新（至多 2 次业务物理发送 + 至多 2 次 TOKEN 发送）；
 * 在业务发起前失败抛 {@link HuashengProtocolException}，业务发起后失败返回包含累计证据的 {@link HuashengCallResult.LocalFailure}。
 *
 * <p>不变量：
 * <ul>
 *   <li>零 Throwable 穿透：对外结果恒为强类型 {@link HuashengCallResult}，异常仅为包含安全诊断的 {@link HuashengProtocolException}；</li>
 *   <li>至多 2 次业务物理发送与至多 2 次 TOKEN 发送；二次业务 61 立即返回 {@link HuashengCallResult.BusinessError}，绝无第 3 次发送；</li>
 *   <li>非 2xx HTTP 状态码、网络超时/IOException、非 61 业务拒绝（如 66）均绝不触发重发；</li>
 *   <li>不可变 attempts 列表完整保全所有已发生的物理通信安全证据。</li>
 * </ul>
 *
 * <p>关键决策：
 * 重试仅限定于业务 61 授权失效恢复，不承担业务层 RETRY_SAFE 语义；
 * 重试时使用完全相同的业务 payload，但必须重新获取秒级时间戳、32 字符 UUID nonce、密文与签名。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#83-61-的一次调用上限、§18.4。
 */
public class HuashengCommunicationFacade {

    @FunctionalInterface
    private interface ResponseDecoder<RES> {
        HuashengInternalAttempt<HuashengParseFact<RES>> decode(
                HuashengHttpExchange exchange, HuashengExpectedOrder expectedOrder, HuashengEndpoint stage);
    }

    private final HuashengTokenManager tokenManager;
    private final HuashengRequestBuilder requestBuilder;
    private final HuashengHttpTransportPort transportPort;
    private final Clock clock;
    private final LongSupplier nanoTicker;
    private final HuashengSafeLog safeLog;

    /**
     * 构造通信门面应用服务。
     *
     * @param tokenManager Token 管理器，非空
     * @param requestBuilder 请求构造器，非空
     * @param transportPort 物理传输端口，非空
     * @param clock Wall Clock 时钟，非空
     * @param nanoTicker 单调 Ticker 时钟源，非空
     * @param safeLog 安全日志服务，非空
     */
    public HuashengCommunicationFacade(
            HuashengTokenManager tokenManager,
            HuashengRequestBuilder requestBuilder,
            HuashengHttpTransportPort transportPort,
            Clock clock,
            LongSupplier nanoTicker,
            HuashengSafeLog safeLog
    ) {
        this.tokenManager = Objects.requireNonNull(tokenManager, "tokenManager不能为null");
        this.requestBuilder = Objects.requireNonNull(requestBuilder, "requestBuilder不能为null");
        this.transportPort = Objects.requireNonNull(transportPort, "transportPort不能为null");
        this.clock = Objects.requireNonNull(clock, "clock不能为null");
        this.nanoTicker = Objects.requireNonNull(nanoTicker, "nanoTicker不能为null");
        this.safeLog = Objects.requireNonNull(safeLog, "safeLog不能为null");
    }

    /**
     * 执行下单（PLACE 端点）。
     */
    public HuashengCallResult<HuashengOrderResponse> place(
            HuashengAuthorizedCallContext context,
            HuashengPlaceRequest request,
            HuashengExpectedOrder expectedOrder,
            HuashengCallBudget budget
    ) {
        return execute(HuashengEndpoint.PLACE, context, request, expectedOrder, budget,
                (exchange, expected, stage) -> exchange.parseOrder(expected, stage));
    }

    /**
     * 执行确认充值（CONFIRM 端点）。
     */
    public HuashengCallResult<HuashengOrderResponse> confirm(
            HuashengAuthorizedCallContext context,
            HuashengConfirmRequest request,
            HuashengExpectedOrder expectedOrder,
            HuashengCallBudget budget
    ) {
        return execute(HuashengEndpoint.CONFIRM, context, request, expectedOrder, budget,
                (exchange, expected, stage) -> exchange.parseOrder(expected, stage));
    }

    /**
     * 执行订单查询（QUERY 端点）。
     */
    public HuashengCallResult<HuashengOrderResponse> query(
            HuashengAuthorizedCallContext context,
            HuashengQueryRequest request,
            HuashengExpectedOrder expectedOrder,
            HuashengCallBudget budget
    ) {
        return execute(HuashengEndpoint.QUERY, context, request, expectedOrder, budget,
                (exchange, expected, stage) -> exchange.parseOrder(expected, stage));
    }

    /**
     * 执行主动取券提卡（TAKE 端点）。
     */
    public HuashengCallResult<HuashengTakeResponse> take(
            HuashengAuthorizedCallContext context,
            HuashengTakeRequest request,
            HuashengExpectedOrder expectedOrder,
            HuashengCallBudget budget
    ) {
        return execute(HuashengEndpoint.TAKE, context, request, expectedOrder, budget,
                (exchange, expected, stage) -> exchange.parseTake(expected));
    }

    private <REQ extends HuashengRequest, RES> HuashengCallResult<RES> execute(
            HuashengEndpoint endpoint,
            HuashengAuthorizedCallContext context,
            REQ request,
            HuashengExpectedOrder expectedOrder,
            HuashengCallBudget budget,
            ResponseDecoder<RES> decoder
    ) {
        Objects.requireNonNull(context, "context不能为null");
        Objects.requireNonNull(request, "request不能为null");
        Objects.requireNonNull(expectedOrder, "expectedOrder不能为null");
        Objects.requireNonNull(budget, "budget不能为null");

        safeLog.recordEntry(context.callContext().operationNo(), context.callContext().connectorVersionId(), endpoint, context.callContext().traceId());

        long nowNanos = nanoTicker.getAsLong();
        if (budget.isDeadlineExceeded(nowNanos)) {
            HuashengFailureDiagnostics diag = HuashengDiagnosticProjector.toSafeDiagnostics(
                    HuashengProtectedFailure.capture(HuashengFailureKind.PREFLIGHT, new IllegalStateException("调用前已达截止时间"), Optional.empty())
            );
            safeLog.recordFailure(diag);
            throw HuashengProtocolException.fromDiagnostics(diag);
        }

        // 1. 获取 Token
        HuashengInternalAttempt<HuashengTokenEntry> tokenAttempt = tokenManager.acquireToken(context);
        if (tokenAttempt instanceof HuashengInternalAttempt.Failed<HuashengTokenEntry> failedToken) {
            HuashengFailureDiagnostics diag = HuashengDiagnosticProjector.toSafeDiagnostics(failedToken.failure());
            safeLog.recordFailure(diag);
            throw HuashengProtocolException.fromDiagnostics(diag);
        }

        HuashengTokenEntry tokenEntry = ((HuashengInternalAttempt.Parsed<HuashengTokenEntry>) tokenAttempt).value();
        List<HuashengExchangeEvidence> attempts = new ArrayList<>();
        if (tokenEntry.tokenEvidence().isPresent()) {
            attempts.add(tokenEntry.tokenEvidence().get());
        }

        // 2. 检查并消耗第 1 次业务预算
        if (!budget.tryAcquireBusinessAttempt(nanoTicker.getAsLong())) {
            HuashengFailureDiagnostics diag = HuashengDiagnosticProjector.toSafeDiagnostics(
                    HuashengProtectedFailure.capture(HuashengFailureKind.PREFLIGHT, new IllegalStateException("无可用业务发送配额"), Optional.empty())
            );
            safeLog.recordFailure(diag);
            throw HuashengProtocolException.fromDiagnostics(diag);
        }

        // 3. 构建第 1 次业务 WireRequest
        HuashengWireRequest firstWire;
        try {
            firstWire = requestBuilder.build(endpoint, request, context.callContext(), Optional.of(tokenEntry.accessToken()));
        } catch (RuntimeException e) {
            HuashengFailureDiagnostics diag = HuashengDiagnosticProjector.toSafeDiagnostics(
                    HuashengProtectedFailure.capture(HuashengFailureKind.PREFLIGHT, e, Optional.empty())
            );
            safeLog.recordFailure(diag);
            throw HuashengProtocolException.fromDiagnostics(diag);
        }

        // 4. 发起第 1 次业务发送 (wasBusinessCallStarted = true)
        HuashengTransportContext firstTransportCtx = HuashengTransportContext.from(context.callContext(), budget.deadlineNanos(), 1);
        HuashengInternalAttempt<HuashengHttpExchange> firstExchangeAttempt = transportPort.exchange(firstWire, firstTransportCtx);

        if (firstExchangeAttempt instanceof HuashengInternalAttempt.Failed<HuashengHttpExchange> failedFirstExchange) {
            HuashengFailureDiagnostics diag = HuashengDiagnosticProjector.toSafeDiagnostics(failedFirstExchange.failure());
            safeLog.recordFailure(diag);
            if (diag.evidence().isPresent()) {
                attempts.add(diag.evidence().get());
            }
            if (diag.failureKind() == HuashengFailureKind.HTTP_ERROR) {
                return new HuashengCallResult.HttpFailure<>(diag, attempts, true);
            }
            return new HuashengCallResult.TransportFailure<>(diag, attempts, true);
        }

        HuashengHttpExchange firstExchange = ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) firstExchangeAttempt).value();
        attempts.add(firstExchange.evidence());

        HuashengInternalAttempt<HuashengParseFact<RES>> firstParseAttempt = decoder.decode(firstExchange, expectedOrder, endpoint);
        if (firstParseAttempt instanceof HuashengInternalAttempt.Failed<HuashengParseFact<RES>> failedFirstParse) {
            HuashengFailureDiagnostics diag = HuashengDiagnosticProjector.toSafeDiagnostics(failedFirstParse.failure());
            safeLog.recordFailure(diag);
            return new HuashengCallResult.InvalidResponse<>(diag, attempts, true);
        }

        HuashengParseFact<RES> firstFact = ((HuashengInternalAttempt.Parsed<HuashengParseFact<RES>>) firstParseAttempt).value();
        if (firstFact instanceof HuashengParseFact.Data<RES> dataFact) {
            return new HuashengCallResult.Parsed<>(dataFact.data(), attempts, true);
        }

        HuashengBusinessError businessError = ((HuashengParseFact.Business<RES>) firstFact).error();
        if (businessError.rawCode() != 61) {
            return new HuashengCallResult.BusinessError<>(businessError, attempts, true);
        }

        // 5. 收到业务 61：启动有界刷新重试
        nowNanos = nanoTicker.getAsLong();
        if (budget.isDeadlineExceeded(nowNanos) || budget.remainingBusinessAttempts() <= 0) {
            HuashengFailureDiagnostics diag = HuashengDiagnosticProjector.toSafeDiagnostics(
                    HuashengProtectedFailure.capture(HuashengFailureKind.PREFLIGHT, new IllegalStateException("已达截止时间或无剩余重试配额"), Optional.of(firstExchange.evidence()))
            );
            safeLog.recordFailure(diag);
            return new HuashengCallResult.LocalFailure<>(diag, attempts, true);
        }

        // 失效当前使用的 token generation
        tokenManager.invalidateIfGeneration(context.toTokenIdentity(), tokenEntry.generation());

        // 重新获取 Token
        HuashengInternalAttempt<HuashengTokenEntry> refreshedTokenAttempt = tokenManager.acquireToken(context);
        if (refreshedTokenAttempt instanceof HuashengInternalAttempt.Failed<HuashengTokenEntry> failedRefresh) {
            HuashengFailureDiagnostics diag = HuashengDiagnosticProjector.toSafeDiagnostics(failedRefresh.failure());
            safeLog.recordFailure(diag);
            return new HuashengCallResult.LocalFailure<>(diag, attempts, true);
        }

        HuashengTokenEntry refreshedEntry = ((HuashengInternalAttempt.Parsed<HuashengTokenEntry>) refreshedTokenAttempt).value();
        if (refreshedEntry.tokenEvidence().isPresent() && !attempts.contains(refreshedEntry.tokenEvidence().get())) {
            attempts.add(refreshedEntry.tokenEvidence().get());
        }

        // 消耗第 2 次业务配额
        if (!budget.tryAcquireBusinessAttempt(nanoTicker.getAsLong())) {
            HuashengFailureDiagnostics diag = HuashengDiagnosticProjector.toSafeDiagnostics(
                    HuashengProtectedFailure.capture(HuashengFailureKind.PREFLIGHT, new IllegalStateException("无可用业务发送配额"), Optional.of(firstExchange.evidence()))
            );
            safeLog.recordFailure(diag);
            return new HuashengCallResult.LocalFailure<>(diag, attempts, true);
        }

        // 构建第 2 次业务 WireRequest
        HuashengWireRequest secondWire;
        try {
            secondWire = requestBuilder.build(endpoint, request, context.callContext(), Optional.of(refreshedEntry.accessToken()));
        } catch (RuntimeException e) {
            HuashengFailureDiagnostics diag = HuashengDiagnosticProjector.toSafeDiagnostics(
                    HuashengProtectedFailure.capture(HuashengFailureKind.PREFLIGHT, e, Optional.of(firstExchange.evidence()))
            );
            safeLog.recordFailure(diag);
            return new HuashengCallResult.LocalFailure<>(diag, attempts, true);
        }

        HuashengTransportContext secondTransportCtx = HuashengTransportContext.from(context.callContext(), budget.deadlineNanos(), 2);
        HuashengInternalAttempt<HuashengHttpExchange> secondExchangeAttempt = transportPort.exchange(secondWire, secondTransportCtx);

        if (secondExchangeAttempt instanceof HuashengInternalAttempt.Failed<HuashengHttpExchange> failedSecondExchange) {
            HuashengFailureDiagnostics diag = HuashengDiagnosticProjector.toSafeDiagnostics(failedSecondExchange.failure());
            safeLog.recordFailure(diag);
            if (diag.evidence().isPresent()) {
                attempts.add(diag.evidence().get());
            }
            if (diag.failureKind() == HuashengFailureKind.HTTP_ERROR) {
                return new HuashengCallResult.HttpFailure<>(diag, attempts, true);
            }
            return new HuashengCallResult.TransportFailure<>(diag, attempts, true);
        }

        HuashengHttpExchange secondExchange = ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) secondExchangeAttempt).value();
        attempts.add(secondExchange.evidence());

        HuashengInternalAttempt<HuashengParseFact<RES>> secondParseAttempt = decoder.decode(secondExchange, expectedOrder, endpoint);
        if (secondParseAttempt instanceof HuashengInternalAttempt.Failed<HuashengParseFact<RES>> failedSecondParse) {
            HuashengFailureDiagnostics diag = HuashengDiagnosticProjector.toSafeDiagnostics(failedSecondParse.failure());
            safeLog.recordFailure(diag);
            return new HuashengCallResult.InvalidResponse<>(diag, attempts, true);
        }

        HuashengParseFact<RES> secondFact = ((HuashengInternalAttempt.Parsed<HuashengParseFact<RES>>) secondParseAttempt).value();
        if (secondFact instanceof HuashengParseFact.Data<RES> secondDataFact) {
            return new HuashengCallResult.Parsed<>(secondDataFact.data(), attempts, true);
        }

        HuashengBusinessError secondBusinessError = ((HuashengParseFact.Business<RES>) secondFact).error();
        return new HuashengCallResult.BusinessError<>(secondBusinessError, attempts, true);
    }
}
