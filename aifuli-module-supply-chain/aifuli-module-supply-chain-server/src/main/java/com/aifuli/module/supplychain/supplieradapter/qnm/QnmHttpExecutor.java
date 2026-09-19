package com.aifuli.module.supplychain.supplieradapter.qnm;

import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;

/**
 * 奇那梦 HTTP 网络传输执行器：基于 OkHttpClient 封装超时控制、耗时统计与 SHA-256 审计摘要计算。
 *
 * <p>遵守 AGENTS.md 第 6 条：第三方调用记录关键参数、响应结果及耗时，敏感信息脱敏。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#10-adapter-元数据调用审计与敏感资产}。</p>
 */
@Slf4j
@Component
public class QnmHttpExecutor {

    private final OkHttpClient defaultClient;

    public QnmHttpExecutor() {
        this(new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(10))
                .followRedirects(false)
                .build());
    }

    public QnmHttpExecutor(OkHttpClient okHttpClient) {
        this.defaultClient = okHttpClient;
    }

    /**
     * 发起表单 POST 请求并记录执行摘要。
     *
     * @param url            完整请求 URL
     * @param params         表单请求参数
     * @param timeoutSeconds 自定义超时时间（秒），0 或负数使用默认
     * @param traceId        链路追踪标识
     * @return HTTP 调用结果
     */
    public HttpExecutionResult postForm(String url, Map<String, String> params, int timeoutSeconds, String traceId) {
        long start = System.currentTimeMillis();
        FormBody.Builder formBuilder = new FormBody.Builder(StandardCharsets.UTF_8);
        StringBuilder rawFormContent = new StringBuilder();
        boolean first = true;
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    formBuilder.add(entry.getKey(), entry.getValue());
                    if (!first) {
                        rawFormContent.append('&');
                    }
                    rawFormContent.append(entry.getKey()).append('=').append(entry.getValue());
                    first = false;
                }
            }
        }
        FormBody formBody = formBuilder.build();
        byte[] requestDigest = sha256(rawFormContent.toString().getBytes(StandardCharsets.UTF_8));

        OkHttpClient client = defaultClient;
        long ticketTimeoutMs = timeoutSeconds * 1000L;
        if (timeoutSeconds > 0 && defaultClient.readTimeoutMillis() != ticketTimeoutMs) {
            client = defaultClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                    .readTimeout(Duration.ofSeconds(timeoutSeconds))
                    .writeTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
        }

        Request request = new Request.Builder()
                .url(url)
                .post(formBody)
                .header("User-Agent", "Aifuli-SupplyChain-QnmAdapter/1.0")
                .header("X-Trace-Id", traceId != null ? traceId : "")
                .build();

        Map<String, String> maskedParams = QnmMaskingUtils.maskParams(params);
        log.info("[QNM][HTTP-OUT] url={}, traceId={}, params={}", url, traceId, maskedParams);

        try (Response response = client.newCall(request).execute()) {
            long duration = System.currentTimeMillis() - start;
            int statusCode = response.code();
            ResponseBody body = response.body();
            String responseStr = body != null ? body.string() : "";
            byte[] responseDigest = sha256(responseStr.getBytes(StandardCharsets.UTF_8));

            log.info("[QNM][HTTP-IN] url={}, status={}, duration={}ms, bodyLen={}",
                    url, statusCode, duration, responseStr.length());

            return new HttpExecutionResult(
                    statusCode,
                    responseStr,
                    requestDigest,
                    responseDigest,
                    duration,
                    false,
                    false,
                    null
            );
        } catch (SocketTimeoutException e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[QNM][HTTP-TIMEOUT] url={}, traceId={}, duration={}ms: {}",
                    url, traceId, duration, e.getMessage());
            return new HttpExecutionResult(
                    null,
                    null,
                    requestDigest,
                    null,
                    duration,
                    true,
                    false,
                    "Socket timeout: " + e.getMessage()
            );
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[QNM][HTTP-ERROR] url={}, traceId={}, duration={}ms: {}",
                    url, traceId, duration, e.getMessage());
            return new HttpExecutionResult(
                    null,
                    null,
                    requestDigest,
                    null,
                    duration,
                    false,
                    true,
                    "IO error: " + e.getMessage()
            );
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[QNM][HTTP-UNKNOWN-ERROR] url={}, traceId={}, duration={}ms",
                    url, traceId, duration, e);
            return new HttpExecutionResult(
                    null,
                    null,
                    requestDigest,
                    null,
                    duration,
                    false,
                    true,
                    "Unexpected error: " + e.getMessage()
            );
        }
    }

    /**
     * 计算 SHA-256 摘要（32 字节二进制）。
     */
    public static byte[] sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data != null ? data : new byte[0]);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * HTTP 调用结果数据结构。
     */
    public record HttpExecutionResult(
            Integer statusCode,
            String responseBody,
            byte[] requestDigest,
            byte[] responseDigest,
            long durationMs,
            boolean timedOut,
            boolean networkError,
            String errorMessage
    ) {
        public HttpExecutionResult {
            requestDigest = requestDigest == null ? null : requestDigest.clone();
            responseDigest = responseDigest == null ? null : responseDigest.clone();
        }

        @Override
        public byte[] requestDigest() {
            return requestDigest == null ? null : requestDigest.clone();
        }

        @Override
        public byte[] responseDigest() {
            return responseDigest == null ? null : responseDigest.clone();
        }
    }
}
