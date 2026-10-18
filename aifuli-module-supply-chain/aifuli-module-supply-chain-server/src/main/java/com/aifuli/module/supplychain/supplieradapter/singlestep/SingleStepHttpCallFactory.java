package com.aifuli.module.supplychain.supplieradapter.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.domain.SingleStepTimeoutContract;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 构造三家单步骤协议的HTTP调用，不承担协议解析、实际执行日志或资金处理。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#46-http预算与租约的本地实施切片。
 */
public final class SingleStepHttpCallFactory {

    private final OkHttpClient rootClient = new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .build();

    /**
     * 使用原单时限和执行者换算的单调资格截止构造一次Call。
     *
     * <p>预算从本方法进入时固定，取整次预算与剩余资格的较小值，覆盖响应体读取；
     * 延迟execute不能重新起算。真正执行及网络发送前再核截止，闩锁拒绝透明第二次发送。
     * 调用方须在try-with-resources中消费/关闭响应，并在实际外呼边界记录规范日志。
     * 异常只表示本地调用未正常完成，不能作为无副作用、关单或释放预留的证据。</p>
     *
     * @param request 已按原单冻结材料装配的请求，不由本工厂修改业务字段
     * @param contract 原单冻结时限，不使用运行期最新配置兜底
     * @param executionDeadlineNanos 执行者由原认领租约换算的进程内System.nanoTime截止，不能持久化
     * @throws InterruptedIOException 构造时执行资格已耗尽
     */
    public Call newCall(Request request, SingleStepTimeoutContract contract, long executionDeadlineNanos)
            throws InterruptedIOException {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(contract, "frozen timeout contract is required");
        long startNanos = System.nanoTime();
        checkDeadline(executionDeadlineNanos);
        long callNanos = Math.min(TimeUnit.MILLISECONDS.toNanos(contract.totalCallTimeoutMs()),
                executionDeadlineNanos - startNanos);
        long deadlineNanos = startNanos + callNanos;
        Request oneShotRequest = oneShotPost(request);
        AtomicBoolean networkStarted = new AtomicBoolean();

        OkHttpClient client = rootClient.newBuilder()
                .connectTimeout(contract.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(contract.readWriteTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(contract.readWriteTimeoutMs(), TimeUnit.MILLISECONDS)
                .addInterceptor(chain -> {
                    checkDeadline(deadlineNanos);
                    return chain.proceed(chain.request());
                })
                .addNetworkInterceptor(chain -> {
                    checkDeadline(deadlineNanos);
                    if (!networkStarted.compareAndSet(false, true)) {
                        throw new ProtocolException("单步骤调用禁止透明二次发送");
                    }
                    return chain.proceed(chain.request());
                })
                .build();
        Call call = client.newCall(oneShotRequest);
        call.timeout().timeout(callNanos, TimeUnit.NANOSECONDS);
        call.timeout().deadlineNanoTime(deadlineNanos);
        return call;
    }

    /**
     * 声明最终请求体不可重传，特别是阻止不受连接重试开关约束的503补发。
     * 保留原编码、长度和字节所有权，不预读或复制含秘密的请求体。
     */
    private static Request oneShotPost(Request request) {
        RequestBody delegate = request.body();
        if (!"POST".equals(request.method()) || delegate == null) {
            throw new IllegalArgumentException("单步骤供应商HTTP仅接受带请求体的POST");
        }
        return request.newBuilder().post(new RequestBody() {
            @Override
            public MediaType contentType() {
                return delegate.contentType();
            }

            @Override
            public long contentLength() throws IOException {
                return delegate.contentLength();
            }

            @Override
            public boolean isDuplex() {
                return delegate.isDuplex();
            }

            @Override
            public boolean isOneShot() {
                return true;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                delegate.writeTo(sink);
            }
        }).build();
    }

    /** 三个准入边界共用同一固定截止，校验失败不触发任何补发。 */
    private static void checkDeadline(long deadlineNanos) throws InterruptedIOException {
        if (deadlineNanos - System.nanoTime() <= 0) {
            throw new InterruptedIOException("单步骤调用预算或执行资格已耗尽");
        }
    }
}
