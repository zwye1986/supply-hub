package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.Objects;
import java.util.Optional;

/**
 * 华昇供应商业务拒绝错误不可变对象。
 *
 * <p>职责：封装供应商 HTTP 200 返回但业务 code != 0 的业务拒绝事实（保留 rawCode、映射 knownErrorKind 及安全证据）。
 *
 * <p>不变量：包可见构造；rawCode 严禁为 0；knownErrorKind 必须等于 {@link HuashengErrorCode#fromRawCode(int)}，
 * 否则抛出固定说明的拒绝异常；toString 仅包含 rawCode 与 kind 名称，不含任何未脱敏数据；
 * 类级禁止意外 JSON 序列化。
 *
 * <p>关键决策：不作为系统异常继承 Throwable，不伪造 cause；证据在 A2 阶段恒为 {@link Optional#empty()}，
 * 由后续通信层补充实际 exchange evidence。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#51-json-接受规则、§15.1.4。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengBusinessError {
    private final int rawCode;
    private final HuashengErrorCode knownErrorKind;
    private final Optional<HuashengExchangeEvidence> safeExchangeEvidence;
    /** 66 安全校验拒绝的受控原因分类；仅匹配官方固定提示，未匹配为空。设计：2026-10-13 华昇错误分类设计 §4.3。 */
    private final Optional<HuashengSecurityReason> controlledSecurityReason;
    /** 供应商诊断编号；仅接受无控制字符且不超过128字符的合法编号。 */
    private final Optional<String> supplierTraceId;

    HuashengBusinessError(int rawCode, HuashengErrorCode knownErrorKind, Optional<HuashengExchangeEvidence> safeExchangeEvidence,
                          Optional<HuashengSecurityReason> controlledSecurityReason, Optional<String> supplierTraceId) {
        if (rawCode == 0) {
            throw new IllegalArgumentException("HuashengBusinessError不允许rawCode为0");
        }
        if (knownErrorKind != HuashengErrorCode.fromRawCode(rawCode)) {
            throw new IllegalArgumentException("knownErrorKind不匹配rawCode: " + rawCode);
        }
        this.rawCode = rawCode;
        this.knownErrorKind = Objects.requireNonNull(knownErrorKind, "缺少knownErrorKind");
        this.safeExchangeEvidence = Objects.requireNonNull(safeExchangeEvidence, "缺少safeExchangeEvidence");
        this.controlledSecurityReason = Objects.requireNonNull(controlledSecurityReason, "缺少controlledSecurityReason");
        this.supplierTraceId = Objects.requireNonNull(supplierTraceId, "缺少supplierTraceId");
        if (supplierTraceId.isPresent() && !isValidTraceId(supplierTraceId.get())) {
            throw new IllegalArgumentException("supplierTraceId非法");
        }
    }

    /** 供应商 traceID 仅接收无控制字符、最多128字符的诊断编号；非法编号由调用方丢弃后传入空。 */
    private static boolean isValidTraceId(String traceId) {
        if (traceId == null || traceId.isEmpty() || traceId.length() > 128) {
            return false;
        }
        for (int i = 0; i < traceId.length(); i++) {
            if (Character.isISOControl(traceId.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取供应商返回的原始错误码整数。
     *
     * <p>职责：完整保留华昇 OpenAPI 返回的原始非零错误码事实。
     * <p>不变量：保证 rawCode != 0；严格保真，不篡改或归一化。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#73-错误码表与策略边界、§15.1.4。
     *
     * @return 原始错误码数字
     */
    public int rawCode() {
        return rawCode;
    }

    /**
     * 获取已知错误码枚举分类。
     *
     * <p>职责：提供强类型的错误码分类枚举，供适配器故障映射与投影决策使用。
     * <p>不变量：保证非 null，且与 {@link HuashengErrorCode#fromRawCode(int)} 结果完全一致；未知码映射为 UNKNOWN。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#73-错误码表与策略边界、§15.1.4。
     *
     * @return 错误码枚举
     */
    public HuashengErrorCode knownErrorKind() {
        return knownErrorKind;
    }

    /**
     * 获取安全交换证据（若有）。
     *
     * <p>职责：提供已脱敏的 HTTP 往返安全诊断证据容器。
     * <p>不变量：保证容器非 null；在 A2 解析阶段恒为 {@link Optional#empty()}，不伪造通信证据。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#91-原始因果的内部保留与单向诊断投影、§15.1.4。
     *
     * @return 包含安全证据的 Optional，或空 Optional
     */
    public Optional<HuashengExchangeEvidence> safeExchangeEvidence() {
        return safeExchangeEvidence;
    }

    /**
     * 获取 66 安全校验拒绝的受控原因分类。
     *
     * <p>职责：仅当 rawCode 为 66 且官方固定提示匹配成功时给出受控分类，供错误分类决策使用。
     * <p>不变量：保证非 null；未匹配官方提示时为空，决策层不得据此猜测副作用。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#43-61-与-66。
     *
     * @return 受控安全原因容器
     */
    public Optional<HuashengSecurityReason> controlledSecurityReason() {
        return controlledSecurityReason;
    }

    /**
     * 获取合法的供应商诊断编号。
     *
     * <p>职责：单独关联供应商侧诊断，不替换平台 traceId。
     * <p>不变量：保证非 null；仅含无控制字符、不超过128字符的合法编号，非法编号由解析层丢弃。
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#82-日志与诊断。
     *
     * @return 合法供应商诊断编号容器
     */
    public Optional<String> supplierTraceId() {
        return supplierTraceId;
    }

    /**
     * 返回安全的字符串表达，仅输出原始错误码与枚举名称，杜绝携带任何报文敏感内容。
     *
     * @return 结构化脱敏文本
     */
    @Override
    public String toString() {
        return "HuashengBusinessError[rawCode=" + rawCode + ",kind=" + knownErrorKind.name() + "]";
    }
}
