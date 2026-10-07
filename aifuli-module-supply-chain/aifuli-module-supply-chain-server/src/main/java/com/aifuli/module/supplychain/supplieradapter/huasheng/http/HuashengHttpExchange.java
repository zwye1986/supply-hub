package com.aifuli.module.supplychain.supplieradapter.huasheng.http;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengProtectedFailure;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExpectedOrder;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderResponse;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengParseFact;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengResponseParser;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTakeResponse;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTokenResponse;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * 华昇 HTTP 物理交换结果与安全解析桥接值对象。
 *
 * <p>职责：
 * 封装单次 2xx 有限响应事实与对应 {@link HuashengExchangeEvidence} 通信证据；
 * 提供至多一次的严格 UTF-8 REPORT 响应解析桥接，向 A2 {@link HuashengResponseParser} 派发明文，
 * 并在解析完成后同步擦除所持有的响应字节数组。
 *
 * <p>不变量：
 * <ul>
 *   <li>唯一构造器包私有，生产所有者限定为 {@link HuashengHttpExecutor}；</li>
 *   <li>响应解析与关闭具备严格同步边界（synchronized），解析至多允许一次；</li>
 *   <li>解析完成后或显式 {@link #close()} 后，内部字节数组同步全量擦除为 0；</li>
 *   <li>关闭或解析后重复解析均返回固定安全 {@link HuashengFailureKind#PREFLIGHT}；</li>
 *   <li>{@link #toString()} 严格脱敏输出 {@code HuashengHttpExchange[redacted]}；</li>
 *   <li>类级标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class)。</li>
 * </ul>
 *
 * <p>关键决策：
 * UTF-8 解码严格采用 REPORT 模式（禁止使用 REPLACE 模式容忍非法编码），
 * 捕获 {@link CharacterCodingException} 为 {@link HuashengFailureKind#ENVELOPE_INVALID} 并附带真实证据。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#175-有限响应严格-utf-8-桥接与生命周期。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengHttpExchange implements AutoCloseable {

    private final byte[] responseBytes;
    private final HuashengExchangeEvidence evidence;
    private final Object lock = new Object();

    private boolean parsed = false;
    private boolean closed = false;

    /**
     * 包私有构造函数，由执行器初始化。
     *
     * @param responseBytes 响应字节数组（克隆隔离）
     * @param evidence 通信证据容器，非空
     */
    HuashengHttpExchange(byte[] responseBytes, HuashengExchangeEvidence evidence) {
        this.responseBytes = responseBytes != null ? responseBytes.clone() : new byte[0];
        this.evidence = Objects.requireNonNull(evidence, "缺少安全证据容器");
    }

    /**
     * 获取不可变通信证据。
     *
     * @return 通信证据
     */
    public HuashengExchangeEvidence evidence() {
        return evidence;
    }

    /**
     * 解析 TOKEN 响应。
     *
     * @return 包含 {@link HuashengTokenResponse} 或失败事实的尝试对象
     */
    public HuashengInternalAttempt<HuashengParseFact<HuashengTokenResponse>> parseToken() {
        if (evidence.endpoint() != HuashengEndpoint.TOKEN) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT,
                    new IllegalArgumentException("端点与parseToken不匹配: " + evidence.endpoint()),
                    Optional.of(evidence)));
        }

        String bodyString;
        try {
            bodyString = acquireAndDecodeBody();
        } catch (CharacterCodingException e) {
            close();
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.ENVELOPE_INVALID, e, Optional.of(evidence)));
        } catch (IllegalStateException e) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT, new IllegalArgumentException(e.getMessage()), Optional.of(evidence)));
        }

        try {
            return HuashengResponseParser.parseToken(bodyString);
        } finally {
            close();
        }
    }

    /**
     * 解析订单响应（PLACE / CONFIRM / QUERY）。
     *
     * @param expectedOrder 预期订单比对物，非空
     * @param stage 对应阶段端点，必须为 PLACE / CONFIRM / QUERY 之一且与证据端点一致
     * @return 包含 {@link HuashengOrderResponse} 或失败事实的尝试对象
     */
    public HuashengInternalAttempt<HuashengParseFact<HuashengOrderResponse>> parseOrder(
            HuashengExpectedOrder expectedOrder, HuashengEndpoint stage) {
        if (expectedOrder == null) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT,
                    new IllegalArgumentException("expectedOrder不能为null"),
                    Optional.of(evidence)));
        }
        if (stage == null) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT,
                    new IllegalArgumentException("stage不能为null"),
                    Optional.of(evidence)));
        }
        if (stage != HuashengEndpoint.PLACE && stage != HuashengEndpoint.CONFIRM && stage != HuashengEndpoint.QUERY) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT,
                    new IllegalArgumentException("stage必须为PLACE/CONFIRM/QUERY之一"),
                    Optional.of(evidence)));
        }
        if (evidence.endpoint() != stage) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT,
                    new IllegalArgumentException("端点与stage不匹配: endpoint=" + evidence.endpoint() + ", stage=" + stage),
                    Optional.of(evidence)));
        }

        String bodyString;
        try {
            bodyString = acquireAndDecodeBody();
        } catch (CharacterCodingException e) {
            close();
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.ENVELOPE_INVALID, e, Optional.of(evidence)));
        } catch (IllegalStateException e) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT, new IllegalArgumentException(e.getMessage()), Optional.of(evidence)));
        }

        try {
            return HuashengResponseParser.parseOrder(bodyString, expectedOrder, stage);
        } finally {
            close();
        }
    }

    /**
     * 解析提卡 TAKE 响应。
     *
     * @param expectedOrder 预期订单比对物，非空
     * @return 包含 {@link HuashengTakeResponse} 或失败事实的尝试对象
     */
    public HuashengInternalAttempt<HuashengParseFact<HuashengTakeResponse>> parseTake(
            HuashengExpectedOrder expectedOrder) {
        if (expectedOrder == null) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT,
                    new IllegalArgumentException("expectedOrder不能为null"),
                    Optional.of(evidence)));
        }
        if (evidence.endpoint() != HuashengEndpoint.TAKE) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT,
                    new IllegalArgumentException("端点与parseTake不匹配: " + evidence.endpoint()),
                    Optional.of(evidence)));
        }

        String bodyString;
        try {
            bodyString = acquireAndDecodeBody();
        } catch (CharacterCodingException e) {
            close();
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.ENVELOPE_INVALID, e, Optional.of(evidence)));
        } catch (IllegalStateException e) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.PREFLIGHT, new IllegalArgumentException(e.getMessage()), Optional.of(evidence)));
        }

        try {
            return HuashengResponseParser.parseTake(bodyString, expectedOrder);
        } finally {
            close();
        }
    }

    /**
     * 关闭本交换对象，同步全量擦除字节数组。幂等操作。
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (!closed) {
                closed = true;
                Arrays.fill(responseBytes, (byte) 0);
            }
        }
    }

    /**
     * 脱敏输出。
     *
     * @return 固定脱敏字面量
     */
    @Override
    public String toString() {
        return "HuashengHttpExchange[redacted]";
    }

    /**
     * 同包防御副本获取，仅供包内单元测试核对底层字节。
     *
     * @return 克隆字节副本
     */
    byte[] responseBytesForInternalInspection() {
        synchronized (lock) {
            return responseBytes.clone();
        }
    }

    private String acquireAndDecodeBody() throws CharacterCodingException {
        byte[] copyToDecode;
        synchronized (lock) {
            if (closed || parsed) {
                throw new IllegalStateException("Exchange已关闭或已完成解析");
            }
            parsed = true;
            copyToDecode = responseBytes.clone();
        }

        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer charBuffer = decoder.decode(ByteBuffer.wrap(copyToDecode));
            return charBuffer.toString();
        } finally {
            Arrays.fill(copyToDecode, (byte) 0);
        }
    }
}
