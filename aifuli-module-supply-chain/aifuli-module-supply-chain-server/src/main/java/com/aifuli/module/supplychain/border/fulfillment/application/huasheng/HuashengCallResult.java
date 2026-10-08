package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengBusinessError;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 华昇应用层门面统一对外结果密封类族。
 *
 * <p>职责：
 * 封装向华昇平台发起调用（place/confirm/query/take）的最终事实，六分支互斥；
 * 仅对外携带强类型业务数据载荷或单向安全诊断 {@link HuashengFailureDiagnostics}，
 * 绝不向外层暴露任何 {@link Throwable}、内部 carrier 或原始响应正文。
 *
 * <p>不变量：
 * <ul>
 *   <li>sealed interface，仅允许 6 个互斥分支；</li>
 *   <li>所有分支必须保全不可变 attempts 证据集合（至多 2 TOKEN + 2 业务）与 wasBusinessCallStarted 标识；</li>
 *   <li>类级与所有实现类均标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class)；</li>
 *   <li>类级与字段绝对零 Throwable、零 ProtectedFailure；</li>
 *   <li>{@link #toString()} 严格脱敏，不输出秘密数据。</li>
 * </ul>
 *
 * <p>关键决策：
 * 所有底层传输/解析/策略异常在 Facade 层终结并投影为确定性不可变安全事实；彻底切断原始异常堆栈与敏感字段穿透到外部的路径。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#72-结果类型、§18.4。
 *
 * @param <T> 成功时的数据载荷类型
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public sealed interface HuashengCallResult<T> permits
        HuashengCallResult.Parsed,
        HuashengCallResult.BusinessError,
        HuashengCallResult.HttpFailure,
        HuashengCallResult.TransportFailure,
        HuashengCallResult.InvalidResponse,
        HuashengCallResult.LocalFailure {

    /**
     * 获取本次调用的所有物理通信证据列表（至多 2 TOKEN + 2 业务）。
     *
     * @return 不可变证据列表
     */
    List<HuashengExchangeEvidence> attempts();

    /**
     * 指示业务外呼是否已真实发起。
     *
     * @return 若业务外呼已发起返回 true，若在业务发起前失败则返回 false
     */
    boolean wasBusinessCallStarted();

    /**
     * 获取最新的通信证据（若有）。
     *
     * @return 最新通信证据 Optional
     */
    default Optional<HuashengExchangeEvidence> latestEvidence() {
        List<HuashengExchangeEvidence> list = attempts();
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(list.size() - 1));
    }

    /**
     * 成功解析分支。
     */
    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class Parsed<T> implements HuashengCallResult<T> {
        private final T data;
        private final List<HuashengExchangeEvidence> attempts;
        private final boolean wasBusinessCallStarted;

        public Parsed(T data, List<HuashengExchangeEvidence> attempts, boolean wasBusinessCallStarted) {
            this.data = Objects.requireNonNull(data, "data不能为null");
            this.attempts = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(attempts, "attempts不能为null")));
            this.wasBusinessCallStarted = wasBusinessCallStarted;
        }

        public T data() {
            return data;
        }

        @Override public List<HuashengExchangeEvidence> attempts() { return attempts; }
        @Override public boolean wasBusinessCallStarted() { return wasBusinessCallStarted; }
        @Override public String toString() { return "HuashengCallResult[Parsed,attempts=" + attempts.size() + "]"; }
    }

    /**
     * 供应商业务拒绝分支（HTTP 200 但 code != 0）。
     */
    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class BusinessError<T> implements HuashengCallResult<T> {
        private final HuashengBusinessError error;
        private final List<HuashengExchangeEvidence> attempts;
        private final boolean wasBusinessCallStarted;

        public BusinessError(HuashengBusinessError error, List<HuashengExchangeEvidence> attempts, boolean wasBusinessCallStarted) {
            this.error = Objects.requireNonNull(error, "error不能为null");
            this.attempts = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(attempts, "attempts不能为null")));
            this.wasBusinessCallStarted = wasBusinessCallStarted;
        }

        public HuashengBusinessError error() {
            return error;
        }

        @Override public List<HuashengExchangeEvidence> attempts() { return attempts; }
        @Override public boolean wasBusinessCallStarted() { return wasBusinessCallStarted; }
        @Override public String toString() { return "HuashengCallResult[BusinessError,rawCode=" + error.rawCode() + ",attempts=" + attempts.size() + "]"; }
    }

    /**
     * HTTP 非 2xx 失败分支（3xx/4xx/5xx）。
     */
    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class HttpFailure<T> implements HuashengCallResult<T> {
        private final HuashengFailureDiagnostics diagnostics;
        private final List<HuashengExchangeEvidence> attempts;
        private final boolean wasBusinessCallStarted;

        public HttpFailure(HuashengFailureDiagnostics diagnostics, List<HuashengExchangeEvidence> attempts, boolean wasBusinessCallStarted) {
            this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics不能为null");
            this.attempts = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(attempts, "attempts不能为null")));
            this.wasBusinessCallStarted = wasBusinessCallStarted;
        }

        public HuashengFailureDiagnostics diagnostics() {
            return diagnostics;
        }

        @Override public List<HuashengExchangeEvidence> attempts() { return attempts; }
        @Override public boolean wasBusinessCallStarted() { return wasBusinessCallStarted; }
        @Override public String toString() { return "HuashengCallResult[HttpFailure,attempts=" + attempts.size() + "]"; }
    }

    /**
     * 底层网络传输失败分支（超时/连接重置等）。
     */
    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class TransportFailure<T> implements HuashengCallResult<T> {
        private final HuashengFailureDiagnostics diagnostics;
        private final List<HuashengExchangeEvidence> attempts;
        private final boolean wasBusinessCallStarted;

        public TransportFailure(HuashengFailureDiagnostics diagnostics, List<HuashengExchangeEvidence> attempts, boolean wasBusinessCallStarted) {
            this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics不能为null");
            this.attempts = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(attempts, "attempts不能为null")));
            this.wasBusinessCallStarted = wasBusinessCallStarted;
        }

        public HuashengFailureDiagnostics diagnostics() {
            return diagnostics;
        }

        @Override public List<HuashengExchangeEvidence> attempts() { return attempts; }
        @Override public boolean wasBusinessCallStarted() { return wasBusinessCallStarted; }
        @Override public String toString() { return "HuashengCallResult[TransportFailure,attempts=" + attempts.size() + "]"; }
    }

    /**
     * 响应报文非法分支（格式错误/金额校验失败/身份不匹配等）。
     */
    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class InvalidResponse<T> implements HuashengCallResult<T> {
        private final HuashengFailureDiagnostics diagnostics;
        private final List<HuashengExchangeEvidence> attempts;
        private final boolean wasBusinessCallStarted;

        public InvalidResponse(HuashengFailureDiagnostics diagnostics, List<HuashengExchangeEvidence> attempts, boolean wasBusinessCallStarted) {
            this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics不能为null");
            this.attempts = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(attempts, "attempts不能为null")));
            this.wasBusinessCallStarted = wasBusinessCallStarted;
        }

        public HuashengFailureDiagnostics diagnostics() {
            return diagnostics;
        }

        @Override public List<HuashengExchangeEvidence> attempts() { return attempts; }
        @Override public boolean wasBusinessCallStarted() { return wasBusinessCallStarted; }
        @Override public String toString() { return "HuashengCallResult[InvalidResponse,attempts=" + attempts.size() + "]"; }
    }

    /**
     * 本地失败分支（业务外呼已开始后的本地超时、预算耗尽或 Token 刷新失败）。
     */
    @JsonSerialize(using = HuashengInternalOnlySerializer.class)
    final class LocalFailure<T> implements HuashengCallResult<T> {
        private final HuashengFailureDiagnostics diagnostics;
        private final List<HuashengExchangeEvidence> attempts;
        private final boolean wasBusinessCallStarted;

        public LocalFailure(HuashengFailureDiagnostics diagnostics, List<HuashengExchangeEvidence> attempts, boolean wasBusinessCallStarted) {
            this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics不能为null");
            this.attempts = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(attempts, "attempts不能为null")));
            this.wasBusinessCallStarted = wasBusinessCallStarted;
        }

        public HuashengFailureDiagnostics diagnostics() {
            return diagnostics;
        }

        @Override public List<HuashengExchangeEvidence> attempts() { return attempts; }
        @Override public boolean wasBusinessCallStarted() { return wasBusinessCallStarted; }
        @Override public String toString() { return "HuashengCallResult[LocalFailure,attempts=" + attempts.size() + "]"; }
    }
}
