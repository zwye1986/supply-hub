package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * 华昇传输层只读上下文值对象。
 *
 * <p>职责：
 * 封装发起单次 HTTP 物理传输所需的非秘密基础设施路由、商户标识、超时与共享单调截止点；
 * 杜绝持有调用凭据材料、私钥明文或认证 Token。
 *
 * <p>不变量：
 * <ul>
 *   <li>类级与字段不可变；</li>
 *   <li>唯一构造器私有，由静态工厂 {@link #from(HuashengCallContext, long, int)} 统一初始化；</li>
 *   <li>尝试序号必须在 1..4 之间（覆盖最多两次 TOKEN 与两次业务请求的统一编号）；</li>
 *   <li>{@link #toString()} 严格脱敏输出固定字面量 {@code HuashengTransportContext[redacted]}；</li>
 *   <li>类级标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class)，拒绝任何序列化。</li>
 * </ul>
 *
 * <p>关键决策：
 * 单向正向深复制 {@link HuashengCallContext} 非秘密字段，deadlineNanos 为同 JVM {@link System#nanoTime()} 单调截止点，
 * 作为整个调用链（TOKEN/业务/刷新/查询）共享的有界绝对时间点，不被每次调用重设为新预算。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#172-纯协议传输上下文与双参端口。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengTransportContext {

    private final String supplierCode;
    private final long connectorVersionId;
    private final String frozenBaseUrl;
    private final String appId;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String operationNo;
    private final String traceId;
    private final long deadlineNanos;
    private final int transportAttemptOrdinal;

    /**
     * 私有构造函数，确保只能通过受控工厂方法构建。
     *
     * @param supplierCode 供应商编码
     * @param connectorVersionId 连接器版本 ID
     * @param frozenBaseUrl 冻结根地址
     * @param appId 商户应用标识
     * @param connectTimeoutMs 连接超时毫秒
     * @param readTimeoutMs 读取超时毫秒
     * @param operationNo 操作序号
     * @param traceId 链路追踪标识
     * @param deadlineNanos 共享单调截止纳秒
     * @param transportAttemptOrdinal 传输尝试序号 (1..4)
     */
    private HuashengTransportContext(String supplierCode,
                                     long connectorVersionId,
                                     String frozenBaseUrl,
                                     String appId,
                                     int connectTimeoutMs,
                                     int readTimeoutMs,
                                     String operationNo,
                                     String traceId,
                                     long deadlineNanos,
                                     int transportAttemptOrdinal) {
        this.supplierCode = supplierCode;
        this.connectorVersionId = connectorVersionId;
        this.frozenBaseUrl = frozenBaseUrl;
        this.appId = appId;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.operationNo = operationNo;
        this.traceId = traceId;
        this.deadlineNanos = deadlineNanos;
        this.transportAttemptOrdinal = transportAttemptOrdinal;
    }

    /**
     * 从冻结调用上下文创建传输上下文。
     *
     * <p>职责：
     * 正向复制非秘密冻结字段并绑定共享单调截止点与尝试序号。
     *
     * <p>不变量：
     * <ul>
     *   <li>拒绝 null 原上下文；</li>
     *   <li>尝试序号必须在 1..4 之间，越界抛出固定 {@link IllegalArgumentException}。</li>
     * </ul>
     *
     * @param frozenContext 冻结调用上下文，非空
     * @param deadlineNanos 同 JVM {@link System#nanoTime()} 单调截止纳秒
     * @param transportAttemptOrdinal 尝试序号，1..4
     * @return 不可变传输上下文
     * @throws IllegalArgumentException 当 frozenContext 为 null 或序号越界时抛出
     */
    public static HuashengTransportContext from(HuashengCallContext frozenContext,
                                                long deadlineNanos,
                                                int transportAttemptOrdinal) {
        if (frozenContext == null) {
            throw new IllegalArgumentException("frozenContext不能为null");
        }
        if (transportAttemptOrdinal < 1 || transportAttemptOrdinal > 4) {
            throw new IllegalArgumentException("尝试序号必须在1到4之间: " + transportAttemptOrdinal);
        }
        return new HuashengTransportContext(
                frozenContext.supplierCode(),
                frozenContext.connectorVersionId(),
                frozenContext.baseUrl(),
                frozenContext.appId(),
                frozenContext.connectTimeoutMs(),
                frozenContext.readTimeoutMs(),
                frozenContext.operationNo(),
                frozenContext.traceId(),
                deadlineNanos,
                transportAttemptOrdinal
        );
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
     * 获取连接器版本主键 ID。
     *
     * @return 连接器版本 ID
     */
    public long connectorVersionId() {
        return connectorVersionId;
    }

    /**
     * 获取冻结供应商根地址。
     *
     * @return 基础 URL
     */
    public String frozenBaseUrl() {
        return frozenBaseUrl;
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
     * 获取业务操作序号。
     *
     * @return 操作序号
     */
    public String operationNo() {
        return operationNo;
    }

    /**
     * 获取分布式追踪标识。
     *
     * @return traceId
     */
    public String traceId() {
        return traceId;
    }

    /**
     * 获取共享单调截止纳秒点。
     *
     * @return deadlineNanos
     */
    public long deadlineNanos() {
        return deadlineNanos;
    }

    /**
     * 获取传输尝试序号。
     *
     * @return 序号 1..4
     */
    public int transportAttemptOrdinal() {
        return transportAttemptOrdinal;
    }

    /**
     * 脱敏输出。
     *
     * @return 固定脱敏字面量
     */
    @Override
    public String toString() {
        return "HuashengTransportContext[redacted]";
    }
}
