package com.aifuli.module.supplychain.supplieradapter.huasheng.http;

import com.aifuli.module.supplychain.infrastructure.security.SupplierEndpointPolicy;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengDiagnosticProjector;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengProtectedFailure;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTransportContext;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengWireRequest;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 华昇 HTTP 物理通信执行器。
 *
 * <p>职责：
 * 实现 {@link HuashengHttpTransportPort} 端口，驱动 OkHttp 执行真实本地 HTTPS 传输：
 * <ul>
 *   <li>基于 {@link SupplierEndpointPolicy} 两次校验冻结根地址与目标 URL；</li>
 *   <li>单调时钟精确设置 Call 超时与绝对截止点（deadlineNanoTime）；</li>
 *   <li>限制单次物理发送：重定向/自动重试关闭，非 duplex 且 isOneShot 的 RequestBody，单次发送网络拦截器闩锁；</li>
 *   <li>严格流式读取有限响应：上限 1MiB+1 解码字节，超限 cancel-before-close 并全量擦除；</li>
 *   <li>记录结构化安全日志（{@link HuashengHttpSafeLog}），杜绝打印敏感载荷与异常文本。</li>
 * </ul>
 *
 * <p>不变量：
 * <ul>
 *   <li>public 生产构造使用系统默认信任与默认 HostnameVerifier，不接受测试信任注入；</li>
 *   <li>测试专用窄构造器设为 private，仅在同模块测试中通过 {@code MethodHandles.privateLookupIn} 反射调用；</li>
 *   <li>禁止 public 构造调用 private 测试构造（各自分别完整初始化 final 字段）；</li>
 *   <li>两个构造均严格核验入参为精确 {@link SupplierEndpointPolicy} 类实例；</li>
 *   <li>执行器内部 OkHttpClient 独立共享连接池与分发器，不篡改全局配置。</li>
 * </ul>
 *
 * <p>关键决策：
 * 依据用户定案（2026-10-07），路径处理遵循 OkHttp 标准 HttpUrl RFC 规范化语义，
 * 最终 URL 仍经 policy 强校验。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#173-executor私有-tls-装配与-deadline、§17.4、§17.5、§17.6。
 */
public final class HuashengHttpExecutor implements HuashengHttpTransportPort {

    private static final int MAX_SUCCESS_BYTES = 1048576; // 1 MiB
    private static final int MAX_READABLE_BYTES = 1048577; // 1 MiB + 1
    private static final MediaType MEDIA_TYPE_PLAIN = MediaType.get("text/plain; charset=utf-8");

    private final SupplierEndpointPolicy endpointPolicy;
    private final HuashengHttpSafeLog safeLog;
    private final OkHttpClient rootClient;

    /**
     * 生产公开构造函数。
     *
     * <p>职责：使用默认系统信任、默认主机名验证与系统默认代理构造执行器。
     *
     * @param endpointPolicy 供应商端点策略，非空且必须为精确 {@link SupplierEndpointPolicy} 类实例
     * @throws IllegalArgumentException 当策略为 null 或子类覆盖时抛出
     */
    public HuashengHttpExecutor(SupplierEndpointPolicy endpointPolicy) {
        if (endpointPolicy == null || endpointPolicy.getClass() != SupplierEndpointPolicy.class) {
            throw new IllegalArgumentException("必须为精确SupplierEndpointPolicy实例");
        }
        this.endpointPolicy = endpointPolicy;
        this.safeLog = new HuashengHttpSafeLog();
        this.rootClient = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
                .build();
    }

    /**
     * 私有测试构造函数。
     *
     * <p>职责：仅允许注入测试专用的 DNS、自签名 SSLSocketFactory 与 TrustManager，显式设置 Proxy.NO_PROXY。
     * 严禁生产代码调用。
     *
     * @param endpointPolicy 供应商端点策略，非空
     * @param fixtureDns 测试 DNS 解析器，可为 null
     * @param fixtureSocketFactory 测试 SSLSocketFactory，可为 null
     * @param fixtureTrustManager 测试 X509TrustManager，可为 null
     * @throws IllegalArgumentException 当策略为 null 或子类覆盖时抛出
     */
    private HuashengHttpExecutor(SupplierEndpointPolicy endpointPolicy,
                                 Dns fixtureDns,
                                 SSLSocketFactory fixtureSocketFactory,
                                 X509TrustManager fixtureTrustManager) {
        if (endpointPolicy == null || endpointPolicy.getClass() != SupplierEndpointPolicy.class) {
            throw new IllegalArgumentException("必须为精确SupplierEndpointPolicy实例");
        }
        this.endpointPolicy = endpointPolicy;
        this.safeLog = new HuashengHttpSafeLog();
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
                .proxy(Proxy.NO_PROXY);
        if (fixtureDns != null) {
            builder.dns(fixtureDns);
        }
        if (fixtureSocketFactory != null && fixtureTrustManager != null) {
            builder.sslSocketFactory(fixtureSocketFactory, fixtureTrustManager);
        }
        this.rootClient = builder.build();
    }

    /**
     * 执行物理 HTTP 交换。
     *
     * @param request 华昇线缆请求，非空
     * @param transportContext 华昇传输上下文，非空
     * @return 内部尝试结果
     */
    @Override
    public HuashengInternalAttempt<HuashengHttpExchange> exchange(
            HuashengWireRequest request, HuashengTransportContext transportContext) {
        long startNanos = System.nanoTime();

        // 1. 基础入参空校验（PREFLIGHT）
        if (request == null) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT,
                    new IllegalArgumentException("request不能为null"),
                    Optional.empty()));
        }
        if (transportContext == null) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT,
                    new IllegalArgumentException("transportContext不能为null"),
                    Optional.empty()));
        }

        HuashengEndpoint endpoint = request.endpoint();
        safeLog.recordStart(transportContext, endpoint);

        // 2. 入口线程中断状态检查（不清中断标志，零网络 PREFLIGHT）
        if (Thread.currentThread().isInterrupted()) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                    endpoint, OptionalInt.empty(), OptionalInt.empty(), durationMs, 0L, 0L, 0, false);
            HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT, new InterruptedException("入口线程已处于中断状态"), Optional.of(evidence));
            safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
            return HuashengInternalAttempt.failed(failure);
        }

        // 3. 共享绝对截止时间检查
        long remainingNanos = transportContext.deadlineNanos() - System.nanoTime();
        if (remainingNanos <= 0) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                    endpoint, OptionalInt.empty(), OptionalInt.empty(), durationMs, 0L, 0L, 0, false);
            HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT, new IllegalArgumentException("调用截止时间已耗尽"), Optional.of(evidence));
            safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
            return HuashengInternalAttempt.failed(failure);
        }
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        if (remainingMs <= 0) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                    endpoint, OptionalInt.empty(), OptionalInt.empty(), durationMs, 0L, 0L, 0, false);
            HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT, new IllegalArgumentException("调用截止时间不足1毫秒"), Optional.of(evidence));
            safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
            return HuashengInternalAttempt.failed(failure);
        }

        // 4. 端点策略校验：先校验 frozenBaseUrl，再校验 request.url()
        try {
            endpointPolicy.validate(transportContext.frozenBaseUrl());
            endpointPolicy.validate(request.url());
        } catch (RuntimeException e) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                    endpoint, OptionalInt.empty(), OptionalInt.empty(), durationMs, 0L, 0L, 0, false);
            HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT, e, Optional.of(evidence));
            safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
            return HuashengInternalAttempt.failed(failure);
        }

        // 5. 请求与上下文绑定一致性校验（URL、Origin、端口、封闭路径、请求头、标识与超时）
        try {
            validateRequestAndContextBinding(request, transportContext);
        } catch (RuntimeException e) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                    endpoint, OptionalInt.empty(), OptionalInt.empty(), durationMs, 0L, 0L, 0, false);
            HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT, e, Optional.of(evidence));
            safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
            return HuashengInternalAttempt.failed(failure);
        }

        // 6. 计算调用毫秒预算（Math.addExact 溢出保护）
        long callMs;
        try {
            long fullCallMs = Math.addExact(
                    (long) request.connectTimeoutMs(),
                    Math.multiplyExact(2L, (long) request.readTimeoutMs())
            );
            callMs = Math.min(fullCallMs, remainingMs);
        } catch (ArithmeticException e) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                    endpoint, OptionalInt.empty(), OptionalInt.empty(), durationMs, 0L, 0L, 0, false);
            HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT, e, Optional.of(evidence));
            safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
            return HuashengInternalAttempt.failed(failure);
        }

        // 7. 组装单次发送 RequestBody 与网络拦截器单次 proceed 闩锁
        byte[] bodyBytes = request.bodyHex().getBytes(StandardCharsets.UTF_8);
        HuashengOneShotRequestBody requestBody = new HuashengOneShotRequestBody(bodyBytes, MEDIA_TYPE_PLAIN);
        CallNetworkInterceptor networkInterceptor = new CallNetworkInterceptor();

        Request.Builder httpRequestBuilder = new Request.Builder()
                .url(request.url())
                .post(requestBody);
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            httpRequestBuilder.addHeader(header.getKey(), header.getValue());
        }
        Request httpRequest = httpRequestBuilder.build();

        OkHttpClient callClient = rootClient.newBuilder()
                .connectTimeout(request.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(request.readTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(request.readTimeoutMs(), TimeUnit.MILLISECONDS)
                .addNetworkInterceptor(networkInterceptor)
                .build();

        Call call = callClient.newCall(httpRequest);
        call.timeout().timeout(callMs, TimeUnit.MILLISECONDS);
        call.timeout().deadlineNanoTime(transportContext.deadlineNanos());

        // 8. 创建 Call 后紧邻 execute 前复核截止时间
        long remainingBeforeExecute = transportContext.deadlineNanos() - System.nanoTime();
        if (remainingBeforeExecute <= 0) {
            call.cancel();
            Arrays.fill(bodyBytes, (byte) 0);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                    endpoint, OptionalInt.empty(), OptionalInt.empty(), durationMs, 0L, 0L, 0, false);
            HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT, new IllegalArgumentException("创建Call后截止时间已耗尽"), Optional.of(evidence));
            safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
            return HuashengInternalAttempt.failed(failure);
        }

        // 9. 紧邻 execute 前冻结计划编码字节与业务发送事实
        long plannedRequestBytes = bodyBytes.length;
        int physicalSendCount = (endpoint != HuashengEndpoint.TOKEN) ? 1 : 0;
        boolean wasBusinessCallStarted = (endpoint != HuashengEndpoint.TOKEN);

        Response response = null;
        try {
            response = call.execute();
        } catch (Throwable t) {
            if (t instanceof Error) {
                Arrays.fill(bodyBytes, (byte) 0);
                throw (Error) t; // 严禁 capture 虚拟机 Error / OOME
            }
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            Integer observedStatus = networkInterceptor.observedStatus();
            OptionalInt statusOpt = observedStatus != null ? OptionalInt.of(observedStatus) : OptionalInt.empty();

            // 观察器已捕获非 2xx 状态（如 503 超大 Retry-After 触发的 NumberFormatException）保持 HTTP_ERROR
            if (observedStatus != null && (observedStatus < 200 || observedStatus >= 300)) {
                HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                        endpoint, statusOpt, OptionalInt.empty(), durationMs, plannedRequestBytes, 0L, physicalSendCount, wasBusinessCallStarted);
                HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                        HuashengFailureKind.HTTP_ERROR, t, Optional.of(evidence));
                safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
                return HuashengInternalAttempt.failed(failure);
            }

            // 传输期一般失败归 TRANSPORT_FAILURE；保留/恢复中断标志
            if (t instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
            HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                    endpoint, statusOpt, OptionalInt.empty(), durationMs, plannedRequestBytes, 0L, physicalSendCount, wasBusinessCallStarted);
            HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                    HuashengFailureKind.TRANSPORT_FAILURE, t, Optional.of(evidence));
            safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
            return HuashengInternalAttempt.failed(failure);
        } finally {
            Arrays.fill(bodyBytes, (byte) 0);
        }

        // 10. 处理 HTTP 响应
        final Response finalResponse = response;
        try (finalResponse) {
            int statusCode = finalResponse.code();
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            OptionalInt statusOpt = OptionalInt.of(statusCode);

            // 非 2xx 响应：不读正文，cancel-before-close，HTTP_ERROR
            if (statusCode < 200 || statusCode >= 300) {
                call.cancel();
                HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                        endpoint, statusOpt, OptionalInt.empty(), durationMs, plannedRequestBytes, 0L, physicalSendCount, wasBusinessCallStarted);
                HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                        HuashengFailureKind.HTTP_ERROR,
                        new ProtocolException("HTTP响应状态非2xx: " + statusCode),
                        Optional.of(evidence));
                safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
                return HuashengInternalAttempt.failed(failure);
            }

            ResponseBody body = response.body();
            if (body == null) {
                HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                        endpoint, statusOpt, OptionalInt.empty(), durationMs, plannedRequestBytes, 0L, physicalSendCount, wasBusinessCallStarted);
                HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                        HuashengFailureKind.TRANSPORT_FAILURE,
                        new ProtocolException("HTTP响应体为null"),
                        Optional.of(evidence));
                safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
                return HuashengInternalAttempt.failed(failure);
            }

            // 流式读取有限响应（最多读取 1048577 字节以探测第 1MiB+1 超限）
            byte[] readBuffer = new byte[MAX_READABLE_BYTES];
            int totalRead = 0;
            try (InputStream in = body.byteStream()) {
                while (totalRead < MAX_READABLE_BYTES) {
                    int read = in.read(readBuffer, totalRead, MAX_READABLE_BYTES - totalRead);
                    if (read == -1) {
                        break;
                    }
                    totalRead += read;
                }
            } catch (IOException e) {
                call.cancel();
                Arrays.fill(readBuffer, (byte) 0);
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                }
                HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                        endpoint, statusOpt, OptionalInt.empty(), durationMs, plannedRequestBytes, totalRead, physicalSendCount, wasBusinessCallStarted);
                HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                        HuashengFailureKind.TRANSPORT_FAILURE, e, Optional.of(evidence));
                safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
                return HuashengInternalAttempt.failed(failure);
            }

            // 校验是否超过 1MiB 上限（第 1MiB+1 字节触发）
            if (totalRead >= MAX_READABLE_BYTES) {
                call.cancel();
                Arrays.fill(readBuffer, (byte) 0);
                HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                        endpoint, statusOpt, OptionalInt.empty(), durationMs, plannedRequestBytes, totalRead, physicalSendCount, wasBusinessCallStarted);
                HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                        HuashengFailureKind.RESPONSE_TOO_LARGE,
                        new ProtocolException("响应体超过1MiB上限"),
                        Optional.of(evidence));
                safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
                return HuashengInternalAttempt.failed(failure);
            }

            // 空 2xx 响应（包含 204/205）：归 ENVELOPE_INVALID
            if (totalRead == 0) {
                HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                        endpoint, statusOpt, OptionalInt.empty(), durationMs, plannedRequestBytes, 0L, physicalSendCount, wasBusinessCallStarted);
                HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                        HuashengFailureKind.ENVELOPE_INVALID,
                        new ProtocolException("HTTP响应体为空"),
                        Optional.of(evidence));
                safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
                return HuashengInternalAttempt.failed(failure);
            }

            // 返回前复核截止时间
            if (System.nanoTime() > transportContext.deadlineNanos()) {
                call.cancel();
                Arrays.fill(readBuffer, (byte) 0);
                HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                        endpoint, statusOpt, OptionalInt.empty(), durationMs, plannedRequestBytes, totalRead, physicalSendCount, wasBusinessCallStarted);
                HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                        HuashengFailureKind.TRANSPORT_FAILURE,
                        new SocketTimeoutException("响应读取完成但在返回前截止时间已耗尽"),
                        Optional.of(evidence));
                safeLog.recordFailure(transportContext, HuashengDiagnosticProjector.toSafeDiagnostics(failure));
                return HuashengInternalAttempt.failed(failure);
            }

            byte[] exactPayload = Arrays.copyOf(readBuffer, totalRead);
            Arrays.fill(readBuffer, (byte) 0);

            HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                    endpoint, statusOpt, OptionalInt.empty(), durationMs, plannedRequestBytes, totalRead, physicalSendCount, wasBusinessCallStarted);
            safeLog.recordResult(transportContext, evidence);

            return HuashengInternalAttempt.parsed(new HuashengHttpExchange(exactPayload, evidence));
        }
    }

    private static void validateRequestAndContextBinding(
            HuashengWireRequest request, HuashengTransportContext context) {
        if (request.connectTimeoutMs() != context.connectTimeoutMs()) {
            throw new IllegalArgumentException("connectTimeoutMs与上下文不一致");
        }
        if (request.readTimeoutMs() != context.readTimeoutMs()) {
            throw new IllegalArgumentException("readTimeoutMs与上下文不一致");
        }
        if (!Objects.equals(request.operationNo(), context.operationNo())) {
            throw new IllegalArgumentException("operationNo与上下文不一致");
        }
        if (!Objects.equals(request.traceId(), context.traceId())) {
            throw new IllegalArgumentException("traceId与上下文不一致");
        }
        if (!Objects.equals(request.headers().get("X-AppId"), context.appId())) {
            throw new IllegalArgumentException("X-AppId与上下文不一致");
        }

        // 校验 Origin、端口与封闭路径一致性
        String base = context.frozenBaseUrl().strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String endpointPath = switch (request.endpoint()) {
            case TOKEN -> "/api/token";
            case PLACE -> "/api/order/placeOrder";
            case CONFIRM -> "/api/order/confirmOrder";
            case QUERY -> "/api/order/queryOrder";
            case TAKE -> "/api/order/takeCard";
            case CALLBACK -> throw new UnsupportedOperationException("CALLBACK is inbound only");
        };
        String expectedUrl = base + endpointPath;

        URI baseUri = URI.create(context.frozenBaseUrl());
        URI requestUri = URI.create(request.url());
        if (!Objects.equals(baseUri.getScheme(), requestUri.getScheme())
                || !Objects.equals(baseUri.getHost(), requestUri.getHost())
                || baseUri.getPort() != requestUri.getPort()) {
            throw new IllegalArgumentException("请求URL origin与baseUrl不一致");
        }
        if (!Objects.equals(expectedUrl, request.url())) {
            throw new IllegalArgumentException("请求URL不匹配端点封闭路径: expected=" + expectedUrl + ", actual=" + request.url());
        }

        // 校验请求头集合
        Map<String, String> headers = request.headers();
        if (!"text/plain; charset=utf-8".equalsIgnoreCase(headers.get("Content-Type"))) {
            throw new IllegalArgumentException("Content-Type头不合法");
        }
        if (!headers.containsKey("X-Sign") || headers.get("X-Sign").isBlank()) {
            throw new IllegalArgumentException("缺少X-Sign头");
        }

        if (request.endpoint() == HuashengEndpoint.TOKEN) {
            if (headers.containsKey("Authorization")) {
                throw new IllegalArgumentException("TOKEN请求禁止携带Authorization头");
            }
            for (String h : headers.keySet()) {
                if (!"Content-Type".equalsIgnoreCase(h) && !"X-AppId".equalsIgnoreCase(h) && !"X-Sign".equalsIgnoreCase(h)) {
                    throw new IllegalArgumentException("包含未授权请求头: " + h);
                }
            }
        } else {
            String auth = headers.get("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                throw new IllegalArgumentException("业务请求缺少有效Bearer Authorization头");
            }
            for (String h : headers.keySet()) {
                if (!"Content-Type".equalsIgnoreCase(h) && !"X-AppId".equalsIgnoreCase(h)
                        && !"X-Sign".equalsIgnoreCase(h) && !"Authorization".equalsIgnoreCase(h)) {
                    throw new IllegalArgumentException("包含未授权请求头: " + h);
                }
            }
        }
    }

    /**
     * 单次写入 RequestBody 守卫。
     */
    static final class HuashengOneShotRequestBody extends RequestBody {
        private final byte[] bodyBytes;
        private final MediaType mediaType;
        private final AtomicBoolean written = new AtomicBoolean(false);

        HuashengOneShotRequestBody(byte[] bodyBytes, MediaType mediaType) {
            this.bodyBytes = bodyBytes;
            this.mediaType = mediaType;
        }

        @Override
        public MediaType contentType() {
            return mediaType;
        }

        @Override
        public long contentLength() {
            return bodyBytes.length;
        }

        @Override
        public boolean isOneShot() {
            return true;
        }

        @Override
        public boolean isDuplex() {
            return false;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            if (!written.compareAndSet(false, true)) {
                throw new ProtocolException("RequestBody禁止二次写入");
            }
            sink.write(bodyBytes);
            sink.flush();
        }
    }

    /**
     * 每次调用专属网络拦截器，确保物理发送单次 proceed 并在本地观察数值 HTTP 状态。
     */
    static final class CallNetworkInterceptor implements Interceptor {
        private final AtomicInteger proceedCount = new AtomicInteger(0);
        private volatile Integer observedHttpStatus = null;

        @Override
        public Response intercept(Chain chain) throws IOException {
            if (proceedCount.incrementAndGet() > 1) {
                throw new ProtocolException("网络拦截器检测到重复物理发送尝试");
            }
            Response response = chain.proceed(chain.request());
            observedHttpStatus = response.code();
            return response;
        }

        Integer observedStatus() {
            return observedHttpStatus;
        }

        int proceedCount() {
            return proceedCount.get();
        }
    }
}
