package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 华昇单次请求调用上下文值对象。
 *
 * <p>职责：封装发起单次华昇出站请求所需的基础设施、路由、凭据材料与链路追踪参数。
 *
 * <p>不变量：
 * <ul>
 *   <li>supplierCode 必须非空非空白；</li>
 *   <li>connectorVersionId 必须非空且大于 0；</li>
 *   <li>baseUrl 必须为绝对 HTTP/HTTPS 地址，禁止包含 query、fragment 或 userinfo；</li>
 *   <li>appId 必须非空且 UTF-8 字节长度恒为 16；</li>
 *   <li>credentialMaterial 必须非空（复用 domain 层材料）；</li>
 *   <li>connectTimeoutMs 与 readTimeoutMs 必须为正整数；</li>
 *   <li>operationNo 与 traceId 必须非空非空白；</li>
 *   <li>类级标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class)；</li>
 *   <li>{@link #toString()} 严格脱敏，仅输出 operationNo 与 connectorVersionId，不输出秘密。</li>
 * </ul>
 *
 * <p>关键决策：直接复用 {@link OperationTicket.CredentialMaterial}，消除跨包分层违例；不可变值对象。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#41-通信与凭据身份、§16.1。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengCallContext {

    private final String supplierCode;
    private final Long connectorVersionId;
    private final String baseUrl;
    private final String appId;
    private final OperationTicket.CredentialMaterial credentialMaterial;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String operationNo;
    private final String traceId;

    /**
     * 构造华昇调用上下文。
     *
     * <p>职责：校验并初始化单次调用的全部环境与安全参数。
     *
     * <p>不变量：任何入参违反安全约束均抛出 {@link IllegalArgumentException}。
     *
     * @param supplierCode 供应商编码，非空非空白
     * @param connectorVersionId 连接器版本 ID，大于 0
     * @param baseUrl 供应商根地址，绝对 HTTP/HTTPS 且无 query/fragment/userinfo
     * @param appId 商户应用标识，UTF-8 字节长度恒为 16
     * @param credentialMaterial 凭据密钥材料，非空
     * @param connectTimeoutMs 连接超时毫秒数，大于 0
     * @param readTimeoutMs 读取超时毫秒数，大于 0
     * @param operationNo 业务操作序号，非空非空白
     * @param traceId 分布式追踪标识，非空非空白
     * @throws IllegalArgumentException 当参数为空、超限或格式不合法时抛出
     */
    public HuashengCallContext(String supplierCode,
                               Long connectorVersionId,
                               String baseUrl,
                               String appId,
                               OperationTicket.CredentialMaterial credentialMaterial,
                               int connectTimeoutMs,
                               int readTimeoutMs,
                               String operationNo,
                               String traceId) {
        if (supplierCode == null || supplierCode.isBlank()) {
            throw new IllegalArgumentException("supplierCode不能为空或全空白");
        }
        if (connectorVersionId == null || connectorVersionId <= 0L) {
            throw new IllegalArgumentException("connectorVersionId必须为正整数");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl不能为空或全空白");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("baseUrl不是合法URI", e);
        }
        if (!uri.isAbsolute() || uri.getHost() == null || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("baseUrl必须为绝对HTTP/HTTPS地址");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl不能包含query/fragment/userinfo");
        }
        if (appId == null || appId.getBytes(StandardCharsets.UTF_8).length != 16) {
            throw new IllegalArgumentException("appId必须非空且UTF-8字节长度恒为16");
        }
        this.credentialMaterial = Objects.requireNonNull(credentialMaterial, "credentialMaterial不能为null");
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
        this.supplierCode = supplierCode;
        this.connectorVersionId = connectorVersionId;
        this.baseUrl = baseUrl;
        this.appId = appId;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.operationNo = operationNo;
        this.traceId = traceId;
    }

    /**
     * 获取供应商编码。
     *
     * @return 供应商编码
     */
    public String supplierCode() {
        return supplierCode;
    }

    /**
     * 获取连接器版本 ID。
     *
     * @return 连接器版本主键 ID
     */
    public Long connectorVersionId() {
        return connectorVersionId;
    }

    /**
     * 获取供应商根地址。
     *
     * @return 基础 URL
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 获取商户应用标识。
     *
     * @return appId
     */
    public String appId() {
        return appId;
    }

    /**
     * 获取凭据材料。
     *
     * @return 凭据材料
     */
    public OperationTicket.CredentialMaterial credentialMaterial() {
        return credentialMaterial;
    }

    /**
     * 获取连接超时毫秒数。
     *
     * @return 连接超时毫秒数
     */
    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    /**
     * 获取读取超时毫秒数。
     *
     * @return 读取超时毫秒数
     */
    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    /**
     * 获取业务操作号。
     *
     * @return 操作序号
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
        return "HuashengCallContext[operationNo=" + operationNo + ", connectorVersionId=" + connectorVersionId + "]";
    }
}
