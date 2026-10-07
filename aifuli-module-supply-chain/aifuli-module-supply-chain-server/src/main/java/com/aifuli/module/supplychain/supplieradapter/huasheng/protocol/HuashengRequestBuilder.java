package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.HuashengCrypto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.security.PrivateKey;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 华昇通信报文构造器。
 *
 * <p>职责：将高层类型化请求对象装配为包含秒级时间戳与独立 Nonce 的 JSON 载荷，执行 AES/CBC/PKCS5Padding 加密与 RSA SHA512withRSA 签名，最终生成不可变 {@link HuashengWireRequest}。
 *
 * <p>不变量：
 * <ul>
 *   <li>支持注入 {@link Clock} 与 {@code Supplier<String>} Nonce 生成器；</li>
 *   <li>每次物理发送独立获取最新秒级时间戳与 32 位无连字符 UUID Nonce；</li>
 *   <li>密文为小写十六进制字符串；签名原文严格为该小写密文字符串的 UTF-8 字节；</li>
 *   <li>TOKEN 端点绝不携带 {@code Authorization} 请求头；业务端点强制携带 {@code Authorization: Bearer <token>}；</li>
 *   <li>出站 {@code Content-Type: text/plain; charset=utf-8}，{@code X-AppId} 与 {@code X-Sign} 完备；</li>
 *   <li>baseUrl 去尾斜杠后拼接端点路径，校验最终 origin 不变且无 query/fragment/userinfo。</li>
 * </ul>
 *
 * <p>关键决策：无状态构造器；每次物理发送生成独立报文；协议层直接产出 WireRequest。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#43-每次发送构造、§16.3。
 */
public final class HuashengRequestBuilder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Clock clock;
    private final Supplier<String> nonceSupplier;

    /**
     * 使用指定时钟与 Nonce 生成器构造报文构造器。
     *
     * @param clock 时钟对象，若为 null 则使用 UTC 系统时钟
     * @param nonceSupplier Nonce 生成器，若为 null 则使用无连字符 UUID 生成器
     */
    public HuashengRequestBuilder(Clock clock, Supplier<String> nonceSupplier) {
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.nonceSupplier = nonceSupplier != null ? nonceSupplier : () -> UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 构造默认报文构造器（使用系统 UTC 时钟与无连字符 UUID Nonce）。
     */
    public HuashengRequestBuilder() {
        this(Clock.systemUTC(), () -> UUID.randomUUID().toString().replace("-", ""));
    }

    /**
     * 构造最终物理出站线缆请求对象。
     *
     * <p>职责：校验请求契约、拼装平铺明文 JSON、加密、计算 RSA 签名并填充 HTTP 请求头。
     *
     * @param endpoint 目标端点枚举，非空
     * @param request 华昇类型化请求对象，非空
     * @param context 调用上下文，非空
     * @param optionalToken 业务端点所需的 Bearer Token，TOKEN 端点传 empty
     * @return 最终线缆请求值对象
     * @throws IllegalArgumentException 当参数为空、端点不匹配、Token 缺失或 Nonce 非法时抛出
     */
    public HuashengWireRequest build(HuashengEndpoint endpoint,
                                     HuashengRequest request,
                                     HuashengCallContext context,
                                     Optional<String> optionalToken) {
        Objects.requireNonNull(endpoint, "endpoint不能为null");
        Objects.requireNonNull(request, "request不能为null");
        Objects.requireNonNull(context, "context不能为null");
        Objects.requireNonNull(optionalToken, "optionalToken不能为null");

        if (endpoint != request.endpoint()) {
            throw new IllegalArgumentException("请求类型与端点不匹配: endpoint=" + endpoint + ", request=" + request.endpoint());
        }

        // 1. 拼接并验证物理 URL
        String targetUrl = resolveAndValidateUrl(context.baseUrl(), endpoint);

        // 2. 获取秒级时间戳与独立 Nonce
        long timestamp = clock.instant().getEpochSecond();
        if (timestamp <= 0) {
            throw new IllegalArgumentException("timestamp必须为正整数");
        }
        String nonce = nonceSupplier.get();
        if (nonce == null || nonce.length() != 32 || !nonce.matches("^[0-9a-zA-Z]{32}$")) {
            throw new IllegalArgumentException("nonce必须为32位无连字符ASCII字符");
        }

        // 3. 组装平铺明文 JSON（顶层无 data 包裹）
        String plainJson;
        if (request instanceof HuashengTokenRequest tokenReq) {
            if (!context.appId().equals(tokenReq.appId())) {
                throw new IllegalArgumentException("appId与调用上下文不一致");
            }
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("app_id", tokenReq.appId());
            root.put("app_secret", tokenReq.appSecret());
            root.put("timestamp", timestamp);
            root.put("nonce", nonce);
            plainJson = writeJson(root);
        } else if (request instanceof HuashengPlaceRequest placeReq) {
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("outOrderSn", placeReq.outOrderSn());
            root.put("couponId", placeReq.couponId());
            root.put("num", placeReq.num());
            root.put("expireTime", placeReq.expireTime());
            root.put("notifyUrl", placeReq.notifyUrl());
            placeReq.outMobile().ifPresent(mobile -> root.put("outMobile", mobile));
            root.put("timestamp", timestamp);
            root.put("nonce", nonce);
            plainJson = writeJson(root);
        } else if (request instanceof HuashengConfirmRequest confirmReq) {
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("orderSn", confirmReq.orderSn());
            root.put("timestamp", timestamp);
            root.put("nonce", nonce);
            plainJson = writeJson(root);
        } else if (request instanceof HuashengQueryRequest queryReq) {
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            queryReq.orderSn().ifPresent(sn -> root.put("orderSn", sn));
            queryReq.outOrderSn().ifPresent(sn -> root.put("outOrderSn", sn));
            root.put("timestamp", timestamp);
            root.put("nonce", nonce);
            plainJson = writeJson(root);
        } else if (request instanceof HuashengTakeRequest takeReq) {
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("orderSn", takeReq.orderSn());
            root.put("timestamp", timestamp);
            root.put("nonce", nonce);
            plainJson = writeJson(root);
        } else {
            throw new UnsupportedOperationException("端点尚未实现: " + endpoint);
        }

        // 4. AES 加密为小写 hex
        String cipherHex = HuashengCrypto.encryptBodyHex(
                plainJson,
                context.credentialMaterial().appSecret(),
                context.appId()
        );

        // 5. RSA SHA512withRSA 签名（对小写 hex 密文字符串的 UTF-8 字节）
        PrivateKey privateKey = HuashengCrypto.loadPkcs8PrivateKey(context.credentialMaterial().privateKeyPem());
        String signHex = HuashengCrypto.signBodyHex(cipherHex, privateKey);

        // 6. 组装请求头
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain; charset=utf-8");
        headers.put("X-AppId", context.appId());
        headers.put("X-Sign", signHex);

        if (endpoint != HuashengEndpoint.TOKEN) {
            if (optionalToken.isEmpty() || optionalToken.get().isBlank()) {
                throw new IllegalArgumentException("业务端点必须携带有效Token");
            }
            headers.put("Authorization", "Bearer " + optionalToken.get().strip());
        }

        return new HuashengWireRequest(
                endpoint,
                targetUrl,
                Collections.unmodifiableMap(headers),
                cipherHex,
                context.connectTimeoutMs(),
                context.readTimeoutMs(),
                context.operationNo(),
                context.traceId()
        );
    }

    /**
     * 静态便捷构造入口。
     *
     * @param endpoint 目标端点
     * @param request 请求对象
     * @param context 调用上下文
     * @param optionalToken Token 容器
     * @param clock 时钟
     * @param nonceSupplier Nonce 生成器
     * @return 最终线缆请求值对象
     */
    public static HuashengWireRequest build(HuashengEndpoint endpoint,
                                            HuashengRequest request,
                                            HuashengCallContext context,
                                            Optional<String> optionalToken,
                                            Clock clock,
                                            Supplier<String> nonceSupplier) {
        return new HuashengRequestBuilder(clock, nonceSupplier).build(endpoint, request, context, optionalToken);
    }

    private static String resolveAndValidateUrl(String baseUrl, HuashengEndpoint endpoint) {
        String basePath = baseUrl.strip();
        while (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        String endpointPath = switch (endpoint) {
            case TOKEN -> "/api/token";
            case PLACE -> "/api/order/placeOrder";
            case CONFIRM -> "/api/order/confirmOrder";
            case QUERY -> "/api/order/queryOrder";
            case TAKE -> "/api/order/takeCard";
        };
        String targetUrl = basePath + endpointPath;

        URI baseUri = URI.create(baseUrl);
        URI targetUri = URI.create(targetUrl);

        if (!Objects.equals(baseUri.getScheme(), targetUri.getScheme())
                || !Objects.equals(baseUri.getHost(), targetUri.getHost())
                || baseUri.getPort() != targetUri.getPort()) {
            throw new IllegalArgumentException("目标URL origin与baseUrl不一致");
        }
        if (targetUri.getRawQuery() != null || targetUri.getRawFragment() != null || targetUri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("目标URL不能包含query/fragment/userinfo");
        }
        return targetUrl;
    }

    private static String writeJson(ObjectNode node) {
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("请求体序列化失败", e);
        }
    }
}
