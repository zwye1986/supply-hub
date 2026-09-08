package com.aifuli.module.supplychain.integration.rtmart;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 旅通 HOME/DETAIL 的单次 HTTP 出站实现。
 *
 * <p>本类只负责将已验证的端口命令转换为签名 JSON 参数集、执行一次受限 HTTP 调用，并将严格的正式响应映射为端口结果；
 * 不参与数据库事务也不自行重试。请求、响应和异常诊断均不得保留 token、sign、私钥、响应正文或完整跳转地址。
 * 设计见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#4-旅通请求协议}、
 * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#5-响应与错误语义}
 * 与 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#6-超时重试与事务}。</p>
 */
final class RtmartEntryUrlHttpAdapter implements RtmartEntryUrlPort {

    private static final Logger LOG = LoggerFactory.getLogger(RtmartEntryUrlHttpAdapter.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=UTF-8");
    private static final String ACCEPT_HEADER_VALUE = "application/json";
    private static final String HOME_OPERATION = "HOME";
    private static final String DETAIL_OPERATION = "DETAIL";
    private static final String SUCCESS_CODE = "E0000";
    private static final String SUCCESS_RESULT = "success";
    private static final String SUCCESS_REASON = "SUCCESS";
    private static final String HTTPS_SCHEME = "https";
    private static final String INVALID_LOG_VALUE = "INVALID";
    private static final Pattern SAFE_LOG_VALUE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final RtmartEntryProperties properties;
    private final OkHttpClient client;
    private final ObjectMapper responseMapper;
    private final RtmartRequestSigner signer;

    /**
     * 注入已经按配置设置超时的 HTTP 客户端和签名器；响应 Mapper 必须是副本，避免改变聚合 Spring 容器共享的
     * ObjectMapper。关闭 source-in-location 防止 Jackson 异常把远端原文带入异常树。
     *
     * <p>无论注入 client 的默认值如何，专用副本都强制禁止重试与跨地址重定向。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#32-新增-integration-adapter}
     * 与 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#6-超时重试与事务}。</p>
     */
    RtmartEntryUrlHttpAdapter(RtmartEntryProperties properties, OkHttpClient client,
                              ObjectMapper objectMapper, RtmartRequestSigner signer) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.client = Objects.requireNonNull(client, "client").newBuilder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        this.responseMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION);
        this.responseMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        this.signer = Objects.requireNonNull(signer, "signer");
    }

    /**
     * 获取 HOME 跳转地址，坐标原样进入签名 JSON 参数集，绝不在此处进行精度转换或文本规范化。
     *
     * <p>详见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#4-旅通请求协议}。</p>
     */
    @Override
    public EntryUrlResult getHomeUrl(HomeUrlCommand command) {
        Objects.requireNonNull(command, "command");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("company_code", command.companyCode());
        fields.put("longitude", command.longitude());
        fields.put("latitude", command.latitude());
        fields.put("token", command.token());
        return invoke(HOME_OPERATION, properties.getHomePath(), command.projectId(), command.channelCode(),
                command.companyCode(), fields);
    }

    /**
     * 获取 DETAIL 跳转地址，只发送冻结支付流水 {@code trade_id}，避免把内部订单号误发给旅通。
     *
     * <p>详见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#4-旅通请求协议}。</p>
     */
    @Override
    public EntryUrlResult getDetailUrl(DetailUrlCommand command) {
        Objects.requireNonNull(command, "command");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("company_code", command.companyCode());
        fields.put("trade_id", command.tradeId());
        fields.put("token", command.token());
        return invoke(DETAIL_OPERATION, properties.getDetailPath(), command.projectId(), command.channelCode(),
                command.companyCode(), fields);
    }

    /**
     * 执行一次出站调用：只记录经过白名单处理的业务标识，并将签名后的完整 JSON 请求限制在当前栈内存。
     *
     * <p>调用不重试；事务边界由 Facade 在调用前完成。详见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#6-超时重试与事务}。</p>
     */
    private EntryUrlResult invoke(String operation, String path, Long projectId, String channelCode, String companyCode,
                                  Map<String, String> businessFields) {
        long startedAtNanos = System.nanoTime();
        LOG.info("[RTMART][entry-url] request operation={}, projectId={}, channelCode={}, companyCode={}", operation,
                projectId, safeLogValue(channelCode), safeLogValue(companyCode));
        try {
            Map<String, String> signedFields = signer.sign(businessFields);
            Request request = new Request.Builder().url(resolve(path)).header("Accept", ACCEPT_HEADER_VALUE)
                    .post(RequestBody.create(responseMapper.writeValueAsBytes(signedFields), JSON_MEDIA_TYPE)).build();
            return execute(operation, request, startedAtNanos);
        } catch (RtmartEntryException exception) {
            logFailureCompletion(operation, projectId, channelCode, companyCode, exception, startedAtNanos);
            throw exception;
        } catch (RuntimeException | IOException exception) {
            RtmartEntryException protocol = RtmartEntryException.protocol(exception);
            logFailureCompletion(operation, projectId, channelCode, companyCode, protocol, startedAtNanos);
            throw protocol;
        }
    }

    /**
     * 读取并验证单次 HTTP 响应。3xx 与任何非 2xx 均在不访问跳转地址的前提下关闭响应并失败；响应正文始终按
     * {@code maxResponseBytes + 1} 的上限读取，禁止使用无界 {@code body.string()}。
     *
     * <p>传输异常仅通过统一安全 cause 边界进入上层。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#5-响应与错误语义}。</p>
     */
    private EntryUrlResult execute(String operation, Request request, long startedAtNanos) {
        Integer httpStatus = null;
        try (Response response = client.newCall(request).execute()) {
            httpStatus = response.code();
            if (!response.isSuccessful()) {
                throw RtmartEntryException.httpStatus(httpStatus);
            }
            RtmartEntryResponse entryResponse = parseBounded(response.body());
            if (!SUCCESS_CODE.equals(entryResponse.code())) {
                throw RtmartEntryException.remoteError(entryResponse.code());
            }
            if (!SUCCESS_RESULT.equals(entryResponse.result()) || !validateJumpUrl(entryResponse.jumpUrl())) {
                throw RtmartEntryException.protocol(null);
            }
            LOG.info("[RTMART][entry-url] response operation={}, httpStatus={}, remoteCode={}, reason={}, elapsedMs={}",
                    operation, httpStatus, SUCCESS_CODE, SUCCESS_REASON, elapsedMillis(startedAtNanos));
            return new EntryUrlResult(entryResponse.jumpUrl());
        } catch (RtmartEntryException exception) {
            throw exception;
        } catch (IOException exception) {
            throw RtmartEntryException.transport(exception);
        }
    }

    /**
     * 用 Content-Length 预检并以有限 source 读取 JSON；任何空白、超限或 Jackson 解析错误都是协议失败。
     * Jackson 原始异常可能携带响应片段，故只能经 {@link RtmartSafeCause} 进入异常因果链。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#51-正式成功响应}
     * 与 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#52-失败分类}。
     */
    private RtmartEntryResponse parseBounded(ResponseBody body) throws IOException {
        if (body == null || body.contentLength() > properties.getMaxResponseBytes()) {
            throw RtmartEntryException.protocol(null);
        }
        byte[] responseBytes = readBounded(body.source(), properties.getMaxResponseBytes());
        if (isBlankAscii(responseBytes)) {
            throw RtmartEntryException.protocol(null);
        }
        try {
            RtmartEntryResponse response = responseMapper.readValue(responseBytes, RtmartEntryResponse.class);
            if (response == null || response.code() == null || response.code().isBlank()) {
                throw RtmartEntryException.protocol(null);
            }
            return response;
        } catch (RtmartEntryException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw RtmartEntryException.protocol(exception);
        }
    }

    /**
     * 最多读取 {@code maxResponseBytes + 1} 字节作为超限哨兵，从实际流量上限阻断伪造 Content-Length。
     * 设计见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#52-失败分类}。
     */
    private static byte[] readBounded(BufferedSource source, int maxResponseBytes) throws IOException {
        Buffer buffer = new Buffer();
        long maximumWithSentinel = (long) maxResponseBytes + 1L;
        while (buffer.size() < maximumWithSentinel) {
            long read = source.read(buffer, maximumWithSentinel - buffer.size());
            if (read == -1L) {
                break;
            }
        }
        if (buffer.size() > maxResponseBytes) {
            throw RtmartEntryException.protocol(null);
        }
        return buffer.readByteArray();
    }

    private static boolean isBlankAscii(byte[] responseBytes) {
        if (responseBytes.length == 0) {
            return true;
        }
        for (byte responseByte : responseBytes) {
            if ((responseByte & 0xff) > 0x20) {
                return false;
            }
        }
        return true;
    }

    /**
     * 在已通过启动校验的 base URL 上解析端点路径；异常不回显 base URL 或路径原文。
     * 设计见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#41-http-约定}。
     */
    private HttpUrl resolve(String path) {
        try {
            HttpUrl url = HttpUrl.get(properties.getBaseUrl()).resolve(path);
            if (url == null) {
                throw new IllegalArgumentException("path cannot be resolved");
            }
            return url;
        } catch (RuntimeException exception) {
            throw RtmartEntryException.protocol(exception);
        }
    }

    /**
     * 只验证 jumpUrl 是无 user-info 的绝对 HTTPS URI；不解码、重排或记录其查询凭证。
     * 设计见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#51-正式成功响应}。
     */
    private static boolean validateJumpUrl(String jumpUrl) {
        if (jumpUrl == null || jumpUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(jumpUrl);
            return uri.isAbsolute() && HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isBlank() && uri.getUserInfo() == null;
        } catch (RuntimeException exception) {
            throw RtmartEntryException.protocol(exception);
        }
    }

    private static String safeLogValue(String value) {
        return value != null && SAFE_LOG_VALUE.matcher(value).matches() ? value : INVALID_LOG_VALUE;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /**
     * 所有受控失败在此统一落一条完成日志；只传入已净化的渠道/企业编码和异常元数据，绝不传递 Throwable、请求参数集、
     * token、签名或响应正文。详见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#5-响应与错误语义}。
     */
    private static void logFailureCompletion(String operation, Long projectId, String channelCode, String companyCode,
                                             RtmartEntryException exception, long startedAtNanos) {
        LOG.info("[RTMART][entry-url] completion operation={}, projectId={}, channelCode={}, companyCode={}, reason={}, "
                        + "httpStatus={}, remoteCode={}, elapsedMs={}", operation, projectId, safeLogValue(channelCode),
                safeLogValue(companyCode), exception.getReason(), exception.getHttpStatus(), exception.getRemoteCode(),
                elapsedMillis(startedAtNanos));
    }

}
