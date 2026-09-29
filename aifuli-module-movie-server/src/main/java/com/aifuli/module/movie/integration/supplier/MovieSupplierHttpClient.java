package com.aifuli.module.movie.integration.supplier;

import com.aifuli.module.movie.enums.MovieChannel;
import com.aifuli.module.movie.framework.supply.MovieSupplyProperties.Account;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.zip.GZIPInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.util.stream.Collectors;

/**
 * JC/JY/LX 只读 HTTP 通道，各渠道独立签名与成功判定，保留正常 TLS 校验。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#2-接入账号与协议
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MovieSupplierHttpClient {
    /** JY 签名中的媒体类型必须与实际请求头逐字一致。 */
    private static final String FORM = "application/x-www-form-urlencoded; charset=UTF-8";
    /** JSON 响应类型。 */
    private static final String JSON = "application/json";
    /** JC/JY API 文档的成功业务码，必须与 success 同时成立。 */
    private static final String JC_SUCCESS_CODE = "1";
    private static final String JY_SUCCESS_CODE = "200";
    /** LX 成功信封只要求业务码，不存在 JC/JY 的 success 字段。 */
    private static final String LX_SUCCESS_CODE = "200";
    /** Jackson 仅解析协议树，金额由适配器直接读取十进制文本。 */
    private final ObjectMapper objectMapper;
    /** 请求时间戳使用统一时钟。 */
    private final Clock movieSupplyClock;
    /** 按连接超时复用 HTTP 连接池，避免逐影院创建线程池。 */
    private final Map<Duration, HttpClient> clients = new ConcurrentHashMap<>();
    /** 同账号的所有任务共用配额闸门；只在请求起始限速，不持有锁等待网络。 */
    private final Map<String, RequestGate> gates = new ConcurrentHashMap<>();

    /**
     * 执行只读供给请求；高频参数及响应摘要记 debug，错误由本轮任务聚合上报。
     * @param account 明确指定的采集账号，不按请求结果自动换账号
     * @param path 固定供应商接口路径
     * @param parameters 不含凭证的业务参数
     * @return 已按渠道验证成功信封的 data 节点，空数组与失败严格区分
     */
    public JsonNode request(Account account, String path, Map<String, String> parameters) {
        long started = System.nanoTime();
        String outcome = "TRANSPORT_ERROR";
        log.debug("电影供应商请求 account={}, channel={}, path={}, params={}",
                account.getAccountRef(), account.getChannel(), path, parameters);
        try {
            gates.computeIfAbsent(account.getAccountRef(), ignored -> new RequestGate()).await(account.getMinRequestInterval());
            String key = credential(account.getAppId());
            String secret = credential(account.getAppSecret());
            URI base = URI.create(account.getBaseUrl());
            if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                    || base.getFragment() != null || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()))) {
                throw new MovieSupplierException("供应商地址必须是无凭证、无查询参数的 HTTP/HTTPS 根地址");
            }
            String endpoint = account.getBaseUrl().replaceAll("/+$", "") + path;
            HttpRequest.Builder request;
            if (account.getChannel() == MovieChannel.JC) {
                TreeMap<String, String> signed = new TreeMap<>(parameters);
                signed.put("partnerCode", key);
                signed.put("sign", jcSignature(signed, secret));
                request = HttpRequest.newBuilder(URI.create(endpoint + "?" + form(signed)))
                        .header("Content-Type", FORM).header("Accept-Encoding", "gzip").GET();
            } else if (account.getChannel() == MovieChannel.LX) {
                // LX 秒级时间戳及业务参数先按原文签名，发送时再做 UTF-8 表单编码。
                TreeMap<String, String> signed = new TreeMap<>();
                parameters.forEach((name, value) -> {
                    if (value != null && !"sign".equals(name)) {
                        signed.put(name, value);
                    }
                });
                signed.put("app_id", key);
                signed.put("time", Long.toString(movieSupplyClock.instant().getEpochSecond()));
                signed.put("sign", lxSignature(signed, secret));
                request = HttpRequest.newBuilder(URI.create(endpoint)).header("Content-Type", FORM)
                        .header("Accept-Encoding", "gzip")
                        .POST(HttpRequest.BodyPublishers.ofString(form(signed), StandardCharsets.UTF_8));
            } else if (account.getChannel() == MovieChannel.JY) {
                TreeMap<String, String> headers = new TreeMap<>();
                headers.put("X-Ca-Key", key);
                headers.put("X-Ca-Nonce", UUID.randomUUID().toString());
                headers.put("X-Ca-Timestamp", Long.toString(movieSupplyClock.millis()));
                // JY 参考 SDK 的资源串不含 URL 编码，表单发送时才进行编码。
                String resource = parameters.isEmpty() ? path : path + "?" + new TreeMap<>(parameters).entrySet().stream()
                        .map(entry -> entry.getKey() + (entry.getValue().isEmpty() ? "" : "=" + entry.getValue()))
                        .collect(Collectors.joining("&"));
                String canonicalHeaders = headers.entrySet().stream()
                        .map(entry -> entry.getKey() + ":" + entry.getValue() + "\n").collect(Collectors.joining());
                String canonical = "POST\n" + JSON + "\n\n" + FORM + "\n\n" + canonicalHeaders + resource;
                request = HttpRequest.newBuilder(URI.create(endpoint)).header("Content-Type", FORM)
                        .header("X-Ca-Signature", jySignature(canonical, secret))
                        .header("X-Ca-Signature-Headers", String.join(",", headers.keySet()))
                        .POST(HttpRequest.BodyPublishers.ofString(form(parameters), StandardCharsets.UTF_8));
                headers.forEach(request::header);
            } else {
                throw new MovieSupplierException("未支持的电影供应商渠道");
            }
            request.header("Accept", JSON).timeout(account.getRequestTimeout());
            HttpClient client = clients.computeIfAbsent(account.getConnectTimeout(), timeout ->
                    HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build());
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            outcome = "HTTP_" + response.statusCode();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MovieSupplierException("供应商 HTTP 状态异常：" + response.statusCode());
            }
            JsonNode envelope = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .without(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION.mappedFeature()).readTree(responseBody(response));
            if (envelope == null || !envelope.isObject()) {
                throw new MovieSupplierException("供应商响应不是 JSON 对象");
            }
            // 错误码只允许短标识，防止上游把敏感报文塞进 code 后进入日志。
            String code = envelope.path("code").asText("");
            outcome = code.matches("[A-Za-z0-9_-]{0,32}") ? "CODE_" + code : "INVALID_CODE";
            String expectedCode = switch (account.getChannel()) {
                case JC -> JC_SUCCESS_CODE;
                case JY -> JY_SUCCESS_CODE;
                case LX -> LX_SUCCESS_CODE;
            };
            boolean success = account.getChannel() == MovieChannel.LX
                    || (envelope.path("success").isBoolean() && envelope.path("success").booleanValue());
            if (!success || !expectedCode.equals(code)) {
                throw new MovieSupplierException("供应商返回失败：" + outcome);
            }
            JsonNode data = envelope.get("data");
            if (data == null || data.isNull()) {
                throw new MovieSupplierException("供应商成功响应缺少 data");
            }
            log.debug("电影供应商响应 account={}, path={}, result={}, items={}", account.getAccountRef(), path, outcome, data.size());
            return data;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MovieSupplierException("供应商请求被中断", exception);
        } catch (IOException exception) {
            throw new MovieSupplierException("供应商传输或 JSON 解析失败", exception);
        } finally {
            log.debug("电影供应商调用结束 account={}, path={}, result={}, elapsedMs={}",
                    account.getAccountRef(), path, outcome, (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** 按参考 JC SDK 的特殊 URL 编码计算 MD5 大写摘要。 */
    static String jcSignature(Map<String, String> parameters, String secret) {
        String canonical = new TreeMap<>(parameters).entrySet().stream().map(entry -> entry.getKey() + "="
                + encode(entry.getValue()).replace("*", "%2A").replace("+", "%2B")).collect(Collectors.joining("&"));
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("MD5")
                    .digest((canonical + "&key=" + secret).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("JVM 不支持供应商签名算法", exception);
        }
    }

    /** 按 JY 网关规范串生成 HMAC-SHA256/Base64 签名。 */
    static String jySignature(String canonical, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("JVM 不支持供应商签名算法", exception);
        }
    }

    /**
     * LX 原文参数排序后追加 app_secret，排除 sign/null；空串仍参与签名。
     * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#81-范围与协议
     */
    static String lxSignature(Map<String, String> parameters, String secret) {
        String canonical = new TreeMap<>(parameters).entrySet().stream()
                .filter(entry -> !"sign".equals(entry.getKey()) && entry.getValue() != null)
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("&"));
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("MD5")
                    .digest((canonical + "&app_secret=" + secret).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("JVM 不支持供应商签名算法", exception);
        }
    }

    /** 使用本轮中台返回的凭证，不重新读取环境变量或切换配置版本。 */
    private static String credential(String value) {
        if (value == null || value.isBlank()) {
            throw new MovieSupplierException("中台返回的电影供应商凭证为空");
        }
        return value;
    }

    /** 生成实际网络传输的 UTF-8 表单。 */
    private static String form(Map<String, String> parameters) {
        return new TreeMap<>(parameters).entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue())).collect(Collectors.joining("&"));
    }

    /** 表单值编码，签名的额外规则由 JC 方法单独处理。 */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** JDK HttpClient 不自动解压 gzip；按响应头解压后统一使用 UTF-8 解析。 */
    private static String responseBody(HttpResponse<byte[]> response) throws IOException {
        String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        if ("gzip".equalsIgnoreCase(encoding)) {
            try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (!"identity".equalsIgnoreCase(encoding)) {
            throw new MovieSupplierException("供应商响应使用不支持的内容编码");
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /**
     * 进程内账号请求间隔，使用单调时钟且等待可中断；跨实例配额仍需部署侧约束。
     * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#73-协议转换请求容量与诊断
     */
    private static final class RequestGate {
        /** 公平锁减少排期高频请求对其它类别的饥饿。 */
        private final ReentrantLock lock = new ReentrantLock(true);
        /** 上次请求起点，首次请求不等待。 */
        private long previousStart;
        private boolean started;

        void await(Duration interval) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                if (started) {
                    long remaining = interval.toNanos() - (System.nanoTime() - previousStart);
                    if (remaining > 0) {
                        TimeUnit.NANOSECONDS.sleep(remaining);
                    }
                }
                previousStart = System.nanoTime();
                started = true;
            } finally {
                lock.unlock();
            }
        }
    }
}
