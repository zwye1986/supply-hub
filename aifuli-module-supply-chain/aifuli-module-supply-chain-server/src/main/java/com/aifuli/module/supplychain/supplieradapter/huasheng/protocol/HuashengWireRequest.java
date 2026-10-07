package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.Map;
import java.util.Objects;

/**
 * 华昇线缆级 HTTP 载荷值对象。
 *
 * <p>职责：封装由 {@link HuashengRequestBuilder} 生成的最终物理出站请求参数，包括已拼接目标 URL、不可变请求头、小写十六进制 AES 密文正文及超时标识。
 *
 * <p>不变量：
 * <ul>
 *   <li>归属 {@code com.aifuli.module.supplychain.supplieradapter.huasheng.protocol} 包，杜绝向 http 包的反向架构违例；</li>
 *   <li>构造器包私有，严格限定由 {@link HuashengRequestBuilder} 实例化；</li>
 *   <li>headers 为不可变 Map；</li>
 *   <li>bodyHex 必须为有效的小写十六进制字符串（偶数长度且仅含 [0-9a-f]）；</li>
 *   <li>connectTimeoutMs 与 readTimeoutMs 必须大于 0；</li>
 *   <li>operationNo 与 traceId 必须非空非空白；</li>
 *   <li>{@link #toString()} 严格脱敏，仅输出 endpoint、url、operationNo 和 traceId，不打印正文密文或请求头；</li>
 *   <li>类级标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class)。</li>
 * </ul>
 *
 * <p>关键决策：不可变纯值载荷；归属 protocol 包以满足 ArchUnit 依赖规则；零 OkHttp 依赖。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#161-架构分层解耦与-wire-请求归属（ad-a3-01--ad-a3-02）。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengWireRequest {

    private final HuashengEndpoint endpoint;
    private final String url;
    private final Map<String, String> headers;
    private final String bodyHex;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String operationNo;
    private final String traceId;

    /**
     * 包私有构造华昇线缆请求。
     *
     * <p>职责：校验并初始化最终物理出站请求的完整参数。
     *
     * @param endpoint 绑定的华昇端点枚举，非空
     * @param url 最终物理请求完整 URL，非空非空白
     * @param headers 不可变 HTTP 请求头映射，非空
     * @param bodyHex 小写十六进制 AES 密文字符串，非空有效 hex
     * @param connectTimeoutMs 连接超时毫秒数，大于 0
     * @param readTimeoutMs 读取超时毫秒数，大于 0
     * @param operationNo 操作序号，非空非空白
     * @param traceId 链路追踪标识，非空非空白
     */
    HuashengWireRequest(HuashengEndpoint endpoint,
                        String url,
                        Map<String, String> headers,
                        String bodyHex,
                        int connectTimeoutMs,
                        int readTimeoutMs,
                        String operationNo,
                        String traceId) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint不能为null");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url不能为空或全空白");
        }
        Objects.requireNonNull(headers, "headers不能为null");
        if (bodyHex == null || bodyHex.isBlank()) {
            throw new IllegalArgumentException("bodyHex不能为空或全空白");
        }
        if (bodyHex.length() % 2 != 0 || !bodyHex.matches("^[0-9a-f]+$")) {
            throw new IllegalArgumentException("bodyHex必须为合法偶数长度小写十六进制字符串");
        }
        if (connectTimeoutMs <= 0) {
            throw new IllegalArgumentException("connectTimeoutMs必须大于0");
        }
        if (readTimeoutMs <= 0) {
            throw new IllegalArgumentException("readTimeoutMs必须大于0");
        }
        if (operationNo == null || operationNo.isBlank()) {
            throw new IllegalArgumentException("operationNo不能为空或全空白");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId不能为空或全空白");
        }
        this.url = url;
        this.headers = Map.copyOf(headers);
        this.bodyHex = bodyHex;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.operationNo = operationNo;
        this.traceId = traceId;
    }

    /**
     * 获取绑定的华昇端点。
     *
     * @return 端点枚举
     */
    public HuashengEndpoint endpoint() {
        return endpoint;
    }

    /**
     * 获取物理目标 URL。
     *
     * @return 完整目标 URL
     */
    public String url() {
        return url;
    }

    /**
     * 获取不可变 HTTP 请求头映射。
     *
     * @return 请求头 Map
     */
    public Map<String, String> headers() {
        return headers;
    }

    /**
     * 获取小写十六进制密文正文。
     *
     * @return 密文 hex 字符串
     */
    public String bodyHex() {
        return bodyHex;
    }

    /**
     * 获取连接超时毫秒数。
     *
     * @return 连接超时
     */
    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    /**
     * 获取读取超时毫秒数。
     *
     * @return 读取超时
     */
    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    /**
     * 获取操作序号。
     *
     * @return operationNo
     */
    public String operationNo() {
        return operationNo;
    }

    /**
     * 获取追踪 ID。
     *
     * @return traceId
     */
    public String traceId() {
        return traceId;
    }

    @Override
    public String toString() {
        return "HuashengWireRequest[endpoint=" + endpoint + ", url=" + url + ", operationNo=" + operationNo + ", traceId=" + traceId + "]";
    }
}
